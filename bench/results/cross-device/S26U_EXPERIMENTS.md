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

> **⚠ §2's F1 and F2 were both revised by §2b below.** F1 (thread count) survives with its magnitude
> cut from ≈10% to ≈5%; **F2 (`cpuArena=false`) is refuted outright** — it was a non-production-path
> artifact. Read §2b before quoting anything here.

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

## 2b. Production-path A/B — **run; it corrects §2 twice**

`ProductionThreadSweepTest` (new). Keeps `ExecutionPolicy.current` intact — `optCache=true`, so every
config loads the baked `.ort` under NO_OPT with mmap, the real shipping path — and varies one knob on
top. **7 configs × 3 rounds, order rotated each round, n=45 per config**, so position (and therefore
thermal drift) averages out instead of accumulating as it does in §2. Battery 31.7 → 34.7 °C during
the run. All arms parity-exact. Raw: `s26ultra_production_sweep.txt`.

| Config | long median | stdev | short median | Δ long vs shipping |
|---|---|---|---|---|
| **`intra1`** | **98 ms** | 3.9 | **27 ms** | **−5.8%** |
| **`intra2`** | **99 ms** | 3.0 | **27 ms** | **−4.8%** |
| `intra2_primePin` | 99 ms | 3.8 | 27 ms | −4.8% |
| `intra4` *(shipping)* | 104 ms | 4.2 | 31 ms | — |
| `intra4_arenaOn` | 106 ms | 6.1 | 32 ms | +1.9% |
| `intra6` | 116 ms | 8.2 | 38 ms | +11.5% |
| `intra8` | 150 ms | 14.9 | 39 ms | +44.2% |

### C1 — F1 confirmed, but smaller than §2 claimed (now **Measured**)

`intra1`/`intra2` beat the shipping `intra4` on the production path too — but by **≈5% on the long
sentence, not the ≈10% the non-production sweep suggested**. The short-sentence gap is larger
(27 vs 31 ms, **−12.9%**), which matters because short utterances are the common case in an
emergency-phrase translator. Direction confirmed, magnitude revised down. `intra6`/`intra8` degrade
steeply, reproducing the oversubscription pattern.

### C2 — **F2 is REFUTED. `cpuArena=false` should stay.**

§2 claimed the shipping `cpuArena=false` costs ≈12%. On the production path turning the arena **on**
is **1.9% slower** (106 vs 104 ms) and noisier (stdev 6.1 vs 4.2). The §2 result was an artifact of
the non-production load path, exactly the caveat that kept it *Inferred*. **No change to the arena
default is warranted** — Phase 7's decision stands on this device as well.

### C3 — **Prime-core pinning gains nothing (negative result)**

`CROSS_DEVICE_REPORT` §2 flagged that the two 4742 MHz prime Oryons idled at 883 MHz for the whole
entry-#9 run, and §8 ranked pinning to them as an **Estimated moderate** win. Measured:
`intra2_primePin` (workers pinned to cpu6-7, ORT ids `7,8`) is **99 ms — identical to plain `intra2`
at 99 ms**, within stdev. **Pinning to the idle prime tier does not help.** The idle primes were not
costing throughput, so that opportunity is closed, not pending.

### What this does *not* license

The rule is `threads = (perfCores/2).coerceIn(1,4)`. Capping at 2 would fix this device and change
nothing on any other entry in the database (every other part has ≤4 perf cores, so it already derives
1 or 2). But **this is one device with one 8-perf-core topology**, and the report's own §6d argues the
policy's value is that it is a rule rather than a fit. Changing a nine-device-validated default on a
single data point would be exactly that over-fit. **Recommendation: cap at 2 only after a second
8-perf-core part confirms it** — recorded as a recommendation, deliberately not applied.

---

## 2c. simpleperf — **the SME question is ANSWERED: SME is live, and it is not the 2×**

§1 established that ORT's profiler cannot see MLAS's SIMD dispatch. `simpleperf` can. Recorded
`cpu-clock -f 1000 --app com.bhashabridge.app` for 11.0 s under `BenchmarkSuiteTest` load:
**31,232 samples, 0 lost**. Raw: `s26ultra_simpleperf_sme.txt`.

| Shared object | Overhead |
|---|---|
| **`libonnxruntime.so`** | **83.23%** |
| `libc.so` | 7.54% |
| `libart.so` | 4.90% |
| `[JIT app cache]` | 3.44% |

### SME is executing (Measured, direct)

The shipped `libonnxruntime.so` has no `.symtab` and exports only 3 sized functions, so symbol
attribution is impossible — every hot entry is a bare offset. But the hottest cluster is a **40-byte
span carrying 21.7% of all ORT time**, which is an inner loop, so it can be decoded straight from
`.text`:

```
0x9afad0  zero     {za}                                  ← SME: zero the ZA tile
0x9afadc  ld1w     {z4.s}, p0/z, [x10]                   ← SVE predicated load
0x9afae0  addvl    x10, x10, #1                          ← SVE vector-length-scaled increment
0x9afae4  ld1h     {z8.h}, p3/z, [x11]
0x9afaec  smopa    za0.s, p2/m, p2/m, z4.b, z8.b         ← SME int8 outer-product accumulate
0x9afaf0  smopa    za1.s, p2/m, p2/m, z4.b, z9.b
0x9afafc  smopa    za2.s, p2/m, p2/m, z4.b, z10.b
0x9afb00  smopa    za3.s, p2/m, p2/m, z4.b, z11.b
0x9afb0c  b.lt     #0x9afadc
```

`smopa … z4.b, z8.b` is **signed 8-bit** outer-product into 32-bit accumulators. This is
`kai_run_matmul_clamp_f32_qai8dxp1vlx4_qsi8cxp4vlx4_1vlx4vl_sme_mopa` — KleidiAI's **SME int8**
kernel, whose name is present as a string in the shipped library. **SME is dispatched, and it is the
single hottest piece of code in the application.**

**This corrects a standing project assumption.** The ORT-upgrade notes recorded that "KleidiAI ships
in the AAR but is inert: its int8 dynamic-quant kernels are SME/SME2-gated; its NEON dotprod/i8mm
kernels are 4-bit only." Both halves are confirmed by the kernel names — the `neon_dotprod` /
`neon_i8mm` kernels are `qsi4c32p` (**4-bit**, useless for our 8-bit weights) while the SME kernels
are `qsi8cxp` (**8-bit**). The conclusion "inert" was correct **for Armv8.0–8.6 hardware only**. On
SME silicon KleidiAI is live, and the 8-bit gap closes.

### But it is worth single digits, not 2× (Measured direction, loose magnitude)

`mlas.disable_kleidiai` (found in the library, plumbed through `OrtTuning.disableKleidiAi` as a
benchmark-only knob) forces MLAS's own kernels, which isolates the contribution. Two runs, both
thread counts:

| | `intra4` | `intra4` no-KleidiAI | `intra1` | `intra1` no-KleidiAI |
|---|---|---|---|---|
| Run A (33.0 °C start) | 102 ms | 107 ms (**+4.9%**) | 111 ms | 115 ms (**+3.6%**) |
| Run B (34.8 → 36.7 °C) | 110 ms | 120 ms (**+9.1%**) | 122 ms | 133 ms (**+9.0%**) |

**Direction is consistent at all four measurement points: KleidiAI/SME on is faster.** Magnitude is
**loosely bounded at ~4–9%** and deliberately not pinned tighter — both A/B runs were thermally
degraded (stdev 12–25 ms against the 3–8 ms of the clean §2b run), and the device passed 35 °C
mid-sequence. Benchmarking was stopped rather than collect more compromised data.

### What this settles

**The S26U's 2× lead over the S22U is NOT explained by SME.** SME is real, active, and the hottest
kernel — and disabling it costs only single-digit percent. The remaining advantage must come from the
Oryon microarchitecture, the 4-thread configuration, and the cool unthrottled run. `CROSS_DEVICE_REPORT`
§6a′ hoped an SME part would show "~2×"; the honest answer, now measured directly rather than
inferred, is **no — the ISA rung is worth a few percent here, and the microarchitecture carries the
rest.** That is the opposite of the tempting narrative and is the single most useful result of this
session.

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

## 4b. HI→EN — the reverse direction, measured for the first time on entry #9

Every cross-device entry to date is EN→HI only. `HiEnBenchmarkTest`, warm `.ort` cache, production
policy (`intra=4`), 30 runs/sentence:

| Input | Output | tokens | median | p95 | stdev | tok/s |
|---|---|---|---|---|---|---|
| `पानी।` | `Water .` | 2 | **23.8 ms** | 28.6 | 3.0 | 116.1 |
| `नमस्ते, आप कैसे हैं?` | `Hi , how are you ?` | 6 | **43.2 ms** | 51.5 | 3.9 | 159.0 |
| `आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ।` | `The weather is great today and I want to go out .` | 12 | **76.7 ms** | 81.5 | 3.6 | 177.0 |

**HI→EN is ~28% faster than EN→HI on the same device** (76.7 ms vs ~106 ms at 12 tokens),
reproducing the Phase 12 result from the SM-M315F for the same reason: the indic-en checkpoint's
per-token `lm_head` is 32k wide, not 122k. Output correct in all three cases.

### A within-device memory asymmetry — and a trap that nearly became a false finding

| State (warm, cache hit) | total PSS |
|---|---|
| EN→HI only (entry #9 benchmark) | **174 MB** |
| HI→EN only | **528 MB** |
| both directions resident | **1077 MB** |

The first HI→EN reading was **761 MB**, which looked like "mmap is ineffective for HI→EN". It was
not a finding — it was the **one-time `.ort` bake**: that run was the first to load HI→EN under the
production policy, so `bakeOrt` extracted the 318 MB of source `.onnx` and ran ALL_OPT. Confirmed by
inspecting `filesDir` (the `hi_en_*.onnx` sources were present while the EN→HI ones were already
purged) and by re-running: sources gone, PSS 761 → 528 MB. **Anything measuring memory on a
direction's first-ever production launch is measuring the bake, not the steady state.**

The remaining 174 vs 528 MB gap *is* real — same device, same session options, same mmap flag, and
the HI→EN model is **smaller on disk** (318 MB of `.ort` vs 451 MB) yet **3× more resident**. This is
a useful constraint on the §5a mmap mystery: whatever governs mmap effectiveness **cannot be a pure
device/platform/kernel property**, because two models differ 3× on one device in one session. It is
at least partly graph- or model-dependent. Recorded as an observation; the mechanism still needs the
ORT-acceptance instrumentation §5a asks for.

---

## 4c. Static analysis and host-side checks

- **Python selftests pass**: `ort_profile_report.py --selftest` OK; `verify_cache.py --selfcheck` 6/6
  PASS (flatten/unflatten round-trip, cache growth, cross-attn constancy, K/V shapes).
- **Android lint had never been run in this project. It reports 22 errors and 63 warnings**, all
  pre-existing. Most are cosmetic (21 `MissingTranslation`, 14 `GradleDependency`, 10
  `IconLauncherShape`). Two are worth attention:

| Finding | Why it matters |
|---|---|
| **`Aligned16KB`: `arm64-v8a/libvosk.so` (`com.alphacephei:vosk-android:0.3.47`) is not 16 KB aligned** | Android 15+ devices ship **16 KB page sizes**, and Play requires 16 KB support for new targets. `libonnxruntime.so` **is** aligned, so translation is safe — but **the speech path would fail to load on a 16 KB-page device**. This device uses 4 KB pages, so it does not manifest here. Fixing needs a newer Vosk build, i.e. a dependency decision, not a code change. |
| `LockedOrientationActivity` ×2 | Expected — the deliberate Phase 10 portrait lock that fixed the unusable landscape layout. Not a defect; a candidate for a lint baseline/suppression. |

Neither was fixed: both are outside "run the tests", and the first is a dependency upgrade with its
own validation burden.

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

Four of the six items this session opened were closed by it, three of them negatively.

| Item | Status |
|---|---|
| Production-path thread A/B | **Closed (§2b).** F1 confirmed at ≈5%, not ≈10% |
| Re-examine `cpuArena=false` | **Closed — refuted (§2b C2).** Keep the shipping default |
| Pin the idle prime cores | **Closed — no gain (§2b C3).** Opportunity withdrawn |
| Baseline Profile generation | **Closed.** Generated on device (API 36); 4510 rules vs 27 hand-written. Effect on TTID still unmeasured |
| `simpleperf` symbol capture for SME/i8mm | **Closed (§2c).** SME confirmed executing (`smopa` int8, hottest loop in the app) and isolated by A/B at **~4–9%, not 2×** |
| Cap `threads` at 2 for 8-perf-core parts | **Open, deliberately not applied** — needs a second 8-perf-core device before changing a nine-device-validated rule (§2b) |
| Startup profile rules | **Open** — `BaselineProfileGenerator` does not set `includeInStartupProfile = true`, so there is no startup profile |
