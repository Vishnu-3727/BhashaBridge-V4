# SM-S948B — Same-Device Experiments (entry #9 follow-ups)

**Date:** 2026-07-31 · **Device:** Samsung Galaxy S26 Ultra, Snapdragon 8 Elite Gen 5 (SM8850)
**Build:** `main` @ `2432b4e` + classifier fix `e581a45` · ORT 1.27.0 · EN→HI unless stated
**Companion to:** `CROSS_DEVICE_REPORT.md` §6a″, §8, §10 — this file holds the four experiments that
report asked for, all run on one device in one session.

> **Provenance rule, unchanged:** every number is read off-device. Claims are tagged **Measured**,
> **Inferred** (controlled comparison, no direct counter) or **Speculative**. Nothing is fabricated,
> and two of the four experiments below returned *negative* results that are reported as such.

---

## 0. What was run

All 13 instrumented classes and the full JVM suite ran on this device.

| Suite | Result |
|---|---|
| JVM unit (6 classes) | **35 tests, 0 failures** |
| Instrumented (13 classes) | **all pass** — incl. `MtEngineInstrumentedTest` 2, `HiEnEngineTest` 3, `ParallelSessionLoadTest` 1, `OptCacheTest` 1 |
| Correctness on the new `intra=4` path | EN→HI and HI→EN outputs unchanged; sweep parity exact across all 12 configs |

`intra=4` had never been exercised by any prior entry — it is the code path the classifier fix
enabled — so the correctness pass matters more than usual here.

---

## 1. ORT operator profiling (§10 item 2) — **the SME question is NOT answered**

`OrtProfilingTest`, production policy (`intra=4`, mmap `.ort`, NO_OPT), 5 warmup + 30 measured
translations, ORT's built-in profiler. Traces: 555 MB total, analysed with
`model_pipeline/ort_profile_report.py`.

### The headline negative result (Measured)

**ONNX Runtime's profiler cannot see which SIMD kernel MLAS dispatched.** Op timings are visible;
the kernel *inside* the op is not. So this experiment **cannot** tell us whether the SME, SVE2 or
i8mm path ran, which was its primary purpose. The report script says so itself under *"What this
trace cannot answer"*. **No SME speedup is claimed anywhere in this database.** Settling it needs
`simpleperf record` + symbol report looking for dotprod/i8mm kernel symbols, or a debug ORT build
with MLAS kernel logging.

### What the traces did establish (Measured)

| Graph | kernel time | ORT overhead outside kernels | int8 GEMM | tensor movement | elementwise/norm |
|---|---|---|---|---|---|
| `decoder_step` (hot loop) | 6267.9 ms | **1669.9 ms (21.0%)** | 44.6% | **31.5%** | 13.0% |
| `decoder_init` | 604.6 ms | 152.5 ms (20.1%) | 48.7% | 31.1% | 12.4% |
| `encoder` | 452.1 ms | 92.2 ms (16.9%) | 42.1% | 25.2% | 24.2% |

- **Execution provider is `CPUExecutionProvider` on every node — zero EP fallback.** All MLAS.
- Hottest op everywhere is `DynamicQuantizeMatMul` (30.5% of `decoder_step`, 38,220 calls).
- **The math is under half the time.** Tensor plumbing (`Reshape` 12.3%, `Concat` 11.0%,
  `Unsqueeze` 5.4%, `Transpose` 4.3%, `Gather` 3.9%) plus 21% ORT dispatch overhead rivals the GEMM.
  `Reshape` alone costs 769 ms across 122,640 calls despite being a no-op view.

**Consequence for optimisation strategy (Inferred):** with GEMM ≈ 45% and movement+elementwise ≈ 45%,
this workload is *not* GEMM-dominated. Per the script's own decision rule, the lever is **fusion and
traffic reduction, not more threads** — which is independently consistent with §2 below.

---

## 2. Intra-op thread sweep (§10 item 1) — **`intra=4` is not optimal here**

`MtTuningSweepTest`, 12 configs × 30 runs × 2 sentences, fresh engine per config, `baseline` first and
`baseline_end` last to expose thermal drift. **All 12 configs parity-exact.**

### Drift must be subtracted before reading anything

`baseline` 106.8 ms → `baseline_end` 121.1 ms = **+13.4% drift** across 97 s (32.4 → 35.8 °C).
Configs run in list order, so the penalty is ≈ **+1.2% per position**. Long sentence (s1):

| Config | position | median ms | raw Δ | ≈ drift-corrected | verdict |
|---|---|---|---|---|---|
| `baseline` (ORT default) | 1 | 106.8 | — | — | reference |
| `opt_none` | 2 | 136.3 | +27.7% | ≈ +26% | much worse |
| `opt_extended` | 3 | 108.8 | +1.9% | ≈ −1% | neutral |
| **`intra1`** | 4 | **100.3** | −6.1% | **≈ −10%** | **best** |
| **`intra2`** | 5 | **101.2** | −5.3% | **≈ −10%** | **best** |
| `intra4` *(shipping)* | 6 | 113.6 | +6.4% | ≈ 0% | = baseline |
| `intra8` | 7 | 155.0 | +45.1% | ≈ +38% | catastrophic |
| `parallel` | 8 | 128.2 | +20.1% | ≈ +11% | worse |
| `parallel_inter2` | 9 | 162.6 | +52.3% | ≈ +42% | much worse |
| `arena_off` *(shipping)* | 10 | 131.9 | +23.5% | ≈ **+12%** | **worse** |
| `mempattern_off` | 11 | 141.2 | +32.3% | ≈ +20% | worse |

### Findings

**F1 — `threads = perfCores/2` overshoots on this device (Measured, with a caveat).** The classifier
fix correctly established `perfCores = 8`; the *derived* thread count of 4 is then ~10% slower than
1 or 2. The fix is still right — labelling six 3.6 GHz Oryon cores "efficiency" was factually wrong —
but its performance consequence here is negative. **Awkward corollary: the pre-fix accident
(`intra=1`) was faster on this device than the corrected value.** The classification and the thread
rule are separate decisions and only the first has been fixed.

**F2 — `cpuArena=false` is a shipping default and costs ≈ 12% here (Measured).** Phase 7 adopted it
on the SM-M315F for −37% memory at no speed cost. Device-dependent; worth re-examining.

**F3 — `intra8` collapses (+38%)**, reproducing the SM-M315F's oversubscription result on completely
different silicon. Also the widest spread in the sweep (stdev 22.5 ms vs 3.3–4.1 for `intra1/2`).

**F4 — anomaly:** `baseline` (ORT's own default, `intraThreads` unset) lands *between* `intra2` and
`intra4`, so ORT is **not** defaulting to 8 threads on an 8-core CPU, and setting 8 explicitly is far
worse than whatever it picks. Unexplained.

### The caveat that keeps F1/F2 as evidence, not proof

**The sweep runs the non-production load path.** `optCache` is deliberately off in these configs (so
the sweep can vary `optLevel`), so every config loaded source `.onnx` under ALL_OPT — not production's
NO_OPT + mmap `.ort`. Thread scaling may differ between them. **The clean confirmation is a
production-path A/B at intra 1/2/4, which has not been run.** Until then F1 and F2 are **Inferred**.

Supporting evidence that the ~10% gap is signal, not noise: §4 below measured **~1% run-to-run spread
between two runs of an identical config** on this device.

---

## 3. Speech pipeline — ASR crosses the realtime threshold

`SpeechPipelineBenchmarkTest`, fixture `speech_i_need_water.wav`, **2.65 s @ 16 kHz mono** — the same
asset the SM-M315F Phase 10 baseline used, so this is directly comparable.

| Stage | SM-M315F | **SM-S948B** | speedup |
|---|---|---|---|
| Vosk EN model load | 1336 ms | **263 ms** | 5.1× |
| ASR | 2087 ms | **505 ms** | 4.1× |
| MT | 402 ms | **66 ms** | 6.1× |
| **ASR + MT pipeline** | 2489 ms | **571 ms** | **4.4×** |
| **ASR speed vs realtime** | **0.79× (slower than speech)** | **5.25×** | — |

Transcript `"i need water please help me"` → `"मुझे पानी चाहिए कृपया मेरी मदद करें"`.
`AudioFileTranscriberTest` also passes (real WAV decode path).

**This is the single largest user-visible change in the database.** On the baseline device ASR ran
slower than the speech it was transcribing; here it runs 5.25× faster than realtime, which is the
difference between a feature that lags and one that feels instant.

---

## 4. Affinity A/B (§8) — **degenerate on this device, measured nothing**

`AffinityBenchmarkTest` reported `RESULT OFF median=102ms` vs `RESULT ON median=101ms` and passed
green. It was measuring a config against itself:

```
BB_AFFINITY: AFFINITY threads=4 perfIds=[0,1,2,3,4,5,6,7] effIds=[]
BB_AFFINITY: AFFINITY_STRING on='null' little='null'
```

`ExecutionPolicy.affinityString` returns null when there is no efficiency cluster to pin away from.
On a uniform-IP CPU that is **correct behaviour**, but it makes the ON and OFF arms byte-identical, so
the 30-iteration counterbalanced A/B compared two identical configurations. Fixed in `2f349b2` — the
test now raises an assumption failure naming the topology instead of passing.

**The accidental useful result (Measured):** two runs of an identical config differ by **1 ms out of
~102 (≈1%)**, with stdev 4.1 and 5.9 ms. That is this device's run-to-run noise floor, and it is what
makes §2's ~10% gap credible.

**Affinity remains unattributable after nine devices** — and on this device the question inverts:
the interesting experiment is pinning *to* the two idle 4742 MHz prime cores (§`CROSS_DEVICE_REPORT`
§2), not away from a little cluster that does not exist.

---

## 5. Three tests were passing while measuring nothing

A theme across this session, worth recording because all three look green in CI:

| Test | Silently did nothing because | Status |
|---|---|---|
| `StartupProbeTest.probeSessionCreation` | Phase 2B purges the source `.onnx`; probe found no file, logged SKIPPED, passed | **Fixed** `2f349b2` — assumption failure |
| `StartupProbeTest.probeParallelSessionLoad` | same | **Fixed** `2f349b2` |
| `AffinityBenchmarkTest` | ON and OFF arms identical when `effIds` is empty | **Fixed** `2f349b2` |
| ORT operator profiling | MLAS SIMD dispatch invisible to the profiler | **Not fixable in-test** — needs `simpleperf`; documented in §1 |

The earlier theory that `connectedAndroidTest` wiping `filesDir` caused the probe skips is **wrong**:
these runs used `am instrument`, `filesDir` was intact, and they still skipped. The cause is the
Phase 2B cache design.

---

## 6. Open items after this session

1. **Production-path thread A/B at intra 1/2/4** — the one experiment that would convert §2's F1 from
   Inferred to Measured, and it bears on a shipping default.
2. **Re-examine `cpuArena=false`** as a universal default (§2 F2).
3. **`simpleperf` symbol capture** — the only remaining route to the SME/i8mm dispatch question.
4. **Pin the idle prime cores** and re-measure.
5. **Baseline Profile generation** — blocked since Phase 4 on needing API 33+; this device is API 36,
   so `:app:generateReleaseBaselineProfile` can finally run. Not attempted in this session.
