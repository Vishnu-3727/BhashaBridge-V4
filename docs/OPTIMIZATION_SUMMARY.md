# BhashaBridge V4 — Optimization Summary

Engineering report for the Arm AI Optimization Challenge. It records every optimization
performed while reconstructing BhashaBridge from v3.4.1 into V4: what existed, what changed,
why, how it was verified, whether it was kept or reverted, and the measured impact. All
performance numbers were measured on the same device (Samsung SM-M315F, Exynos 9611, Armv8.0-A,
4×Cortex-A73 + 4×Cortex-A53, Android 12) and are reproduced in the linked per-phase reports.
Reverted and no-effect experiments are included; they are results too.

---

## 0. Recording protocol — read before adding an entry

This document is the **running ledger**, not a finished report. Every optimization gets an entry,
including the ones that failed. An optimization that is not recorded here did not happen.

**One experiment = one entry = one commit.** The commit message carries the detail; the entry
carries the chain, so a reader can follow *problem → attempt → result → next attempt* without
`git log`.

**Entry template** (copy this):

```
### 3.N <Name>                                   [KEEP | REVERT | NO EFFECT | OPEN]

**Problem.**       What was slow/wrong, and how it was noticed. Name the measurement that exposed it.
**Investigation.** What was measured or read to find the cause. Rejected hypotheses go here.
**Implementation.** What actually changed, in one paragraph. Files, not prose.
**Verification.**  Parity check + test counts. Translation output must be identical, or the entry
                   must say why the change is allowed to alter it.
**Benchmark.**     Numbers, device, n, and the baseline they are against. If none: say NOT MEASURED.
**Evidence grade.** MEASURED (device numbers, this change) | INFERRED (numbers from a related run)
                   | NOT MEASURED (reasoned only).
**Decision.**      KEEP / REVERT / NO EFFECT / OPEN, and the reason.
**Next.**          What this makes possible or forces. For a REVERT or a partial fix, the follow-up
                   experiment — that is what makes this a chain rather than a list.
```

**Status legend**

| Status | Meaning |
|---|---|
| **KEEP** | Measured a win (or a required correctness change) and it shipped. |
| **REVERT** | Tried, measured worse, removed. The number is the value — it bounds the design space. |
| **NO EFFECT** | Within run-to-run drift. Kept only if it costs nothing and may pay on other hardware; otherwise removed. |
| **OPEN** | Landed but unpriced, or queued and not started. Every OPEN entry must name what would close it. |

**Three rules that the project has already been bitten by:**

1. **Never claim a speedup that was not measured on a device.** `13007e3` (§3.21) is a provable
   halving of copy work with **no** latency claim attached, because no device was connected. That is
   the correct shape.
2. **A benchmark that runs the non-production load path measures the non-production load path.** The
   Phase 7 sweep's `cpuArena=false` "costs 12%" finding was refuted by the production-path A/B
   (§3.20 C2). Sweeps that vary `optLevel` must disable `optCache`, and their results do not transfer.
3. **Subtract thermal drift before reading anything.** Run `baseline` first and last, counterbalance
   arms, and rotate rounds. §3.20 §2b's 7 configs × 3 rotated rounds is the pattern.

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
| 11A | Startup instrumentation (`Metrics` stage marks, probes) | Enabling — no code path changed |
| 11B | Tokenizer: buffered + block-wise dictionary parse | KEEP — tokenizer −67%, engine ready −32.8% |
| 11C | Parallel ONNX session initialization | KEEP — engine ready −36.7% warm, +126 MB peak |
| 2A | Bake ALL_OPT graph on first launch, load NO_OPT after | KEEP — session build −65% warm |
| 2B | ORT-format cache + memory-mapped load | KEEP — parity on time, ~−50% steady-state filesDir |
| 3 | Arm-aware intra-op thread affinity | NO EFFECT on the test device; kept as a no-op-when-degenerate |
| 4 | Baseline Profile (hand-authored → generated) | KEEP — cold TTID −5.5% on the S26U |
| 5 | Unified benchmark + validation framework | Enabling — the harness every later claim uses |
| — | ONNX Runtime 1.17.1 → 1.27.0 | KEEP — gated on the full benchmark |
| 12 | HI→EN cached INT8 pipeline | KEEP — bidirectional; closes the R-PROV provenance gap |
| — | Cross-device campaign, entries #1–#9 | 2 classifier fixes KEEP; SME priced; 2 earlier findings retracted |
| — | Intra-op clamp `[1,4]` → `[1,2]` | KEEP (INFERRED — needs re-measurement on the S26U) |
| — | Halve per-token logits copying | OPEN (NOT MEASURED) |
| — | Decode ceiling 18 → 128 steps | KEEP (correctness) — **incomplete, see §3.22** |
| 13 | One shared weight blob per direction | KEEP — APK −276.7 MB (−31%), latency unchanged |
| — | Slice the last position in `decoder_init` (Q1) | NO GAIN on greedy — held for beam (Q6) |
| — | Per-channel INT8 weight scales | REVERT — −16.7% logit error, +4–8% latency |

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
direction, created lazily, released on `onTrimMemory(TRIM_MEMORY_BACKGROUND)` while no caller is
using them. Activities borrow, never own (rules R4.3–R4.5). The fix is structural, not vigilance — an
Activity cannot orphan a session and a rotation cannot trigger a reload.

The level was `TRIM_MEMORY_COMPLETE` until an audit found that Android stopped delivering it to apps
targeting API 34+, making the release path unreachable on every modern device even though its call
site was present and correct (R4.6). Worth stating rather than quietly fixing: the same class of
defect as the v3.4.1 leak this section is about, caught the same way — by checking that the trigger
fires, not that the code exists.

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

### 3.10 Startup instrumentation (Phase 11A) — enabling

**Problem.** Phase 10 recorded "27 s to first translation" as a single number. Nothing said which
part of it was the neural network, so any startup optimization would have been a guess.

**Investigation.** `Metrics` stage marks along the whole startup path — process fork, per-dictionary
tokenizer parse, reverse-index build, per-graph asset verify / extract / session create, cache
contract, Vosk unpack and native load. Inline and `BuildConfig.DEBUG`-gated, so release is unchanged.
`StartupProbeTest` isolates each cost against a floor (raw I/O, no decode, no parse).

**Result that redirected the next three phases.** Of ~25 s, **49% was the tokenizer reading two JSON
dictionaries** and **46% was ORT creating three sessions**. Asset extraction — the visibly expensive
472 MB move — was 1.8 s, once, first run only. The models were not the problem.

**Benchmark.** Not applicable — a measurement phase. **Evidence grade:** MEASURED (SM-M315F).

**Decision.** Enabling. **Next:** §3.11 attacks the 49%, §3.12 the 46%.
See `ENGINE_STARTUP_ANALYSIS.md`.

### 3.11 Tokenizer startup (Phase 11B) — KEEP

**Problem.** Half of engine startup was a JSON parser (§3.10).

**Investigation.** `parseFlatIntDict` consumed **one character per `Reader.read()`**, each crossing
into `StreamDecoder`, over 3.4 M characters in `dict.TGT.json`. The probe held the parser and the
file constant and changed only the reader:

| Path | ms |
|---|---|
| Raw byte read — no decode, no parse (I/O floor) | **18** |
| `parseFlatIntDict(InputStreamReader(...))` — production | **9,951** |
| `parseFlatIntDict(BufferedReader(…, 64 KB))` | **1,082** |

I/O was 0.2% of the stage; parse logic ~11%; the remaining ~89% was per-call reader overhead —
work producing no output at all.

**Implementation.** Two changes in `Tokenizer`: a 64 KB `BufferedReader`, and a parser that pulls a
`CharArray` block at a time and walks it in-array instead of calling `read()` per character. Same
charset, same character sequence, same branches, same map — only fetch granularity changed.

**Verification.** Byte-identical translation output; parser unit tests including a block-boundary
case (entries deliberately not aligned to the 64 K seam).

**Benchmark (SM-M315F).** Tokenizer load **12,675 → 4,188 ms (−67%)**; engine ready
**24,662 → 16,584 ms (−32.8%)**. Runtime latency and memory unchanged.

**Evidence grade:** MEASURED. **Decision.** KEEP. **Next:** sessions are now 74% of what remains
→ §3.12. The parse is still ~1 s and is *still* serial with session load — see §9 Q3.
See `TOKENIZER_STARTUP_OPTIMIZATION.md`.

### 3.12 Parallel session initialization (Phase 11C) — KEEP

**Problem.** After §3.11, ORT session creation was 74% of startup, and the three graphs were built
one after another on a four-big-core CPU.

**Investigation.** Phase 11A had already established every precondition: disk I/O is a rounding
error inside `createSession` (79 ms read vs 2,619 ms create for the encoder), graph optimization
passes dominate, the work is single-threaded (`intra=1` vs `intra=2` changes nothing), and the three
loads are independent — probe: **serial 12,285 ms vs 6,258 ms on three threads (1.96×)**.

**Implementation.** `OnnxModels` submits the three loads to a three-thread pool and blocks until all
complete, so every consumer still sees a fully-built object. Failure handling is the reason it is a
pool and not three bare threads: every future is awaited before anything throws, sessions that *did*
load are closed before the exception escapes (otherwise a partially-loaded engine leaks hundreds of
MB with no owner — L2 in a new disguise), and the pool is shut down in a `finally`.

**Verification.** Byte-identical output; no leaked threads; `ParallelSessionLoadTest`.

**Benchmark (SM-M315F).** Engine ready **16,584 → 10,502 ms warm (−36.7%)**, **17,627 → 11,287 ms
cold (−36.0%)**. Cost: **+126 MB peak memory** during load, measured and accepted.

**Evidence grade:** MEASURED. **Decision.** KEEP. **Next:** the remaining serial component is the
tokenizer parse (§9 Q3). Cumulative: 24.7 s → 10.5 s across §3.11 + §3.12.
See `PARALLEL_SESSION_INITIALIZATION.md`.

### 3.13 Optimized-graph cache (Phase 2A) — KEEP

**Problem.** ORT ran its full `ALL_OPT` optimizer pass on every launch, for graphs that never change
between launches.

**Implementation.** Cache hit → `createSession(opt, NO_OPT)`, optimization skipped. Cache miss →
`createSession(src, ALL_OPT + setOptimizedModelFilePath)`, so the optimized graph is written as a
side effect of the session that already serves this launch. Stamp = app version | ORT version |
asset length, written only after a successful build.

**Verification.** Output identical; `OptCacheTest` on device.

**Benchmark (SM-M315F, EN→HI).** Session build **cold 9,945 ms → warm 3,494 ms (−65%, 6,451 ms
saved)**.

**Evidence grade:** MEASURED. **Decision.** KEEP. **Next:** the cached artifact was still `.onnx`
and still read into heap → §3.14.

### 3.14 ORT-format cache + memory-mapped load (Phase 2B) — KEEP

**Problem.** §3.13's cache stored an optimized `.onnx` and loaded it into a heap buffer; three
concurrent extractions OOM'd the 256 MB Dalvik heap, and the source copy stayed on disk forever.

**Implementation.** Bake once to **ORT format** with `session.save_model_format=ORT`; load with
`NO_OPT` + `session.load_model_format=ORT` + `use_memory_mapped_ort_model=1`. The source `.onnx` is
extracted only for that one bake and purged on the next launch, so steady-state storage is the
`.ort` files alone. Any failure (corrupt cache, failed mmap, no disk space) deletes and regenerates,
degrading to an uncached session — the cache can never break startup.

**Verification.** Output identical; corrupt-cache and missing-stamp paths exercised.

**Benchmark (SM-M315F, EN→HI, cooled).** Warm build **3,738 ms** — parity with §3.13's 3,494 ms
(encoder mmap load 1,149 vs 1,348 ms). Cold build 14,092 ms on the extract+bake launch. The win is
**not** time: it is ~**−50% steady-state `filesDir`** and a load that no longer scales with heap.

**Evidence grade:** MEASURED. **Decision.** KEEP — equal time, half the disk, no heap ceiling.
**Next:** the mmap benefit turned out to vary by device in a way that is still unexplained
(§3.20, MT6878 retraction).

### 3.15 Intra-op thread affinity (Phase 3) — NO EFFECT

**Problem.** Phase 7 attributed decode variance to workers migrating onto the little cluster.
Pinning them should remove that jitter.

**Implementation.** `session.intra_op_thread_affinities`, built from detected performance-core ids.
Two encoding traps, both handled: ORT requires exactly `intraThreads − 1` groups (it never pins the
calling thread), and ORT processor ids are **1-based** while `/sys` numbering is 0-based.

**Benchmark (SM-M315F, EN→HI, cooled, 30 iters, OFF/ON counterbalanced).** All arms within ~2%,
stdevs overlapping. Re-tested on the S26U (entry #9 §4): **degenerate — the ON and OFF arms were
byte-identical** because `effIds` is empty on a uniform-IP CPU, so the test was measuring nothing and
passing. Fixed to skip visibly (`2f349b2`).

**Evidence grade:** MEASURED (twice, both null). **Decision.** NO EFFECT — kept because it costs
nothing and correctly disables itself when there is no big/LITTLE split to pin to, but **no gain is
claimed**. **Next:** a genuine big.LITTLE device under thermal load is the only test that could
still move this.

### 3.16 Baseline Profile (Phase 4 + `967455b`) — KEEP

**Problem.** Startup ran interpreted until JIT caught up; no AOT profile shipped.

**Investigation.** On-device generation needs API 33+; the Phase 4 device was an unrootable API 31
SM-M315F, so Phase 4 shipped a hand-authored 27-rule profile and verified only that it *installed*
(`dumpsys package dexopt → status=speed-profile`). Entry #9's API 36 device unblocked real generation.

**Implementation.** Macrobenchmark journey: launch → wait for the engine (translate button enabled =
tokenizer + three `.ort` sessions) → type → translate → idle. **4,510 generated rules** replaced the
27 hand-written ones, including synthetic lambdas and Kotlin intrinsics no human would list.

**Benchmark (SM-S948B).** Cold TTID **159.4 → 150.6 ms (−5.5%)**; warm unchanged.

**Evidence grade:** MEASURED. **Decision.** KEEP. **Next:** TTID is not time-to-*translate*; the
~10.5 s engine load dominates the user's actual wait and is untouched by this.

### 3.17 Unified benchmark framework (Phase 5) — enabling

**Problem.** Each experiment had grown its own timing code and its own percentile arithmetic; two
reports could not be compared without checking whether they computed p95 the same way.

**Implementation.** `Stats` (one home for n/min/max/mean/median/p95/p99/stdev, nearest-rank, matching
the host-side parser), `SystemStats` (single-shot memory/CPU/thermal/battery, every field nullable —
`null` means the device did not expose it, with reasons collected), `BenchmarkSuiteTest` (startup →
cache sizes → first translation → 30 counterbalanced iterations → system snapshots → one JSON), and
`bench_report.py` (JSON → CSV + Markdown, with a `--baseline` regression mode).

**Gotcha worth keeping.** `connectedAndroidTest` uninstalls the app and wipes external storage, so
the report file is gone after the run — reassemble from `REPORT_JSON` logcat chunks, or drive tests
with `adb shell am instrument` after a manual install (entry #9's method, and the better one).

**Decision.** Enabling — every cross-device claim from entry #1 onward uses this harness.

### 3.18 ONNX Runtime 1.17.1 → 1.27.0 — KEEP

**Problem.** The runtime was pinned to v3.4.1's 2024 build; ten releases of MLAS kernel work,
including KleidiAI int8 microkernels, were unavailable.

**Implementation.** Version bump in its own commit, gated on the full benchmark (R7.3). 1.27.1 is a
GitHub tag with no published `onnxruntime-android` AAR, so 1.27.0 is the newest artifact that exists.

**Verification.** Parity on all benchmark sentences; full suite re-run.

**Evidence grade:** MEASURED. **Decision.** KEEP. **Next:** this is what made §3.20's SME
investigation possible at all — `mlas.disable_kleidiai` does not exist in 1.17.
See `ORT_UPGRADE_HANDOFF.md`.

### 3.19 HI→EN cached pipeline (Phase 12) — KEEP

**Problem.** Only EN→HI had cached INT8 graphs. v3.4.1's HI→EN graphs had no export script and no
traceable checkpoint (the R-PROV provenance gap), so they could not be trusted or reproduced.

**Implementation.** The same three-graph cached export, from the named
`ai4bharat/indictrans2-indic-en-dist-200M` checkpoint, through the same verification pipeline.
Identical config (18 layers, 8 heads, 512 hidden), so the 72-tensor cache contract and every runtime
path are direction-agnostic — nothing in `OnnxModels` beyond the asset triple is direction-aware.

**Benchmark.** Within 0.6% of the EN→HI graphs on size (121.2 vs 121.6 MB encoder, 111.1 vs 111.8 MB
decoder). HI→EN latency measured for the first time in entry #9 (§4b).

**Evidence grade:** MEASURED. **Decision.** KEEP — bidirectional, and R-PROV is closed.

### 3.20 Cross-device campaign, entries #1–#9 — mixed

Nine devices, Armv8.0 → ARMv9, four vendors. This is the entry that **retracts** things, which is why
it is one entry and not nine.

**KEEP — two CPU-classifier fixes.** (a) `dc3011e`: the old rule "only the top frequency tier is
performance" put a Snapdragon 8 Gen 1's three A710 mid cores in with the A510 littles and ran
inference single-threaded; the rule became "every tier above the lowest is performance". (b)
`e581a45`: on an 8× Oryon SM-S948B *every* core is `CPU part 0x002`, DVFS-split 6 @3629 + 2 @4742 —
the frequency rule then called six big cores "efficiency". The split is now gated on **core IP**.
Frequency ratio provably cannot substitute: the Dimensity 930's genuine A55/A78 split is 2000/2200 =
0.91, *higher* than that Oryon's 0.77.

**ANSWERED — SME is live, and it is worth 4–9%, not 2×.** ORT operator profiling **cannot** detect
SIMD dispatch (MLAS's kernel choice is invisible to it) — a negative result worth not re-deriving.
`simpleperf` (83.2% of CPU in `libonnxruntime.so`) plus capstone disassembly of the hottest 40-byte
loop found `smopa za0.s, p2/m, p2/m, z4.b, z8.b` — KleidiAI's SME int8 kernel. Priced by A/B via
`mlas.disable_kleidiai`: **+4.9%/3.6% cool, +9.1%/9.0% hot**. So the S26U's 2× over the S22U is the
core plus threads plus thermal headroom, **not the ISA**.

**REFUTED — `cpuArena=false` "costs 12%".** That came from `MtTuningSweepTest`, which runs the
**non-production** load path (`optCache` off, ALL_OPT, source `.onnx`). On the production path
arena-on is 1.9% *slower*: the shipping default was right and the sweep was measuring something else.

**NEGATIVE — prime-core pinning gains nothing** (99 ms pinned vs 99 ms not; idle 4742 MHz cores cost
nothing). **RETRACTED — "the mmap win is MT6878-exclusive"**: this Qualcomm part also hits 74.5 MB
native heap, and kernel recency is the best surviving fit but CPH2603 contradicts it. Mechanism
unexplained; the way to resolve it is instrumenting ORT's mmap acceptance, **not** more devices.

**Evidence grade:** MEASURED throughout. **Next:** §3.21's clamp change came out of §2b here.
See `bench/results/cross-device/S26U_EXPERIMENTS.md` and `CROSS_DEVICE_REPORT.md`.

### 3.21 Intra-op clamp `[1,4]` → `[1,2]` (`64e8934`) — KEEP, INFERRED

**Problem.** `threads = (perfCores / 2).coerceIn(1, 4)`. The rule was nine-device-validated; the
**clamp** was not — `[1,4]` was written before any 8-performance-core part existed, and only such a
part could ever reach its upper bound.

**Investigation.** Entry #9 is that part. `ProductionThreadSweepTest` on the real shipping load path
(`optCache` on, NO_OPT, mmap `.ort`; 7 configs × 3 rotated rounds, n=45 each, every arm
parity-exact): **intra2 99 ms vs intra4 104 ms long (−4.8%)**, **27 ms vs 31 ms short (−12.9%)**,
intra6/intra8 degrading steeply. No entry in the database has ever measured 4 as optimal; Phase 7
found 2 > 4 on the SM-M315F too. Eight of nine devices already derive 1 or 2 and are unaffected.

**Verification.** `ExecutionPolicyTest` pins the derivation (1/2/3/4/6/8 perf cores → 1/1/1/2/2/2)
and checks the affinity group count still tracks the thread count, so the bound cannot move silently.

**Evidence grade:** **INFERRED** — the gain comes from entry #9's table, not from a run after this
edit. No device was attached.

**Decision.** KEEP. **Next:** ~~re-run `ProductionThreadSweepTest` + `BenchmarkSuiteTest` on the S26U
to convert this to MEASURED~~ — **done, and this entry is now MEASURED on both counts.** §3.37 measured
the claim underneath the clamp on the SM-M315F (2026-08-12); the SM-S948B became available the same
day and **§3.38 measured the bound itself on the only topology that derives 4**: `intra4` is +7.4%
long and +19.2% short there against the clamped `intra2`, at 2.1× the CPU. The old `[1,4]` bound would
have shipped exactly that configuration to that part.

### 3.22 Per-token logits copy (`13007e3`) — OPEN, NOT MEASURED

**Problem.** `lastLogitsRow` read the next-token logits as
`(tensor.value as Array<Array<FloatArray>>)[0].last().copyOf()` — **two** full-width copies per
generated token (122,672 floats per decoder position for EN→HI). Invisible to ORT's operator
profiler, which sees kernel time only; it sits in the 21% "outside kernels" bucket entry #9 measured
and could not attribute.

**Investigation, including the wrong assumption.** The plan was "switch to `getFloatBuffer()`, it is
a zero-copy view". **It is not** — disassembling ORT 1.27.0's `OnnxTensor` shows
`FloatBuffer.allocate(capacity)` then `put(nativeView)`, a full heap copy. Swapping `value` for it
buys *nothing* on its own. What it does buy is the **second** copy: that heap buffer is array-backed,
so when `dec_len == 1` (every `decoder_step`, i.e. all but the first token of every translation) the
backing array **is** the row and `array()` returns it with nothing further copied.

**Implementation.** Two full-width copies per token become one. `dec_len > 1` (`decoder_init`, once
per translation) still slices. Extracted to an internal top-level function so the test calls the
shipping code, not a reimplementation.

**Verification.** `MtEngineInstrumentedTest` parity case at `dec_len` 1 **and** 3 — both branches,
since a single-position check passes even with the offset arithmetic broken. 39 JVM tests pass.

**Benchmark — MEASURED 2026-08-06 on the SM-M315F** (`LogitsReadBenchmarkTest`, n=200 interleaved,
reader timed in isolation against a `[1, 1, vocab]` tensor). Interleaved rather than batched so a
thermal ramp or a GC pause lands on both arms:

| vocab | boxed read (old) | buffer read (new) | saved/token | speedup |
|---|---|---|---|---|
| 122,672 (EN→HI) | 1274.4 µs | 728.6 µs | **545.8 µs** | 1.75× |
| 32,296 (HI→EN) | 517.0 µs | 338.1 µs | 178.9 µs | 1.53× |

At 12 generated tokens — the project's long benchmark sentence — that is **6.55 ms** saved per EN→HI
translation and 2.15 ms per HI→EN. Against the 667 ms 12-token median on this device (§3.9) it is
**~1.0% of end-to-end latency**, and the prediction in the commit message held: the wider vocabulary
gains more. Medians, because the stdev on the wide-vocabulary arm (2.7 ms) is dominated by GC pauses
on 490 KB allocations — which is itself the argument for §9 Q1.

**Evidence grade:** **MEASURED** (isolated reader; not an end-to-end A/B, which at ~1% would sit
inside this device's run-to-run drift).

**Decision.** KEEP. **Next:** the upstream lever — `decoder_init` computes and returns logits for
**every** prefix position and the runtime discards all but the last. Slicing that inside the exported
graph shrinks ORT's own allocation, not just this copy (§9 Q1).

### 3.23 Decode ceiling 18 → 128 (`99d163b`) — KEEP (correctness), INCOMPLETE

**Problem.** `DecodeConfig.targetCap` promised `max(minTargetLen, sourceLen)` tokens, but both
decoders loop `0 until maxSteps` and `maxSteps` defaulted to **18**. Any input over 18 tokens was cut
off mid-sentence with nothing shown to the user. v3.4.1 shipped the same mismatched pair.

**Implementation.** `maxSteps` 18 → 128, redocumented as an absolute runaway ceiling rather than the
working limit; `targetCap` now clamps into it, so one number bounds generation and the loop bound can
no longer disagree with the cap — which is exactly what hid this. 128 rather than unbounded because
`Tokenizer.encode` applies no source-length limit; it bounds the worst case at ~5.8 s on the slowest
device in the database.

**Verification.** Two new tests: a 40-token source yields 40 tokens (the regression that would have
caught it), and a 5,000-token source clamps to `maxSteps`. Outputs at 2/6/12 tokens — every benchmark
sentence and every parity golden — are unaffected.

**Evidence grade:** MEASURED (JVM); on-device re-run of `MtEngineInstrumentedTest` / `HiEnEngineTest`
still outstanding.

**Decision.** KEEP. **Next — this fix is incomplete.** The 2026-08-06 audit (`AUDIT_2026-08-06.md`,
H2) found the *working* limit is now `targetCap = max(14, sourceLen)`, i.e. a target may never exceed
its **source token count**. Translation expands; the same silent mid-sentence truncation therefore
still happens, just at a different threshold. Queued as §9 Q0 with the expansion-factor fix and a
corpus measurement of how often it fires.

### 3.24 Resource-lifecycle pass (2026-08-06) — KEEP

One audit, seven commits, `e5d7587..fdb3ae4`. Grouped as one entry because they came from a single
sweep with one question: *what is still allocated, running, or locked after it should not be?*
Each has its own commit; the per-item detail is there.

**§3.24a — A ten-second lock on the main thread (`e5d7587`).** Every method on `BhashaBridgeApp` was
`@Synchronized`, so all shared one monitor — including `translator()`, which **builds an engine
inside it**: ~10 s of dictionary parsing and session creation. `startRecording` runs `withResources`
on the main dispatcher, so tapping the microphone during a load parked the UI thread on that lock.
An **ANR**, not a stall. Split into `borrowLock` (counter, nanoseconds), `engineLock` (map, brief)
and a per-direction `loadLocks` entry held across construction. Lock order `borrowLock → engineLock`,
never reversed, so no cycle can form. **NOT MEASURED** — reasoned from the lock scope and Phase 11C's
measured 10.5 s construction.

**§3.24b — Two engines resident after a swap (`028af3c`).** `engines.getOrPut` kept every direction
ever opened; the only eviction was `onTrimMemory`, which fires on backgrounding, **not** on a swap
tap. One tap therefore left both engines live: six ONNX sessions against the ~605–620 MB PSS a single
direction costs (§3.8, §3.9). `translator()` now evicts the other directions, deferred exactly like a
trim if anything is borrowed.

**MEASURED 2026-08-06 on the SM-M315F** (`EngineFootprintTest`, one instrumentation invocation per
run so each starts from a fresh process). The arithmetic estimate was low, and one of the three
numbers below is a result nobody had asked for:

*Two engines resident, built directly to reproduce the pre-fix state:*

| state | PSS | native heap |
|---|---|---|
| idle | 51 MB | 3 MB |
| one engine (EN→HI, after one translation) | 966 MB | 885 MB |
| **two engines** (+ HI→EN) | **1,718 MB** | 1,622 MB |

**1.72 GB, a 1.78× ratio** — not the 1.2 GB estimated. On a 4 GB device that is most of what the
system will lend a foreground app.

*Where the eviction fires (`9b21693`), n=3 per arm, medians:*

| metric | evict **after** build | evict **before** build | delta |
|---|---|---|---|
| swap peak PSS | 1,394.8 MB | **883.1 MB** | −511.7 MB (−36.7%) |
| post-swap PSS | 934.7 MB | **541.8 MB** | −392.9 MB (−42.0%) |
| swap-back reload | 3,911 ms | 3,456 ms | −455 ms |
| baseline, one engine | 629.6 MB | 627.4 MB | control — agrees to 0.4% |

The first version of this fix evicted *after* publishing the new engine, so a swap still peaked with
both resident. Moving the eviction before the build is where most of the win is, and the peak is
what the low-memory killer reacts to.

**The reload is 3.5 s, not the ~10 s claimed when this landed.** That figure was Phase 11C's cold
engine construction; a swap-back hits the warm `.ort` mmap path. The trade is better than it was
argued to be.

**Unasked-for result: `release()` does not return memory to the OS.** After closing both engines,
PSS went 1,491 MB → 1,441 MB after `System.gc()` + 3 s → 1,441 MB after 13 s. It plateaus just below
the two-engine peak. So the eviction does **not** hand ~600 MB back to the system; it stops the
*peak* from including a second engine, and lets the native allocator reuse those arenas for the next
one — which the post-swap column shows it doing. That is a materially weaker claim than "frees
600 MB", and it is the accurate one. Whether this is scudo retaining freed spans or ORT holding
allocations past `close()` is unresolved (§9 Q11).

**Evidence grade:** **MEASURED**.

**§3.24c — Native use-after-free on the Vosk model (`ad7d0c0`).** Both speech paths fetched the model
and *then* opened the borrow; a `TRIM_MEMORY_BACKGROUND` in that window freed it and `Recognizer`
ran against a freed pointer. Replaced with `withSpeechModel(direction) { }`, which pins first and
loads inside the pin (on `Dispatchers.IO`, so pin-first is not paid for with a main-thread model
load); `speechModels` is now `@PublishedApi internal`, so the mistake is no longer expressible.

**§3.24d — Three things that kept running after being stopped (`2c0d747`).** (i) `stop()` set a
`@Volatile Boolean` that `record()` set back to `true` **inside the flow builder**, so a stop issued
during the model load was overwritten and the microphone stayed live — sessions are now numbered and
`stop()` claims `generation + 1`, which a boolean cannot express. (ii) A file transcription could not
be stopped at all (`capture.stop()` reaches a different object), so `stopRecording()` now cancels the
job. (iii) **TTS kept speaking after the screen stopped** — `onStop` silenced the microphone but not
the speaker.

**§3.24e — Two per-iteration allocations (`6f9e210`).** `pcm.copyOfRange` per 4096-sample chunk
(~1,400 short-lived 8 KB arrays for a 60 s file) → one reused buffer. `prefix.toHashSet()` per
generated token — boxing every token into a `java.lang.Long` on the path `CODING_STANDARDS 9.2`
declares allocation-free → an O(n²) scan over a prefix bounded at 128, zero allocation. Behaviour
identical in both; **NOT MEASURED**, and no latency claim.

**§3.24f — An uncancellable decode loop, and a cross-thread `micJob` (`4516333`).** `decodeTrack`
looped until the codec signalled end-of-stream with no cancellation check: a decoder that never
signals pinned an IO thread for the life of the process. `micJob` was cleared from
`invokeOnCompletion` (the completing thread) and read on main with no happens-before edge — stale
non-null wedges the microphone, stale null starts a second session on the same model.

**§3.24g — Extraction that cannot fit (`fdb3ae4`).** `extractAsset` wrote until the disk said no;
the resulting `IOException` reached the user as `error_direction_unavailable`, the same message used
for a direction that was never exported. Now checked against `usableSpace` (asset × 2, for the graph
plus its bake) before the first byte, with a message naming what it needs and what is free.

**Checked and found clean** — recorded so the next pass does not re-derive them: no Activity-context
retention anywhere (`Tts` takes `applicationContext`, `AudioFileTranscriber` takes the Application,
`AsrCorrector` is stateless); `AudioRecord`, its three effects, `Recognizer`, `MediaCodec` and
`MediaExtractor` are all released in `finally` on every path including the throwing ones; the
`Metrics` `ThreadLocal` is cleared by `end()` and is debug-only; `WaveformView` allocates nothing per
frame.

**Known ceiling, not fixable from Java (documented, not queued).** `lastLogitsRow` costs one
122,672-float heap array — **~490 KB per generated token** — because ORT's Java `getFloatBuffer()`
copies (§3.22). At ~12 tokens that is ~5.9 MB of large-object-space churn per translation. There is
no output-binding API in ORT's Java surface to write into a reused buffer, so the only lever is
making the tensor smaller: §9 Q1's graph-side slice.

**Post-change regression check — MEASURED 2026-08-06, SM-M315F, cooled** (`MtBenchmarkTest`, 30 runs
per sentence, 31.4 → 31.1 °C — the device *cooled* during the run rather than ramping):

| sentence | frozen baseline (§3.9) | after this session | Δ median |
|---|---|---|---|
| Water. (2 tok) | 163.2 ms · p95 193.0 · sd 11.1 | 166.4 · 173.4 · 9.5 | +2.0% (p95 −10.2%) |
| Hello, how are you? (6 tok) | 365.1 · 400.3 · 16.4 | 350.0 · 390.1 · 17.6 | −4.1% |
| The weather… (12 tok) | 667.2 · 686.5 · 18.4 | 640.1 · 670.5 · 22.6 | −4.1% |

No regression from the lifecycle pass, the escape fix or the cap change. The 4.1% is **not** credited
to this session: the baseline is Phase 8, and ORT 1.27.0, the ORT-format mmap cache and the logits
copy all landed in between — of which only the logits copy is separately priced (~1 point, §3.22).

**The thermal warning is worth more than the result.** The same build, same device, same test read
**640 / 680 / 690 / 864 ms** on the 12-token sentence across one afternoon as the phone went from
31 °C to 34 °C under repeated 938 MB installs — a **35% spread with no code change at all**. Two of
those numbers were nearly written up as a regression. Every comparison against the frozen baseline
must state its temperature, and a run whose stdev is 5× the baseline's (115.7 ms, seen at 33.8 °C)
is a discarded run, not a data point.

**Decision.** KEEP — all seven. **Next:** §9 Q10 prices §3.24b; the rest are correctness and cost
nothing to hold.

### 3.25 Where the memory goes after `release()` (Q11) — MEASURED, and it corrects §3.24b

**Problem.** §3.24b measured PSS sitting at 1,441 MB after both engines were closed, against 51 MB
idle, unmoved by a GC and unmoved thirteen seconds later, and concluded *"`release()` does not return
memory to the OS"*. Three explanations fit that observation and they have very different
consequences: ORT not freeing (a real leak, and the eviction is worthless), the allocator retaining
freed spans (memory free to the process, eviction only enables reuse), or the mmapped `.ort` files
staying resident.

**Investigation.** `NativeMemoryReturnTest` on the SM-M315F (cooled, 31.4 °C). The three are
separable without JNI: `Debug.getNativeHeapAllocatedSize` is what malloc owes the app,
`getNativeHeapSize` is what the allocator holds from the OS, and `/proc/self/maps` says what is
mapped.

| stage | PSS | heap alloc | heap size | `.ort` mappings |
|---|---|---|---|---|
| idle | 32 MB | 6.8 MB | 7.8 MB | 0 |
| engine built | 666 MB | 557.8 MB | 632.6 MB | 0 |
| after translate | 667 MB | 558.1 MB | 640.3 MB | 0 |
| **released, immediately** | 533 MB | **13.2 MB** | 497.1 MB | 0 |
| released + GC + 2 s | 526 MB | 13.1 MB | 497.1 MB | 0 |
| **released + 10 s** | **372 MB** | 13.2 MB | **333.2 MB** | 0 |
| second engine built | 618 MB | 557.9 MB | 561.7 MB | 0 |

**Result 1 — ORT frees correctly, and §3.24b's conclusion was wrong.** Allocated native memory drops
from **557.8 MB to 13.2 MB the instant `release()` returns** — a 97.6% drop, so `close()` is doing its
job and there is no leak. The allocator then hands pages back to the OS *asynchronously*: heap size
falls 632.6 → 497.1 MB immediately and → 333.2 MB about ten seconds later, with PSS following
666 → 533 → 372 MB. **Memory is returned; it is returned late.** §3.24b's thirteen-second observation
was taken after *two* engines on a larger, more fragmented heap and had not come back within that
window — a slower case of the same behaviour, not a different one. The correction stands as the
entry: `release()` works, and the eviction returns real memory to the system, just not instantly.

**Result 2 — an unasked-for finding: the `.ort` models are not memory-mapped.** `/proc/self/maps`
shows **zero mappings under the app's data directories** at any point, while the native heap grows by
~551 MB when the engine loads. The weights are heap-resident. §3.14 (Phase 2B) describes this path as
"loaded via memory-mapped I/O", and `loadOptions()` does set
`session.use_memory_mapped_ort_model=1` — which is a **real** key in ORT 1.27.0 (confirmed by
`strings` on `libonnxruntime.so`, which also carries *"session.use_memory_mapped_ort_model is ignored
when loading from a buffer"*). The session is created from a **path**, not a buffer, so that
exclusion should not apply, and the Java binding does have a path-based JNI entry point
(`createSession__JJLjava_lang_String_2J`).

The most likely mechanism is that MLAS **pre-packs** int8 weights at session init: the mapped bytes
are copied into freshly allocated, kernel-friendly buffers and the mapping is dropped. That would
make §3.14's disk-space win real (only `.ort` is kept, the source is purged) while its memory-mapping
claim is true only during load, not in steady state. **Not proven** — queued as Q13.

**Evidence grade:** MEASURED for both results; the *mechanism* behind result 2 is a hypothesis.

**Decision.** No code change. §3.24b's wording is corrected above and §3.14's mmap claim is now
qualified. **Next:** Q13.

### 3.26 Is prepacking what unmaps the model? (Q13) — REFUTED, and a large negative result

**Problem.** §3.25 found the `.ort` weights heap-resident with no file mapping and named MLAS
pre-packing as the likely cause: it copies int8 weights into kernel-friendly buffers, which would let
the mapped originals go.

**Investigation.** `MmapPrepackTest` on the SM-M315F, cooled (30.5 → 30.0 °C). Two things §3.25 could
not do: a watcher thread polling `/proc/self/maps` every 50 ms **during** `createSession` — a
post-load sample cannot tell "never mapped" from "mapped, then unmapped" — and an A/B on the newly
plumbed `session.disable_prepacking`.

| | prepack **on** (ships) | prepack **off** |
|---|---|---|
| peak mapped during load | **451 MB** | **451 MB** |
| mapped after load | 0 | 0 |
| session load | 4,919 ms | 3,773 ms |
| native heap allocated | 559 MB | **1,067 MB** |
| process PSS | 753 MB | 856 MB |
| translate median | **636 ms** | **2,909 ms** |

**Result 1 — the mmap is real, and §3.14 is rescued.** 451 MB of `.ort` **is** mapped while the
sessions build, then unmapped once they are built. Phase 2B's "loaded via memory-mapped I/O" is true;
what §3.25 caught was the steady state after the mapping is released, not its absence. The §3.25
entry stands as written — no mapping *persists* — but the claim it appeared to contradict does too.

**Result 2 — prepacking is refuted as the mechanism.** The mapping is dropped identically with
prepacking off. The remaining candidate is initializer copying: ORT's `SaveInitializedTensors` copies
constants into the session allocator during load, and the config that would avoid it
(`session.use_ort_model_bytes_for_initializers`) applies to the **buffer** load path, not the
path-based one this code uses. That is Q14.

**Result 3 — the negative result is the valuable one.** Disabling prepacking is not a
memory-for-speed trade: it is **4.6× slower (636 → 2,909 ms) and uses nearly twice the native heap
(559 → 1,067 MB)**. Prepacking pays for itself twice over. `disablePrepacking` stays benchmark-only
and is documented as a REVERT so nobody reaches for it as a memory lever.

**Evidence grade:** MEASURED. **Decision.** REVERT `disable_prepacking`; keep the knob for
measurement. **Next:** Q14 — the only remaining route to file-backed weights is loading the `.ort`
through a file-mapped `ByteBuffer` with `use_ort_model_bytes_directly` +
`use_ort_model_bytes_for_initializers`, which ORT's Java surface does expose
(`createSession(ByteBuffer, …)`).


### 3.27 File-backed initializers (Q14) — WORKS, but it is a change of memory *kind*, not amount

**Problem.** §3.26 left the weights as ~559 MB of **anonymous** native heap: the `.ort` mapping is
dropped once the sessions are built, because a path-based load copies every initializer into the
session allocator. Anonymous pages must be swapped or killed under pressure; clean file-backed pages
can be dropped and re-read.

**Implementation.** `OrtTuning.mappedInitializers` (default **off**). Maps the `.ort` with
`FileChannel.map` and builds the session from ORT's `createSession(ByteBuffer, …)` overload with
`session.use_ort_model_bytes_directly` + `session.use_ort_model_bytes_for_initializers`, so
initializers point into the mapping. `session.use_memory_mapped_ort_model` is *ignored* on the buffer
path — ORT says so in its own strings — which is why the mapping is made here rather than requested.
The `MappedByteBuffer` is retained until `release()`: ORT holds pointers into those bytes for the
session's life, and the JDK has no explicit unmap, so dropping the reference **is** the contract. The
list is declared above the `init` block, because Kotlin runs property initializers in declaration
order and the three graphs load during `init`.

**Benchmark (SM-M315F, ~31.9 °C, both arm orderings, n=10 translations per arm).** Both orders were
run because the first arm reads 451 MB off storage and the second finds it in the page cache:

| | path load | mapped initializers |
|---|---|---|
| load, cold page cache | 6,981 ms | 4,058 ms |
| load, warm page cache | 3,292 ms | 2,703 ms |
| native heap allocated | 559.0 / 559.4 MB | **407.8 / 407.5 MB** |
| native PSS | 595 / 652 MB | **366 / 351 MB** |
| total PSS | 680 / 735 MB | 766 / 759 MB |
| `.ort` mapped after load | 0 | **451 MB, 3 mappings** |
| translate median | 779 / 626 ms | 692 / 668 ms |

Output was byte-identical in every arm.

**What survives both orderings** — the only claims made:

- **451 MB becomes file-backed** and stays mapped for the session's life.
- **Anonymous native heap falls 151 MB (−27%)**; native PSS falls 230–300 MB (−39–46%).
- **Total PSS rises 30–86 MB**, because those mapped pages are resident and counted.

**What does not survive.** The −61% load time was **page cache**: whichever arm ran first was slower,
in both directions. No load-time claim. Translate latency overlaps between orderings (779/626 against
692/668) — no latency claim either way.

**Evidence grade:** MEASURED for the memory result; explicitly NOT MEASURED for load and latency,
where the ordering confound is larger than any effect.

**Decision.** **OPEN — kept off by default.** This is not a smaller footprint, it is a *better* one:
the same weights held as clean, evictable, file-backed pages instead of anonymous pages the kernel
can only swap or kill for. That should make the process a worse OOM victim, which is what §3.24b's
1.72 GB two-engine measurement was really about. But "should" is not measured, the headline PSS number
moves the wrong way, and one device's single run does not flip a shipping default.

**Next:** Q15 — drive the app under real memory pressure on the M31 and compare survival and re-read
cost between the arms. Until then the knob stays off and documented.


### 3.28 Reclaim behaviour under real pressure (Q15) — half proven, and the half that matters is not

**Problem.** §3.27 made the weights file-backed and left the shipping decision open on one claim:
that clean, evictable pages make the process a worse OOM victim than anonymous ones. That was
reasoning. This measures it.

**A correction to the premise before the numbers.** The M31 has **6 GB of zram** (5.1 GB free), so
anonymous pages here are *not* unswappable — they are compressed. The real contest is
drop-and-re-read-from-flash against compress-and-decompress-in-zram, which is a far narrower gap than
"reclaimable versus fatal".

**Method, including two mechanisms that failed.** Pressure is applied in 128 MB steps up to 3 GB
while the engine stays live, sampling `.ort` resident bytes from `/proc/self/smaps`, `MemAvailable`,
swap use, and a real translation at each step. Getting genuine pressure took three attempts:

- `ByteBuffer.allocateDirect` — **rejected**: Android accounts direct buffers against the VM limit,
  so a 128 MB request threw `OutOfMemoryError` while the device still had 2 GB free. It measured the
  allocator's policy, not the kernel's.
- A **private mapping of `/dev/zero`** — **rejected**: `FileChannel.map` refuses it with `IOException`.
- **`MemoryFile` (ashmem)** — works: real pinned pages, outside the Java heap.

**Result 1 — reclaim works exactly as intended (reproducible, both runs).**

| pressure | `.ort` resident | translate | still working |
|---|---|---|---|
| baseline | 322,644 kB | 641 ms | yes |
| 2,048 MB | 322,644 kB | 664 ms | yes |
| 2,560 MB | **3,172 kB** | 711 ms | yes |
| 3,072 MB | 3,024 kB | 1,326 ms | yes |

The kernel drops ~319 MB of model pages the moment it needs them back, and the app keeps translating.
The second run reproduced it (322 MB → 1,092 kB, translate 1,183 ms). **The cost is a latency spike
of roughly 2× while the pages are re-read from flash** — and it arrives exactly when the device is
already under pressure, which is worth stating plainly.

**Result 2 — the survival claim is NOT established.** Six paired trials per arm, alternating:

| arm | survived | killed by lmkd |
|---|---|---|
| mapped initializers | 5 | **1** |
| path load | 4 | **2** |

Kills happened in **both** arms. 2-versus-1 out of six is noise, not an effect. The first trial —
where the path arm was killed and the mapped arm walked through the same pressure — looked decisive
and was not: the following trial had both arms survive, and the difference traced to how much memory
the system happened to have free when each run started.

**Evidence grade:** MEASURED for reclaim (reproduced, large, unambiguous). **NOT ESTABLISHED** for
OOM survival, which was the entire justification for shipping it.

**Decision.** `mappedInitializers` **stays OFF by default.** §3.27's memory result stands — 451 MB
does become file-backed and anonymous heap does fall 151 MB — but the benefit that would justify
changing a shipping default is unproven, and there is now a measured *cost* (the 2× latency spike on
reclaim) that was not visible before this test.

**Next:** a survival difference this small needs either many more trials from a controlled starting
state (fresh boot, fixed `MemAvailable`) or a device where the app genuinely does not fit — a 3–4 GB
phone, which is the population that would benefit. Neither is available today, so the knob stays off
and documented rather than being flipped on a hunch dressed up as six data points.


### 3.29 Tokenizer parallel with session load (Q3) — **REVERTED**; and Q4 re-scoped by measurement

**Problem.** `MtEngine` built its `Tokenizer` and then its `OnnxModels`. The three graphs already
load on three threads (§3.12), but the two dictionary parses ran **first, alone, on the calling
thread** — work nothing else was waiting on.

**Implementation.** The parse is submitted to one daemon thread and the sessions build on the calling
thread; the result is awaited before the constructor returns, so every consumer still sees a fully
built engine. If the parse throws, the already-built sessions are released before the exception
leaves — otherwise three loaded sessions would have no owner. `Tokenizer.load`'s stage marks now land
on a worker with no active `Metrics` run (R6.3 is thread-confined), so the elapsed time is replayed
as a counter on the constructing thread and the startup breakdown survives the move.

**Benchmark (SM-M315F, three fresh processes per arm — one engine per process, because a second build
finds the page cache warm and the ORT environment up).**

| | before | after |
|---|---|---|
| engine construction | 4,698 / 3,499 / 3,535 ms → **median 3,535** | 3,090 / 2,380 / 2,378 ms → **median 2,380** |

**−1,155 ms, −32.7%.** Output identical (`पानी ।`).

**Evidence grade:** MEASURED. **Decision.** KEEP.

**What the same test says about Q4 — and it is not what the queue assumed.** Each half, timed alone
in its own process:

| half | median |
|---|---|
| tokenizer (two vocabularies) | **2,622 ms** |
| three ONNX sessions | 2,148 ms |

The tokenizer is the **longer** pole, not the shorter one, so it is still the critical path even after
being parallelised — concurrency hid the sessions behind the parse, not the other way round.

Then a one-line attempt at the parse cost, which failed: the vocabularies ship DEFLATE-compressed
(3.39 MB → 1.19 MB), so every load inflates ~4 MB before parsing a character. Adding `json` to
`noCompress` measured **2,605 ms against 2,622 ms — NO EFFECT**, and was reverted rather than kept
for 2.6 MB of APK and no number. The inflate is not the cost.

Three loads inside one process locate it:

| load | ms |
|---|---|
| 1st (cold pages, cold JIT) | 2,586 |
| 2nd | 1,350 |
| 3rd | 1,303 |

So ~1,300 ms is the steady-state parse (matching Phase 11B's 1,082 ms) and ~1,250 ms is the parser
running **interpreted before the JIT catches up**. Neither is I/O.

**Q4 therefore has a ceiling of about 440 ms**, not the ~1 s the queue implied: the tokenizer only
costs engine time while it exceeds the sessions' 2,148 ms, so a packed vocabulary can recover
2,586 − 2,148 on a cold process and **nothing at all** once warm. That is a real number but a small
one for a new on-disk format with its own cache-invalidation and verification burden. Q4 stays open,
re-scoped, with a cheaper thing to try first: confirm whether the generated Baseline Profile (§3.16)
actually covers `parseFlatIntDict`, since half the cold cost is JIT warm-up on that one loop.


**REVERTED 2026-08-06, the same day, on a measurement in the real app.** The benchmark above is
wrong, and how it is wrong matters more than the change did.

`EngineLoadTest.engineConstructionCost` timed `Tokenizer.load` **before** building the engine, to
report the two halves separately. That first load warmed the dictionary pages *and* let the JIT
compile the parser, so the engine's own parse ran in ~1.3 s instead of ~2.9 s and duly disappeared
behind the session loads. The 32.7% "win" was the measurement warming its own subject.

The real app, three cold launches per arm (force-stop, relaunch, `engine_init` from `Metrics`),
device at 32.3 °C:

| median | serial (before Q3) | parallel (Q3) |
|---|---|---|
| `engine_init` total | **5,134 ms** | 5,475 ms |
| `sessions:parallel` | 2,153 ms | 2,651 ms |
| tokenizer parse | 2,917 ms | **5,457 ms** |

**Q3 made cold start 341 ms worse (+6.6%)**, because the parse slowed from 2.9 s to 5.5 s — nearly
2×. The premise was that the parse ran while the machine was idle. It does not: `OnnxModels` already
loads three graphs on three threads, each ORT session with `intra=2`, so the four big cores are
saturated before the tokenizer starts. A fourth CPU-bound thread finds no spare capacity, contends
for the same cores, and pays scheduling and migration on top — the same effect §3.8 measured when
`intra_op=8` collapsed, reached from a different direction.

Reverted to the serial load. The `tokenizer_us` counter added for Q3 is **kept**: it is what made the
regression visible, and `Metrics` compiles out of release.

**Two corrections beyond the revert itself:**

1. **Engine construction is ~5.1 s, not the ~2.4 s claimed above** — that figure came from the same
   warmed benchmark. The comparison against §3.12's ~10.5 s is therefore not like-for-like, and no
   cumulative startup claim is made here.
2. **Q4 re-opens *larger*, not smaller.** The serial parse is 2.9 s of a 5.1 s cold start — the single
   biggest component of engine construction, against 2.15 s for all three ONNX sessions. The ~440 ms
   ceiling computed above came from the warm numbers and is wrong.

**The AOT arm of the same run measured nothing.** `cmd package compile -m speed -f` reported
`status=verify` afterwards: ART declines to AOT-compile a `debuggable` build. Both arms therefore ran
identically compiled, and the question — how much of the parse cost is JIT warm-up that a release
build with the Baseline Profile (§3.16) would recover — stays **unanswered** until there is a release
build, which is blocked on `AUDIT_2026-08-06.md` H4.

### 3.30 One shared weight blob per direction (Phase 13) — KEEP

**Problem.** The APK carried 909 MB of assets, and V4's own comparison against v3.4.1
(`V3_VS_V4_COMPARISON.md` §6) recorded that as the project's single measurable regression: +283 MB
against the version it replaced, written off as the price of the KV-cache. It was not the cache.

**Investigation.** Hashing the raw tensor bytes of every initializer in the exported INT8 graphs:

```
EN->HI  decoder_init 201.8 MB   decoder_step 192.3 MB
        byte-identical in both: 402 tensors, 192.3 MB
        genuinely unique to decoder_step: 0.0 MB
HI->EN  decoder_init 109.3 MB   decoder_step  99.8 MB
        byte-identical in both: 402 tensors,  99.8 MB
        genuinely unique to decoder_step: 0.0 MB
```

**`decoder_step` contains no unique tensor data at all.** `torch.onnx.export` materialises a full
copy of the decoder's weights into each graph, so 292.1 MB of the payload is a second copy of bytes
already shipped — almost exactly the +283 MB regression. A name-based comparison finds only 65.5 MB
of it, because `torch.onnx.export` mints fresh `onnx::MatMul_NNNN` names per export and the same
matrix appears under different names; content is the only usable key.

A second redundancy is visible and **not** addressed here: the tied embedding is materialised twice
*inside* each decoder as `decoder.embed_tokens.weight_quantized` (122672, 512) UINT8 and
`onnx::MatMul_6364_quantized` (512, 122672) INT8 — the same matrix, transposed, under two different
quantization schemes, so content-addressing cannot catch it. Four copies of one 62.8 MB matrix
across the EN→HI decoder pair. Queued as Q16.

**Implementation.** `model_pipeline/dedup_weights.py` rewrites a direction's three graphs so their
initializers point at one content-addressed external blob. Two deliberate limits: tensors below
`--min-bytes` (1 KB) stay inline, because constant-folded shape tensors must be readable during
shape inference and externalising them fails the load with `Cannot parse data from external
tensors` — measured, after an earlier version moved everything and broke exactly that way; and the
blob name is per direction (`weights.bin` / `hi_en_weights.bin`) because both extract into the same
`filesDir`.

App side, `OnnxModels`: `sharedWeights` declared above `init` (declaration order — the same trap as
Q14's `mappedModels`), `ensureSharedWeights` double-checked around a lock because the three loads
race for one blob, and `purgeSharedWeights` called once from `init` **before** any load rather than
per-graph — a cached graph's purge would otherwise delete the blob out from under a graph mid-bake.
Both asset layouts are supported: a build with no blob sets `sharedWeightsLength = 0` and behaves
exactly as before, so a bad export is a rebuild rather than a bricked launch.

**Two correctness traps found while wiring it, worth more than the size win:**

1. **The `.ort` cache would have gone stale silently.** `cacheStamp` keyed on the graph's asset
   length. Deduped, the graph is ~2 MB and every weight is in the blob, so a re-quantized export
   would ship hundreds of MB of new weights under a graph whose length barely moved, and the app
   would reuse the `.ort` built from the old ones — wrong translations, no error, clearing only on
   reinstall. The blob's length is now part of the key.
2. **The storage check under-reserved by ~50×.** `assetLength(graph) * 2` reserved 4 MB for a bake
   that writes 200 MB.

**Verification.** `dedup_weights.py --verify` on all six graphs: **bit-identical**, `max_abs_diff 0`.
On device: `MtEngineInstrumentedTest` 3/3 and `HiEnEngineTest` 3/3, both directions, cold bake from
a cleared app.

**Benchmark (SM-M315F, `MtBenchmarkTest`, 30 runs/sentence, 33.3–34.1 °C).**

| | 2 tok | 6 tok | 12 tok | APK |
|---|---|---|---|---|
| baseline, self-contained graphs | 159.4 ms | 350.3 ms | 626.5 ms | 893.97 MB |
| **shared blob** | **159.0** | **347.0** | **622.4** | **617.23 MB** |
| control (blob arm re-run last, hotter) | 160.3 | 349.5 | 625.8 | — |

**−276.7 MB of APK (−31%) at no latency cost** — the three arms agree within 0.8%, and the control
ran at 33.8–33.9 °C against the measured arm's 33.5–33.6, so the agreement is not thermal luck.

**What this does *not* do, measured rather than assumed.** ORT's ORT-format writer inlines every
initializer and ignores `session.optimized_model_external_initializers_*` (the baked pair is
397.9 MB with those keys set and 397.9 MB without). On device the three `.ort` files come out at
203.7 / 194.2 / 75.0 MB — **byte-identical in size to the baseline build's**. So steady-state device
storage is unchanged; the win is the APK and the download.

**Evidence grade:** MEASURED (host parity, device parity, device latency, sizes).
**Decision.** KEEP.
**Next.** Q17 — a route to carry the saving onto the device exists and is measured: baking ALL_OPT
to optimized **ONNX** with external initializers yields a 0.74 MB graph plus a separate weights file,
which content-addresses to **204.3 MB against the 397.9 MB baked pair**, with NO_OPT load times
overlapping (894→953 ms, 998→900 ms) and identical output. It requires baking on the host, which
raises a portability question ORT does not answer for us.

**One incidental finding that would have poisoned any of this.** `./gradlew assembleDebug` reported
BUILD SUCCESSFUL in 20 s and produced a byte-identical 894 MB APK **after the assets were replaced**;
only `--rerun-tasks` picked the change up. Anyone re-exporting a model can benchmark the previous one
and never know. Every arm above was built with `--rerun-tasks`.

### 3.31 Slice the last position inside `decoder_init` (Q1) — NO GAIN, and the premise was wrong

**Problem.** §9 Q1, carried as "the largest remaining inference lever": `decoder_init` runs the
output projection over *every* prefix position and returns `[1, dec_len, vocab]`, of which the
runtime reads one row and discards the rest.

**Implementation.** `DecoderInitWrapper.forward` slices before the projection —
`self.lm_head(out.last_hidden_state[:, -1:, :])`, rank kept at 3 — and `logits` drops from
`{0: "batch", 1: "dec_len"}` to `{0: "batch"}`. Only the logits are sliced; `past_key_values` still
covers every position or the self-attention cache would be short its earlier keys. `cached_export.py`
gained `--graphs` so one graph can be re-exported without rewriting the two that did not change.

**Verification.** `verify_cache.py` full gate **7/7 PASS, max_abs_diff 4.48e-01** — identical to the
shipping build. Output is `(1, 1, 122672)` at every prefix length.

**Benchmark.** Host, interleaved arms, `intra_op=2`, n=60: dec_len 1 **+0.3%**, 2 −3.0%, 8 −12.3%,
32 **−26.5%**. On the SM-M315F, against the §3.30 control:

| | 2 tok | 6 tok | 12 tok | `decoder_init` |
|---|---|---|---|---|
| control | 160.3 ms | 349.5 ms | 625.8 ms | **49.0 ms** |
| Q1 sliced | 163.5 | 352.6 | 632.7 | **49.6 ms** |

**`decoder_init` does not move**, and it is the only stage the change touches.

**Why the premise was wrong.** `GreedyDecoder` seeds `generated = [startToken]` and calls
`nextLogits` with a **one-element prefix**; `CachedLogitsSource` routes that to `runInit`, and every
later call extends by exactly one token and takes `runStep`. So `decoder_init` runs **once per
translation, always at `dec_len = 1`** — the case where the slice removes exactly zero work. The
queue entry describes something true of the graph and never exercised by the workload. For the same
reason this does **not** touch §3.24's 490 KB/token churn either: that is `lastLogitsRow` on
`decoder_step`, which already returned `[1, 1, vocab]`.

**A test pins the old contract.** `MtEngineInstrumentedTest:113` asserts `shape[1] == prefix.size` —
"one logits row per prefix position" — and fails against the sliced graph, while both translation
tests pass. It was **not** edited to green: that is the H1 mistake (`AUDIT_2026-08-06.md`) of a test
asserting the old behaviour as correct, and which contract is right is a decision, not a patch.

**Evidence grade:** MEASURED (host and device).
**Decision.** **NO GAIN on the shipping path.** Not shipped on latency grounds; it is parity-exact
and costs nothing, so it is held for the one case that does exercise it.
**Next.** Q6. Beam search falls back to `decoder_init` every step with a full prefix — precisely the
12–26% case — so this is a prerequisite for beam being viable, not a greedy optimization. Ship it
with Q6 or not at all, and settle the test contract at the same time.

### 3.32 Per-channel INT8 weight scales — REVERT

**Problem.** `quantize_cached.py` calls `quantize_dynamic(weight_type=QInt8)` with every other
argument at its default: per-tensor scales, no `reduce_range`. Per-channel scales are one keyword
argument and target *model quality*, the challenge category this project has nothing else in.

**Investigation, including a probe that was simply wrong.** The first measurement compared arms
against the fp32 graph on **random gaussian inputs** and reported per-channel 142% *worse*. That test
fed standard-normal tensors into `encoder_hidden_states`, far out of distribution for the decoder; it
measured the activation quantizer's behaviour on noise. Discarded. The project has now made this
class of error three times (§3.20's non-production sweep, §3.29's self-warmed benchmark) and the
pattern is identical each time: a cheap proxy that does not run the real thing.

The real gate, run offline against both cached checkpoints:

| arm | 3 graphs | `max_abs_diff` vs fp32 | gate |
|---|---|---|---|
| per-tensor (ships) | 472.5 MB | **0.448** | 7/7 PASS |
| `per_channel` | 475.2 MB (+0.57%) | **0.373 (−16.7%)** | 7/7 PASS |
| `per_channel` + `reduce_range` | 475.2 MB | 0.457 (+2.0%) | 7/7 PASS |

**Benchmark (SM-M315F, against the §3.30 control).**

| | 2 tok | 6 tok | 12 tok |
|---|---|---|---|
| control | 160.3 ms | 349.5 ms | 625.8 ms |
| per-channel | 169.8 | 377.1 | 650.3 |
| | **+6.0%** | **+7.9%** | **+3.9%** |

The control was re-run last and hotter, so the device did not drift: the slowdown is the change.
Per-channel scales evidently take a slower MLAS path on this Armv8.0 part.

**Verification.** `MtEngineInstrumentedTest` 3/3 — translation is correct, just slower.

**Evidence grade:** MEASURED (host quality, device latency).
**Decision.** **REVERT.** A 16.7% reduction in logit error is not worth 4–8% of end-to-end latency,
and the quality side is not even corpus-validated — the gate's reference token sequence is degenerate
(`3973` × 24), so check 7 does not discriminate between these arms. `reduce_range` is a REVERT on
both axes and should not be revisited.
**Next.** If model quality is pursued it needs a corpus and BLEU/chrF, not a logit norm, and a lever
that does not cost latency. Recorded so nobody reaches for `per_channel` again expecting a free win.

---

### 3.33 What the recogniser actually costs (Q8, part 1) — NO EFFECT, and Q8's premise was wrong

**Problem.** Q8 has stood open since Phase 10 on one number: "ASR is 0.79× realtime on the M315F —
slower than the speech it transcribes." That number came from `SpeechPipelineBenchmarkTest`, which
times `AudioFileTranscriber.transcribe(...).toList()`: one sample, of MediaCodec plus downmix plus
resample plus flow collection plus Vosk, attributed entirely to Vosk. Nothing could be tuned against
it, because it could not say which part was slow.

**Investigation.** Read the model configuration before touching code, and found what looked like a
free win: `assets/model/conf/mfcc.conf` is `--sample-frequency=8000 --high-freq=3700 --num-mel-bins=20`.
The English model is a **telephone-band model**. The app records at 16 kHz and builds
`Recognizer(model, 16000f)`, so every sample above 3.7 kHz is captured, copied, RMS'd and handed to
Kaldi — which resamples it away, because vosk forces `allow_downsample = true` after reading that
file. The hypothesis was that the wasted band plus the per-buffer resample was a real fraction of
the cost. It is not. (The Hindi model is unaffected either way: its `mfcc.conf` sets no
`--sample-frequency`, so it is a genuine 16 kHz model.)

**Implementation.** No production change. New `AsrTuningBenchmarkTest`: parses the fixture WAV by
walking the RIFF chunk list, then runs 15 timed passes per arm through a fresh `Recognizer`, polling
`partialResult` per buffer exactly as production does. Four arms — production 16 kHz; 8 kHz; and
16 kHz at 1024- and 8192-sample buffers. The 8 kHz audio is prepared **outside** the timed region by
a 65-tap Blackman-windowed sinc decimation, because that is the honest comparison: on the microphone
path the platform resampler produces 8 kHz for free from the 48 kHz the hardware captures anyway, so
the only question is what *Vosk* then costs.

**Verification.** All four arms returned a byte-identical transcript, `"i need water please help me"`;
the test fails the run if any arm changes what was heard.

**Benchmark.** SM-M315F, 33.5 °C → 33.4 °C, 15 iterations/arm, 2.65 s of audio:

| Arm | median | p95 | stdev | ms per audio-second |
|---|---|---|---|---|
| 16 kHz, 4096 (production) | 1448 ms | 1483 | 42.9 | 547 |
| 8 kHz, 2048 | 1428 ms | 1468 | 28.1 | 540 |
| 16 kHz, 1024 | 1435 ms | 1477 | 31.2 | 543 |
| 16 kHz, 8192 | 1480 ms | 1526 | 62.5 | 560 |

The whole spread between arms (1428–1480) is narrower than the sample range *within* the baseline arm
(1398–1573). Nothing here is a signal.

**Evidence grade:** MEASURED (SM-M315F, n=15 per arm).
**Decision.** **NO EFFECT** — the sample-rate mismatch is real but costs nothing worth having, and
the production buffer size is already at the optimum. No change ships. The 8 kHz idea is recorded as
closed so nobody re-derives it from `mfcc.conf` and expects a win.

**The headline result is the correction, not the arms.** Vosk alone runs the fixture in 1448 ms of
2.65 s of audio: **0.55× realtime, i.e. 1.8× faster than the speech it transcribes**, not 0.79×. The
recogniser keeps up with live dictation on this device with 45% headroom. The missing ~640 ms in the
old figure (2087 ms, 31% of it) is the **file-import path** — MediaCodec, downmix, the naive
resampler and flow collection — which the microphone never pays. Q8 was aimed at the wrong stage.

**Next.** Two follow-ups, neither of them the one Q8 named. (a) The 45% headroom is the *English*
model's on a mid-range device; `model-hi/conf/model.conf` still ships the wide decode configuration
(`--max-active=7000 --beam=13.0 --lattice-beam=4.0`) where English ships the fast one (3000/10.0/2.0),
so Hindi is the arm that can plausibly exceed realtime — and it cannot be scored, because there is no
Hindi audio fixture. (b) If the import path is worth 640 ms, that is where an ASR optimization would
actually pay.

---

### 3.34 Hindi decodes too wide, and it only shows in noise (Q8a) — KEEP

**Problem.** §3.33 closed Q8 by showing the recogniser keeps up: 0.55× realtime in English. That was
measured on clean synthesised speech, which is the one condition a recogniser is never used in. The
two shipped models also disagree about how hard to search — English carries the small-model fast
configuration, Hindi the wide one — over a Hindi graph 2.4× larger (55.9 MB of FST against 34.4 MB).

**Investigation.** `AsrTuningBenchmarkTest` grew a Hindi arm and, more importantly, three *conditions*:
clean, and the same utterance at 10 dB and 5 dB SNR with fixed-seed white noise. The noise is what
found this. On clean audio the stock Hindi configuration looks fine (0.65×) and the retune looks like
a 19% nicety. Under noise the stock configuration **collapses past realtime — 1.91× at 10 dB, 2.59×
at 5 dB** — because a decoder that cannot find a confident path keeps `max-active=7000` hypotheses
alive and pays for all of them. That is the failure mode the app is built for: a traveller in a
market, a station, a hospital corridor. At 1.91× a ten-second Hindi sentence takes nineteen seconds,
and the backlog grows for as long as the speaker keeps talking.

There is no Hindi fixture in the repo, and that gap is why this was never measured. One was made:
synthesised on the device through Google TTS's Hindi voice, resampled 24 kHz → 16 kHz and
silence-trimmed on the host, committed as `androidTest/assets/speech_hi_paani.wav` (3.09 s,
`"मुझे पानी चाहिए। कृपया मेरी मदद कीजिए।"`).

**Implementation.** Two files, and the second is what makes the first reach anybody.
`assets/model-hi/conf/model.conf`: `--max-active` 7000 → 3000, `--beam` 13.0 → 10.0, `--lattice-beam`
4.0 → 2.0 — the values English already ships; every other line untouched. Then `AssetFolder.unpack`,
which returned early whenever the destination directory existed and was non-empty. `filesDir` survives
app updates, so that early return meant **a retuned asset would reach new installs only** — every
existing user would keep decoding at 7000 forever, silently. It now writes a `<folder>.stamp` beside
the published directory holding `VERSION_CODE` plus a hash of the `conf/` files, and re-unpacks on a
mismatch. Keyed on `conf/` and not the whole folder because hashing 56–81 MB per launch is
unaffordable and `openFd` throws on these assets (`noCompress` covers `onnx`/`bin`/`pb`, not
`.mdl`/`.fst`).

**Verification.** In the deciding run, transcripts were identical between every configuration within
every condition — including both degraded ones, where all arms were wrong in exactly the same way.
**§3.36 corrects how much that is worth:** a re-run showed the recogniser is not deterministic at
10 dB across repeats of the *same* configuration, so some of that identity was luck. The speed
result is untouched and reproduced exactly; the accuracy conclusion is now "no change attributable
to the beam", not "identical output". New `SpeechAssetFreshnessTest` 2/2 proves both halves on a
device that started from a **stale unpacked model**: `filesDir` held `max-active=7000` before the
install and 3000 after the first load, and the Hindi model still transcribes `पानी`. Full speech
suite 8/8 instrumented, 27 JVM unit tests green.

**Benchmark.** SM-M315F, 15 iterations per cell, 3.09 s of audio, arms in fixed order with the stock
configuration repeated last as the drift control:

| Condition | stock 7000/13.0/4.0 | **shipped 3000/10.0/2.0** | mid 5000/11.5/3.0 | stock re-run |
|---|---|---|---|---|
| clean | 2003 ms · 0.65× | **1626 ms · 0.53×** | 1722 ms · 0.56× | 1997 ms · 0.65× |
| 10 dB SNR | 5918 ms · **1.91×** | **2354 ms · 0.76×** | 3952 ms · 1.28× | 5924 ms · 1.92× |
| 5 dB SNR | 8000 ms · **2.59×** | **3057 ms · 0.99×** | 5265 ms · 1.70× | 7995 ms · 2.59× |

−19% clean, **−60% at 10 dB, −62% at 5 dB**. The drift control reproduces the stock arm to within
0.3% at every condition while the battery rose 34.2 → 34.9 °C, so none of this is thermal. The `mid`
arm is recorded because it settles the shape of the trade: halfway is still 1.28× at 10 dB, i.e. still
losing to live speech, so there was no cautious middle worth taking.

**Evidence grade:** MEASURED (SM-M315F, n=15 per cell, counterbalanced) for latency. For accuracy:
**MEASURED but narrow** — one utterance, one synthetic voice, white noise rather than babble. It
establishes that the narrower beam does not change this recogniser's answer; it is not a WER corpus
and no claim beyond that is supported.
**Decision.** **KEEP.** A configuration that exceeds realtime in the conditions the product exists to
serve is a defect, not a tuning preference, and the fix costs nothing measurable in accuracy.
**Next.** The accuracy evidence is the thin part and the honest way to thicken it is a small recorded
Hindi corpus with a reference transcription — the same thing Q6 needs for beam-vs-greedy. Until that
exists, treat "transcript unchanged" as the bound, not "WER unchanged". English was already at the
narrow configuration and is untouched, but it has never been measured under noise either; the same
three conditions applied to the English model would say whether 0.55× clean survives a market.

---

### 3.35 Does the speech path leak when it is used repeatedly? — NO, and now it is proven

**Problem.** §3.24's resource-lifecycle pass closed a Vosk use-after-free, a leaked `AudioRecord`, an
uncancellable `MediaCodec` loop and a lost `stop()` — all by **reading**. Every speech test runs one
session, and one session cannot tell a working release from a missing one: both look identical when
the resource is acquired once and the process then exits. The audit could say the code was now
correct; it could not say the process was clean.

**Implementation.** No production change. `SpeechChurnLeakTest` repeats each speech path and measures
drift after a warm-up: 20 file transcriptions, and 32 microphone sessions built through a **fresh
`AudioCapture` per cycle** — harsher than production, which keeps one for the ViewModel's life,
because a per-session leak hides inside a reused object's steady state. `RECORD_AUDIO` is granted
through `uiAutomation`, so the microphone path runs headless on silence; the resources being counted
are acquired and released identically whether or not anyone speaks.

**File descriptors are the instrument, not memory.** `AudioRecord`, each of the three audio effects,
`MediaCodec` and `MediaExtractor` all hold a kernel object with an fd apiece, so a missed release is a
straight line in `/proc/self/fd` that no allocator, GC or page cache can blur. Native heap is reported
but bounded loosely — §3.25 already measured jemalloc returning pages on its own schedule, and a tight
bound there would fail honestly-clean runs.

**Benchmark.** SM-M315F, probed in quarters:

| Path | cycles | fds | threads | native heap | PSS |
|---|---|---|---|---|---|
| file transcription | 20 | 90 → 90 (**0**) | +2 | +133 KB | +198 KB |
| microphone session | 32 | 92 → 86 (**−6**) | 0 | **−1074 KB** | −655 KB |

Quartering is what settled it. A first 12-cycle run showed the microphone path gaining 2.3 MB of
native heap and it was not obvious from two numbers whether that was hysteresis or a slope; over 32
cycles the series reads 93054 → 94121 → 93118 → 94281 → 91980 KB — it oscillates by ~1 MB and finishes
**below** where it started. Not a leak, an allocator.

**Evidence grade:** MEASURED (SM-M315F).
**Decision.** **NO LEAK.** Both paths hold flat. The test ships as a regression guard, which is the
durable value here: the next change to `AudioCapture` or `AudioFileTranscriber` now has something that
fails if it drops a release, instead of a reviewer who has to notice.
**Next.** Untested by this: the direction-swap and trim paths, where the *models* are released rather
than per-session objects. §3.24b and §3.25 measured those once each; neither has a churn test.

---

### 3.36 The recogniser is nondeterministic per model load — §3.34's accuracy claim was too strong

**Problem.** §3.34 shipped the Hindi retune partly on "transcripts are identical between every
configuration within every condition". Re-running the sweep after the change broke that claim in the
most direct way available: **`hi_shipped` and `hi_shipped_recheck` — the same configuration, run
twice in the same test — heard different things.** At 10 dB in one run the first arm dropped a
trailing word; at 5 dB in the next, the two produced `"जैसे काली स्याही में लगे"` against
`"जैसे काली स्याही फेंकने लगे"`. If the control pair can disagree, a difference between two *real*
arms cannot be attributed to the arm, and §3.34 was reading some luck as evidence.

**Investigation.** The instability is **per `Model` instance, not per utterance**, which is the part
that makes it easy to miss. Every arm in the re-run reported `distinct=1`: 15 repeated recognitions
through one loaded model are byte-identical every time. Load the model again with the same
`conf/model.conf` and the search can settle somewhere else. So the original harness — which kept only
the last iteration's transcript — was not even wrong about stability *within* an arm; it simply had
no way to see across arms, and neither does the obvious fix of counting distinct transcripts per arm,
because each set has exactly one member.

The counterbalance arm was already there for latency drift. It turns out to be the right control for
this too, and it costs nothing extra.

**Implementation.** Test-only, three changes to `AsrTuningBenchmarkTest`. Arms now collect the **set**
of transcripts over their repeats and log `distinct=N`, with every variant printed when N > 1. The
verdict is computed against the control pair: `SAME`, `CHANGED`, or **`NOISY`** when
`hi_shipped_recheck` disagrees with `hi_shipped` in that condition, plus an explicit
`CONTROL_DISAGREES` line. And the comparison arms carry **literal** values — `hi_wide_7000` pins
7000/13.0/4.0 rather than "English's configuration", because once Hindi *became* that configuration
the old arm names described the same numbers and the wide setting silently dropped out of the
comparison the test exists to guard.

**Benchmark.** SM-M315F, control agreeing to within 1.2% at every condition, so this is the run to
quote. It reproduces §3.34's headline from the other direction — the pre-retune configuration is
still slow, and still heard exactly the same thing:

| Condition | shipped 3000/10/2 | wide 7000/13/4 | mid 5000/11.5/3 | shipped re-run |
|---|---|---|---|---|
| clean | 1641 ms · 0.53× | 1993 ms · 0.64× | 1732 ms · 0.56× | 1660 ms · 0.54× |
| 10 dB SNR | 2383 ms · 0.77× | **5970 ms · 1.93×** | 3968 ms · 1.28× | 2356 ms · 0.76× |
| 5 dB SNR | 3084 ms · 1.00× | **8048 ms · 2.60×** | 5255 ms · 1.70× | 3077 ms · 1.00× |

1.93× and 2.60× against §3.34's 1.91× and 2.59×, now measured three times — the wide configuration's
cost is the most reproducible number in this section, and the regression guard fires.

**One intervening run is not quotable, and the control is how you know.** An earlier attempt had
`hi_shipped` come out 7–10% *slower* than `hi_shipped_recheck` at every condition, stdev 238/212
against 14/13, because the first arm ran while dex verification was still settling after an APK
install. Its ratios were right (1.93×, 2.60×); its absolute baseline was not. Read the control pair
before reading the numbers.

**How often the instability actually appears:** twice in four sweeps, each time one condition in one
arm, never twice in the same place. So the `NOISY` branch is correct-by-construction rather than
correct-by-observation — it has not fired since it was written, because every run since has had an
agreeing control. Recorded so that whoever first sees `CONTROL_DISAGREES` reads it as the expected
behaviour of this recogniser rather than a broken test.

**Evidence grade:** MEASURED. **Decision.** **KEEP the retune; correct the claim.** The decision
§3.34 made is unaffected — a 2.5× latency difference is far outside anything this nondeterminism
moves, and no arm has ever been observed to hear *worse* than the wide one. What changes is the
strength of the accuracy statement: it is **"no change attributable to the beam"**, not "identical
output".

**Next.** The 5 dB result deserves naming: at 0.93–1.01× even the narrowed configuration sits on the
realtime line on this device, so the retune bought headroom at 10 dB rather than immunity at 5 dB. A
phone slower than the M31 will be over 1.0 there. That is the case Q8c's English arm should also be
measured against, and it is an argument for an endpointing or partial-flush strategy rather than
another beam change — the decode width has now been swept and there is nothing left in it.

---

### 3.37 The thread policy, measured on the production path at last (Q2b) — KEEP

**Problem.** §3.21 tightened the intra-op clamp from `[1,4]` to `[1,2]` and recorded itself as
**INFERRED**: the numbers came from the SM-S948B *before* the edit, and that device is gone. It has
been the ledger's one unconfirmed shipping default ever since. Checking what it would take to confirm
turned up worse than a missing run — the harness could not have answered the question:

- **No arm in `ProductionThreadSweepTest` was the shipping configuration.** Every arm set
  `intraOpAffinities = null` while production ships `intra=2` **with** one pinned worker. The sweep
  compared six things, none of which was what users run.
- **`intra=3` had never been measured on any device** — the arms were 1/2/4/6/8.
- **Inter-op had never been measured on the production path at all.** `interThreads` is plumbed
  (`OnnxModels.kt`), but ORT ignores it unless the execution mode is PARALLEL, so an arm that sets it
  alone measures nothing. The only evidence was Phase 7's `parallel_inter2` REVERT, taken under
  ALL_OPT with the cache off — and rule #2 of this document says such results do not transfer.
- tokens/sec, CPU utilization and frequency were not collected, and battery temperature came from a
  sysfs path that read `n/a` for the **entire** S26U run.

**Investigation.** Two suites, both on the real load path (baked `.ort`, NO_OPT, mmap), n=30 per arm
per sentence, three rounds with the config order rotated, parity asserted on output **and** generated
token count. `SHIPPING` — `ExecutionPolicy.current` untouched — runs in both suites as the common
control, so the two invocations can be checked against each other before being read together.

Arms are now built by one function that regenerates the affinity string from
`ExecutionPolicy.affinityString` and `require()`s ORT's `threads - 1` group count. That is not
tidiness: `base` carries exactly one group, ORT rejects any other count at `createSession`, and
inheriting it would have thrown for every arm with a different thread count ~2.4 s into a session
load. The same function forces `parallel = true` whenever `interThreads` is set.

**Implementation.** Test-only — `ProductionThreadSweepTest` plus the `CountingDecoder` promoted out of
`TruncationCorpusTest` (`b5b8baa`), and one counter fix (`6929a59`). Nothing under `app/src/main`
changed, so nothing shipped changed.

Instrumentation is `SystemStats` snapshots bracketing the timed runs only: process CPU seconds →
cores-busy and **CPU-ms per translation**, migrations and involuntary context switches, in-load
per-core frequency, battery temperature via `BatteryManager`, and a PSS tripwire. No background
sampler — these are exact kernel counters — and no system-wide CPU figure is reported, because
`/proc/stat` is SELinux-blocked and *reads back empty rather than failing*, which would make it a
plausible-looking zero.

The first smoke run exposed exactly that failure mode in the metric it *did* have:
`migrations=0 nonvolCtxt=0` on all six arms, because `SystemStats` reads `/proc/self/sched` and
`/proc/self/status`, and both describe the **thread-group leader** — the app's idle main thread, which
neither translates nor belongs to ORT's pool. Summed across `/proc/self/task/*/sched` instead (the
field is `se.nr_migrations`, matched exactly so `nr_migrations_cold` does not fold in) it became the
most informative column in the sweep.

**Benchmark.** SM-M315F, `perf=4[4,5,6,7]`, charging, 34.3 → 33.8 °C — the device *cooled* during the
ladder. Drift ratio 1.04 and 1.00; the control pair agrees to **2.6% long / 0.0% short**, so anything
smaller than ~2.6% across suites is not a result. Full tables in
`bench/results/cross-device/m31_exynos9611_thread_sweep.md`, raw log alongside it.

| arm | long | Δ | short | Δ | stdev | p95 | CPU-ms/tx | migrations |
|---|---|---|---|---|---|---|---|---|
| **SHIPPING** intra2+pin | **623** | — | **163** | — | 21.3 | 677 | 968 | 170 |
| `intra1` | 721 | +15.7% | 185 | +13.5% | 17.8 | 760 | **512** | 26 |
| `intra2_noAff` | 641 | +2.9% | 165 | +1.2% | 47.3 | 711 | 987 | 343 |
| `intra3_aff` | 628 | +0.8% | 182 | +11.7% | 30.0 | 663 | 1564 | 789 |
| `intra4_aff` | 674 | +8.2% | 207 | +26.9% | 60.3 | 788 | 2193 | 1860 |
| `intra4_noAff` | 643 | +3.2% | 195 | +19.6% | **88.8** | **896** | 2151 | 1262 |
| `intra2_parallel_inter1` | 619 | −3.1%* | 160 | −1.8%* | 20.4 | 667 | 972 | 163 |
| `intra2_parallel_inter2` | 729 | **+14.1%** | 224 | **+37.4%** | 42.2 | 871 | 1783 | 585 |
| `intra6_noAff` | 751 | +17.5% | 264 | +62.0% | 79.1 | 899 | 3228 | 2825 |
| `intra8_noAff` | 913 | **+42.9%** | 326 | **+100%** | 59.3 | 1033 | 4195 | 4624 |

\* against the EXECMODE suite's own `SHIPPING` (639 ms / 163 ms), i.e. inside the noise floor.

Four findings, in the order they settle things:

1. **The shipping configuration is the fastest arm on both sentence lengths**, at the second-lowest
   stdev in the set. Not a tie broken by rounding: the next-best short median is +1.2% and everything
   else is +8% or worse.
2. **4 threads loses in both forms.** Pinned — 4 threads at its best — is +8.2% / +26.9% with 2.8× the
   stdev. Unpinned is nominally +3.2% on the long median but carries the **worst jitter in the sweep**
   (stdev 88.8 ms, p95 896 ms against 677 ms): judged on median + jitter rather than the best single
   run, it is the worst arm on the ladder. That is the claim under the clamp, on the production path.
3. **3 threads is not a rung.** Best p95 in the suite and a long median inside the noise floor, but
   **+61% CPU** and +11.7% on the short sentence — the shape most real input takes.
4. **PARALLEL mode is free; the second inter-op thread is not.** The `inter1` control changes nothing
   measurable and burns *identical* CPU (972.33 vs 972.33 ms/tx), so the cost is the second thread:
   +14.1% / +37.4% and +83% CPU. **Phase 7's `parallel_inter2` REVERT now has production-path
   numbers** — it transfers, which rule #2 did not let us assume.

Two things fell out that were not the question. **Affinity is not neutral on this device after all:**
single-variable, pinning halves migrations (170 vs 343), cuts involuntary switches 62%, and drops
stdev 55% (21.3 vs 47.3 ms), at a median difference still inside the noise floor. The earlier "neutral
on the SM-M315F" reading came from medians alone; affinity's claim was always about jitter, and this
is the first run on this device that could see the mechanism. And **`intra1` costs half the CPU of
shipping for +15.7% latency** (512 vs 968 CPU-ms/tx) — already measured, if a battery-saver or
thermally-throttled mode is ever wanted.

**Evidence grade:** **MEASURED — for the claim, not for the bound.** The M31 has 4 performance cores
and therefore *derives* 2; it cannot reach the `[1,2]` clamp's upper bound at all. This sweep sets
`intraThreads` explicitly, so it tests "4 threads never wins" and not the bound itself. **The bound
stays INFERRED for 8-performance-core parts**, and no obtainable device derives 4 — the S22 Ultra also
derives 2 after `dc3011e`. §3.21 is now confirmed on its substance and still unconfirmed on its
arithmetic, which is a smaller gap than it had and an honest one.

**Decision.** **KEEP `(perfCores / 2).coerceIn(1, 2)` with big-cluster affinity, unchanged.** No
shipping code was touched by this entry.

**Next.** Run both suites on the S22 Ultra when it is available: it adds an ARMv9 / i8mm / SVE2
topology and, with `disableKleidiAi`, separates "KleidiAI is worth something" from "SME is worth
something" (Q12). Note also that `bench/results/cross-device/samsung_s22ultra_snapdragon8gen1.md`
records `perf=1[7], eff=7` → `intra=1`: that entry was measured under the **old** frequency-only
perf/eff split, before `CpuCapabilities.perfEffSplit` was changed to split at the bottom tier, so its
numbers are single-threaded and do not represent shipping policy. Re-measure it or mark it stale.

---

### 3.38 The clamp's bound, closed on the device that reaches it — and KleidiAI does not reproduce

**Problem.** §3.37 closed the *claim* under the `[1,2]` clamp and left its *bound* INFERRED, because
no obtainable device derived 4 threads. The SM-S948B became available again on 2026-08-12. It is the
only topology in the database that derives 4 — 8 uniform Oryon cores (`CPU part 0x002` ×8), so the
uniform-IP rule counts all eight as performance and `8 / 2 = 4` is exactly what `coerceIn(1, 2)`
truncates. The bound is testable on this part and nowhere else.

**Investigation.** Same harness, same protocol as §3.37 (n=30 per arm per sentence, three rotated
rounds, parity on output and token count), plus a third suite: `sweepKleidiAi`, an A/B of
`mlas.disable_kleidiai` at two thread counts, which is only meaningful on silicon with something to
dispatch to.

Affinity is unavailable on a uniform-IP part — `efficiencyCoreIds` is empty, so `affinityString`
returns null and the arms fall back to no-pin. That turned out to be a gift: it produces **two
duplicate pairs**, arms whose configurations are byte-identical, which measure the run's
repeatability directly. `SHIPPING` ≡ `intra2_noAff` came out **3.2%** apart and
`intra4_aff` ≡ `intra4_noAff` **0.0%** apart, so the floor for this run is ~3%.

**Benchmark.** Full tables in `bench/results/cross-device/s26ultra_thread_sweep_2026-08-12.md`.

| arm | long | Δ | short | Δ | stdev | CPU-ms/tx | migrations |
|---|---|---|---|---|---|---|---|
| **SHIPPING** (intra2) | **95** | — | **26** | — | 3.73 | 158 | 131 |
| `intra1` | 89 | −6.3% | 24 | −7.7% | 5.07 | **64** | 30 |
| `intra2_noAff` (≡ SHIPPING) | 92 | −3.2% | 25 | −3.8% | 3.47 | 154 | 139 |
| `intra3` | 93 | −2.1% | 26 | 0.0% | 3.23 | 243 | 420 |
| `intra4_aff` | 102 | **+7.4%** | 31 | **+19.2%** | 4.76 | 329 | 714 |
| `intra4_noAff` | 102 | **+7.4%** | 30 | +15.4% | 5.17 | 330 | 693 |

**4 threads loses on the one device that would ever derive it** — +7.4% long, +15–19% short, higher in
every round, at 2.1× the CPU. The old `[1,4]` clamp would have shipped precisely this configuration
here.

**One arm is not settled and is recorded as open.** `intra1` is −6.3% / −7.7% against `SHIPPING` and
lower in all three rounds at **40% of the CPU** — but against its own duplicate control it is only
−3.3% / −4.0%, at the repeatability floor, with a *worse* stdev. It does not clear the >5%-on-both
bar, so it is not actioned.

**The thermal rule earned its keep.** The execution-mode suite entered at 36.3 °C with drift 1.17 and
its `SHIPPING` control read **118 ms against the ladder's 95 ms — 24% apart, same code, same session**.
Its arms reproduce the M31's shape (PARALLEL free, `inter2` +4.2%/+12.9%, `intra8` +41.5%) but its
magnitudes are not quotable and no number crosses between the two suites.

**KleidiAI: entry #9's direction does not reproduce.** §3.20 priced KleidiAI's SME kernels at **4–9%
in favour of ON**, from two runs it described as thermally degraded (stdev 12–25 ms). Re-measured at
two thread counts in two thermal states, with stdev 2.4–9.5 ms and non-overlapping per-round medians:

| | KleidiAI on | off | Δ |
|---|---|---|---|
| intra2 (shipping), 36.3 °C | 119 ms | **107 ms** | **off 10.1% faster** |
| intra2 (shipping), 33.2 °C | 92 ms | **80 ms** | **off 13.0% faster** |
| intra4, 36.3 °C | 128 ms | 121 ms | off 5.5% faster |
| intra4, 33.2 °C | 96 ms | 93 ms | off 3.1% faster |

Four points, both thermal states, **all the opposite sign to §3.20**. The effect is largest at 2
threads, which is what this device ships, and KleidiAI also costs 25–56 MB of PSS. A cold re-run was
done specifically to test whether the sign was a throttling artefact; it is not — cold shows the
larger gap.

**SME is still live** — this is a claim about the kernels being faster, not about them running.
`s26ultra_simpleperf_sme.txt` still shows the hottest symbol as
`kai_run_matmul_clamp_f32_qai8dxp1vlx4_qsi8cxp4vlx4_1vlx4vl_sme_mopa`. What changed between the two
sessions is neither the ISA nor ORT (1.27.0 both times) but ~30 commits of build, of which §3.30's
shared weight blob and the `.ort` opt-cache path are the named suspects — a hypothesis, not a finding.

**Evidence grade:** **MEASURED** for the thread bound (one device, the only one that reaches it) and
**MEASURED, CONTESTED** for KleidiAI — two sessions disagree on the sign, and this one has the better
controls but is still a single device.

**Decision.** **KEEP the thread policy, now bound-confirmed: §3.21 moves from INFERRED to MEASURED.**
**Nothing shipped for KleidiAI.** Disabling it is worth 10–13% at the configuration this part runs,
which makes it a policy question and not a benchmark note — but a policy that keys off `caps.sme`
would ship on the evidence of one device against a prior session that measured the opposite, and
`disableKleidiAi` is a benchmark-only knob today.

**Next.** Three things, in order: a dedicated cold `intra1` vs `intra2` A/B on this part (§1.1 of the
results file); a third cold KleidiAI run, and if possible a second SME device, before any policy keys
off it; and — if the KleidiAI regression holds — a bisect against §3.30 to find what changed, since
that is the difference between a device quirk and a build defect.

---

### 3.39 Both of §3.38's open arms, counterbalanced — KleidiAI settles, `intra1` does not

**Problem.** §3.38 left two questions open on the SM-S948B, and both were open for the same reason:
the arm of interest was compared against a control that came from somewhere else — another suite,
another run, or a duplicate that happened to exist. `intra1` was −6.3% against one control and −3.3%
against another. KleidiAI reversed §3.20's sign but had no duplicate pair of its own.

**Investigation.** Re-run both cold with the control **inside** the design: every configuration
appears twice, so `_a` vs `_b` measures the run's repeatability and the arms of interest measure the
effect. Two new suites, `sweepOneVsTwo` and a `sweepKleidiAi` extended with recheck arms at the
shipping thread count. Test-only.

**Benchmark — `intra1` vs `intra2`.** 30.4 → 31.8 °C, **drift 1.00**, cores at 2942 MHz throughout.
The duplicate pairs landed on **identical medians** — `intra1` 83/83, `intra2` 87/87 — so the floor
is ~0:

| | long | short | stdev | CPU-ms/tx |
|---|---|---|---|---|
| `intra1` (a / b) | 83 / 83 | 22 / 22 | 2.18 / 3.64 | 59 / 57 |
| `intra2` (a / b) | 87 / 87 | 23 / 24 | 1.33 / 3.00 | 141 / 143 |

Real and reproducible: **−4.6% long at 41% of the CPU**, round medians never overlapping. And still
**sub-threshold** — 4.6% on the long sentence misses the >5%-on-both bar by one number. **Not
shipped**, and it must not become a rule regardless: the same arm is **+15.7% on the SM-M315F**
(§3.37). One thread wins on eight wide Oryon cores and loses badly on four A73s, so this is a
device-class effect with no predicate any current detector supplies. The durable part is the CPU
column — 41% of the energy for 4.6% more latency is what a battery-saver mode on this silicon class
would look like.

**Benchmark — KleidiAI, third replicate.** 31.8 → 33.4 °C, drift 1.03, with recheck arms:

| | long | short | CPU-ms/tx | PSS | round medians |
|---|---|---|---|---|---|
| on (arm / recheck) | 90 / 91 | 24 / 24 | 149 / 150 | 673 / 677 MB | 89·92·90, 89·91·93 |
| off (arm / recheck) | **78 / 80** | **21 / 22** | 133 / 135 | 653 / 638 MB | 77·78·81, 78·81·81 |

**Controls agree to 1.1% and 2.5%; the effect is 12.7% — five times the floor**, with no overlap in
any round. Across all three runs at the shipping thread count: −10.1% (36.3 °C), −13.0% (33.2 °C),
−12.7% (31.8 °C); at `intra4`, −5.5% / −3.1% / −5.2%.

**It is not a latency-for-energy trade.** Disabling KleidiAI also costs ~10% less CPU (133–135 vs
149–150 ms/tx) and ~35–45 MB less PSS. Better on all three axes simultaneously.

**Evidence grade:** MEASURED. KleidiAI is settled **on this device and this build** — three runs,
three temperatures, in-run controls, against §3.20's two runs that it called thermally degraded
(stdev 12–25 ms, against 2.0–2.5 ms here). §3.20's magnitude is superseded and its **direction is
withdrawn**; what survives from it is that SME executes, which `simpleperf` showed directly.

**Decision.** **Nothing shipped yet, and one cheap test stands between here and shipping.** Entry #9
established that KleidiAI's NEON `dotprod`/`i8mm` kernels are `qsi4c32p` — **4-bit**, inert for this
project's 8-bit weights — and only its SME kernels are 8-bit `qsi8cxp`. If that holds,
`disableKleidiAi = true` is a **no-op on every non-SME part** and a 12.7% win on SME silicon, which
makes an unconditional setting as safe as one keyed off `caps.sme` and far simpler. **That no-op is an
assumption until measured.**

**Next.** Run `sweepKleidiAi` on the SM-M315F (Armv8.0: no dotprod, no i8mm, no SME). Indistinguishable
arms there confirm the inert half and put the change on two devices; a difference there means the knob
does something outside SME and the policy must key off `caps.sme` instead. Only then decide whether
`ExecutionPolicy` sets it — and it is a shipping default, so it lands with its own entry, not this one.

---

### 3.40 SHIPPED — `ExecutionPolicy` disables KleidiAI on SME parts

**Problem.** §3.39 settled that KleidiAI's SME kernels are 10–13% *slower* than MLAS's own at the
shipping thread count on the SM-S948B, at ~10% more CPU and ~35–45 MB more PSS. `disableKleidiAi` was
a benchmark-only knob, so every SME device shipped the slower path.

**Implementation.** One line in `ExecutionPolicy.select`: `disableKleidiAi = caps.sme`, plus the
policy `name` gaining a `,noKleidiAI` marker and the debug log line printing `kleidiAI=on|OFF`.
Nothing else in the policy moves.

**The predicate is `caps.sme`, not `true`, and that is the whole design.** Entry #9's kernel names
argue KleidiAI is inert off SME silicon — its NEON `dotprod`/`i8mm` kernels are `qsi4c32p`, 4-bit,
useless for 8-bit weights — and the SM-M315F measured that directly: KleidiAI on/off there differed
by **2.1% against that run's own 4.0% control spread**, i.e. not attributable, with `intra4` pointing
the other way. But the M31 has *neither* dotprod nor i8mm, so it cannot speak for parts that have
i8mm without SME (the S22 Ultra, the Dimensity entries). Keying off `caps.sme` makes every non-SME
device byte-identical **by construction** rather than by an argument, and costs one branch.

**Verification.** `ExecutionPolicyTest.kleidiAiIsDisabledOnlyOnSmeParts` pins both directions and
asserts threads/affinity/arena do not move with the flag — the same guard style that kept the thread
clamp from drifting. Full JVM suite green; `:app` and `:benchapp` both assemble (the mirror compiles
`ExecutionPolicy` too).

On the SM-S948B after the change, cold: policy derives
`arm-adaptive(threads=2,noKleidiAI) intra=2 arena=false affinity=OFF kleidiAI=OFF`,
`MtEngineInstrumentedTest` **3/3 parity-exact**, `BenchmarkSuiteTest` 1/1.

| | entry #9 (2026-07-31, `intra=4`) | now (`intra=2`, KleidiAI off) |
|---|---|---|
| inference median | 99 ms | **78 ms** |
| p95 | 113 ms | **85 ms** |
| first translation | 110 ms | 86 ms |
| tokens/sec | 412.8 | **565.4** |

**That −21% is compound and must not be attributed to this change.** Between those two measurements
sit the intra-op clamp (worth ~6% on this part on the current build), this KleidiAI change (~12.7%),
and roughly forty commits of other work. The isolated price of KleidiAI is the A/B in §3.39, not this
table.

**Evidence grade:** MEASURED on one SME device (three A/B runs plus this post-change confirmation);
the non-SME half is MEASURED on the M31 as a null result and guaranteed structurally by the
predicate.

**Decision.** **SHIP.** Non-SME devices: no behavioural change. SME devices: −12.7% latency, ~10%
less CPU, ~35–45 MB less PSS.

**Next.** Two things this rests on that a second SME device would firm up: that the regression is a
property of SME silicon generally rather than of this part, and that §3.20's opposite result was a
measurement artefact rather than a build difference. If a bisect against §3.30 ever explains the
latter, the predicate may want to be narrower — or the underlying cause fixed instead, at which point
this line should be re-tested rather than assumed.

---

### 3.41 Full instrumented regression on the SM-S948B — clean, and one test was wrong

**Problem.** §3.40 shipped a policy change that alters which int8 kernels ORT dispatches, on a device
class the suite had last been run against on 2026-07-31 with 13 test classes. There are now 27, plus
~40 commits. A kernel-selection change is exactly the kind that surfaces in the memory-lifecycle and
speech paths rather than in the translation numbers, so the whole suite was run on ARMv9/SME.

**Benchmark.** 21 classes, 43 tests, **zero product failures**. Everything below ran on the SM-S948B
at `arm-adaptive(threads=2,noKleidiAI)`:

| area | classes |
|---|---|
| translation, both directions | `MtEngineInstrumentedTest` (3), `HiEnEngineTest` (3), `HiEnBenchmarkTest`, `MtBenchmarkTest` |
| load path | `OptCacheTest`, `ParallelSessionLoadTest`, `EngineLoadTest` (4), `StartupProbeTest` (3), `MmapPrepackTest` |
| memory & lifecycle | `EngineFootprintTest` (2), `MappedInitializersTest` (2), `NativeMemoryReturnTest`, `PressureReclaimTest` (2), `TrimReleaseTest` (5) |
| speech | `SpeechAssetFreshnessTest` (2), `AudioFileTranscriberTest`, `SpeechPipelineBenchmarkTest`, `SpeechChurnLeakTest` (2) |
| decode | `TruncationCorpusTest` (2), `LogitsReadBenchmarkTest`, `BenchmarkSuiteTest` |

**HI→EN ran on this device for the first time** — engine and benchmark both clean. Every speech class
had also only ever run on the SM-M315F before today.

**The one failure was the test's, not the product's.** `SpeechChurnLeakTest` reported
`file_transcription leaked file descriptors: +14 over 20 cycles (116 -> 130)`. Run out to 60 cycles
the shape settles it:

```
baseline 116 · 15 cycles 136 · 30 cycles 136 · 45 cycles 136 · 60 cycles 136
```

**Flat for 45 consecutive cycles**, native heap +100 KB total, PSS +441 KB. A 0.33 fds/cycle leak
would have reached +42 by cycle 60; instead every descriptor is taken before cycle 15 and none after.
It is a bounded one-time cost of Android 16's codec stack — bigger than the M31's, where the 3-cycle
warm-up absorbed it entirely, which is why §3.35 saw fds flat there. The `microphone_session` arm,
which opens an `AudioRecord` and three audio effects per cycle, is **132 at every probe** — the
control that places the cost in `MediaCodec`/`MediaExtractor` rather than in the speech path at large.

**Root cause in the harness:** `churn()` probes in quarters *precisely* to tell settling from leaking —
its own comment says "a total is not a shape" — and then asserted on `after - before`, throwing that
shape away. The assertion now measures growth **after the halfway point**: a real one-per-cycle leak
puts `cycles / 2` descriptors in that window and is caught *harder* than before, while a plateau puts
zero. Cycle counts became instrumentation args (`-e cycles 60`) so a suspected plateau can be run out,
which is also the answer to the residual weakness — a very slow leak hiding under the slack in a short
run. Both arms now report `tailGrowthFds=0`.

This is not a test edited to green: the plateau was established at 60 cycles *before* the assertion was
touched, and the replacement is strictly more sensitive to the defect the test exists to catch.

**Evidence grade:** MEASURED. **Decision.** No product change. The suite stands on ARMv9/SME.

**Next.** Re-run `SpeechChurnLeakTest` on the SM-M315F to confirm the tail-based assertion still passes
there (§3.35's data says it will — fds were flat to −6), and fold the shape assertion into the other
churn-style probes if any grow one.

---

### 3.42 Why KleidiAI reversed — it is the kernels, not the artifact (and a dead load path found on the way)

**Problem.** §3.40 shipped `disableKleidiAi = caps.sme` on the strength of §3.39, which contradicts
§3.20's opposite-signed result on the same device. If something between the two sessions changed what
MLAS is *handed* — §3.30 moved the graphs' weights into a shared external blob, and the `.ort` bake
re-inlines it — then the right fix would be upstream and the shipped policy would be treating a
symptom. A commit bisect was the obvious instrument, and it is the wrong one: **the model assets are
gitignored**, so no source checkout can revert that half of the change.

**Investigation.** A 2×2 instead, on HEAD, no rebuild: KleidiAI on/off under **both** load paths at
one fixed thread count, with the production pair repeated as a within-run control.

- `optCache = true` — production: baked `.ort`, NO_OPT, mmap, blob re-inlined.
- `optCache = false` — the source `.onnx` under ALL_OPT, external weights, as exported.

The first attempt at `intra = 4` (entry #9's thread count) could not resolve it: effects of 2.8–4.9%
against control disagreement of 3.9–6.1% and drift 1.08. That is the arm where the effect is smallest
(§3.39: 3–6% at `intra4` against 10–13% at `intra2`), so the question moved to where the signal is.

**Benchmark.** `intra = 2`, 30.9 °C, drift 1.06, `sweepKleidiAiVsCache`:

| load path | KleidiAI on | off | Δ |
|---|---|---|---|
| baked `.ort` (production) | 94 ms | 85 ms | **−9.6%** |
| baked `.ort`, recheck | 94 ms | 85 ms | identical to the above |
| source `.onnx`, ALL_OPT | 93 ms | 85 ms | **−8.6%** |

**Both control pairs returned exactly the same medians** — 94/94 and 85/85 — so the floor is zero and
a ~9% effect is not in doubt.

**The load path is exonerated.** KleidiAI costs the same whether ORT receives the baked artifact with
its weights re-inlined or the source graph with §3.30's external data. Neither the bake nor the shared
blob mediates it.

**So the reversal is not explained by anything in this codebase.** ORT is 1.27.0 in both sessions, the
ISA is unchanged, and no source commit touches MLAS kernel dispatch. With the artifact and the load
path ruled out, the parsimonious reading is that **§3.20's measurement was wrong** — which it half
said about itself: both of its A/B runs were "thermally degraded (stdev 12–25 ms)" and it declined to
pin the magnitude tighter than 4–9%. The runs here carry stdev 2.4–5.9 ms and zero-disagreement
controls. **A commit bisect is not justified: there is no candidate mechanism left in our code for it
to find.** §3.40's predicate stands as the right layer.

**A dead code path found on the way, and it is the more serious finding.** Every `optCache = false`
arm failed with `IllegalStateException: ONNX session load failed` before running one translation.
Phase 13 (§3.30) made the source graphs reference their weights externally; `OnnxModels.init` purges
that blob before any load because the *baked* path only needs it while baking; and
`loadSourceUncached` — which hands ORT the `.onnx` itself — never restored it. `ensureSharedWeights`
is called only from the bake.

Not a niche path: **`OrtTuning.optCache` defaults to `false`**, so every directly-constructed tuning
uses it — all eleven arms of `MtTuningSweepTest` — and `:benchapp` runs
`ExecutionPolicy.current.copy(optCache = false)` for both its **MT phase and its KleidiAI A/B**
(`MtWorkload.kt:93`, `:319`) while staging no `weights.bin`. **The phone benchmark's translation phase
has been broken since §3.30.** It survived undetected because that sweep takes 13 minutes and
`:benchapp` is driven by hand from the launcher. Fixed in `loadSourceUncached` (`3a34fb2`) rather than
by keeping the blob alive everywhere, so the production path's disk behaviour stays as §3.30 measured
it.

**Incidental, and worth knowing:** at steady state the opt-cache buys **no latency** on this device —
93/85 uncached against 94/85 cached. That is not a regression; §3.10's case for it was always cold
start, and this simply confirms the win is entirely in load time.

**Evidence grade:** MEASURED. **Decision.** **§3.40 stands unchanged.** §3.20's direction stays
withdrawn, now with a mechanism ruled out rather than merely outvoted.

**Next.** The one thing that would still move this is a second SME device: everything here says
"KleidiAI's SME kernels are slower for this workload", and one part cannot distinguish that from
"slower on this part". Also worth a look now that `optCache = false` works again — `MtTuningSweepTest`
has been running against a broken load path, so any of its numbers taken since §3.30 are void.

---

### 3.43 Energy per translation on the SM-M315F — 0.9 J, and what the hardware would not give

**Problem.** The project had good thermal evidence (90 consecutive translations, +0.5 °C, no
throttling signature) and no energy result at all. "How much battery does a translation cost" was
unanswerable.

**What the hardware allows, established before measuring anything.** `/sys/class/power_supply/battery`
is SELinux-blocked, so everything comes through `BatteryManager`. Its charge counter moves in steps of
**5361 µAh on this device** (measured: 3704451 → 3709812 → 3715173 → 3720534), updating every ~20 s —
about 21.8 mWh, or 78 J, per tick. **A single translation is a small fraction of one tick, so
per-translation energy cannot be read directly at any sample rate.** The only honest instrument is a
long sustained run whose drain clears the quantum many times, with the device's idle draw subtracted:
`((drain_busy − drain_idle) × V̄) / translations`. Power rails would have been better —
`dumpsys powerstats` returns nothing here and the `IPowerStats` HAL is not exposed.

**Implementation.** `SustainedEnergyTest#cycle`: idle → busy → idle in **one** invocation and **one**
unplug window, with the two idle arms bracketing the workload so baseline drift is visible rather than
assumed. It refuses to produce a number while charging — charging current dwarfs the workload and
drives the counter *upward*, so a reading taken on USB is not weak but wrong. Engine construction and
warm-up happen outside every window. Screen state is fixed and identical across arms, so the display
cancels in the subtraction instead of the CPU risking suspend with the screen off.

**Benchmark.** SM-M315F, 8-sentence mixed-length corpus, EN→HI, `intra=2` + affinity:

| phase | duration | charge drain | note |
|---|---|---|---|
| idle | 480.4 s | **0 µAh** — counter never moved from 4556850 | ⇒ idle draw is **< 40.2 mAh/h** |
| busy | 840.8 s | **139,386 µAh** (4556850 → 4417464) | **2095 translations**, V̄ = 3.9135 V (56 samples) |

The closing idle arm was cut short by an early replug and the cycle voided itself by its own rule —
**but the missing baseline is bounded, not unknown**, which is what rescues the run: idle drew less
than one quantum in 8 minutes, so its rate lies in [0, 11.16] µAh/s and brackets the answer tightly.

- busy energy = 0.139386 Ah × 3.9135 V = **1963.9 J**
- less the *maximum possible* idle draw over the same 840.8 s = **1831.5 J**

Token count recovered separately (`corpusTokens`, run plugged in — greedy decoding is deterministic
and parity-exact, so a sentence's token count is a property of the model, not of the run that saw it):
**55 tokens per 8-sentence cycle**, so 2095 translations = 261 cycles + 7 sentences = **14,395 tokens**.

| metric | value |
|---|---|
| **energy per translation** | **0.87 – 0.94 J** (≈0.91 J) |
| **energy per 1000 generated tokens** | **127 – 136 J** (≈132 J) |
| sustained power while translating | **≈2.34 W** (597 mAh/h at 3.91 V) |
| quantisation error | ±3.8% (26 quanta measured) |
| throughput | 2.49 translations/s, median 374 ms, p95 774 ms |
| thermal | **29.7 → 30.0 °C across 2095 consecutive translations** |
| battery level | 85% → 82% |

**No throttling signature**: 0.3 °C over 14 minutes of unbroken inference, and the p95/median ratio of
2.07 is the mixed-length corpus (2 to 15 tokens), not degradation. At 597 mAh/h this phone's 6000 mAh
battery is roughly **10 hours of continuous, back-to-back translation** — a bound on the workload, not
a usage prediction.

**Evidence grade:** MEASURED, with the stated interval. The width comes from the hardware's 5361 µAh
quantum, not from noise, and is reported rather than averaged away.

**Three harness failures preceded any data, all mine, all worth recording** because each was a way of
getting a confident wrong answer instead of no answer: the unplug gate compared against `"none"`, a
string that exists nowhere, so it never opened while the log cheerfully printed
`plugged=unplugged`; three chained invocations meant three independent gates, and the first opened
during a previous step's unplug; and the gate's five-minute wait expired before a human could reach
the cable. The workload was being measured carefully and the instrument around it not at all.

**Decision.** No code change — this is a characterisation, and the first energy figure the project has.

**Next.** The V3-vs-V4 half. v3.4's tree builds here (`JAVA_HOME` plus `-Dorg.gradle.java.home`
override its hardcoded Linux path; `local.properties` needed repointing), and a twin harness
`SustainedEnergyV3Test` is written against v3's own `Translator` with the identical corpus, arms and
gate. **v3 caps decoding at `maxLength = 18` and this corpus's longest translation is 15 tokens, so
v3 will not truncate on it** — the confound that would have flattered v3 does not apply here, which is
why this corpus is the right one for the comparison.

---

### 3.44 Where the 2.15 s of session load goes (Q18) — KEEP, and Q14's knob is the answer

**Problem.** `sessions:parallel` is 2.15 s of a ~6 s cold start and had never been broken down. The
queue asked for a faster session startup without knowing which part of it was expensive.

**Investigation.** Three cold launches of the real app first, for the shape (SM-M315F, `.ort` cache
warm, 34.2 °C): encoder `create` 0.87–1.09 s / 75 MB against decoder_init 2.07–2.17 s / 204 MB and
decoder_step 2.07–2.21 s / 194 MB, all three concurrent. **The critical path is one decoder-sized
load and the encoder is free**, so only the decoders are worth attacking.

Then `OrtLoadProbeTest` (new): the same three `.ort` files, production options as the baseline, 7 arms
× 3 **rotated** rounds, sessions closed immediately so only construction is timed. The rotation is the
whole design — §3.27's "−61% load time" was the page cache, and this probe exists to not repeat that.

| arm | median ms | vs baseline |
|---|---|---|
| serial load | 3733 | +69% |
| baseline (path, NO_OPT, mmapped `.ort`) | 2205 | — |
| `session.use_device_allocator_for_initializers=1` | 2108 | −4%, inside the spread |
| `session.intra_op.allow_spinning=0` | 1917 | −13%, spreads overlap |
| `intraThreads=1` | 1908 | −13%, not shippable (§3.37) |
| **mapped initializers** | **1477** | **−33%** |
| `session.disable_prepacking=1` (diagnostic) | 1414 | −36% |

**Reading all 473 MB off storage took 335 ms**, so the load is not I/O-bound at all. The arms split
the 2205 ms into roughly **790 ms of MLAS prepacking** (not removable — §3.26 priced inference at 4.6×
without it), **730 ms of copying initializers into the session allocator** (removable), and ~690 ms of
flatbuffer and graph residual.

Rejected on the numbers: `use_device_allocator_for_initializers` (nothing beyond drift),
`intra_op.allow_spinning=0` (overlapping spreads at n=3 — the loaders' spin-waiting is not the cost),
and serialising the loads, which is 69% worse and re-confirms Phase 11C on the warm path. Note the
direction, because it looks like a contradiction of §3.29 and is not: **removing** a fourth
CPU-bound thread helped there, while these three loads are the same work split up, not a new
competitor for the same cores.

**Implementation.** One line: `ExecutionPolicy.select` now sets `mappedInitializers = true`. The
mechanism is Q14's, already in `OnnxModels.createMappedSession` since §3.27 and shipped off —
`FileChannel.map` plus `session.use_ort_model_bytes_directly` and
`session.use_ort_model_bytes_for_initializers`, so initializers point into the mapped `.ort` instead
of being copied. `OrtTuning`'s own default stays `false`, so every existing benchmark still measures
the old path.

**Verification.** `MtEngineInstrumentedTest` 3/3, JVM suite green, `BenchmarkSuiteTest` output
`पानी ।` — parity exact. Sustained median 617 ms, long-sentence median 630 ms, 73.3 tok/s at 34.8 °C.

**Benchmark (the one that decides it).** Real app, cold launch, **arms interleaved** path/mapped ×3,
battery temperature flat at 34.7–34.8 °C:

| median | path load | mapped initializers |
|---|---|---|
| `sessions:parallel` | 2183 ms | **1633 ms** |
| `engine_init` total | 6190 ms | **5589 ms** |
| decoder_init `create` | 2006 ms | 1477 ms |
| encoder `create` | 881 ms | 708 ms |

**−550 ms of session load (−25.2%), −601 ms of cold start (−9.7%)**, ranges non-overlapping in both
arms. `MappedInitializersTest` re-run in both orderings agrees and adds the memory picture: native
heap 559 → 408 MB, native PSS 656/530 → 382/363 MB, translate median 628/615 → 619/620 ms (neutral),
**total PSS 744/612 → 787/768 MB**.

**Evidence grade:** MEASURED — real app, cold, interleaved arms, one device.

**Decision.** **KEEP.** The trade is explicit: ~550 ms of startup for a resident-pages accounting
cost. The weights become clean file-backed pages the kernel can drop, instead of anonymous heap it can
only swap or kill for, which is the direction §3.24b/§3.28 already wanted.

**Next.** Two things this opens. (1) **Total PSS is now the honest worry**, not native heap: re-run
`PressureReclaimTest` (Q15) with the flip in place — that measurement decides whether file-backed
weights survive pressure better, and it is now the shipping configuration rather than an experiment.
(2) **What is left is ~790 ms of prepacking, and decoder_init and decoder_step prepack byte-identical
weights** (§3.30 proved the tensors identical). ORT shares prepacked weights across sessions through
`PrepackedWeightsContainer`, which the Java API does not expose; `addConfigEntry` does reach
`session.save_external_prepacked_constant_initializers`, so that is the cheaper thing to try first, at
bake time.

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
| Buffered + block dict parse | Startup | Probe: 9,951 → 1,082 ms, same parser | KEEP | tokenizer −67%, engine −32.8% |
| Parallel session load | Startup | Probe 12,285 → 6,258 ms (1.96×) | KEEP | engine ready −36.7%, +126 MB peak |
| Bake ALL_OPT once (2A) | Skip graph opt per launch | On-device cold/warm | KEEP | session build −65% |
| ORT format + mmap (2B) | Disk + heap | On-device, cooled | KEEP | time parity, ~−50% filesDir |
| Thread affinity | Kill migration jitter | 2 devices, counterbalanced | NO EFFECT | within ~2%; degenerate on uniform IP |
| Generated Baseline Profile | Cold start | Macrobenchmark, API 36 | KEEP | TTID −5.5% |
| ORT 1.17.1 → 1.27.0 | Newer MLAS kernels | Full benchmark, parity | KEEP | enabled the SME investigation |
| KleidiAI / SME | Price the ISA | `disable_kleidiai` A/B + simpleperf | KEEP (default on) | +4.9–9.1%, **not** 2× |
| Prime-core pinning | Use the 4.7 GHz cores | On-device A/B | REVERT | 99 ms vs 99 ms — nothing |
| `cpuArena=false` costs 12% | — | Production-path A/B | **RETRACTED** | arena-on is 1.9% slower; default was right |
| mmap win is MT6878-only | — | Entry #9 | **RETRACTED** | mechanism unexplained |
| Clamp `[1,4]` → `[1,2]` | Stop over-threading flagships | Entry #9 sweep, n=45 | KEEP (INFERRED) | intra2 −4.8% / −12.9% vs intra4 |
| Halve logits copy | Remove per-token copy | Bytecode-provable; no device | OPEN | 2 copies → 1; **unpriced** |
| Shared weight blob | Stop shipping the decoder twice | Byte hashing + device A/B with closing control | KEEP | APK −276.7 MB, latency unchanged |
| Slice `decoder_init` logits (Q1) | Skip discarded positions | Device: `decoder_init` 49.0 → 49.6 ms | NO GAIN | greedy never runs it at `dec_len` > 1 |
| Per-channel INT8 | Better quality, same size | Gate 0.448 → 0.373; device +4–8% | REVERT | quality gain costs latency |
| `reduce_range` | Avoid INT8 saturation | Gate 0.448 → 0.457 | REVERT | worse on both axes |

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

## 9. Open experiment queue

The live queue, ordered. Each item states its **hypothesis**, **how it will be measured**, and what
result would close it — so a run either produces a §3 entry or a recorded negative, never a shrug.

**Closed since this list was last written** (they were listed here as "future work" while already
done, which is the rot this section now exists to prevent): HI→EN cached export → §3.19 · SME2
validation → §3.20 (answered: 4–9%, not 2×) · zero-copy logits read → §3.22 (and the "zero-copy"
premise was wrong) · Q1's `decoder_init` slice → §3.31 (done, and the premise was wrong again — the
graph wastes the work, the workload never asks it to).

**Two of this queue's entries have now been closed by discovering their premise did not hold** (Q1
here, the `getFloatBuffer` "zero-copy view" in §3.22). Both were written from the shape of the code
rather than from a measurement of the path actually taken. An entry that names a cost should also
name the workload that pays it.

**Hardware available (2026-08-06).** The **SM-M315F is the only device on hand**; the SM-S948B that
produced entry #9 is **no longer available**, and the most that can be obtained is an **S22 Ultra**
(Snapdragon 8 Gen 1: Armv9, i8mm and SVE2, **no SME**). This closes some queue items and reopens
others — the affected rows say so rather than silently assuming a device that is gone.

Three consequences worth stating once:

- **The SME question stays answered, and cannot be re-opened.** §3.20 priced KleidiAI's SME kernels
  at 4–9% on the S26U and that evidence stands in `bench/results/cross-device/`. No device that can
  be obtained has SME, so **no SME claim may be re-measured or extended** — only cited.
- **The `[1,4]` → `[1,2]` clamp (§3.21) can no longer be closed by derivation.** It only changes
  behaviour on an 8-performance-core part. The M31 derives 2 (4 perf cores) and the S22 Ultra derives
  2 as well (1×X2 + 3×A710 = 4 perf cores after `dc3011e`), so neither device *reaches* the bound. It
  can still be closed on the underlying claim — "4 threads is never optimal" — by an explicit sweep,
  since `ProductionThreadSweepTest` sets `intraThreads` directly rather than deriving it (Q2b).
  **Done 2026-08-12 (§3.37): the claim holds, the bound is still INFERRED.**
- **KleidiAI on i8mm-only silicon is newly worth measuring.** The S22 Ultra has i8mm and SVE2 but no
  SME, and the `mlas.disable_kleidiai` A/B did not exist when that device was last benchmarked. It
  would separate "KleidiAI is worth something" from "SME is worth something" (Q12).

| # | Experiment | Hypothesis | Measurement that closes it | Expected size |
|---|---|---|---|---|
| **Q19** | **Bake the prepacked weights, so the two decoders stop packing the same bytes twice** | §3.44 leaves ~790 ms of MLAS prepacking as the largest remaining piece of session load, and §3.30 proved decoder_init and decoder_step hold **byte-identical** tensors — so half of that work is duplicated. ORT's `PrepackedWeightsContainer` shares it across sessions but is **not reachable from the Java API**; `session.save_external_prepacked_constant_initializers` is, and moves the packing to bake time | Set it in `bakeOptions`, then the §3.44 probe again: `sessions:parallel` in the real app, cold, interleaved. Watch `.ort` size and `filesDir` — prepacked layouts are larger on disk, and Q17 is already fighting for that space. Parity before anything | Up to ~790 ms of a 1.6 s session load, if it transfers at all |
| **Q0** | **Length-cap expansion factor** | `targetCap = max(14, sourceLen)` still truncates: targets expand past their source. `1.6× + 8` fixes it | 200-sentence corpus per direction, count no-EOS stops before/after; latency p95 must stay bounded by `maxSteps` | Correctness, not speed |
| ~~Q1~~ | ~~Slice the last position inside `decoder_init`~~ | — | **CLOSED — NO GAIN (§3.31).** Done, gate 7/7, and worth nothing: greedy seeds a one-token prefix, so `decoder_init` only ever runs at `dec_len = 1` and `decoder_init` measured 49.6 ms against a 49.0 ms control. The "largest remaining inference lever" was true of the graph and never exercised by the workload. Held for Q6 | done |
| **Q16** | Collapse the tied embedding, stored twice inside every decoder | `decoder.embed_tokens.weight_quantized` (V, 512) UINT8 and `onnx::MatMul_*_quantized` (512, V) INT8 are the same matrix under two quantization schemes — 62.8 MB each in EN→HI, so four copies across the decoder pair. Content-addressing cannot catch it (§3.30); it needs an export-side change so one copy serves both the gather and the projection | Re-export → `verify_cache.py` 7/7 → APK size → `MtBenchmarkTest` on the M31, since a runtime transpose would trade size for latency | Up to ~125 MB EN→HI, ~33 MB HI→EN |
| **Q17** | Carry the §3.30 saving onto the device | The `.ort` bake re-inlines the shared blob, so device storage is unchanged. Baking ALL_OPT to optimized **ONNX** with external initializers instead measured 204.3 MB against the 397.9 MB baked pair, NO_OPT load times overlapping and output identical — but the bake would move to the host, and ORT does not promise its optimized output is portable across platforms | Host-bake on x86, load the result on the M31: parity first, then `BenchmarkSuiteTest`. A parity failure closes this permanently and is the cheap outcome to look for | ~190 MB of device storage per direction |
| ~~Q2a~~ | ~~Price the logits copy~~ | — | **CLOSED 2026-08-06** — `LogitsReadBenchmarkTest` on the M31: 545.8 µs/token saved at 122k vocab, 6.55 ms per 12-token translation, ~1.0% end-to-end (§3.22) | done |
| ~~Q2b~~ | ~~Does 4 intra-op threads ever win?~~ | — | **CLOSED 2026-08-12 — KEEP (§3.37).** No: on the SM-M315F production path, `intra4` pinned is +8.2% long / +26.9% short with 2.8× the stdev, and `intra4` unpinned carries the worst jitter in the sweep (p95 896 ms vs 677 ms). The shipping `intra2`+affinity arm is the fastest on both sentences. `intra3` and inter-op were measured for the first time and both lose. **The clamp's *bound* stays INFERRED** — the M31 derives 2 and cannot reach it | done |
| ~~Q3~~ | ~~Overlap the tokenizer parse with session load~~ | — | **CLOSED 2026-08-06 — REVERTED.** Cold start got 341 ms *worse* (5,134 → 5,475 ms): the big cores are already saturated by the three session loads, so the parse slowed 2.9 → 5.5 s. The benchmark showing a win had warmed the parser first (§3.29) | done |
| **Q4** | Packed binary vocabulary | **Re-opened larger by the Q3 revert.** The parse is serial again and is now the biggest single piece of engine construction: **2.9 s of a 5.1 s cold start**, against 2.15 s for all three ONNX sessions. `noCompress` on the JSON measured NO EFFECT, so it is the parser, not the I/O | A packed vocabulary (sorted `String[]`+`int[]`, or one `.bin`), measured in the **real app** via `engine_init` over 3 cold launches — never by a test that loads the tokenizer beforehand | Up to ~2 s of a 5.1 s cold start |
| **Q5** | Instrument ORT's mmap acceptance | The device-dependent mmap benefit (§3.20 retraction) is unexplained; more devices have not resolved it and will not | ORT debug logging / native heap accounting. **Blocked**: the contradicting pair was the S26U and CPH2603, neither of which is available | Explanation, not speed |
| **Q6** | Greedy vs Beam on the real runtime | Beam is implemented, unit-tested, and has never been run against the model — its quality/latency trade is unknown | On-device A/B, quality judged on a fixed sentence set; note beam falls back to `decoder_init` every step today | Unknown; may be REVERT |
| **Q7** | Execution-provider / kernel selection | The detector surfaces `dotprod`/`i8mm`/`sve2`/`sme2` but `ExecutionPolicy` does not act on them | Per-EP A/B where an EP exists for the part | Likely small — §3.20 priced the ISA at 4–9% |
| ~~Q8~~ | ~~Speech pipeline: ASR is 0.79× realtime~~ | — | **CLOSED 2026-08-11 — the premise was wrong (§3.33).** Vosk alone is **0.55× realtime** on the M31 (1448 ms of 2.65 s audio, n=15); the missing 640 ms was the file-import path, not the recogniser. Sample rate and buffer size both measured NO EFFECT | done |
| ~~Q8a~~ | ~~Hindi decode configuration~~ | — | **CLOSED 2026-08-11 — KEEP (§3.34).** The fixture was made (device TTS) and the answer was bigger than expected: stock Hindi runs **1.91× realtime at 10 dB SNR and 2.59× at 5 dB**, i.e. it loses to live speech in exactly the noisy conditions the product targets. Narrowed to English's 3000/10.0/2.0: −60% at 10 dB, transcripts identical in all three conditions | done |
| **Q8c** | English under noise | §3.34 measured Hindi across clean/10 dB/5 dB and found the failure only noise exposes. English was measured **clean only** (0.55×) and is already at the narrow configuration, so there is no retune left to try — but "keeps up" is still an untested claim outside a quiet room | The same three conditions in `AsrTuningBenchmarkTest`, English arm. Cheap: the harness and the noise generator already exist | Diagnostic; may open a real problem on low-end hardware |
| **Q8b** | The 640 ms import path | `AudioFileTranscriber` costs ~44% on top of recognition: MediaCodec decode, a channel-average downmix, a linear-interpolation resampler with no anti-alias filter, and full flow collection. The resampler is also an accuracy bug, not only a cost | Split the stages inside `AsrTuningBenchmarkTest`; only then decide whether it is decode or resample | Up to ~640 ms per imported file; import only, never the microphone |
| ~~Q10~~ | ~~Price the single-engine eviction~~ | — | **CLOSED 2026-08-06** — swap peak −511.7 MB (−36.7%), post-swap −392.9 MB, reload 3.5 s not 10 s (§3.24b) | done |
| ~~Q11~~ | ~~Why does `release()` not return memory?~~ | — | **CLOSED 2026-08-06** — it does: alloc 557.8 → 13.2 MB instantly, PSS 666 → 372 MB within ~10 s as the allocator releases lazily. §3.24b's conclusion was wrong and is corrected in §3.25 | done |
| ~~Q13~~ | ~~Is prepacking what unmaps the model?~~ | — | **CLOSED 2026-08-06** — refuted. 451 MB *is* mapped during load and unmapped after, with or without prepacking. Disabling prepacking is 4.6× slower and uses 2× the heap: a REVERT (§3.26) | done |
| ~~Q14~~ | ~~Can the weights stay file-backed?~~ | — | **CLOSED 2026-08-06** — yes: 451 MB stays mapped, anonymous heap −151 MB, native PSS −230–300 MB, output identical. Total PSS rises 30–86 MB; the load and latency claims died to a page-cache confound (§3.27). Kept OFF by default — **until §3.44 rotated the arms and priced the load claim it could not make: it now ships ON** | done |
| ~~Q15~~ | ~~Is file-backed actually a better OOM victim?~~ | — | **CLOSED 2026-08-06** — reclaim proven (319 MB dropped under pressure, app keeps working, ~2× latency spike); survival **not** proven (kills 1/6 mapped vs 2/6 path, both arms killed). Default stays OFF (§3.28). **Re-opened in effect by §3.44**: mapped initializers now ship, so this A/B should be re-run with the arms the other way round — the question is no longer whether to enable it but whether the shipping config survives pressure | done, re-run queued |
| ~~Q18~~ | ~~Break down the 2.15 s of session load~~ | — | **CLOSED 2026-08-12 — KEEP (§3.44).** Not I/O (473 MB reads in 335 ms): ~790 ms prepacking, ~730 ms initializer copying, ~690 ms residual, per-decoder. Q14's `mappedInitializers` removes the copy — **`sessions:parallel` 2183 → 1633 ms, cold start 6190 → 5589 ms**, real app, interleaved arms, parity exact. `use_device_allocator_for_initializers`, `intra_op.allow_spinning=0` and serial loading all measured no better or worse | done |**Rule for this table:** an item leaves it only by becoming a §3 entry — including as a REVERT or a
NO EFFECT. Deleting a row because it turned out not to work is how a ledger starts lying.

---

## Report metadata

- **Status:** living document. Last extended 2026-08-12 (§3.37 the production-path thread and
  execution-mode sweep on the M31, Q2b closed as KEEP; §3.38 the same on a recovered SM-S948B, which
  closes §3.21's bound and **contests §3.20's KleidiAI direction**).
- **Optimization sections (§3):** §3.1–§3.37 (38 headings). §3.1–§3.9 are the original phase-gated
  reconstruction; §3.10–§3.23 backfill everything landed since (startup, caches, affinity, baseline
  profile, bench framework, ORT upgrade, HI→EN, the nine-device campaign); §3.24–§3.29 are the
  2026-08-06 lifecycle and memory pass; §3.30–§3.32 are the 2026-08-10 export-side work;
  §3.33–§3.36 are the 2026-08-11 speech pass; §3.37 is the 2026-08-12 thread sweep.
- **Open items:** 9 in §9 (Q0, Q4, Q5, Q6, Q7, Q8b, Q8c, Q16, Q17), plus §3.23, a fix the 2026-08-06
  audit found incomplete, and **one newly contested result: §3.20's KleidiAI direction, which §3.38
  measured with the opposite sign on the same device.** **Every shipping default is now MEASURED on
  the production path** — §3.21 was the last INFERRED one, and §3.37 plus §3.38 closed both its claim
  and its bound.
- **Decisions by kind:** the ledger now carries 3 REVERTs with device numbers (`intra_op=8`, NO_OPT,
  PARALLEL+inter=2), 3 more from later passes (prime-core pinning, `disable_prepacking`, per-channel
  INT8), 2 retractions (§3.20's arena claim, §3.25's `release()` claim), and 2 entries closed by
  finding their own premise false (§3.22's zero-copy assumption, §3.31's Q1).
- **Tables:** timeline §2; KV-cache §3.7; ORT tuning §3.8; probe tables §3.11/§3.12; decision matrix
  §4; open queue §9.
- **Cross-reference:** `AUDIT_2026-08-06.md` — the correctness audit whose H2/P1/P2 findings feed
  §9 Q0, Q1 and Q3.
- **Internal cross-references:** `ENGINEERING_PLAN.md`, `LESSONS_FROM_V3.md`, `DECODING_ARCHITECTURE.md`,
  `MODEL_PIPELINE.md`, `INDICTRANS2_ARCHITECTURE.md`, `EXPORT_FEASIBILITY.md`, `EXPORT_WITH_CACHE.md`,
  `KV_CACHE_RUNTIME.md`, `CACHE_BENCHMARK.md`, `ORT_TUNING.md`, `ARM_PLATFORM_OPTIMIZATION.md` (11 documents).
