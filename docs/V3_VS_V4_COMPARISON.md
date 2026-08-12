# v3.4.1 vs V4 — measured comparison

What the rewrite actually bought, and what it cost. Every number here already exists in one of the
two projects' evidence trails; this document only puts them side by side. Sources are named per
section so nothing has to be taken on trust.

**Device.** Both sides are Samsung Galaxy M31 (SM-M315F, Exynos 9611, 4×Cortex-A73 + 4×Cortex-A53,
Armv8.0-A, 6 GB, Android 12) unless a row says otherwise. Two rows use the SM-S948B (Snapdragon 8
Elite Gen 5) because no M31 number exists for them; they are marked.

**Baseline choice.** v3.4.1's own `TechnicalReport.md` reports latency by *word count* and was not
produced by an instrumented harness. Where a stronger number exists it is used instead: Phase 6D
re-ran **v3's own int8 graphs on V4's benchmark harness** (same tokenizer, same decoder, same
sentences, 30 runs), which is the only apples-to-apples measurement of the v3 lineage that exists.
Rows using it say so.

---

## 1. Translation latency — EN→HI

`MtBenchmarkTest`, 30 runs per sentence, 3 warm-ups discarded, greedy, medians.
Source: `docs/CACHE_BENCHMARK.md` (v3 lineage) and `docs/OPTIMIZATION_SUMMARY.md` §3.24 (V4 current,
cooled run 2026-08-06).

| Sentence | Tokens | v3 lineage (int8 uncached) | V4 current | Speedup |
|---|---|---|---|---|
| "Water." | 2 | 184.5 ms | **166.4 ms** | 1.11× |
| "Hello, how are you?" | 6 | 526.4 ms | **350.0 ms** | 1.50× |
| "The weather is very nice today and I want to go outside." | 12 | 1353.6 ms | **640.1 ms** | **2.11×** |

Decode component only: 143.7 → 133.8 / 459.5 → 296.7 / 1260.6 → 554.3 ms.

v3.4.1's self-reported table agrees on shape — 3–5 words 250–450 ms, 6–8 words 450–700 ms, 10–13
words 900–1400 ms — against V4's 166 / 350 / 640 ms.

### The complexity class changed, not just the constant

| Tokens | v3 lineage tokens/s | V4 tokens/s |
|---|---|---|
| 2 | 13.9 | 14.9 |
| 6 | 13.1 | 20.2 |
| 12 | **9.5** (falling) | **21.6** (rising) |

**Cause.** v3.4.1's ONNX export wrapper called the decoder with only `input_ids`,
`encoder_hidden_states`, `encoder_attention_mask` — no `use_cache`, no `past_key_values`. The
IndicTrans2 model implements the mBART caching contract; the *wrapper* dropped it, so every decode
step re-attended the whole growing prefix: O(n²). V4 hand-built a three-graph cached export
(`encoder` / `decoder_init` / `decoder_step`) with the 72-tensor cache flattened to named ONNX I/O.
Per-step cost is now flat at ~44–46 ms from token 2 onward.

### Latency stability

| | v3.4.1 config (`intraOp=4`, arena on) | V4 (`intra=2`, arena off) |
|---|---|---|
| stdev, 12 tokens | 93.0 ms | **18.4–22.6 ms** (−78%) |
| p95, 12 tokens | 864.0 ms | **670.5 ms** (−20%) |

v3.4.1 hard-coded `intraOpThreads=4, interOpThreads=2, ALL_OPT`. Measured on its own device, the
naive "all four big cores" rule gives 719.0 ms / stdev 88.8 against 667.2 ms / stdev 18.4 for the
derived two threads. The hard-coded value was slower *and* five times jitterier on the hardware it
was written for.

---

## 2. Translation latency — HI→EN

v3.4.1 shipped a HI→EN pair with **no export script and no traceable checkpoint** (the R-PROV
provenance gap): the graphs could not be reproduced or trusted, and were never benchmarked. V4
re-exported from the named `ai4bharat/indictrans2-indic-en-dist-200M` checkpoint through the same
7-check verifier.

Measured on the **SM-S948B** (no M31 figure exists), `HiEnBenchmarkTest`, 30 runs:

| Input | tokens | median | p95 | stdev | tok/s |
|---|---|---|---|---|---|
| `पानी।` | 2 | 23.8 ms | 28.6 | 3.0 | 116.1 |
| `नमस्ते, आप कैसे हैं?` | 6 | 43.2 ms | 51.5 | 3.9 | 159.0 |
| `आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ।` | 12 | 76.7 ms | 81.5 | 3.6 | 177.0 |

~28% faster than EN→HI on the same device: the indic-en `lm_head` is 32k wide, not 122k.

No v3.4.1 comparison is possible. Recorded as *added capability with evidence*, not as a speedup.

---

## 3. Startup

v3.4.1 never instrumented startup. Its report's only claim is "HI→EN first load takes approximately
10–15 seconds". V4's Phase 10 measured the same-shape load path at 27.0 s, and that is the honest
starting point for the chain below.

Engine-ready, EN→HI, M31. Source: `OPTIMIZATION_SUMMARY.md` §3.10–§3.14, §3.29.

| Stage | Engine ready | Δ |
|---|---|---|
| Phase 10 baseline | 27,000 ms | — |
| Buffered + block-wise dictionary parse (§3.11) | 16,584 ms | −32.8% |
| Parallel ONNX session load (§3.12) | 10,502 ms | −36.7% |
| Optimized-graph cache + `.ort` mmap (§3.13, §3.14) | **~5,134 ms** cold-launch median | — |

**~5.3× faster to first translation.** Current breakdown: tokenizer parse 2,917 ms, three ONNX
sessions 2,153 ms. The parse is now the longer pole.

The finding that redirected the work: of the original ~25 s, **49% was a JSON parser** reading two
dictionaries one character per `Reader.read()`, and **46% was ORT building sessions**. Unpacking
472 MB of assets — the visibly expensive part — was 1.8 s, once. The models were never the problem.

One reverted experiment belongs here: running the tokenizer parse *concurrently* with the session
loads made cold start **+6.6% worse** (5,134 → 5,475 ms). Three ORT sessions at `intra=2` already
saturate four big cores; a fourth CPU-bound thread only adds contention.

Time to first frame (V4 only, no v3.4.1 figure): 1,864 ms first ever launch, 1,196 ms subsequent,
1,422 ms release. On the SM-S948B with the generated Baseline Profile, cold TTID 159.4 → 150.6 ms
(−5.5%).

---

## 4. Memory

v3.4.1 reported no memory numbers, and leaked. The chain `Activity → Translator →
OnnxSessionManager → OrtSession` had a creator at every level and a destroyer at none:
`OnnxSessionManager.release()` was correct code with **zero call sites**, so every rotation leaked
the model's native heap and re-paid the full model load.

V4, measured (`OPTIMIZATION_SUMMARY.md` §3.8, §3.24b, §3.25; `VALIDATION_REPORT.md` §2.4):

| State | V4 |
|---|---|
| One engine, idle after a translation | 605–670 MB PSS |
| After 90 consecutive translations | 616–630 MB (lower than it started) |
| Rotation | 670 → 672 MB, **no reload** |
| Background → foreground | 641 → 646 MB, no reload |
| Peak with Vosk English also resident | 743 MB |
| After `onTrimMemory(BACKGROUND)` release | 454 MB |

Two structural wins over v3.4.1:

- **CPU arena off**: process memory 983 → 617 MB (**−38%**) at no latency cost. v3.4.1 ran ORT
  defaults, i.e. arena on.
- **Evict the other direction before building the new one**: a swap tap used to leave both engines
  live — measured at **1,718 MB PSS** for six sessions. Evicting before the build drops swap peak
  1,394.8 → **883.1 MB (−36.7%)** and post-swap 934.7 → 541.8 MB (−42.0%). v3.4.1 had no eviction
  path at all.

`release()` returns allocated native heap 557.8 → **13.2 MB** the instant it returns (97.6%); the
allocator hands pages back to the OS asynchronously over ~10 s.

---

## 5. Correctness defects both versions shipped, only V4 fixed

| Defect | v3.4.1 | V4 |
|---|---|---|
| `maxSteps=18` vs `targetCap = max(14, sourceLen)` — long inputs cut mid-sentence, silently | present | fixed: 128-step ceiling + `sourceLen*1.6 + 8` cap |
| Native use-after-free — model fetched *before* the borrow was opened; a background trim in that window freed it under `Recognizer` | present | fixed (`withSpeechModel`, pins before load) |
| Lost stop — `stop()` set a boolean the flow builder set back, so a stop during model load left the microphone live | present | fixed (numbered sessions) |
| TTS kept speaking after the screen stopped | present | fixed |
| Tokenizer kept the backslash instead of decoding JSON escapes | present | fixed |
| Per-rotation native leak (§4) | present | fixed structurally |

**Truncation, measured on device** (n=16 sentences, 5–25 source tokens, real engine):

| Rule | Truncated |
|---|---|
| old, `targetCap = max(14, sourceLen)` — what v3.4.1 ships | **5 / 16 (31%)** |
| new, `sourceLen * 1.6 + 8` | **0 / 16** |

All five were long sources (20–25 tokens); two generated *more* tokens than the cap allowed (25
against a cap of 23, 26 against 22). Every benchmark sentence in either project is 2, 6 or 12 tokens,
which is why the defect survived the entire life of both codebases.

---

## 6. Where V4 is worse

**Disk.** The KV-cache splits each decoder into `decoder_init` + `decoder_step`, duplicating weights.

| | v3.4.1 | V4 |
|---|---|---|
| Graphs per direction | 2 (encoder, decoder) | 3 (encoder, decoder_init, decoder_step) |
| EN→HI models | 267 MB | 453 MB |
| HI→EN models | 223 MB | 318 MB |
| Vosk models (en-in + hi) | 134 MB | 134 MB |
| **Total assets** | **~638 MB** | **909 MB** → **619 MB** (see below) |
| APK | ~577 MB debug | 894 MB debug → **617 MB** measured, both directions |

**The +283 MB was not the price of the cache. It was an export defect, and it is now measured and
fixed.** Hashing the raw tensor bytes showed `decoder_step` holds **no unique tensor data at all** —
every byte of it already exists in `decoder_init`, because `torch.onnx.export` materialises a full
copy of the decoder's weights into each graph. Pointing both at one content-addressed blob took the
debug APK from **893.97 MB to 617.23 MB (−31%)** with translation output bit-identical and latency
unchanged on the M31 (`OPTIMIZATION_SUMMARY.md` §3.30).

So the honest current line: **V4 ships a bidirectional model in less space than v3.4.1 used for the
same two directions**, and the KV-cache's real cost was never the 283 MB it was charged for. What
remains true is that neither version is distributable through Play without asset delivery or a
first-run download.

One caveat this table cannot hide: the saving is in the APK and the download. Device steady-state
storage is unchanged, because ORT's `.ort` bake re-inlines the weights — measured, not assumed.

Also still open in V4, and no better than v3.4.1: R8 disabled, no release signing config, no
landscape layout (V4 enforces portrait rather than degrading), speech WER unmeasured, TTS latency
unmeasured.

---

## 7. Engineering posture

| | v3.4.1 | V4 |
|---|---|---|
| ONNX Runtime | 1.17.1 (2024) | 1.27.0 |
| Thread policy | hard-coded `intraOp=4, interOp=2` | derived from `/proc/cpuinfo` HWCAP + cpufreq topology; `intra = (perfCores/2)` clamped [1,2] |
| `@Test` methods | 2 | 94 |
| Devices validated | 1 | 9, Armv8.0 → Armv9, four vendors |
| Export pipeline | none for HI→EN; no verification gate | `cached_export.py` + `quantize_cached.py` + `verify_cache.py` (7 numeric checks, 7/7) |
| Negative results published | — | every REVERT / NO EFFECT recorded with its numbers |
| Model binaries in git | yes | no (`.gitignore` R14.5) |
| `INTERNET` permission | — | **absent**; the offline claim is enforced by the manifest |

Quantization parity, V4: greedy token sequences **identical** to the fp32 reference, max logit delta
0.448; 145 of 217 `MatMul` became `MatMulInteger`; 1869 MB fp32 → 472 MB INT8 (3.96×).

---

## 8. Reading these numbers

Same build, same device, same test read **640 / 680 / 690 / 864 ms** on the 12-token sentence across
one afternoon as the phone went 31 °C → 34 °C under repeated 938 MB installs — a **35% spread with
no code change at all**. Two of those were nearly written up as a regression.

Any cross-version delta under roughly 10% in this document is not readable without its temperature.
The three that carry the argument — 2.11× decode, 5.3× engine ready, 31% → 0% truncation — are far
outside that band. The small ones are not claimed as wins.
