# HI→EN Cached Translation Pipeline (Phase 12)

The last feature gap in V4: Hindi→English translation existed in v3.4.1 but shipped with **no export
script and no traceable checkpoint** (risk R-PROV), so V4 never carried it forward.

**Result: implemented. HI→EN now runs the same cached INT8 KV-cache runtime as EN→HI, from a named,
re-runnable export. R-PROV is closed by reproduction, not by assertion.** The entire production
change is three asset filenames.

All device measurements on the **SM-M315F** (Exynos 9611, 4×A73 + 4×A53, Armv8.0-A, Android 12),
debug build, `arm-adaptive(threads=2) intra=2 arena=false`.

---

## 1. Investigation

Five prerequisites, each answered before any code was written.

### 1.1 Which exact HI→EN model did V3 use?

**`ai4bharat/indictrans2-indic-en-dist-200M`. Established by reproduction, from two independent
directions.**

`MODEL_PIPELINE.md` §5 could only call this "almost certainly" — the naming matched, but the sizes
did not mirror EN→HI (V3's HI→EN encoder is *larger* and its decoder *smaller* than their EN→HI
counterparts), and no script existed. Both anomalies are now explained.

**Evidence A — the dictionaries are the checkpoint's own.** The two HI→EN vocabularies V4 inherited
from v3.4.1 were compared entry-by-entry against the files the HF repo ships:

| Shipped asset | Checkpoint file | Entries | Result |
|---|---|---|---|
| `dict.SRC_HI.json` | `dict.SRC.json` | 122,706 | **identical** (`ref == got`) |
| `dict.TGT_EN.json` | `dict.TGT.json` | 32,296 | **identical** |

Not "same size" — full map equality, ids included. `hin_Deva`=8 and `eng_Latn`=4 in the source
vocabulary, which is exactly what `Tokenizer.langIds` hard-codes as its HI→EN fallback.

**Evidence B — re-quantizing reproduces V3's file sizes.** Exporting and INT8-quantizing this
checkpoint from scratch produced:

| Graph | This phase | v3.4.1 asset | Δ |
|---|---|---|---|
| `encoder_int8` | 121.2 MB | `hi_en_encoder_int8.onnx` 121.6 MB | −0.3% |
| `decoder_init_int8` | 111.1 MB | `hi_en_decoder_int8.onnx` 111.8 MB | −0.6% |

V3's decoder was *uncached*, so it is the counterpart of `decoder_init`, and it lands within 0.6%.
The residual is the cache plumbing the new graph carries and V3's did not.

**And the size anomaly resolves.** The two checkpoints are mirrors: the config is identical except
that the vocabularies swap sides.

| | en-indic (EN→HI) | indic-en (HI→EN) |
|---|---|---|
| `encoder_vocab_size` | 32,322 | **122,706** |
| `decoder_vocab_size` | 122,672 | **32,296** |
| layers / heads / embed dim | 18 / 8 / 512 | 18 / 8 / 512 |

A 122,706-row source embedding makes the HI→EN *encoder* the big graph; a 32,296-row `lm_head` makes
its *decoder* the small one. The "discrepancy" flagged in `MODEL_PIPELINE.md` was the correct
signature of the right checkpoint all along.

### 1.2 Can the original export pipeline be reproduced?

**Yes, with zero new code.** `cached_export.py` was written in Phase 6A with `--direction` as an
argument and `MODEL_NAMES["hi_en"]` already pointing at this checkpoint; `quantize_cached.py` and
`verify_cache.py` take directories and a direction. All three ran unmodified.

The Phase 6A blockers are both still down: the account (`Vishnu-3727`) remains authenticated and the
`indic-en` licence is accepted (`config.json` fetched HTTP 200, not 401), and the toolchain is intact
— python 3.12.9, torch 2.7.0+cu128, transformers 4.38.2, onnx 1.22.0, onnxruntime 1.27.0.

### 1.3 Is a cached decoder export possible for this direction?

**Yes, and it produces the identical contract.** The export reported
`layers=18 heads=8 head_dim=64 hidden=512` — the same geometry as EN→HI, so the same **72** cache
tensors (18 × 4), in the same layer-major order. The step graph inspects as:

```
step graph: 74 inputs, 72 cache tensors, encoder_hidden_states present = False
```

74 = 72 cache + `decoder_input_ids` + `encoder_attention_mask`. The cross-attention pruning that
makes the cache a win in EN→HI happens here too, for the same reason.

### 1.4 Does INT8 quantization preserve parity?

**Yes. Both gates pass 7/7.**

| Gate | atol | max_abs_diff | Greedy tokens | Verdict |
|---|---|---|---|---|
| fp32 (`onnx_cached_hi_en`) | 1e-4 | **9.30e-06** | identical | **7/7 PASS** |
| INT8 (`onnx_cached_hi_en_int8`) | 1.0 | **5.19e-01** | identical | **7/7 PASS** |

Directly comparable to EN→HI's 9.06e-06 / 4.48e-01. The decisive check is #7: despite a 0.52 logit
shift, INT8 argmax never diverges from the fp32 torch reference.

Both gates use a synthetic all-ones source, so they prove *parity*, not translation quality. That is
checked separately in §1.5 and on-device in §5.1.

### 1.5 Can the runtime reuse the existing MtEngine architecture?

**Yes — nothing in the runtime was direction-specific except three filenames.** Before writing
anything, the whole path was traced:

| Component | Already handles HI→EN? | Evidence |
|---|---|---|
| `MtEngine` | Yes | No direction branch anywhere; takes `Direction`, passes it down |
| `Tokenizer` | Yes | `load()` already maps `HI_TO_EN → dict.SRC_HI.json / dict.TGT_EN.json`; `langIds` already has the HI→EN tags |
| Dictionaries | Yes | Both already in `app/src/main/assets` (§1.1) |
| `CachedLogitsSource` | Yes | Reads `pastInputNames` from the graph; never hard-codes a count |
| `OnnxModels` | **No** | The `HI_TO_EN` branch named files that were never exported |
| `BhashaBridgeApp` | Yes | `engines: HashMap<Direction, MtEngine>` — one engine per direction, already |
| UI swap | Yes | `swapBtn → viewModel.swapDirection()` was wired in Phase 9 and failed only at session creation |
| `AsrCorrector`, `Tts`, `VoskModels` | Yes | All already branch on `Direction` (Phase 9) |

So the "enable the swap" work the brief allows for turned out to be **already done**; the swap was
never disabled, it just threw when `OnnxModels` looked for a file that did not exist.

**Conclusion: implementation proceeds.** No blocker was found at any of the five gates.

---

## 2. Model provenance

| | Value |
|---|---|
| Checkpoint | `ai4bharat/indictrans2-indic-en-dist-200M` (gated; licence accepted) |
| Revision | `eb9e49d81077cfc5311e82ff36d8c1fc11557b5d` |
| Architecture | `IndicTransForConditionalGeneration`, `trust_remote_code=True` |
| Geometry | 18 encoder / 18 decoder layers, 8 heads, head_dim 64, embed 512 |
| Vocabulary | src 122,706 (Indic) / tgt 32,296 (English) |
| Tokenizer assets | the checkpoint's own `dict.SRC.json` / `dict.TGT.json`, verified identical to the shipped `dict.SRC_HI.json` / `dict.TGT_EN.json` |

Model binaries are **not** committed (R14.5). `model_pipeline/` holds the scripts; the ~1.7 GB of
graphs stay gitignored and are regenerated by the commands in §3.

---

## 3. Export process

Three commands, all against unmodified scripts:

```bash
cd model_pipeline
python cached_export.py    --direction hi_en --out onnx_cached_hi_en
python verify_cache.py     --onnx-dir onnx_cached_hi_en      --direction hi_en --atol 1e-4
python quantize_cached.py  --src onnx_cached_hi_en --out onnx_cached_hi_en_int8
python verify_cache.py     --onnx-dir onnx_cached_hi_en_int8 --direction hi_en --atol 1.0
```

Then the three INT8 graphs are copied into `app/src/main/assets/` as `hi_en_*_int8.onnx`.

### Sizes

| Graph | fp32 | INT8 | Ratio | EN→HI INT8, for comparison |
|---|---|---|---|---|
| encoder | 479.2 MB | **121.2 MB** | 3.95× | 74.9 MB |
| decoder_init | 436.3 MB | **111.1 MB** | 3.93× | 203.6 MB |
| decoder_step | 398.4 MB | **101.4 MB** | 3.93× | 194.0 MB |
| **total** | **1313.9 MB** | **333.7 MB** | 3.94× | 472.5 MB |

HI→EN ships **138.8 MB less** than EN→HI, because its decoder — run once per output token — projects
onto a 32,296-word English vocabulary instead of a 122,672-piece Devanagari one. That is also why it
is the faster direction (§5.2).

### Real-sentence check before touching Android

The gates use synthetic input, so the INT8 graphs were additionally driven with a Python port of the
**shipped Kotlin tokenizer** (`Tokenizer.kt` `encode`/`decode`, same dictionaries, same greedy
subword rules), so a pass here predicts device behaviour:

| Hindi in | English out |
|---|---|
| मुझे पानी चाहिए | `I need water .` |
| मेरी मदद करो | `Help me` |
| यह कितने का है ? | `How much is that ?` |
| नमस्ते , आप कैसे हैं ? | `Hi , how are you ?` |

---

## 4. Runtime integration

### The entire production diff

```diff
-            // HI->EN cached graphs are not yet exported (Phase 6A did en_hi only; R-PROV). Naming is
-            // fixed here so the export, when it lands, drops straight in.
             Direction.HI_TO_EN ->
-                Triple("hi_en_encoder.onnx", "hi_en_decoder_init.onnx", "hi_en_decoder_step.onnx")
+                Triple("hi_en_encoder_int8.onnx", "hi_en_decoder_init_int8.onnx", "hi_en_decoder_step_int8.onnx")
```

One `Triple`, in `OnnxModels.kt`. Nothing else in `app/src/main/` changed.

This is the payoff of the Phase 6B seam: the decoder asks for "logits for this prefix", the cache
lives behind `LogitsSource`, and the cache *ordering* is read from the graph rather than hard-coded
(`pastInputNames = decoderStep.inputInfo.keys.filter { it !in NON_CACHE_STEP_INPUTS }`). A second
direction with identical geometry therefore needs no runtime code at all — and one with *different*
geometry would also work, because nothing counts to 72 in Kotlin.

**No duplicated runtime code**: no HI→EN engine, decoder, cache, or session class exists. The
concurrency, tuning and startup work of Phases 7–11C apply to this direction automatically — HI→EN
loads its three sessions in parallel and uses the same `intra=2, arena=off` policy without a line of
new code.

### Ownership and lifecycle

Unchanged: `BhashaBridgeApp` already keyed engines by `Direction`, so a swap constructs the second
engine on first use and both are released together at `TRIM_MEMORY_COMPLETE`. The memory consequence
of *both* being resident is real and measured in §5.3.

---

## 5. Validation and benchmarks

### 5.1 Correctness (on device)

`HiEnEngineTest`, new this phase, mirrors `MtEngineInstrumentedTest`. **3/3 pass.**

| Check | Result |
|---|---|
| Hindi in → English out | `'मुझे पानी चाहिए' => 'I need water .'` |
| Output contains no Devanagari (right dictionary + right graphs) | PASS |
| Determinism — same input twice | `'मेरी मदद करो' => 'Help me out' \| 'Help me out'`, equal |
| Cache tensor count | **72** |
| `decoder_step` has no `encoder_hidden_states` | PASS |
| Cache **ordering identical to EN→HI** (both engines constructed, lists compared) | PASS |
| Native cleanup on `release()` | PASS (no throw) |

JVM unit tests: **20/20** (TokenizerTest 6, DecoderTest 7, MetricsTest 6, Example 1).

**One honest discrepancy.** `मेरी मदद करो` decodes to `Help me out` on device but `Help me` on the
desktop reference. Both are correct English and both are internally deterministic; the divergence is
between *hosts*, not between runs — desktop is onnxruntime 1.27 on x86-64, device is 1.17.1 on
arm64-v8a, and INT8 `MatMulInteger` kernels differ between those. The behaviour that matters for the
app — repeatability on the target device — holds, and is asserted by the test above. The other three
sentences matched the desktop reference exactly.

### 5.2 Latency, first-token latency and throughput

`HiEnBenchmarkTest` is shape-identical to `MtBenchmarkTest` (3 warmup passes, 30 runs, same markers,
same frozen parser) and its three sentences are the direct Hindi translations of that test's English
ones, so sentence length is held roughly constant. Both benchmarks were run **back-to-back in one
session**, HI→EN first.

The first attempt ran HI→EN first and EN→HI second, which produced a 12-token EN→HI median of 859.0
ms against Phase 11C's 668.8 ms for the identical test — a thermal order effect, not a direction
effect. Rather than caveat it, the pair was **run again in the opposite order**. Both orderings are
reported; position 1 is the cool, trustworthy comparison.

**Position 1 — cool device (n=30 per sentence)**

| Direction | Sentence | Tokens | Total median | p95 | stdev | Encoder | Decode | **First token** | Per cached step | tok/s |
|---|---|---|---|---|---|---|---|---|---|---|
| **HI→EN** | पानी। | 2 | **152.0** | 170.0 | 5.8 | 38.5 | 111.9 | **78.6** | 67.9 | 17.9 |
| **HI→EN** | नमस्ते, आप कैसे हैं? | 6 | **324.2** | 354.9 | 11.9 | 60.9 | 260.6 | **103.7** | 41.5 | 23.0 |
| **HI→EN** | आज मौसम बहुत अच्छा है… | 12 | **657.5** | 736.1 | 61.6 | 95.8 | 559.4 | **148.2** | 43.1 | 21.5 |
| EN→HI | Water. | 2 | 171.8 | 192.8 | 9.2 | 35.8 | 132.7 | 81.7 | 79.8 | 15.1 |
| EN→HI | Hello, how are you? | 6 | 372.0 | 399.3 | 15.3 | 61.2 | 308.7 | 111.5 | 47.7 | 19.4 |
| EN→HI | The weather is very nice… | 12 | 675.2 | 704.5 | 20.0 | 88.9 | 584.1 | 139.5 | 45.3 | 20.5 |

**Position 2 — warm device, same sessions (n=30)**

| Direction | 2 tok | 6 tok | 12 tok |
|---|---|---|---|
| HI→EN | **136.7** | **290.4** | **523.4** |
| EN→HI | 164.6 | 367.6 | 859.0 |

First-token latency is `encoder + decoder_init` from the `Metrics` counters (`init_us`) — the real
time to the first visible word. EN→HI's 675.2 ms at position 1 matches Phase 11C's 668.8 ms to within
1%, which is the check that the counterbalanced protocol worked.

**Reading it honestly.** HI→EN is faster in **both** orderings, so the direction effect is real and
not an artefact of test order. On the clean position-1 comparison it is **11.5% faster at 2 tokens,
12.8% at 6, and 2.6% at 12**, with first-token latency 3.7–8.1% lower. The position-2 gaps are far
larger (17%, 21%, 39%) but that run has EN→HI in the thermally penalised slot, so **the position-1
figures are the ones to quote.**

The mechanism is structural and expected: the per-token `lm_head` projection is 32,296-wide instead
of 122,672-wide, and the decoder graphs are roughly half the size. HI→EN's encoder is correspondingly
*slower* on short input (38.5 vs 35.8 ms) — the same trade seen from the other side.

### 5.3 Engine startup

Measured in the app, not a test, from a fresh install (so extraction is paid):

| Direction | `engine_init` | tokenizer group | `sessions:parallel` |
|---|---|---|---|
| EN→HI (first launch) | 14,420 ms | 4,563 ms | 9,845 ms |
| **HI→EN (after swap)** | **8,829 ms** | **1,200 ms** | **7,623 ms** |

HI→EN is 39% cheaper to start: its target dictionary is 32,296 entries instead of 122,672, so the
tokenizer group collapses from 4.6 s to 1.2 s, and its graphs are 139 MB smaller. Both directions get
the Phase 11B buffered reader and Phase 11C parallel session loading with no new code.

### 5.4 Memory

| State | totalPss | nativePss | dalvikPss |
|---|---|---|---|
| HI→EN engine only | **492.6 MB** | 414.3 MB | 20.4 MB |
| EN→HI engine only | 624.8 MB | 539.3 MB | 28.9 MB |
| **Both directions resident** | **1113.7 MB** | 1009.4 MB | 46.6 MB |

Repeated across three benchmark sessions: HI→EN alone 483.0 / 492.6 / 509.5 MB, both 1086.6 / 1111.9
/ 1113.7 MB. Steady state — measured after 90+ translations, so this is sustained footprint, not a
transient peak.

HI→EN alone is **132 MB lighter** than EN→HI, tracking the model sizes. But a user who swaps
direction ends the session holding **~1.09 GB**, because `BhashaBridgeApp` keeps one engine per
direction alive. On this 6 GB device that survives; it is the single most important limitation of
this phase and is discussed in §6.

### 5.5 On-device UI and lifecycle

Driven on the device against a fresh install, one screen at a time:

| Step | Result |
|---|---|
| Onboarding → language choice → main screen | Renders, EN→HI active |
| **Tap swap** | Header flips to `Hindi ⇄ English`, labels to HINDI/ENGLISH, hint to "Type or speak in Hindi…" |
| Engine loaded on swap | `Loading MT engine: HI_TO_EN` → ready in **8,829 ms**, no crash, no ANR |
| Background → foreground with **both** engines resident | **0** engine reloads, same PID (20805) |
| Emergency phrases sheet in HI→EN | Opens, phrases play — **but see the defect below** |

**Defect found — emergency phrases ignore the active direction.** With HI→EN selected, tapping
"I need help" fills the input box (labelled **HINDI**) with the English `I need help` and the output
box (labelled **ENGLISH**) with the Hindi `मुझे मदद चाहिए`. The sheet writes its canned EN/HI pair
into the fields positionally, without consulting `direction`. This is pre-existing Phase 9 behaviour,
not a regression from this phase — it was simply unreachable while HI→EN could not load. **Not fixed
here:** the brief limits UI changes to enabling the swap, and this phase is a single commit. It is a
one-line swap of the two strings at the sheet's callback and should be the first item of any
follow-up.

**Not verified this phase:** Devanagari typed into the UI by hand. `adb shell input text` throws
`NullPointerException` on non-ASCII and this device exposes no `cmd clipboard` service, so no
scripted route exists to put Hindi in the text field. HI→EN translation is proven at the engine layer
(§5.1) through the identical `TranslateViewModel` path EN→HI uses; the last mile is a keyboard, not a
code path. Rotation was also not re-verified — `settings put system user_rotation` did not take
effect on this device this session (it did in Phase 10, which is where the portrait-lock defect was
found and fixed).

---

## 6. Known limitations

1. **Both-directions footprint ≈ 1.09 GB PSS.** Swapping keeps both engines resident by design
   (Phase 4's process-scoped ownership, which is what killed the v3.4.1 rotation leak). Nothing here
   is leaked — `onTrimMemory(TRIM_MEMORY_COMPLETE)` releases both — but on a 2–3 GB device the
   background-kill risk after a swap is real. The obvious fix is to evict the non-active engine on
   swap, trading ~10 s of reload for ~500 MB. **Not implemented:** it changes engine lifecycle, which
   this brief puts on the do-not-modify list.

2. **APK is now 926 MB** (arm64-v8a debug), up from ~600 MB, since the app ships 806 MB of ONNX
   across both directions. Above the 200 MB Play base-module limit; shipping this needs asset packs
   or on-demand download of the second direction. Release packaging is explicitly out of scope here.

3. **Desktop/device output can differ on individual sentences** (§5.1). Expected from INT8 kernel
   differences across ORT versions and ISAs; the device is self-consistent. It does mean the Python
   reference is a smoke test, not a golden file.

4. **Emergency phrases are direction-blind** (§5.5) — English lands in the box labelled Hindi when
   HI→EN is active. Pre-existing, newly reachable, deliberately not fixed in this commit.

5. **UI-typed Hindi and rotation were not re-verified on device** (§5.5) — no scriptable way to enter
   Devanagari, and the rotation setting did not apply this session.

6. **Translation quality is not scored.** Every check here is parity or plausibility — no BLEU/chrF
   against a held-out set, in either direction. The round-trip is qualitatively strong
   (`The weather is very nice today and I want to go outside.` → Hindi → `The weather is great today
   and I want to go out .`), but that is an anecdote, not a metric.

7. **The first translation after a swap pays a full engine init** — 8.8 s, measured (§5.3). Cheaper
   than EN→HI's cold start, but it is a visible wait at the moment the user asked for something.

8. **Speech in the HI→EN direction was already present** (Vosk `model-hi`, `AsrCorrector.correctHindi`,
   English TTS) from Phase 9 — it was simply unreachable while MT for the direction failed. It is now
   reachable, and was not re-validated end-to-end here.
