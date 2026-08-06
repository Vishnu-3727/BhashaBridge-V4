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

**Decision.** KEEP. **Next:** re-run `ProductionThreadSweepTest` + `BenchmarkSuiteTest` on the S26U
to convert this to MEASURED. Until then it is the ledger's one unconfirmed shipping default.

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
premise was wrong).

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
- **KleidiAI on i8mm-only silicon is newly worth measuring.** The S22 Ultra has i8mm and SVE2 but no
  SME, and the `mlas.disable_kleidiai` A/B did not exist when that device was last benchmarked. It
  would separate "KleidiAI is worth something" from "SME is worth something" (Q12).

| # | Experiment | Hypothesis | Measurement that closes it | Expected size |
|---|---|---|---|---|
| **Q0** | **Length-cap expansion factor** | `targetCap = max(14, sourceLen)` still truncates: targets expand past their source. `1.6× + 8` fixes it | 200-sentence corpus per direction, count no-EOS stops before/after; latency p95 must stay bounded by `maxSteps` | Correctness, not speed |
| **Q1** | Slice the last position **inside** the exported `decoder_init` | The graph returns logits for every prefix position and the runtime discards all but the last; slicing upstream shrinks ORT's own allocation, not just our copy (§3.22). Those 490 KB/token allocations are also what makes the wide-vocab stdev in §3.22 | `model_pipeline` re-export → `verify_cache.py` 7/7 + `MtBenchmarkTest` on the M31 | Unknown; the largest remaining inference lever, and now the only one with real headroom |
| ~~Q2a~~ | ~~Price the logits copy~~ | — | **CLOSED 2026-08-06** — `LogitsReadBenchmarkTest` on the M31: 545.8 µs/token saved at 122k vocab, 6.55 ms per 12-token translation, ~1.0% end-to-end (§3.22) | done |
| **Q2b** | Does 4 intra-op threads ever win? | §3.21 tightened the clamp to `[1,2]` on one device's evidence. **Neither available device derives 4**, so the bound itself is untestable here — but the claim under it is not | `ProductionThreadSweepTest` on an S22 Ultra, which sets `intraThreads` explicitly: intra 1/2/4/6/8 on the production path, rotated rounds, parity-exact arms | Confirms or refutes a shipping default on a second topology |
| **Q3** | Overlap the tokenizer parse with session load | `Tokenizer.load` (~1 s) runs serially *before* three sessions load on three threads; it is independent of all of them | `engine_init` stage marks, cold and warm, n=10 | ~0.5–1 s of a ~10.5 s cold start |
| **Q4** | Packed binary vocabulary | Parsing 3.4 MB of JSON per launch is avoidable entirely: ship sorted `String[]`+`int[]` or a single `.bin` | Same stage marks as Q3; parity on encode/decode over the full vocabulary | Removes the parse (~1 s) outright |
| **Q5** | Instrument ORT's mmap acceptance | The device-dependent mmap benefit (§3.20 retraction) is unexplained; more devices have not resolved it and will not | ORT debug logging / native heap accounting. **Blocked**: the contradicting pair was the S26U and CPH2603, neither of which is available | Explanation, not speed |
| **Q6** | Greedy vs Beam on the real runtime | Beam is implemented, unit-tested, and has never been run against the model — its quality/latency trade is unknown | On-device A/B, quality judged on a fixed sentence set; note beam falls back to `decoder_init` every step today | Unknown; may be REVERT |
| **Q7** | Execution-provider / kernel selection | The detector surfaces `dotprod`/`i8mm`/`sve2`/`sme2` but `ExecutionPolicy` does not act on them | Per-EP A/B where an EP exists for the part | Likely small — §3.20 priced the ISA at 4–9% |
| **Q8** | Speech pipeline | The Vosk path has never been optimized; ASR is 0.79× realtime on the M315F — slower than the speech it transcribes, on the only device now available | `SpeechPipelineBenchmarkTest` on the M31 | Unknown; the M31 number is the user-visible worst case |
| ~~Q10~~ | ~~Price the single-engine eviction~~ | — | **CLOSED 2026-08-06** — swap peak −511.7 MB (−36.7%), post-swap −392.9 MB, reload 3.5 s not 10 s (§3.24b) | done |
| ~~Q11~~ | ~~Why does `release()` not return memory?~~ | — | **CLOSED 2026-08-06** — it does: alloc 557.8 → 13.2 MB instantly, PSS 666 → 372 MB within ~10 s as the allocator releases lazily. §3.24b's conclusion was wrong and is corrected in §3.25 | done |
| ~~Q13~~ | ~~Is prepacking what unmaps the model?~~ | — | **CLOSED 2026-08-06** — refuted. 451 MB *is* mapped during load and unmapped after, with or without prepacking. Disabling prepacking is 4.6× slower and uses 2× the heap: a REVERT (§3.26) | done |
| ~~Q14~~ | ~~Can the weights stay file-backed?~~ | — | **CLOSED 2026-08-06** — yes: 451 MB stays mapped, anonymous heap −151 MB, native PSS −230–300 MB, output identical. Total PSS rises 30–86 MB; the load and latency claims died to a page-cache confound (§3.27). Kept OFF by default | done |
| ~~Q15~~ | ~~Is file-backed actually a better OOM victim?~~ | — | **CLOSED 2026-08-06** — reclaim proven (319 MB dropped under pressure, app keeps working, ~2× latency spike); survival **not** proven (kills 1/6 mapped vs 2/6 path, both arms killed). Default stays OFF (§3.28) | done |**Rule for this table:** an item leaves it only by becoming a §3 entry — including as a REVERT or a
NO EFFECT. Deleting a row because it turned out not to work is how a ledger starts lying.

---

## Report metadata

- **Status:** living document. Last extended 2026-08-06 (§0 protocol, §3.10–§3.23 backfill, §9 queue).
- **Optimization sections (§3):** 23. §3.1–§3.9 are the original phase-gated reconstruction;
  §3.10–§3.23 backfill everything landed since (startup, caches, affinity, baseline profile, bench
  framework, ORT upgrade, HI→EN, the nine-device campaign, and the three most recent changes).
- **Open items:** 10 in §9, of which two (§3.21, §3.22) are landed-but-unpriced and one (§3.23) is a
  fix the 2026-08-06 audit found incomplete.
- **Tables:** timeline §2; KV-cache §3.7; ORT tuning §3.8; probe tables §3.11/§3.12; decision matrix
  §4; open queue §9.
- **Cross-reference:** `AUDIT_2026-08-06.md` — the correctness audit whose H2/P1/P2 findings feed
  §9 Q0, Q1 and Q3.
- **Internal cross-references:** `ENGINEERING_PLAN.md`, `LESSONS_FROM_V3.md`, `DECODING_ARCHITECTURE.md`,
  `MODEL_PIPELINE.md`, `INDICTRANS2_ARCHITECTURE.md`, `EXPORT_FEASIBILITY.md`, `EXPORT_WITH_CACHE.md`,
  `KV_CACHE_RUNTIME.md`, `CACHE_BENCHMARK.md`, `ORT_TUNING.md`, `ARM_PLATFORM_OPTIMIZATION.md` (11 documents).
