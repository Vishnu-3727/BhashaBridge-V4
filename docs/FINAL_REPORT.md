# BhashaBridge V4 — final report

Offline English↔Hindi speech-to-speech translation on Android. A 200M-parameter IndicTrans2
transformer runs entirely on the phone's Arm CPU — recognition, translation and speech — and the app
declares no `INTERNET` permission, so the offline claim is enforced by the manifest rather than
promised in a policy.

This document is the single narrative pass over the project: how it works, where v3.4.1 was, what the
rebuild changed and what each change measured, and what is still open. It adds no new measurements.
Every number traces to a committed file, named per section. `OPTIMIZATION_SUMMARY.md` remains the
ledger; this is the report you read first.

**Baseline device** SM-M315F (Exynos 9611, 4×A73 + 4×A53, Armv8.0, 6 GB, Android 12) unless a row
says otherwise. **Validated on** 9 devices, 4 vendors, Armv8.0 → Armv9. **Runtime** ONNX Runtime
1.27.0.

| | v3.4.1 | V4 | |
|---|---|---|---|
| 12-token decode | 1353.6 ms | **640.1 ms** | 2.11× |
| Cold engine ready | 27,000 ms | **2,736 ms** | 9.9× |
| Process memory (PSS) | 981 MB | **460 MB** | −53% |
| Run-to-run σ, 12 tokens | 93.0 ms | **18.4 ms** | −78% |
| Long-sentence truncation | 5 / 16 (31%) | **0 / 16** | correctness |
| Across the ecosystem | 1 device | **50.3 → 412.8 tok/s** | same APK |

---

## 1. How it works

Speech in, speech out, four stages, no network call at any point.

| Stage | What runs | Cost |
|---|---|---|
| 1. Recognition | Vosk, on-device Kaldi models for en-in and hi (134 MB) | 0.55× realtime (EN, clean) |
| 2. Tokenization | SentencePiece dictionaries, parsed once per install into a packed binary cache | 1,036 ms cold / 252 ms warm |
| 3. Translation | IndicTrans2 200M-distilled, INT8, three ONNX graphs with a live KV-cache | 640 ms · 12 tokens · Armv8.0 |
| 4. Speech | Android system TTS | not instrumented (§6.5) |

### 1.1 The three-graph decoder

IndicTrans2's shipped ONNX decoder **had no KV-cache ports at all**. The export wrapper exposed only
`input_ids` / `encoder_hidden_states` / `encoder_attention_mask` — the underlying model implements the
mBART caching contract, but the wrapper dropped it, so the graph physically could not cache and every
generated token re-attended the entire prefix.

Optimum has no config for the custom `IndicTrans` architecture, so the cached decoder was
**hand-exported** as `encoder` + `decoder_init` + `decoder_step`, with the 72-tensor cache
(18 layers × 4) flattened to named ONNX I/O, verified numerically, quantized to INT8, and wired in
behind an abstraction that did not change.

That last clause is the architectural point. `MtEngine` depends only on the `Decoder` interface, and
the `LogitsSource` seam absorbed the entire uncached→cached rewrite without a single change to the
decode loop.

Source: `model_pipeline/EXPORT_WITH_CACHE.md`, `docs/KV_CACHE_RUNTIME.md`,
`docs/DECODING_ARCHITECTURE.md`.

### 1.2 The runtime configures itself from the CPU

`CpuCapabilities.detect()` reads HWCAP feature names from `/proc/cpuinfo` (NEON, FP16, dotprod, i8mm,
SVE/SVE2, SME/SME2) and big/little topology from `/sys` cpufreq. `ExecutionPolicy.select(caps)`
derives the ORT configuration: intra-op threads = half the performance cluster clamped `[1,2]`, CPU
arena off, sequential execution.

**There is no device list anywhere in the code.** The app shows the user the detected CPU and the
derived policy in its "Model & device" panel.

Source: `docs/ARM_PLATFORM_OPTIMIZATION.md`.

---

## 2. Where v3.4.1 was

v3.4.1 worked. It translated, offline, on a phone. It is kept untouched as an experimental control,
and every criticism below is a defect located in its source, not an impression of it.

None of these were careless. Each was a locally reasonable decision that compounded — which is the
actual lesson, because architecture does not fail through bad commits, it fails through good commits
nobody was measuring against a rule.

### 2.1 Structural

- **MainActivity was 961 lines.** It owned both `Translator` instances, both loading states, the
  direction state, the streaming-partial gate, the debounced typed-input corrector, three executors,
  the drawer, the history dialog, audio-file import and the bilingual UI. There was never a commit
  where it "became too large" — it passed 300 lines without anyone noticing, then 600, then 961.
- **Native resources had no owner.** The chain `Activity → Translator → OnnxSessionManager →
  OrtSession` had a creator at every level and a destroyer at none. `OnnxSessionManager.release()` was
  correct code with **zero call sites**, so every rotation leaked ~639 MB of native heap and re-paid
  the 8.6-second model load. The method existing is what made the code *look* safe in review.
- **~600 of 3,640 lines were dead.** A 302-line `SpeechManager.kt` with zero references; a fully
  implemented, never-validated beam search retained "for potential future use"; two `SpannableString`
  blocks built on every call and assigned to nothing, targeting an `ImageView` that had replaced the
  `TextView` they were written for.
- **Thread configuration was a hard-coded guess** — `intraOp=4, interOp=2`, with a comment claiming
  the values "were evidently tuned against real device measurements." Hedged language that means the
  author was inferring, not reporting.

Full defect list with the rule each produced: `docs/LESSONS_FROM_V3.md`.

### 2.2 Measured

Phase 6D re-ran v3's own INT8 graphs on V4's benchmark harness — same tokenizer, same decoder, same
sentences, 30 runs — which is the only apples-to-apples measurement of the v3 lineage that exists.

| Symptom | v3.4.1 | What it actually was |
|---|---|---|
| 12-token latency | 1353.6 ms | O(n²) decode — no KV-cache in the export |
| Throughput at 12 tokens | 9.5 tok/s | *Falling* with length, from 13.9 at 2 tokens |
| Run-to-run σ | 93.0 ms | Four threads fighting over four big cores |
| Process memory | 981 MB | ORT defaults, i.e. CPU arena on |
| Startup | "10–15 s" (self-reported) | Never instrumented; the same load path measured 27.0 s |
| Rotation | full reload | The leak above, paid again every time |
| Long-sentence output | 31% truncated | `maxSteps=18` against a cap promising `max(14, sourceLen)` |
| HI→EN provenance | none | Shipped graphs with no export script and no traceable checkpoint |
| `@Test` methods | 2 | — |

**The defect that survived both codebases.** Every benchmark sentence in either project is 2, 6 or 12
tokens. The truncation bug only fires on 20–25-token sources. A benchmark set chosen for convenience
hid a user-visible correctness defect for the entire life of two versions — a lesson about benchmark
selection, not about decoding.

Source: `docs/V3_VS_V4_COMPARISON.md`, `docs/CACHE_BENCHMARK.md`, `docs/AUDIT_2026-08-06.md`.

---

## 3. What V4 changed

Four levers carry the result. Everything else in the ledger is measurement around them.

### 3.1 Lever 1 — the complexity class

| Sentence | Tokens | v3 lineage | V4 | Speedup |
|---|---|---|---|---|
| "Water." | 2 | 184.5 ms | **166.4 ms** | 1.11× |
| "Hello, how are you?" | 6 | 526.4 ms | **350.0 ms** | 1.50× |
| "The weather is very nice today and I want to go outside." | 12 | 1353.6 ms | **640.1 ms** | **2.11×** |

| Output tokens | v3 lineage tok/s | V4 tok/s |
|---|---|---|
| 2 | 13.9 | 14.9 |
| 6 | 13.1 | 20.2 |
| 12 | **9.5 ↓** | **21.6 ↑** |

Per-step cost is flat at ~44–46 ms from token 2 onward. Time to first token: 78.5 / 107.1 / 139.5 ms
at 2 / 6 / 12 tokens.

**The number that carries the argument is not 2.11×. It is that one column falls and the other rises.**

### 3.2 Lever 2 — size, and the cost that turned out not to be one

ORT dynamic quantization (`QuantType.QInt8`, no calibration) took model weights **1869 MB → 472 MB
(3.96×)**, converting 145 of 217 `MatMul` nodes to `MatMulInteger`. Greedy token sequences stayed
**byte-identical to the fp32 reference**, max logit delta 0.448.

Splitting the decoder in two took assets from ~638 MB to 909 MB, and that +283 MB was charged to the
KV-cache for months. It was an export defect: hashing the raw tensor bytes showed `decoder_step` holds
**no unique tensor data at all** — every byte already exists in `decoder_init`, because
`torch.onnx.export` writes a full copy of the weights into each graph.

| Change | Before | After | Delta |
|---|---|---|---|
| One content-addressed weight blob per direction | 893.97 MB | 617.23 MB | −31% APK |
| Let the APK compress the blobs | 617.3 MiB | 520.3 MiB | −97.0 MiB |
| Ship optimized-ONNX + shared blob (storage) | 473 MB | 280 MB | −193 MB |
| Same change, process memory | 783 MB | 460 MB | −324 MB PSS |
| CPU arena off | 981 MB | 605 MB | −38% PSS |
| Evict the other direction before building the new one | 1394.8 MB | 883.1 MB | −36.7% swap peak |

Every row was measured with translation output held bit-identical.

The honest current line: **V4 ships a bidirectional model in less space than v3.4.1 used for the same
two directions**, and the KV-cache's real cost was never the 283 MB it was charged for.

One caveat the table cannot hide: the APK saving does not move device steady-state storage on its own,
because ORT's `.ort` bake re-inlines the weights — measured, not assumed. The storage row above is what
closed that gap.

### 3.3 Lever 3 — startup, and why instrumentation came first

Of the original ~25 s, **49% was a JSON parser** consuming one character per `Reader.read()` across
3.4 M characters, and **46% was ORT building sessions**. Unpacking 472 MB of assets — the visibly
expensive part, the one everyone assumes — was 1.8 s, once. Measuring first is what stopped three
weeks being spent on the models.

| Step | Engine ready | Change |
|---|---|---|
| Phase 10 baseline | 27,000 ms | — |
| Buffered + block-wise dictionary parse | 16,584 ms | −32.8% |
| Parallel ONNX session load | 10,502 ms | −36.7% |
| Optimized-graph cache + `.ort` mmap | ~5,134 ms | −51% |
| Mapped initializers (removes the initializer copy) | 4,812 ms | −6% |
| Packed binary vocabulary cache | **2,736 ms** | −43% |

The tokenizer alone went **3,086 → 1,036 ms** cold and 1,255 → 252 ms warm. Reading the packed format
is ~157,000 iterations of a trivial loop instead of 4,000,000 of a branchy one; the cost removed was
JIT warm-up as much as parsing.

The last row's originally published figures (tokenizer 514 ms, `engine_init` 2264 ms) were measured
against a **truncated** vocabulary cache and are retracted in place; the table carries the corrected
measurement.

**One reverted experiment belongs here.** Running the tokenizer parse concurrently with the session
loads made cold start **6.6% worse** (5,134 → 5,475 ms). Three ORT sessions at `intra=2` already
saturate four big cores; a fourth CPU-bound thread only adds contention. The benchmark that showed a
win had warmed the parser first.

### 3.4 Lever 4 — a derived Arm policy instead of a constant

The naive rule was implemented, measured, and rejected: "threads = all four performance cores" gives
**719.0 ms / σ 88.8** against **667.2 ms / σ 18.4** for the half-cluster rule. v3.4.1's hard-coded
value was slower *and* five times jitterier on the hardware it was written for.

A later production-path sweep confirmed it directly: `intra4` pinned is +8.2% on the long sentence and
+26.9% on the short one, with 2.8× the standard deviation; `intra4` unpinned carries the worst jitter
in the sweep (p95 896 ms against 677 ms).

**The classifier then broke twice on real silicon, and both fixes are measured.** The rule "only the
top frequency tier is performance" filed a Snapdragon 8 Gen 1's three A710 mid cores as little and ran
inference single-threaded on the most capable CPU in the database. Corrected to "every tier above the
lowest," which then broke on an 8× Oryon part where every core reports `CPU part 0x002` and DVFS
splits 6 @3629 + 2 @4742 — calling six full-size cores "efficiency." The split is now gated on **core
IP**, because frequency ratio provably cannot substitute: the Dimensity 930's genuine A55/A78 split is
2000/2200 = 0.91, *higher* than that Oryon's 0.77.

---

## 4. What it measures across the ecosystem

Same APK, no recompile.

| Device | ISA | intra / affinity | tok/s | 12-token median | cold engine-init |
|---|---|---|---|---|---|
| SM-M315F (Exynos 9611) | Armv8.0, no dotprod | 2 / ON | 50.3 | 894 ms | 17,197 ms |
| moto g73 (D930) | Armv8.2 | 1 / OFF | 177.5 | 256 ms | 6,936 ms |
| SM-E146B (Exynos 1330) | Armv8.2 | 1 / OFF | 152.4 | 307 ms | 6,118 ms |
| Nothing A015 (D7300) | Armv8.2 | 2 / ON | 200.5 | 232 ms | 4,430 ms |
| LAVA LXX518 (D7300) | Armv8.2 | 2 / ON | 207.5 | 219 ms | 3,904 ms |
| OPPO CPH2603 (D1080) | Armv8.2 | 1 / OFF | 151.9† | 304 ms† | 4,818 ms |
| vivo V2338 (SD6 Gen 1) | Armv8.2 | 2 / ON | 186.8 | 247 ms | 7,960 ms |
| Galaxy S22 Ultra (SD8 Gen 1) | Armv8.6 + i8mm | 1 / OFF‡ | 211.8†‡ | 188 ms†‡ | 16,205 ms† |
| **Galaxy S26 Ultra (SD8 Elite Gen 5)** | **Armv9 + i8mm + SVE2 + SME** | 4 / OFF | **412.8** | **106 ms** | **2,472 ms** |

†throttled ‡single-threaded under the policy misclassification since fixed.

The regression harness reports **+720% throughput** against the Armv8.0 baseline: 14 metrics improved,
0 regressed. HI→EN, measured separately, is ~28% faster than EN→HI (23.8 / 43.2 / 76.7 ms, 177.0
tok/s) because the indic-en `lm_head` is 32k wide rather than 122k.

Source: `bench/results/cross-device/CROSS_DEVICE_REPORT.md`.

### 4.1 The ISA rung is real and it is free

Armv8.0 → Armv8.2 with dotprod takes throughput 50 → 152–207 tok/s, because MLAS dispatches its
SDOT/UDOT int8 GEMM kernel on HWCAP at load. Same binary. The isolated dotprod share cannot be
separated from the A73→A78 microarchitecture step, so the *enabler* is graded MEASURED and the *split*
is explicitly ESTIMATED.

### 4.2 SME is live, and it is worth 4–9%, not 2×

ORT's operator profiler **cannot** detect SIMD dispatch — MLAS's kernel choice is invisible to it, a
negative result worth not re-deriving. So the proof came from `simpleperf` (83.2% of CPU inside
`libonnxruntime.so`) plus capstone disassembly of the hottest 40-byte loop, which is
`smopa za0.s, p2/m, p2/m, z4.b, z8.b` — KleidiAI's SME int8 outer-product kernel. It was then *priced*
by A/B through `mlas.disable_kleidiai`: **+4.9%/3.6% cool, +9.1%/9.0% hot**.

The S26 Ultra's 2× over the S22 Ultra is therefore microarchitecture plus thread count plus thermal
headroom — **not the ISA**. Proving the kernel executes and then finding it worth single digits is a
more useful result than claiming the 2×.

### 4.3 What is not claimed

No execution-provider or kernel selection acts on the detected `dotprod` / `i8mm` / `sve2` / `sme2`
flags. INT8 acceleration comes from MLAS's own HWCAP dispatch, which this project did not build.
`ExecutionPolicy` is the single place such selection would go. The detector surfaces SME2; **no SME2
optimization exists and none is claimed.**

### 4.4 Quality, held as a gate

Behavioural parity was a gate on every runtime change: no optimization that altered output was ever
accepted. Separately, the INT8 model was scored against fp32 on a real corpus — WMT14 newstest, first
500 sentences, paired bootstrap, 1000 resamples:

| Direction | BLEU fp32 → INT8 | p | chrF2++ fp32 → INT8 | p |
|---|---|---|---|---|
| EN→HI | 21.88 → 21.85 | 0.336 | 49.21 → 48.93 | 0.037 |
| HI→EN | 32.31 → 32.79 | 0.039 | 58.67 → 58.83 | 0.134 |

Opposite signs, comparable magnitudes, marginal p-values — a quantization perturbation, not
degradation. FLORES was intended; both HuggingFace mirrors are gated (403), and the substitution is
documented rather than worked around.

The finding worth more than the scores: exact token parity on real sentences is **50.6% / 44.4%**,
against a synthetic gate's claim of identical greedy tokens. Half the outputs diverge and the corpus
scores are statistically indistinguishable — parity overstated the risk and was never a quality metric.

Source: `docs/QUALITY_EVALUATION.md`.

### 4.5 Under sustained load

500 direction switches: 0 failures, PSS −13 MB, native heap +1 MB. 1024 consecutive translations
unplugged: +29% latency drift, **attributed rather than labelled** — the big cluster steps 2942 → 2092
MHz monotonically while PSS falls and coresBusy holds, so DVFS, with memory, GC, threading and
lifecycle each excluded by their own measurement.

The release build is **faster than debug on every latency metric** (81.0 vs 86.0 ms, 559.7 vs 535.1
tok/s, engine-init 1839 vs 2039 ms) at the same 32.7 °C both ends — because `Metrics` and debug logging
compile out, not because of R8, which is still off. The consequence is stated rather than buried:
every latency number in the ledger is a debug number and roughly 5% pessimistic.

Source: `docs/SUSTAINED_STRESS_TEST.md`, `docs/RELEASE_VALIDATION.md`.

---

## 5. Method, and three rules learned the hard way

Every performance number was produced by `BenchmarkSuiteTest` — the shipped benchmark, running the
shipped code path — under schema `bb-bench/1`: 30 measured iterations, 5 warm-up, counterbalanced
sentence order, startup measured cold/warm/hot, a `SystemStats` snapshot before and after. Fields the
device does not expose are recorded as `null` with the reason, rather than omitted: `nr_migrations`,
`energyCounter` and SoC thermal zones are not readable unrooted and are honestly absent. Raw JSON is
committed append-only, one file per device, and never overwritten.

1. **Never claim a speedup that was not measured on a device.** Entries carry an evidence grade —
   MEASURED / INFERRED / NOT MEASURED. One commit is a provable halving of copy work with **no**
   latency claim attached, because no device was connected when it landed.
2. **A benchmark that runs the non-production load path measures the non-production load path.** A
   sweep finding that `cpuArena=false` "costs 12%" was **refuted** by a production-path A/B and is
   retracted in place — the sweep had run with the optimized-graph cache off.
3. **Subtract thermal drift before reading anything.** Same build, same device, same test read
   **640 / 680 / 690 / 864 ms** on the 12-token sentence across one afternoon as the phone went
   31 °C → 34 °C under repeated large installs — a **35% spread with no code change at all**. Two of
   those were nearly written up as a regression.

Any delta in this report under roughly 10% is not readable without its temperature. The three that
carry the argument — 2.11× decode, 9.9× engine ready, 31% → 0% truncation — are far outside that band.
The small ones are not claimed as wins.

**The ledger discipline.** One experiment, one entry, one commit. The ledger carries **6 REVERTs** with
device numbers, **2 retractions** of earlier published findings, and **2 entries closed by discovering
their own premise was false**. An item leaves the open queue only by becoming an entry — including as
a REVERT or a NO EFFECT. Deleting a row because it turned out not to work is how a ledger starts lying.

### 5.1 Engineering posture, side by side

| | v3.4.1 | V4 |
|---|---|---|
| ONNX Runtime | 1.17.1 (2024) | 1.27.0 |
| Thread policy | hard-coded `intraOp=4, interOp=2` | derived from `/proc/cpuinfo` HWCAP + cpufreq topology |
| `@Test` methods | 2 | 94 |
| Devices validated | 1 | 9, four vendors |
| Export pipeline | none for HI→EN; no verification gate | `cached_export.py` + `quantize_cached.py` + `verify_cache.py` (7 numeric checks, 7/7, max_abs_diff 9.06e-06) |
| Negative results published | — | every REVERT / NO EFFECT, with its numbers |
| Model binaries in git | yes | governed by `.gitignore` R14.5 |
| `INTERNET` permission | present | **absent** — the offline claim is enforced by the manifest |

---

## 6. Where V4 is still worse, or simply unfinished

Ordered by how much they matter.

1. **Distribution.** 619 MB of assets, down from 909 MB, still exceeds Play's per-ABI limits.
   Side-loading works today; shipping through Play needs Play Asset Delivery or a first-run download.
   This reaches more users than any technical factor in this report.
2. **Release engineering is unfinished.** R8 is disabled, the release build is signed with the SDK
   debug key, and `versionCode` has never been incremented — which also feeds the ORT cache stamp.
   Nothing ships until this is done. `AUDIT_2026-08-06.md` H4.
3. **No fine-tuning.** The model-quality category is addressed only as output-correctness and
   quantization-parity work. Saying so is cheaper than dressing something else up as it.
4. **Speech recognition accuracy is unmeasured.** The pipeline is proven against a real 16 kHz WAV; no
   word-error rate against human voices was measured. The bundled Vosk Hindi model publishes
   14.96–39.08% WER depending on test set, which is the realistic ceiling.
5. **TTS latency is unmeasured.** Playback is confirmed to start; time-to-first-audio needs an
   utterance-progress probe the app does not expose. Speech-to-speech is therefore quoted as
   "≈2.5 s **plus** the system TTS engine's own start latency" rather than as one number.
6. **One shipping default rests on INFERRED evidence.** The intra-op clamp's *bound* `[1,2]` comes
   from a sweep table, not from a run after the edit, and no remaining device has enough performance
   cores to reach it. The underlying claim — "4 threads is never optimal" — has since been closed as
   KEEP; the bound has not.
7. **Portrait only.** A landscape layout was found unusable during validation and the orientation is
   locked rather than left broken. A responsive layout is the real fix.
8. **No demo video.**

**Arm Performix was not used and was not evaluated.** The method in §5 is what the claims rest on:
re-runnable by a judge from this repo, schema'd so two runs are comparable, with the raw evidence
committed. Adopting Performix would be a genuine addition — an independent measurement of the same
work — and it is named here as a next step rather than passed over in silence.

---

## 7. What is still on the table

The live queue, ordered by expected value. Each item states its hypothesis and what would close it, so
a run produces a §3 entry or a recorded negative — never a shrug. Full queue with closures:
`OPTIMIZATION_SUMMARY.md` §9.

### 7.1 Export-side — the largest remaining wins

**Merge the two decoder graphs into one.** (From Q24, closed as not achievable from Java.) The
duplication is real and large: loaded together, the two decoders share **884 KB of 323,756**, so
`decoder_step`'s ~152 MB is resident twice — roughly **37% of process anonymous memory**.
`addExternalInitializers` is the only Java-reachable mechanism and it **costs +64.7 MB** where it
should have saved 61.3: the tensor is zero-copy but the session copies it anyway. The runtime cannot
fix this. One merged decoder graph, branching on cache presence, is the fix, and it is an export
change.

**Collapse the tied embedding stored twice per decoder.** (Q16, **79.3 MB**.)
`decoder.embed_tokens.weight_quantized` (V, 512) UINT8 and `onnx::MatMul_*_quantized` (512, V) INT8 are
the same matrix under two quantization schemes and two orientations — confirmed by reading the
initializer table. The shared-blob work already deduplicates every other copy; this is the one case it
cannot catch. Export both uses from one scheme and orientation so `dedup_weights.py` content-addresses
them. Worth 62.8 MB EN→HI + 16.5 MB HI→EN — **not** the ~158 MB once recorded, which counted both
copies of a matrix still needed once. The gate must be a **quality** check, not just parity, since
re-quantizing may move the output.

**A QDQ re-export, to make the accelerator EPs reachable.** (Follows from Q7.) All of CPU / NNAPI /
XNNPACK are compiled into the AAR, so this was never a build limitation. **XNNPACK claims zero nodes**
— the hot GEMMs fuse to `com.microsoft` contrib ops (`DynamicQuantizeMatMul`, `MatMulIntegerToFloat`)
that its EP cannot take. NNAPI takes ~5% of nodes, inflates CPU node executions 35–40% through
partitioning, and is **2.25× slower**. That is a property of the export, not of the CPU; a QDQ-form
re-export is the only thing that would change the answer.

### 7.2 Correctness and coverage

**Close the length-cap fix on a real corpus.** (Q0.) The `sourceLen * 1.6 + 8` expansion factor fixed
5/16 truncations to 0/16 on a 16-sentence device test. Closing it properly means a 200-sentence corpus
per direction, counting no-EOS stops before and after, with p95 latency staying bounded by `maxSteps`.
Correctness, not speed.

**English ASR under noise.** (Q8c — cheap, and may open a real problem.) Hindi was measured across
clean / 10 dB / 5 dB and the failure only noise exposes was found: stock Hindi runs **1.91× realtime at
10 dB and 2.59× at 5 dB**, i.e. it loses to live speech in exactly the noisy conditions the product
targets. Narrowing to English's decode configuration (3000/10.0/2.0) cut that 60% at 10 dB with
transcripts identical in all three conditions. English was measured **clean only** (0.55×) and is
already at the narrow configuration, so there is no retune left to try — but "keeps up" is still an
untested claim outside a quiet room. The harness and the noise generator both already exist.

**Split the 640 ms audio-import path.** (Q8b.) `AudioFileTranscriber` costs ~44% on top of recognition:
MediaCodec decode, a channel-average downmix, a linear-interpolation resampler with no anti-alias
filter, and full flow collection. The resampler is an accuracy bug as well as a cost. Split the stages
inside `AsrTuningBenchmarkTest` before deciding whether it is decode or resample. Affects imported
files only, never the microphone.

**Beam search against the real runtime.** (Q6 — may end as a REVERT.) Beam is implemented and
unit-tested and has never been run against the model; its quality/latency trade is unknown. It also
falls back to `decoder_init` every step today, which is what the Q1 slice experiment was ultimately
held for. An on-device A/B with quality judged on a fixed sentence set closes it either way.

### 7.3 Blocked, and honest about it

**Explain the device-dependent mmap benefit.** (Q5.) The contradicting pair was the S26 Ultra and the
CPH2603, neither of which is available. More devices have not resolved it and will not. This needs ORT
debug logging or native heap accounting on the specific silicon that disagreed — an explanation, not a
speedup.

**Any further SME claim.** Permanently closed. SME was priced at 4–9% on the S26 Ultra and that
evidence stands in `bench/results/cross-device/`. No device that can now be obtained has SME, so **no
SME claim may be re-measured or extended** — only cited. Stating the boundary is part of the result.

### 7.4 Product-level, beyond the queue

- **Play Asset Delivery or a first-run download** — the single change that most affects who can
  actually install this.
- **Finish release engineering** — R8 on, a real signing config, `versionCode` incremented. Then
  re-baseline every latency number against the release build, which is already known to be ~5% faster.
- **Measure WER against human voices**, in both languages, against the published 14.96–39.08% ceiling.
- **Instrument time-to-first-audio** so speech-to-speech can be quoted as one number.
- **Fine-tuning** — the one challenge category this project addresses only obliquely.
- **Run the suite under Arm Performix** as an independent check on the same work.

---

## Sources

Every figure above traces to a committed file.

| Document | What it holds |
|---|---|
| `docs/OPTIMIZATION_SUMMARY.md` | The ledger. §3.1–§3.57 with evidence grades, plus the open queue in §9 |
| `docs/V3_VS_V4_COMPARISON.md` | The side-by-side measurement, including where V4 is worse |
| `docs/LESSONS_FROM_V3.md` | Each v3.4.1 defect and the rule it produced |
| `docs/CACHE_BENCHMARK.md` | Cached vs uncached at equal precision |
| `docs/QUALITY_EVALUATION.md` | BLEU / chrF2++ against fp32, paired bootstrap |
| `docs/ARM_PLATFORM_OPTIMIZATION.md` | CPU detection and the derived policy |
| `docs/RELEASE_VALIDATION.md`, `docs/SUSTAINED_STRESS_TEST.md` | The release build and the load campaign |
| `docs/AUDIT_2026-08-06.md` | The independent correctness audit and its findings |
| `bench/results/cross-device/` | Raw per-device JSON/CSV/Markdown, append-only |
| `SUBMISSION.md` | The same evidence mapped to the challenge's categories |

Nothing above is estimated unless it says so.
