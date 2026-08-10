# BhashaBridge V4 — Arm AI Optimization Challenge submission

**Track: Mobile AI.** Offline English↔Hindi speech-to-speech translation on Android. A 200M-parameter
IndicTrans2 transformer runs entirely on the phone's Arm CPU — recognition, translation and speech.
The app declares **no `INTERNET` permission**, so the offline and privacy claims are enforced by the
manifest rather than promised in a policy.

This document maps the challenge's optimization categories to the evidence in this repository. Every
number below was measured on a device and traces to a named file. Nothing is estimated unless it says
so.

---

## The one-paragraph version

IndicTrans2's shipped ONNX decoder **had no KV-cache ports at all** — the export wrapper exposed only
`input_ids` / `encoder_hidden_states` / `encoder_attention_mask`, so the graph physically could not
cache and every generated token re-attended the entire prefix. Optimum has no config for the custom
`IndicTrans` architecture, so the cached decoder was **hand-exported** as `decoder_init` +
`decoder_step` graphs with 72 named cache tensors (18 layers × 4), verified numerically, quantized to
INT8, and wired in behind an abstraction that did not change. Decode went from O(n²) to O(n), which
shows up as **tokens/sec rising with output length instead of falling**. Everything after that —
runtime tuning, startup work, the capability-derived Arm policy, nine devices — is measurement.

---

## 1. Model size — reduce size on disk or in memory

| Result | Measurement | Evidence |
|---|---|---|
| **1869 MB → 472 MB INT8 (3.96×)** | ORT dynamic quantization, `QuantType.QInt8`, no calibration; 145 of 217 `MatMul` → `MatMulInteger` | `model_pipeline/EXPORT_WITH_CACHE.md`, `OPTIMIZATION_SUMMARY.md` §3.6 |
| **Process memory −38%** (981 → 605 MB PSS) | CPU arena off, 12 configs × 30 runs, one variable at a time | `docs/ORT_TUNING.md`, §3.8 |
| **Swap peak −36.7%** (1394.8 → 883.1 MB), post-swap −42.0% | Evict the other direction *before* building the new one, n=3 per arm | §3.24b |
| **451 MB made file-backed**, anonymous heap −151 MB (−27%) | `FileChannel.map` + `use_ort_model_bytes_for_initializers`; both arm orderings run | §3.27 |
| **~−50% steady-state `filesDir`** | Bake once to ORT format, purge the source `.onnx` | §3.14 |
| Output preserved throughout | Greedy token sequences **identical** to the fp32 reference; max logit Δ 0.448 | `verify_cache.py --atol 1.0`, 7/7 |

Two of these carry qualifications that belong here rather than in a footnote. `mappedInitializers`
ships **off by default**: the memory result is real, but the OOM-survival benefit that justified it
was measured and **not established** (kills in both arms, 1/6 vs 2/6 — noise), and a measured cost
appeared instead: a ~2× latency spike while reclaimed pages are re-read (§3.28). And `release()` does
return memory, but **asynchronously** — allocated native heap drops 557.8 → 13.2 MB instantly, while
the allocator hands pages back to the OS over roughly ten seconds (§3.25, which corrects an earlier
wrong conclusion in this same repo).

**Cost, stated plainly.** The KV-cache splits each decoder into `decoder_init` + `decoder_step`,
duplicating weights. Total assets went from ~638 MB in v3.4.1 to **909 MB**. That +283 MB is what
bought the 2.11× decode and the linear cost curve. See `docs/V3_VS_V4_COMPARISON.md` §6.

---

## 2. Model speed — tokens/sec, time to first token, latency

**KV-cache, INT8 uncached vs INT8 cached, same device, tokenizer, decoder and sentences, 30 runs each**
(`docs/CACHE_BENCHMARK.md`):

| Sentence | Tokens | Uncached | Cached | Speedup |
|---|---|---|---|---|
| "Water." | 2 | 184.5 ms | 174.4 ms | 1.06× |
| "Hello, how are you?" | 6 | 526.4 ms | 355.2 ms | 1.48× |
| "The weather is very nice today and I want to go outside." | 12 | 1353.6 ms | 637.4 ms | **2.12×** |

**The complexity class changed, which is the result that matters.** Uncached tokens/sec *falls* with
output length (13.9 → 13.1 → 9.5). Cached *rises* and flattens (14.9 → 20.2 → 21.6). Per-step cost is
flat at ~44–46 ms from token 2 onward.

**Time to first token** (`VALIDATION_REPORT.md` §2.2, TTFT = encoder + `decoder_init`):

| Tokens | Total median | p95 | stdev | TTFT | per-step |
|---|---|---|---|---|---|
| 2 | 163.1 ms | 196.8 | 11.5 | **78.5 ms** | 75.7 ms |
| 6 | 364.5 ms | 403.9 | 17.1 | **107.1 ms** | 45.7 ms |
| 12 | 667.9 ms | 683.5 | 18.0 | **139.5 ms** | 44.2 ms |

**Tail latency and predictability**, production config vs untuned: p95 (12-token) 864 → 695 ms
(−20%), run-to-run stdev 96 → 21 ms (**−78%**). The tuning win is variance and memory, not a large
median speedup, and the report says so rather than claiming otherwise.

**Startup — 27.0 s → ~5.1 s to first translation**, on the Armv8.0 baseline device:

| Change | Engine ready | Evidence |
|---|---|---|
| Phase 10 baseline | 27,000 ms | `VALIDATION_REPORT.md` §2.1 |
| Buffered + block-wise dictionary parse | 16,584 ms (−32.8%) | §3.11 |
| Parallel ONNX session load | 10,502 ms (−36.7%) | §3.12 |
| Optimized-graph cache + `.ort` mmap | **~5,134 ms** cold-launch median | §3.13, §3.14, §3.29 |

The instrumentation is the story here. Of the original ~25 s, **49% was a JSON parser** consuming one
character per `Reader.read()` across 3.4 M characters, and **46% was ORT building sessions**.
Unpacking 472 MB of assets — the visibly expensive part — was 1.8 s, once. Measuring first is what
stopped three weeks being spent on the models.

**HI→EN**, measured on the S26 Ultra, 30 runs: 23.8 / 43.2 / **76.7 ms** at 2/6/12 tokens, 177.0
tok/s — ~28% faster than EN→HI, because the indic-en `lm_head` is 32k wide rather than 122k.

---

## 3. Model quality — improve fine-tuning or output quality for a given model size

**No fine-tuning was performed.** This is the one category where the project is partial, and saying so
is cheaper than dressing something else up as it.

What *was* improved is output quality at fixed model size, and it is measured:

| Defect | Result | Evidence |
|---|---|---|
| **Silent mid-sentence truncation** — `maxSteps` defaulted to 18 while `targetCap` promised `max(14, sourceLen)`, so long inputs were cut off with no EOS and no indication to the user | **31% → 0%** of test sentences truncated (n=16, 5–25 source tokens, through the real engine) | commits `b8c2ed2`, `be32b59`; §3.23 |
| Tokenizer kept the backslash instead of decoding JSON escapes, mis-reading four vocabulary entries | fixed; quotation marks now survive a round trip (`He said "hello" to me.` → `उन्होंने कहा , " मुझे नमस्कार । "`) | commit `129ba14`; `AUDIT_2026-08-06.md` H1 |
| ASR corrector rewrote the middle of words | fixed | commit `cd7badb` |

Both first two shipped in v3.4.1 as well. Every benchmark sentence in the project is 2, 6 or 12 tokens,
which is exactly why the truncation defect survived for the entire life of both codebases — a lesson
about benchmark selection more than about decoding.

**Quantization did not cost quality**: greedy token sequences are byte-identical to the fp32 reference
across the verification set, at a max logit delta of 0.448. Behavioural parity was a **gate** on every
runtime change in this project; no optimization that altered output was ever accepted.

---

## 4. Inference server speed

**Not applicable.** Mobile track — there is no server, no network call and no `INTERNET` permission.

---

## 5. Developer experience — tools, workflows, setup, documentation, usability

| | Evidence |
|---|---|
| **Reproducible model pipeline** | `cached_export.py` → `quantize_cached.py` → `verify_cache.py`. Dimensions read from the model config, never hard-coded |
| **A numeric gate, not a smoke test** | `verify_cache.py` runs 7 checks: model loads, `use_cache` executes, init/step outputs valid, cache count and shapes correct, cached logits match the reference, greedy tokens identical. **7/7, max_abs_diff 9.06e-06.** Cache flattening has a model-free `--selfcheck` |
| **Benchmarks are re-runnable tests, not screenshots** | `MtBenchmarkTest`, `MtTuningSweepTest`, `ProductionThreadSweepTest`, `HiEnBenchmarkTest`, `BenchmarkSuiteTest`, `LogitsReadBenchmarkTest`, `StartupProbeTest` |
| **One statistics implementation** | `Stats` (n/min/max/mean/median/p95/p99/stdev, nearest-rank) matching the host-side parser, so two reports can be compared without checking how each computed p95 |
| **Regression mode** | `bench_report.py --baseline` — JSON → CSV + Markdown with per-metric deltas |
| **Raw evidence committed** | Append-only JSONL/JSON/CSV per device in `bench/results/`; provenance rule is that every number comes from on-device `REPORT_JSON` |
| **Release-safe instrumentation** | `Metrics` entry points are `inline` and `BuildConfig.DEBUG`-gated — compile-time elimination, not an R8 side effect. **Zero app log lines in a full release session**, so user speech cannot reach a log |
| **Architecture written down and enforced** | `ARCHITECTURE_RULES.md`, `DEPENDENCY_RULES.md`, `CODING_STANDARDS.md` |
| **Negative results published** | Every REVERT and NO EFFECT is in `OPTIMIZATION_SUMMARY.md` with its numbers, including two **retracted** earlier findings |
| **22 documents**, including one about what the previous version got wrong and why | `docs/` |

The ledger discipline is the part worth a judge's attention: `OPTIMIZATION_SUMMARY.md` §0 defines a
recording protocol — one experiment, one entry, one commit — and every entry states its **evidence
grade** (MEASURED / INFERRED / NOT MEASURED) and its **next** step, so the document reads as
*problem → attempt → result → next attempt* rather than a list of wins.

---

## 6. Arm-specific optimization

**The runtime reads the CPU and configures itself. There is no device list anywhere in the code.**

`CpuCapabilities.detect()` reads HWCAP feature names from `/proc/cpuinfo` (NEON, FP16, dotprod, i8mm,
SVE/SVE2, SME/SME2) and big/little topology from `/sys` cpufreq. `ExecutionPolicy.select(caps)` derives
the ORT configuration: intra-op threads = half the performance cluster clamped `[1,2]`, CPU arena off,
sequential execution. The app **shows the user** the detected CPU and the derived policy in its "Model
& device" panel.

**The naive rule was implemented, measured, and rejected**: "threads = all four performance cores"
regressed to 719.0 ms / stdev 88.8 against 667.2 ms / stdev 18.4 for the half-cluster rule.

**Nine devices, four vendors, Armv8.0 → Armv9** (`bench/results/cross-device/CROSS_DEVICE_REPORT.md`):

| Device | ISA | dotprod | intra / affinity | tokens/sec | long median | cold engine-init |
|---|---|---|---|---|---|---|
| SM-M315F (Exynos 9611) | Armv8.0 | **no** | 2 / ON | 50.3 | 894 ms | 17,197 ms |
| moto g73 (D930) | Armv8.2 | yes | 1 / OFF | 177.5 | 256 ms | 6,936 ms |
| SM-E146B (Exynos 1330) | Armv8.2 | yes | 1 / OFF | 152.4 | 307 ms | 6,118 ms |
| Nothing A015 (D7300) | Armv8.2 | yes | 2 / ON | 200.5 | 232 ms | 4,430 ms |
| LAVA LXX518 (D7300) | Armv8.2 | yes | 2 / ON | 207.5 | 219 ms | 3,904 ms |
| OPPO CPH2603 (D1080) | Armv8.2 | yes | 1 / OFF | 151.9† | 304 ms† | 4,818 ms |
| vivo V2338 (SD6 Gen 1) | Armv8.2 | yes | 2 / ON | 186.8 | 247 ms | 7,960 ms |
| Samsung S22 Ultra (SD8 Gen 1) | Armv8.6 + i8mm | yes | 1 / OFF‡ | 211.8†‡ | 188 ms†‡ | 16,205 ms† |
| **Samsung S26 Ultra (SD8 Elite Gen 5)** | **Armv9 + i8mm + SVE2 + SME** | yes | 4 / OFF | **412.8** | **106 ms** | **2,472 ms** |

†throttled ‡single-threaded (policy misclassification, since fixed)

**Same APK, no recompile: 50.3 → 412.8 tokens/sec across the ecosystem.** The regression harness
reports +720% throughput against the Armv8.0 baseline, 14 metrics improved, 0 regressed.

**Three Arm-specific results worth reading in full:**

**(a) The ISA rung is real and it is free.** Armv8.0 → Armv8.2 with dotprod takes throughput 50 →
152–207 tok/s, because MLAS dispatches its SDOT/UDOT int8 GEMM kernel on HWCAP at load. Same binary.
The isolated dotprod share cannot be separated from the A73→A78 microarchitecture step, so the
*enabler* is Measured and the *split* is explicitly Estimated.

**(b) SME is live, and it is worth 4–9%, not 2×.** This is the result the project is most careful
about. ORT's operator profiler **cannot** detect SIMD dispatch — MLAS's kernel choice is invisible to
it, a negative result worth not re-deriving. So: `simpleperf` (83.2% of CPU inside
`libonnxruntime.so`) plus capstone disassembly of the hottest 40-byte loop, which is
`smopa za0.s, p2/m, p2/m, z4.b, z8.b` — KleidiAI's SME int8 outer-product kernel. Then **priced** by
A/B through `mlas.disable_kleidiai`: **+4.9%/3.6% cool, +9.1%/9.0% hot**. The S26 Ultra's 2× over the
S22 Ultra is therefore microarchitecture plus thread count plus thermal headroom — **not the ISA**.
Proving the kernel executes and then finding it worth single digits is a more useful result than
claiming the 2×.

**(c) The classifier broke twice on real silicon, and both fixes are measured.** The rule "only the
top frequency tier is performance" filed a Snapdragon 8 Gen 1's three A710 mid cores as little and ran
inference **single-threaded on the most capable CPU in the database**. Fixed to "every tier above the
lowest is performance" — which then broke on an 8× Oryon part where *every* core is `CPU part 0x002`,
DVFS-split 6 @3629 + 2 @4742, calling six full-size cores "efficiency". The split is now gated on
**core IP**, because frequency ratio provably cannot substitute: the Dimensity 930's genuine A55/A78
split is 2000/2200 = 0.91, *higher* than that Oryon's 0.77.

**What is not claimed.** No execution-provider or kernel selection acts on the detected
`dotprod`/`i8mm`/`sve2`/`sme2` flags — INT8 acceleration comes from MLAS's own HWCAP dispatch, which
this project did not build. `ExecutionPolicy` is the single place such selection would go. The
detector surfaces SME2; **no SME2 optimization exists and none is claimed.**

---

## Benchmark method, and a note on Arm Performix

Every performance number in this repository was produced by `BenchmarkSuiteTest` — the shipped
benchmark, running on the shipped code path — under schema `bb-bench/1`: 30 measured iterations, 5
warm-up, counterbalanced sentence order, startup measured cold/warm/hot, a `SystemStats` snapshot
before and after. Fields the device does not expose are recorded as `null` with the reason, rather
than omitted: `nr_migrations`, `energyCounter` and SoC thermal zones are not readable unrooted and
are honestly absent. Raw JSON is committed append-only, one file per device, and never overwritten.

**Arm Performix was not used, and was not evaluated.** The method above is what the claims rest on:
re-runnable by a judge from this repo, schema'd so two runs are comparable, with the raw evidence
committed. Adopting Performix would be a genuine addition — an independent measurement of the same
work — and it is named here as a next step rather than passed over in silence.

**Three method rules this project learned the hard way**, stated because they qualify everything above:

1. **Never claim a speedup that was not measured on a device.** Entries carry an evidence grade;
   `13007e3` is a provable halving of copy work with **no** latency claim attached, because no device
   was connected when it landed.
2. **A benchmark that runs the non-production load path measures the non-production load path.** A
   sweep finding that `cpuArena=false` "costs 12%" was **refuted** by a production-path A/B and is
   retracted in place — the sweep ran with the optimized-graph cache off.
3. **Subtract thermal drift before reading anything.** Same build, same device, same test read
   **640 / 680 / 690 / 864 ms** on the 12-token sentence across one afternoon as the phone went 31 °C
   → 34 °C under repeated 938 MB installs — a **35% spread with no code change at all**. Two of those
   were nearly written up as a regression. Any comparison here under roughly 10% is not readable
   without its temperature.

---

## Known limitations

Ordered by how much they matter.

1. **909 MB of assets.** Per-ABI release APK exceeds Play's limits; side-loading works today, and
   shipping through Play needs Play Asset Delivery or a first-run download. Distribution limits reach
   more than any technical factor here.
2. **Release engineering is unfinished.** R8 is disabled, the release build is signed with the SDK
   debug key, and `versionCode` has never been incremented — which also feeds the ORT cache stamp.
   `AUDIT_2026-08-06.md` H4. Nothing ships until this is done.
3. **No fine-tuning.** The model-quality category is addressed only as output-correctness work (§3).
4. **Speech recognition accuracy is unmeasured.** The file test proves the pipeline against a real
   16 kHz WAV; no word-error rate against human voices was measured. The bundled Vosk Hindi model
   publishes 14.96–39.08% WER depending on test set, which is the realistic ceiling.
5. **TTS latency is unmeasured.** Playback is confirmed to start; time-to-first-audio needs an
   utterance-progress probe the app does not expose. Speech-to-speech is therefore quoted as
   "≈2.5 s **plus** the system TTS engine's own start latency" rather than as one number.
6. **One shipping default rests on INFERRED evidence.** The intra-op clamp `[1,4]` → `[1,2]` comes
   from the S26 Ultra's sweep table, not from a run after the edit. That device is no longer
   available, and neither remaining device *reaches* the bound, so it can only be closed by an
   explicit thread sweep on a second topology (§9 Q2b).
7. **Portrait only.** A landscape layout was found unusable during validation and the orientation is
   now locked rather than left broken. A responsive layout is the real fix.
8. **No demo video.**

---

## Where to look next

| Document | What it is |
|---|---|
| [`docs/OPTIMIZATION_SUMMARY.md`](docs/OPTIMIZATION_SUMMARY.md) | The technical report. Every optimization, kept **and** reverted, with evidence grades and an open-experiment queue |
| [`docs/JUDGE_SCORECARD.md`](docs/JUDGE_SCORECARD.md) | Self-assessment against these criteria, written to argue against itself |
| [`docs/V3_VS_V4_COMPARISON.md`](docs/V3_VS_V4_COMPARISON.md) | What the rewrite bought, measured against the previous version — including where it is worse |
| [`bench/results/cross-device/CROSS_DEVICE_REPORT.md`](bench/results/cross-device/CROSS_DEVICE_REPORT.md) | Nine devices, Armv8.0 → Armv9, with the corrections and retractions in place |
| [`bench/results/cross-device/S26U_EXPERIMENTS.md`](bench/results/cross-device/S26U_EXPERIMENTS.md) | The SME investigation, the thread sweep, and the first HI→EN measurements |
| [`docs/ARM_PLATFORM_OPTIMIZATION.md`](docs/ARM_PLATFORM_OPTIMIZATION.md) | CPU detection and the policy derived from it |
| [`docs/CACHE_BENCHMARK.md`](docs/CACHE_BENCHMARK.md) | Cached vs uncached, at equal precision |
| [`docs/AUDIT_2026-08-06.md`](docs/AUDIT_2026-08-06.md) | Independent engineering audit, including the defects it found |
| [`docs/LESSONS_FROM_V3.md`](docs/LESSONS_FROM_V3.md) | Every lesson is a real defect in the previous source, with the rule it produced |
