# ONNX Runtime Tuning (Phase 7)

Tunes ONNX Runtime execution for the int8 cached runtime — no change to the decoder,
tokenizer, cache, weights, or export/quant pipelines. Only `SessionOptions` vary, via
the new `OrtTuning` plumbed into `OnnxModels`/`MtEngine` (default `null` per knob = ORT's
own default = pre-Phase-7 behaviour).

## Method

One knob changed per config, each independently benchmarked in a single build so they
share thermal conditions (`MtTuningSweepTest`, 12 configs, 30 runs × 2 sentences each,
fresh `MtEngine` per config). `baseline` (all ORT defaults) runs first and `baseline_end`
last to bound thermal drift. Parser: `model_pipeline/bench_tune_parse.py`; raw evidence:
`model_pipeline/bench_tune.log` markers.

- **Device:** SM-M315F, Exynos 9611 (4×A73 @2.3 GHz big + 4×A53 @1.7 GHz little), Android 12.
- **Drift:** baseline s1 686.2 ms → baseline_end 655.2 ms (−4.5%), a mild warm-up tailwind.
  Effects smaller than ~5% are treated as noise.
- **Parity:** every config produced **identical** output on both sentences. Tuning changes
  speed/memory, never translation.

## Per-knob results (median, longer sentence s1 = "The weather…", 12 tokens)

| knob / config | total ms | Δ total | decode ms | Δ decode | stdev | mem MB | decision |
|---|---|---|---|---|---|---|---|
| **baseline** (ORT defaults) | 686.2 | — | 608.4 | — | 96.7 | 983 | reference |
| GraphOpt `NO_OPT` | 773.3 | +12.7% | 653.7 | +7.4% | 90.9 | 953 | **REVERT** (slower) |
| GraphOpt `EXTENDED` | 641.5 | −6.5% | 567.3 | −6.8% | 94.3 | 940 | NO EFFECT (≈ drift) |
| **intra_op = 1** | 752.4 | +9.7% | 588.8 | −3.2% | 20.1 | 970 | REVERT (slow median) |
| **intra_op = 2** | 653.5 | −4.8% | 547.0 | −10.1% | **15.3** | 971 | **KEEP** |
| **intra_op = 4** | 637.3 | −7.1% | 557.4 | −8.4% | 93.0 | 995 | (fast, but jittery) |
| **intra_op = 8** | 1301.7 | +89.7% | 1099.7 | +80.7% | 133.7 | 962 | **REVERT** (oversubscribe) |
| exec `PARALLEL` | 610.0 | −11.1% | 533.4 | −12.3% | 108.0 | 971 | NO EFFECT (drift+variance) |
| `PARALLEL`+inter=2 | 758.4 | +10.5% | 654.6 | +7.6% | 122.3 | 998 | **REVERT** (slower) |
| **arena off** (`cpuArena=false`) | 685.6 | −0.1% | 597.9 | −1.7% | 113.1 | **617** | **KEEP** (−37% mem) |
| memPattern off | 678.6 | −1.1% | 604.1 | −0.7% | 125.4 | 985 | NO EFFECT |
| baseline_end (drift) | 655.2 | −4.5% | 554.3 | −8.9% | 90.6 | 971 | drift marker |

### Reading the knobs

1. **GraphOptimizationLevel** — ORT's default is already `ALL_OPT`. `NO_OPT` is clearly
   slower (+12.7%), confirming graph optimisation earns its keep. `EXTENDED` looked ~6%
   faster but that is inside the drift band and did not reproduce as a real gain. **Keep the
   default (`ALL`).**
2. **intra_op_num_threads** — the decisive knob. The default lets ORT spread onto the little
   cores, which jitters badly (stdev 96.7). `=2` pins to the two big cores: decode **−10.1%**
   and **stdev 96.7 → 15.3** (−84%), p95 664 vs 952 (−30%). `=4` has a marginally better
   median but stays jittery (stdev 93). `=1` is stable but slow; `=8` oversubscribes the
   8 cores and is catastrophic (+90%). **KEEP `intra_op = 2`** — best stability, strong median.
3. **inter_op / execution mode** — `PARALLEL` alone looked fast but is drift-and-variance
   confounded (stdev 108) and does not isolate a real gain; adding `inter=2` is clearly slower
   (+10.5%). Sequential execution (default) is right for this single-translation-at-a-time
   flow. **Keep sequential; no inter_op threads.**
4. **Arena allocator (`cpuArena`)** — disabling it costs **nothing** in speed (−0.1% total)
   and drops process memory **983 → 617 MB (−37%)**. The arena pre-reserves a large pool that
   this steady workload never needs. **KEEP `cpuArena = false`.**
5. **Memory pattern** — disabling it does nothing measurable (−1.1%, mem unchanged). **Keep
   the default (on).**
6. **CPU memory arena** — same knob as (4); covered.
7. **Session reuse** — already optimal: `BhashaBridgeApp` owns one `MtEngine` per direction at
   process scope (R4.4/R4.5), so sessions are created once and reused for every translation. No
   change needed; measured implicitly (no per-call session creation appears in any run).
8. **Tensor allocation reuse** — the per-step `OnnxTensor.value` boxing is a *code*-level
   allocation, not a `SessionOptions` knob, and lives in the frozen cache path. Out of scope
   for this phase (a zero-copy `getFloatBuffer` rewrite is a separate, later change).
9. **RunOptions** — exposes no latency-relevant lever for this workload (log severity,
   terminate flag); not varied.
10. **ORT profiling** — a diagnostic (`enableProfiling`), not a production setting; available
    to pinpoint hot ops in a future Arm-specific phase, left off in production.

## Production config: `intra_op = 2` + `cpuArena = false`

The two KEEP knobs are orthogonal (compute scheduling vs allocation) and each independently
evidenced, so they are combined as `OrtTuning.production()` and set as the default. Confirmed
on-device (`MtBenchmarkTest`, 30 runs × 3 sentences, tuned default):

| sentence | tokens | metric | 6D untuned cached | tuned (prod) | Δ |
|---|---|---|---|---|---|
| Hello… | 6 | p95 / stdev | 524.6 / 60.3 | 395.6 / **15.9** | −24.6% / −74% |
| The weather… | 12 | p95 / stdev | 864.0 / 96.1 | 694.7 / **20.7** | −19.6% / −78% |
| (process) | — | total PSS | 981.5 MB | **605.4 MB** | **−38.3%** |

Parity exact (पानी ।, हैलो , आप कैसे हैं ?, आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ ।).

## Conclusion

The production runtime is **measurably better, honestly characterised**:

- **Memory −38%** (981 → 605 MB) — the clearest, most reproducible win (arena off).
- **Tail latency and predictability** — p95 down ~20–25% and run-to-run stdev down ~74–84%
  (intra_op=2 pinning to big cores). For a user-facing translator, worst-case felt latency
  and consistency matter as much as the median.
- **Median latency** — in the *controlled same-session* sweep, `intra_op=2` is −5% total /
  −10% decode vs baseline. Across separate build sessions the median is within noise, so no
  large median speedup is claimed; the defensible gains are memory and tail/variance.

No knob changed translation output. No ORT tuning beyond `SessionOptions`; no thread affinity,
XNNPACK, NEON, SME, or `RuntimeConfig` — those remain later phases. Beam not benchmarked.

## Reproduce

```bash
# on-device sweep (12 configs), then parse:
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bhashabridge.app.mt.MtTuningSweepTest
adb logcat -d > model_pipeline/bench_tune.log
python model_pipeline/bench_tune_parse.py model_pipeline/bench_tune.log
```
