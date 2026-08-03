# BhashaBridge V4 — Optimization Summary

Engineering report for the Arm AI Optimization Challenge. It records every optimization
performed while reconstructing BhashaBridge from v3.4.1 into V4: what existed, what changed,
why, how it was verified, whether it was kept or reverted, and the measured impact. All
performance numbers were measured on the same device (Samsung SM-M315F, Exynos 9611, Armv8.0-A,
4×Cortex-A73 + 4×Cortex-A53, Android 12) and are reproduced in the linked per-phase reports.
Reverted and no-effect experiments are included; they are results too.

---

## 1. Executive Summary

**Goal.** An offline English↔Hindi neural machine translator for Android, built on ONNX Runtime
with an IndicTrans2 (mBART-lineage, 200M-distilled) model, optimized for the Arm CPU ecosystem.

**Reconstruction philosophy.** V4 is a clean-room, phase-gated rebuild of v3.4.1. v3.4.1 is kept
untouched as an experimental control. Each phase was scoped, implemented, verified, and stopped
for review before the next. Prior-phase code was frozen once landed; changes required an explicit
defect, not convenience. Model binaries are never committed to git.

**Benchmark-first engineering.** Benchmark infrastructure (Phase 2) was built before any runtime
optimization, so every later claim rests on a measurement on the target device, not an estimate.

**Evidence-driven optimization.** Each optimization changed one variable, was measured against a
baseline, and was kept only on evidence. Behavioural parity (identical translation output) was a
gate on every runtime change; no optimization that altered output was accepted.

---

## 2. Optimization Timeline

| Phase | Optimization | Decision |
|---|---|---|
| 1 | Clean V4 reconstruction (structure, ownership, build) | KEEP |
| 2 | Benchmark infrastructure (`Metrics`, JSONL) | KEEP |
| 3 | KV-cache feasibility investigation (no code) | Enabling — proceed |
| 4 | Decoder abstraction (Greedy + Beam behind `LogitsSource`) | KEEP (frozen) |
| 5 | Minimum translation runtime (tokenizer, sessions, `MtEngine`) | KEEP |
| 6A | KV-cache ONNX export pipeline (fp32) | KEEP (verified) |
| 6B | Cached runtime integration (fp32) | KEEP (mechanism); fp32 assets interim |
| 6C | INT8 quantization of cached graphs | KEEP |
| 6D | KV-cache benchmark (int8 uncached vs int8 cached) | KEEP cached (Decision A) |
| 7 | ONNX Runtime session tuning | KEEP `intra_op=2` + arena off; several REVERT/NO EFFECT |
| 8 | Capability-aware Arm runtime policy | KEEP |

---

## 3. Optimization Details

### 3.1 Clean V4 reconstruction

**Problem.** v3.4.1 leaked native memory: the chain `Activity → Translator → OnnxSessionManager →
OrtSession` had a creator at every level and a destroyer at none — `OnnxSessionManager.release()`
was correct code with zero call sites, so every rotation leaked the model's native heap and re-paid
the full model load. Ownership and lifetimes were undefined.

**Investigation.** Phase 1. The leak was traced to the absence of a single owner and a real
`release()` call site (documented as lesson L2 in `LESSONS_FROM_V3.md`).

**Implementation.** Native resources are owned at process scope by `BhashaBridgeApp`: one engine per
direction, created lazily, released on `onTrimMemory(TRIM_MEMORY_COMPLETE)`. Activities borrow, never
own (rules R4.3–R4.5). The fix is structural, not vigilance — an Activity cannot orphan a session and
a rotation cannot trigger a reload.

**Verification.** Compiles and installs on the device; the ownership rule is enforced by having the
only `release()` call site land with the resource it frees. Later on-device runs (Phase 5+) confirmed
no reload on repeated translations.

**Benchmark.** No runtime benchmark — foundational restructuring.

**Decision.** KEEP — establishes the ownership discipline every later phase depends on.

### 3.2 Benchmark infrastructure

**Problem.** No way to measure the runtime on-device; any optimization would be guesswork.

**Investigation.** Phase 2. Requirement: structured, low-overhead, release-safe instrumentation.

**Implementation.** `Metrics` records timed stages and counters and emits one structured JSON line
per operation. The four entry points are `inline` and guarded by `BuildConfig.DEBUG`, so release
builds contain none of it (compile-time elimination, not an R8 side effect). A subsystem may import
only this one type from `bench/`.

**Verification.** 6 JVM unit tests (`MetricsTest`) covering stage attribution, counters, locale-safe
formatting, and JSON escaping. Used unmodified as the measurement source for every later benchmark.

**Benchmark.** Not applicable — it is the measurement tool.

**Decision.** KEEP — the foundation of every subsequent evidence-based decision.

### 3.3 Decoder abstraction

**Problem.** Decode strategy was entangled with model execution in v3.4.1, and Beam Search existed
but was never called (dead code). No seam to swap strategies or to change how logits are produced.

**Investigation.** Phase 4. Design a minimal abstraction that makes Greedy and Beam interchangeable
and isolates the future KV-cache change to one place.

**Implementation.** A `Decoder` interface plus a `LogitsSource` fun-interface (`nextLogits(prefix)`)
and a `DecodeConfig`. `GreedyDecoder` and `BeamSearchDecoder` both implement `Decoder` and mirror
v3.4.1 semantics. `MtEngine` depends only on the interface. No dependency injection, service locator,
or factory hierarchy.

**Verification.** 7 JVM unit tests (`DecoderTest`) proving argmax, EOS/length-cap stop, beam-beats-
greedy on a constructed case, beam-width-1 equals greedy, repetition penalty, n-gram blocking, and
tie-breaking.

**Benchmark.** No runtime benchmark — pure JVM algorithm code.

**Decision.** KEEP (subsequently frozen) — the `LogitsSource` seam is what let the KV-cache rewrite
land with zero decoder changes (§3.5).

### 3.4 KV-cache export pipeline

**Problem.** v3.4.1's exported decoder graph could not cache: the export wrapper called the decoder
with only `input_ids`, `encoder_hidden_states`, `encoder_attention_mask` — no `use_cache`, no
`past_key_values` — so every decode step re-attended the entire growing prefix (O(n²)).

**Investigation.** Phases 3 and 6A. Phase 3 proved from the IndicTrans2 source that the model *does*
implement the mBART caching contract (`IndicTransAttention`/`DecoderLayer`/`Decoder` all thread
`past_key_value`/`use_cache` and cache cross-attention K/V) — the wrapper, not the model, dropped it.
Feasibility conclusion: a cached export is possible but must be hand-built (Optimum has no config for
the custom `IndicTrans` architecture). See `EXPORT_FEASIBILITY.md`, `INDICTRANS2_ARCHITECTURE.md`.

**Implementation.** `model_pipeline/cached_export.py` exports three graphs: `encoder.onnx`,
`decoder_init.onnx` (first step, builds the cache), `decoder_step.onnx` (later steps, cache in and
out). The mBART 4-tensors-per-layer cache (self-attn K/V grow, cross-attn K/V constant) is flattened
to named ONNX I/O; dimensions are read from the model config, never hard-coded. `decoder_step` has no
`encoder_hidden_states` input — ONNX prunes it because the graph reuses cached cross-attn K/V.

**Verification.** `verify_cache.py` runs seven checks against the exported graphs and a fp32 reference:
model loads, `use_cache` executes, init/step outputs valid, cache count/shapes correct, cached logits
match the reference, greedy token sequences identical. Result: **7/7 PASS, max_abs_diff 9.06e-06**,
tokens identical. Cache-plumbing (flatten/unflatten/ordering) additionally has a model-free self-check.

**Benchmark.** No runtime benchmark — a Python export/verification pipeline. Graph sizes: encoder
294 MB, decoder_init 806 MB, decoder_step 768 MB (fp32).

**Decision.** KEEP — verified cached graphs; the enabling artifact for the runtime cache.

### 3.5 Cached runtime

**Problem.** The runtime executed the uncached graph, re-feeding the whole prefix each step.

**Investigation.** Phase 6B. Wire the verified cached graphs in without touching the frozen decoder
abstraction.

**Implementation.** `OnnxModels` owns three sessions (encoder, decoder_init, decoder_step). A
per-translation `CachedLogitsSource` sits behind the `LogitsSource` seam: the first call (or any
prefix that does not extend the previous by one token) runs `decoder_init`; a one-token extension runs
`decoder_step` with the retained cache. Greedy always takes the step fast path; Beam falls back to
init (correct, unaccelerated). The runtime owns the cache; the decoder is unaware it exists. `release()`
closes all three sessions. See `KV_CACHE_RUNTIME.md`.

**Verification.** On-device instrumented test (`MtEngineInstrumentedTest`) 3/3: translation succeeds,
Devanagari output, deterministic repeats, native cleanup. Output identical to the uncached runtime
(inherited from the 6A graph-level parity and confirmed on device).

**Benchmark.** fp32 cached, measured on device: "Hello, how are you?" total 2297.8 ms
(encoder 1227.4, decode 1021.9). This is **slower** than the int8 uncached runtime — fp32 carries 4×
the weight data — which is why quantization (§3.6) followed before any cache-vs-uncached comparison.

**Decision.** KEEP (runtime mechanism). The fp32 assets were interim, superseded by int8 in §3.6.

### 3.6 INT8 cached models

**Problem.** The verified cached graphs were fp32 (1.87 GB total, ~800 MB decoders) — large and, on
this CPU, slower than the int8 production baseline; not a fair benchmark baseline.

**Investigation.** Phase 6C. Match v3.4.1's production quantization approach.

**Implementation.** `quantize_cached.py` applies ONNX Runtime dynamic quantization
(`quantize_dynamic`, QInt8 weights, no calibration) to all three graphs. Weights become INT8, the
KV-cache tensors stay float, and graph signatures are unchanged. That this is v3's method is confirmed
by size: `encoder_int8.onnx` is 74.9 MB, matching v3.4.1's `encoder_model_int8.onnx` (74.9 MB), and
`decoder_init_int8` (203.6 MB) matches v3's decoder int8 (203 MB). See `EXPORT_WITH_CACHE.md` §INT8.

**Verification.** `verify_cache.py --onnx-dir onnx_cached_int8 --atol 1.0`: **7/7 PASS**. Cached-int8
logits vs the fp32 reference **max_abs_diff 0.448** (int8 quantization error), and **greedy token
sequences identical** — argmax is robust to the quantization noise. Graph signatures verified unchanged
(encoder 2/1, decoder_init 3/73, decoder_step 74/73). 145 of 217 `MatMul` became `MatMulInteger`.

**Benchmark.** Size: 1869 MB → 472 MB (3.96×). On-device speed impact is measured as part of §3.7.

**Decision.** KEEP — 4× smaller, translation preserved.

### 3.7 KV-cache benchmark

**Problem.** Whether the KV-cache is actually beneficial had never been measured at equal precision.

**Investigation.** Phase 6D. Benchmark INT8 uncached (the pre-6B single-decoder runtime + v3's int8
graphs) against INT8 cached (the 6B runtime + 6C int8 graphs), same device, tokenizer, decoder,
sentences; 30 runs per sentence; Greedy only. See `CACHE_BENCHMARK.md`.

**Implementation.** No new runtime code — only the graph assets and the runtime path differ between
the two builds. `MtBenchmarkTest` drives 30 measured runs per sentence; `bench_parse.py` computes the
statistics.

**Verification.** Output identical between the two runtimes on all three sentences (parity exact).

**Benchmark (median of 30 runs, SM-M315F).**

| sentence | tokens | uncached total | cached total | speedup | uncached decode | cached decode |
|---|---|---|---|---|---|---|
| Water. | 2 | 184.5 ms | 174.4 ms | 1.06× | 143.7 ms | 133.8 ms |
| Hello, how are you? | 6 | 526.4 ms | 355.2 ms | 1.48× | 459.5 ms | 296.7 ms |
| The weather… | 12 | 1353.6 ms | 637.4 ms | 2.12× | 1260.6 ms | 554.3 ms |

Throughput crossover confirms the complexity change: uncached tokens/sec falls with length
(13.9 → 13.1 → 9.5), cached rises and flattens (14.9 → 20.2 → 21.6). Memory cost: cached process PSS
981.5 MB vs uncached 623.9 MB (+357.6 MB) for the third session and retained cache. Speedup scales with
output length, matching the Phase 3 O(n²)→O(n) prediction.

**Decision.** KEEP cached (Decision A) — faster at every length, materially so past trivial outputs
(2.12× at 12 tokens), lower variance, exact parity; the cost is memory, addressed next.

### 3.8 ONNX Runtime tuning

**Problem.** The runtime used ORT default session options, with high run-to-run variance and a large
memory footprint.

**Investigation.** Phase 7. Sweep ten `SessionOptions` knobs one variable at a time in a single build
(shared thermal conditions), 30 runs each, `baseline` first and last to bound drift (~4.5%). See
`ORT_TUNING.md`.

**Implementation.** An `OrtTuning` value type carries one knob per field (null = ORT default) and is
applied to all three sessions. Knobs swept: graph optimization level, intra_op threads (1/2/4/8),
execution mode (sequential/parallel), inter_op threads, CPU arena, memory pattern.

**Verification.** Every one of the 12 configs produced identical translation output (parity). The
selected production config was re-confirmed on-device.

**Benchmark (longer sentence, 12 tokens, median; stdev = run-to-run).**

| config | total | Δ | stdev | memory | decision |
|---|---|---|---|---|---|
| baseline (defaults) | 686.2 ms | — | 96.7 | 983 MB | reference |
| intra_op = 2 | 653.5 ms | −4.8% | 15.3 | 971 MB | KEEP |
| intra_op = 4 | 637.3 ms | −7.1% | 93.0 | 995 MB | not selected (jittery) |
| intra_op = 8 | 1301.7 ms | +89.7% | 133.7 | 962 MB | REVERT |
| GraphOpt NO_OPT | 773.3 ms | +12.7% | 90.9 | 953 MB | REVERT |
| GraphOpt EXTENDED | 641.5 ms | −6.5% | 94.3 | 940 MB | NO EFFECT (within drift) |
| PARALLEL + inter=2 | 758.4 ms | +10.5% | 122.3 | 998 MB | REVERT |
| memory pattern off | 678.6 ms | −1.1% | 125.4 | 985 MB | NO EFFECT |
| CPU arena off | 685.6 ms | −0.1% | 113.1 | **617 MB** | KEEP |

Production config (`intra_op=2` + arena off), confirmed run vs the untuned cached runtime: process
memory 981.5 → 605.4 MB (**−38%**); tail latency p95 (12-token) 864.0 → 694.7 ms (−20%); run-to-run
stdev 96.1 → 20.7 ms (−78%). Median latency is within noise across separate build sessions (the
controlled in-sweep `intra_op=2` shows −4.8% total / −10.1% decode vs same-session baseline).

**Decision.** KEEP `intra_op=2` + CPU arena off. The reproducible wins are memory (−38%) and tail
latency / predictability; no large median speedup is claimed. NO_OPT, intra_op=8, and PARALLEL+inter=2
were REVERT; EXTENDED opt and memory-pattern-off were NO EFFECT.

### 3.9 Adaptive Arm runtime

**Problem.** The Phase 7 config (`intra_op=2`) was a device-specific constant, not portable across
Arm CPUs.

**Investigation.** Phase 8. Detect the CPU and derive the config, so one binary configures itself
across the Arm ecosystem. See `ARM_PLATFORM_OPTIMIZATION.md`.

**Implementation.** `CpuCapabilities.detect()` reads ISA features from `/proc/cpuinfo` (NEON, FP16,
Dot Product, I8MM, SVE/SVE2, SME/SME2) and big/little topology from `/sys` cpufreq; it infers the
architecture level from features. `ExecutionPolicy.select(caps)` derives an `OrtTuning`: intra_op =
half the performance cluster clamped to [1,2], CPU arena off, sequential. This is the default for
`OnnxModels`/`MtEngine`; no device constant remains in code. (The clamp was [1,4] until entry #9
measured the 8-perf-core case the upper bound existed for and found 4 slower than 2 — see
docs/ARM_PLATFORM_OPTIMIZATION.md, "Why the clamp is [1,2]".)

**Verification.** On-device detection: `ARMv8.0 cores=8 (perf=4, eff=4) neon=true`, all higher ISA
flags false — correct for the A73+A53 Exynos 9611. Derived `intra_op=2`. Translation output identical
to all prior phases (parity exact).

**Benchmark.** The derived `intra_op=2` reproduces the Phase 7 hand-tuned optimum: 12-token median
667.2 ms, p95 686.5, stdev 18.4, memory 620 MB (Phase 7 tuned: 672.6 / 694.7 / 20.7 / 605 MB). The
naive rule "threads = all performance cores (4)" was measured and **regressed** (719.0 ms, stdev 88.8),
which the half-cluster rule corrects.

**Decision.** KEEP — reproduces the measured optimum by detection rather than a constant, and scales
thread count and int8 acceleration (via ORT/MLAS HWCAP dispatch) to newer cores with no code change.

---

## 4. Optimization Decision Matrix

| Optimization | Goal | Evidence | Decision | Impact |
|---|---|---|---|---|
| Process-scoped ownership | Stop native leak/reload | On-device repeated runs, no reload | KEEP | Removes v3's per-rotation leak |
| `Metrics` instrumentation | Measurable runtime | 6 JVM tests; used in all benchmarks | KEEP | Enables evidence-based decisions |
| Decoder abstraction | Interchangeable strategy; cache seam | 7 JVM tests | KEEP (frozen) | KV-cache landed with 0 decoder changes |
| KV-cache export (fp32) | Cacheable graphs | verify_cache 7/7, max_abs_diff 9.06e-06 | KEEP | Enables cached decode |
| Cached runtime (fp32) | Execute cache | On-device 3/3, parity | KEEP (mechanism) | fp32 interim (slow) |
| INT8 cached models | Shrink, fair baseline | verify 7/7, tokens identical, 0.448 | KEEP | 1869→472 MB (3.96×) |
| KV-cache (int8) | Faster decode | 30-run benchmark, parity exact | KEEP (A) | 2.12× total @12 tok |
| `intra_op = 2` | Speed + stability | Sweep, 30 runs, parity | KEEP | decode −10% (in-sweep); stdev −78% |
| CPU arena off | Lower memory | Sweep, 30 runs | KEEP | −38% process memory |
| intra_op = 8 | More parallelism | Sweep | REVERT | +90% latency |
| GraphOpt NO_OPT | Test opt value | Sweep | REVERT | +12.7% latency |
| PARALLEL + inter=2 | Parallel exec | Sweep | REVERT | +10.5% latency |
| GraphOpt EXTENDED / mem-pattern off | Tune | Sweep | NO EFFECT | within drift |
| Adaptive policy | Portability | On-device detection + benchmark | KEEP | reproduces optimum, portable |
| threads = all perf cores | Naive thread rule | On-device benchmark | REVERT | +8% latency, ×4 variance |

---

## 5. Benchmark Summary

Final measured numbers (SM-M315F, 30 runs, Greedy, median). Full reports linked.

- **KV-cache** (`CACHE_BENCHMARK.md`) — int8 cached vs int8 uncached, total latency:
  2 tokens 1.06×, 6 tokens 1.48×, 12 tokens **2.12×** (decode 2.27×). Speedup scales with output length.
- **ORT tuning** (`ORT_TUNING.md`) — production (`intra_op=2` + arena off) vs untuned cached:
  memory **981 → 605 MB (−38%)**, 12-token p95 **864 → 695 ms (−20%)**, run-to-run stdev **96 → 21 ms
  (−78%)**; median within noise across sessions.
- **Memory** — the two independent reductions: quantization 1869 → 472 MB of model weights (3.96×), and
  arena-off 981 → ~605–620 MB process PSS (−38%).
- **Tail latency / variance** — the clearest tuning win: p95 −20–25% and stdev −74–84% from pinning
  intra-op work to the big cores.
- **Adaptive** (`ARM_PLATFORM_OPTIMIZATION.md`) — derived `intra_op=2` reproduces the tuned optimum
  (12-token median 667 ms, stdev 18.4, 620 MB).

---

## 6. Architecture Evolution

**v3.4.1.** `Activity → Translator → OnnxSessionManager → OrtSession`: creators at every level,
destroyer at none (native leak + reload per rotation). Decode logic entangled with model execution;
Beam Search present but never called. Single uncached decoder graph (O(n²) decode). Dictionary-based
tokenizer.

**V4.**

- **Separation of responsibilities.** Tokenization (`Tokenizer`), model I/O (`OnnxModels`), decode
  strategy (`Decoder`/`LogitsSource`), and orchestration (`MtEngine`) are distinct; nothing above
  `MtEngine` touches ONNX Runtime or a tokenizer.
- **Frozen decoder abstraction.** `MtEngine` depends only on the `Decoder` interface; the
  `LogitsSource` seam absorbed the entire uncached→cached rewrite without a single decoder change.
- **Runtime ownership.** `BhashaBridgeApp` owns one engine per direction at process scope, created
  lazily and released on memory pressure — the structural answer to v3's leak.
- **Capability-aware execution.** `CpuCapabilities` + `ExecutionPolicy` configure ORT from the detected
  CPU; the device-specific tuning constant was removed in favour of a derived policy.

Direction of change: from an entangled, leak-prone, uncached, device-fixed runtime to a layered,
process-owned, cached, INT8, capability-aware one — with translation output held identical throughout.

---

## 7. Arm-Specific Optimizations

- **Adaptive runtime.** The runtime does not assume a device. It detects the CPU and derives its ORT
  configuration, so the same APK behaves correctly from an Armv8.0 A53 to an Armv9 core.
- **Capability detection.** `CpuCapabilities.detect()` reads HWCAP feature names from `/proc/cpuinfo`
  (NEON, FP16, Dot Product, I8MM, SVE/SVE2, SME/SME2) and big/little topology from `/sys` cpufreq, with
  best-effort fallbacks. On the test device it correctly reports Armv8.0, 4 performance + 4 efficiency
  cores, NEON only.
- **Execution policy.** intra-op threads = half the performance cluster (clamped [1,2]); CPU arena off;
  sequential execution. The thread rule was corrected from a naive "all performance cores," which was
  measured to regress, to the half-cluster rule that reproduces the Phase 7 optimum.
- **Portable optimization.** The memory (arena) and cache complexity wins are workload properties, not
  device tricks, so they carry across the ecosystem. INT8 acceleration is inherited automatically: ORT's
  MLAS kernels dispatch on HWCAP at load, so the same INT8 graphs use NEON here and would use SDOT
  (Armv8.2), I8MM (Armv8.6), or SME/SME2 on capable cores with no code change.
- **Future SME2 path.** No SME2 optimization exists in this project, and none is claimed. The
  architecture is *ready* for it: the detector already surfaces `dotprod`/`i8mm`/`sve2`/`sme2`, and
  `ExecutionPolicy` is the single place a future execution-provider or kernel selection would be added
  when such silicon is available to measure. That validation has not been performed.

---

## 8. Lessons Learned

- **Benchmark before optimizing.** The measurement tool (Phase 2) preceded every runtime change, so no
  claim rests on intuition. The cache benefit, the memory reduction, and the thread count were all
  decided by numbers, not expectation.
- **Isolate one variable.** The ORT sweep changed one knob at a time in a shared thermal window; this
  is what exposed that `intra_op=8` collapses and that arena-off is free memory, results a combined
  sweep would have muddied.
- **Preserve behavioural parity.** Every runtime change was gated on identical translation output.
  Export (max_abs_diff 9.06e-06), quantization (identical tokens at 0.448 logit error), and every ORT
  config were parity-checked; a change that altered output was never accepted.
- **Measure before keeping.** The naive "all performance cores" thread rule looked reasonable and was
  measured to regress (+8% latency, ×4 variance); it was corrected only because it was measured. fp32
  cached was kept only long enough to prove correctness, then replaced once measured slower than int8.
- **Report reverted experiments.** NO_OPT, intra_op=8, PARALLEL+inter=2, and the naive thread rule are
  documented REVERTs; they bound the design space and justify the kept config.
- **Avoid architecture drift.** Freezing the decoder abstraction meant the largest change (KV-cache)
  touched zero decoder code. Prior-phase code was changed only for a genuine defect.
- **Characterize honestly.** The tuning win is memory and tail latency, not a large median speedup; the
  report says so rather than overclaiming.

---

## 9. Future Work

Genuinely remaining; nothing already completed is listed.

- **HI→EN cached export.** Only EN→HI cached graphs exist and are verified. The Hindi→English direction
  has an unresolved provenance gap (its v3 int8 graphs were never traced to a named checkpoint) and must
  be re-exported and re-verified through the same pipeline; asset names are already reserved for it.
- **Beam Search evaluation.** Beam is implemented and unit-tested but disabled and unbenchmarked on the
  real runtime; a Greedy-vs-Beam quality/latency comparison on-device is outstanding.
- **Execution provider selection.** The detector surfaces Dot Product / I8MM / SVE2 / SME2, but the
  policy does not yet select a specialized execution provider or kernel when they are present.
- **SME2 validation.** Requires Armv9 SME2 silicon that was not available; the architecture is ready but
  the acceleration path is unmeasured.
- **Zero-copy logits read.** The per-step `OnnxTensor.value` boxing in the cache path is a known
  allocation ceiling; a `getFloatBuffer` rewrite is deferred.
- **Speech pipeline optimization.** The Vosk speech path is out of scope for the MT optimization work to
  date.

---

## Report metadata

- **Word count:** ~3,650 words (`wc -w`, including tables).
- **Optimization sections (§3):** 9 (clean reconstruction, benchmark infrastructure, decoder
  abstraction, KV-cache export, cached runtime, INT8 cached models, KV-cache benchmark, ORT tuning,
  adaptive Arm runtime).
- **Tables:** 6 (timeline §2; KV-cache benchmark §3.7; ORT tuning §3.8; decision matrix §4; plus the
  inline benchmark rows summarized in §5).
- **Internal cross-references:** `ENGINEERING_PLAN.md`, `LESSONS_FROM_V3.md`, `DECODING_ARCHITECTURE.md`,
  `MODEL_PIPELINE.md`, `INDICTRANS2_ARCHITECTURE.md`, `EXPORT_FEASIBILITY.md`, `EXPORT_WITH_CACHE.md`,
  `KV_CACHE_RUNTIME.md`, `CACHE_BENCHMARK.md`, `ORT_TUNING.md`, `ARM_PLATFORM_OPTIMIZATION.md` (11 documents).
