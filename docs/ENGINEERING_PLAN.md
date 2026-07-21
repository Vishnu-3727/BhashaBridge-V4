# BhashaBridge V4 — Phase 1 Engineering Plan

**Status:** plan only, no implementation.
**Source:** `C:\Users\vishn\Downloads\BhashaBridge_v3.4.1` (v3.4.1, 3640 LOC Kotlin)
**Target:** `C:\Users\vishn\AndroidStudioProjects\BhashaBridgeV4` (empty AGP 9.3 / AppCompat skeleton)
**Context:** Arm AI Optimization Challenge submission.
**Baseline of record:** `bench/results/20260714_183707` on Samsung Galaxy M31 (Exynos 9611).

---

## 0. Executive summary

Three findings drive the whole plan.

**A. The single biggest win is not in Kotlin — it is in the ONNX export.**
`translation_build/export_decoder_dynamic.py` exports a `DecoderWrapper` whose `forward()` takes
only `(input_ids, encoder_hidden_states, encoder_attention_mask)`. No `past_key_values` in,
no `present` out. The graph *physically cannot* cache. Every fix to the Kotlin decode loop is
capped by that. KV cache is a Python re-export task first, an app task second.

**B. The most defensible perf work on the target device is not KleidiAI.**
Exynos 9611 is Armv8.0 — NEON only, **no dotprod, no i8mm, no SVE, no SME**. KleidiAI's int8 GEMM
micro-kernels select on `i8mm`/`dotprod`/`SME2`. On the M31 they will fall back to the same NEON
path ORT already uses and show ≈0% gain. Claiming a KleidiAI win on this device would be
unreproducible. Plan therefore uses a **two-device matrix**: M31 stays the baseline-of-record,
plus one Armv8.6+ device (i8mm-capable) added to demonstrate the KleidiAI/SME axis honestly.

**C. The 50 MB/translation GC churn has one dominant cause, and it is one line.**
`Translator.kt:204` — `(logitsTensor.value as Array<Array<FloatArray>>)[0].last().copyOf()`.
`OnnxTensor.value` materialises the *entire* `[1, seq, vocab]` output as boxed nested Java arrays
before anything is read. At seq=18, vocab≈64k that is ~4.6 MB of arrays **per decode step**, ~18
steps per translation, to read 64k floats. `getFloatBuffer()` reads the same data with zero copy.

Everything else is ordinary structural cleanup.

---

## 1. Current architecture (v3.4.1, as measured)

### 1.1 Module map

| File | LOC | Role |
|---|---:|---|
| `MainActivity.kt` | 961 | Sole Activity. UI + orchestration + translation dispatch + direction state + drawer + history + audio import |
| `Translator.kt` | 494 | Tokenise → encoder → greedy decode → detokenise → post-process. Contains `EnPostProcessor` |
| `SpeechManager.kt` | 302 | **Dead. Zero references outside its own declaration.** |
| `AudioFileTranscriber.kt` | 227 | Decode audio file → 16 kHz PCM → Vosk |
| `SentencePieceTokenizer.kt` | 209 | Hand-written JSON dict parser + greedy longest-match subword split |
| `ASRCorrector.kt` | 207 | Phrase-table ASR correction. `isLanguageToolReady()` hardcoded `false` |
| `AudioCaptureController.kt` | 170 | Mic loop → Vosk recogniser, partial/final callbacks |
| `EmergencyPhrasesController.kt` | 144 | Emergency-phrase overlay UI |
| `WaveformView.kt` | 126 | Custom mic-amplitude view |
| `OnnxSessionManager.kt` | 118 | Asset copy + 2× `OrtSession` creation, per direction |
| `SetupActivity.kt` | 117 | First-run language choice |
| `TtsController.kt` | 111 | Android TTS wrapper |
| `VoskModelLoader.kt` | 98 | Vosk model load + recogniser rebuild |
| `FileUtils.kt` | 72 | Recursive asset-folder copy |
| `EmergencyPhrases.kt` | 71 | Static phrase table |
| `OnboardingActivity.kt` | 71 | First-run intro |
| `TranslationHistory.kt` | 35 | In-memory ring of translation pairs |
| `BhashaBridgeApp.kt` | 29 | `MultiDexApplication` |
| `MainActivity_backup.kt` | 78 | **Dead. Stray file at repo root, not in source set.** |

### 1.2 Runtime flow

```
mic tap ──► AudioCaptureController (audioExecutor)
              │ partial ──► maybeStreamTranslate()  [gate: ≥3 words, ≥250 ms, changed]
              │ final   ──► ASRCorrector
              ▼
        MainActivity.runTranslation()  (translateExecutor, single thread)
              ▼
        Translator.translate()
              ├─ SentencePieceTokenizer.encode()
              ├─ encoderSession.run()            ← once per call
              └─ loop ≤18 steps:
                   decoderSession.run(full prefix)   ← recomputes ALL past attention
                   OnnxTensor.value → Array<Array<FloatArray>>   ← full materialise
                   repetition penalty → n-gram block → argmax
              ▼
        tgtTokenizer.decode() → EnPostProcessor (HI→EN only)
              ▼
        mainHandler.post { outputText, TTS, history }
```

### 1.3 Ownership graph (the defect in Problem 4, drawn)

```
MainActivity ──owns──► translatorEnHi : Translator?
             ──owns──► translatorHiEn : Translator?
                            └──owns──► OnnxSessionManager
                                            ├── OrtSession (encoder)   ~75–122 MB
                                            └── OrtSession (decoder)  ~112–204 MB

MainActivity.onDestroy():
    audioCapture.releaseImmediately()   ✓
    voskModelLoader.close()             ✓
    ttsController.shutdown()            ✓
    3× executor.shutdown()              ✓
    translator*.release()               ✗  NEVER CALLED
```

`OnnxSessionManager.release()` exists and is correct. It has **zero call sites**. Rotation
destroys the Activity, drops both `Translator` references, and leaks ~639 MB of native heap the
JVM GC cannot reclaim (native arenas are not JVM objects). The new Activity then re-creates both
sessions from scratch — a second 8.6 s wait *and* a second 639 MB allocation.

### 1.4 Measured baseline (M31, n=10 launches / n=30 translations per class)

| Metric | Value |
|---|---|
| Cold launch → first frame | 1113 ms |
| Cold → ASR ready | 3665 ms |
| Cold → translation ready | **8599 ms** |
| Translate, 1 word | 282 ms |
| Translate, 4 words | 392 ms |
| Translate, 10 words | 806 ms |
| Translate, 21 words | **2518 ms** |
| Peak PSS | 749 MB (native heap 639 MB) |
| APK on device | 634.6 MB |
| filesDir model copies | 636 MB (**double storage**) |
| Battery temp over 26 min | 38.3 → 38.4 °C (no thermal ramp) |

1→21 words = **9× latency for 21× tokens**. Super-linear. This is the O(n²) decode signature.

### 1.5 Root-cause table — all 12 reported problems, traced to source

| # | Reported symptom | Actual root cause, located |
|---|---|---|
| 1 | No KV cache | `export_decoder_dynamic.py` — `DecoderWrapper.forward()` has no `past_key_values` arg and returns logits only. Graph-level, not app-level |
| 2 | No modern Arm opts | ORT 1.17.1 (`app/build.gradle`), default CPU EP, `ALL_OPT` at runtime. No EP selection, no `intra_op_thread_affinities`, no pre-optimised graph |
| 3 | 8.7 s to ready | Serial cost chain: first-run 636 MB asset copy → `env.createSession()` graph load → `ALL_OPT` optimisation pass **at runtime, every launch** → `warmUp()` |
| 4 | No native ownership | `release()` defined at `OnnxSessionManager.kt:113`, zero callers. Owner is an Activity, which Android destroys on rotation |
| 5 | MainActivity owns everything | 961 lines; `runTranslation()` mixes threading, discard policy, UI mutation, TTS, history, latency logging |
| 6 | No benchmark evidence | Partly fixed — `bench/` harness is real and produced §1.4. Missing: TTFT, tokens/sec, per-stage breakdown, BLEU/chrF/COMET, WER, power |
| 7 | Tokenizer not SentencePiece | `SentencePieceTokenizer.kt` is greedy longest-match over a JSON dict. Real SP is **unigram Viterbi over log-probs** — different segmentation. The shipped `model.SRC`/`model.TGT` protobufs (7.3 MB) are never opened |
| 8 | Beam search dead | `translateBeam()` + `topKIndices()` = 133 LOC, zero callers. `beamWidth` field unused |
| 9 | Massive logits copies | `Translator.kt:204` and `:312` — `OnnxTensor.value` materialises `[1,seq,64k]` as boxed arrays per step |
| 10 | GC churn ~50 MB | Same as #9, plus `logits.copyOf()`, `MutableList<Long>` boxing every token, `LongArray(size){1L}` mask per call, `getBlockedTokens()` allocating a `Set` + `subList` per step |
| 11 | Thread config generic | `setIntraOpNumThreads(4)` / `setInterOpNumThreads(2)` fixed for all sessions, both directions, all devices. 4 threads on big.LITTLE can land on Cortex-A53s |
| 12 | No lifecycle awareness | No `ViewModel`, no `onSaveInstanceState`, no config-change handling. Native resources parented to the Activity |

### 1.6 Additional defects found while reading (not in the original 12)

| ID | Finding | Where |
|---|---|---|
| N1 | Dead `SpeechManager.kt`, 302 LOC | zero refs |
| N2 | Dead `MainActivity_backup.kt`, 78 LOC | repo root, outside source set |
| N3 | Two inert `SpannableString` blocks built and discarded every `applyUiLanguage()` call | `MainActivity.applyUiLanguage()` |
| N4 | Dead `applyTricolourTitle()` | `MainActivity.kt` |
| N5 | Double storage: 636 MB in APK assets **and** 636 MB copied to `filesDir` | `OnnxSessionManager.copyAsset()` |
| N6 | `values-hi/strings.xml` exists but bilingual UI is done with inline `if (isHindi)` pairs — resource system bypassed | throughout `MainActivity` |
| N7 | Audio-file import always uses the **English** Vosk model, even in HI→EN mode | `MainActivity.processAudioFile()` |
| N8 | `maxLength = 18` hard cap silently truncates long input | `Translator.kt:68` |
| N9 | `hiEnLoading` is a plain `Boolean` read/written from three threads — not volatile, not atomic | `MainActivity` |
| N10 | 7.3 MB of `model.*` SentencePiece protobufs shipped, never opened | `assets/` |
| N11 | `translate()` catch returns literal `"Translation failed"` — an error string rendered as if it were a translation | `Translator.kt:138` |
| N12 | V4 skeleton's `build.gradle.kts` declares no Kotlin plugin, but `MainActivity.kt` is Kotlin | V4 `app/build.gradle.kts` |

---

## 2. Proposed architecture

### 2.1 Principles

1. **Native resources are process-scoped, never Activity-scoped.** One owner, one creator, one destroyer.
2. **The decode loop allocates nothing per step.** All buffers preallocated at engine construction.
3. **Every optimisation is switchable at runtime and measured**, because the submission is judged on evidence, not on claims.
4. **Delete before adding.** ~600 LOC of the 3640 is dead or inert.
5. **No abstraction with one implementation.** Config enums and `when` blocks, not plugin frameworks.

### 2.2 Target package layout

```
com.bhashabridge.app
├─ MainActivity.kt              ~180 LOC   bind views, observe state, forward events
├─ TranslateViewModel.kt        ~150 LOC   UI state, survives rotation, owns nothing native
│
├─ mt/
│   ├─ MtEngine.kt              ~120 LOC   translate(text, direction) -> Result. Owns nothing it didn't make
│   ├─ OnnxModels.kt            ~130 LOC   sessions per direction; the ONLY creator/destroyer
│   ├─ GreedyDecoder.kt         ~160 LOC   KV-cache decode loop, zero per-step allocation
│   ├─ Tokenizer.kt             ~140 LOC   encode/decode
│   └─ RuntimeConfig.kt         ~60  LOC   EP, thread count, affinity, KV on/off — one data class
│
├─ asr/
│   ├─ SpeechInput.kt           ~180 LOC   mic capture + Vosk, merges AudioCaptureController + VoskModelLoader
│   ├─ AudioFileInput.kt        ~200 LOC   file → 16 kHz PCM (ported near-verbatim)
│   └─ TextCleanup.kt           ~140 LOC   ASRCorrector, trimmed
│
├─ tts/Speaker.kt               ~90  LOC
│
├─ ui/
│   ├─ WaveformView.kt          ~120 LOC   ported verbatim
│   ├─ EmergencyPhrases.kt      ~70  LOC   data only
│   └─ EmergencySheet.kt        ~120 LOC   BottomSheetDialogFragment, replaces overlay plumbing
│
└─ bench/
    ├─ Metrics.kt               ~110 LOC   stage timers, TTFT, tokens/sec; emits structured logcat + JSONL
    └─ BenchActivity.kt         ~130 LOC   debug-only sweep runner (exported, adb-launchable)
```

Estimated ~2100 LOC vs 3640. Target: **≥40% smaller, with more features measured.**

### 2.3 Ownership model (fixes #4 and #12 by construction)

```
Application process
   └── MtEngineHolder                 ← object, process-scoped. THE owner.
         ├── get(direction): MtEngine     creates on demand, caches
         └── releaseAll()                 the ONE destroyer

   Activity ──► ViewModel ──► MtEngineHolder.get(dir)   ← borrows, never owns, never releases
```

Release triggers, all in `Application`:

```
onTrimMemory(TRIM_MEMORY_COMPLETE | TRIM_MEMORY_UI_HIDDEN) → releaseAll()
ProcessLifecycleOwner ON_STOP + 60 s idle                  → releaseAll()
```

Rotation destroys the Activity. `ViewModel` survives. `MtEngineHolder` never notices. Zero reload,
zero leak, zero state loss. Backgrounding for 60 s returns 639 MB to the system.

`MtEngine.close()` becomes `private`; only `MtEngineHolder` may call it. Ownership becomes
unambiguous at the type level, not by convention.

### 2.4 The KV-cache decode loop (fixes #1, #9, #10)

**Required export change** (`export_decoder_kv.py`, new):

```python
class DecoderStep(torch.nn.Module):
    def forward(self, input_ids, encoder_hidden_states,
                encoder_attention_mask, *past):
        # past = flattened past_key_values, 4 tensors per layer
        out = self.decoder(input_ids=input_ids,                # seq len 1 after step 0
                           encoder_hidden_states=...,
                           encoder_attention_mask=...,
                           past_key_values=unflatten(past),
                           use_cache=True)
        return self.lm_head(out.last_hidden_state), *flatten(out.past_key_values)
```

Export **two graphs**, standard ORT seq2seq practice:
- `decoder_init.onnx` — no past in, `present` out (step 0)
- `decoder_step.onnx` — past in, present out, `input_ids` fixed at length 1

Cross-attention K/V are computed from `encoder_hidden_states`, which never changes within a call —
they are computed once in `decoder_init` and passed through unchanged. Only self-attention K/V grow.

**Resulting complexity:** per-step work becomes O(1) in generated length instead of O(n).
Total decode goes O(n²) → O(n).

**App-side loop, allocation budget = zero per step:**

```
preallocated once per MtEngine:
    logitsView   : FloatBuffer   ← from OnnxTensor.getFloatBuffer(), no copy
    generated    : LongArray(MAX_LEN)      ← no boxing, no MutableList<Long>
    kvIn / kvOut : Array<OnnxTensor>       ← swapped by reference each step, never reallocated
    maskBuf      : LongBuffer  (direct)
    ngramWindow  : LongArray(n-1)          ← replaces per-step Set + subList

per step:
    bind kvIn, run, read logitsView[argmax over 64k], swap(kvIn, kvOut)
```

`OrtSession.run()` output tensors are read via `getFloatBuffer()` — a view over native memory,
**no JNI copy of 64k floats, no boxed arrays**. This alone is expected to remove the bulk of the
~50 MB transient allocation.

Also applied: `IoBinding` where ORT Java exposes it, so KV output buffers are bound once and reused
across steps rather than re-allocated by the runtime per `run()`.

### 2.5 Arm optimisation axis (fixes #2, #11) — honest version

`RuntimeConfig` is one data class, swept by the benchmark runner:

```kotlin
data class RuntimeConfig(
    val ep: Ep = Ep.CPU,                 // CPU | XNNPACK | QNN
    val intraOpThreads: Int = 4,
    val bigCoreAffinity: Boolean = false,
    val kvCache: Boolean = true,
    val preOptimizedGraph: Boolean = true,
)
```

| Lever | Mechanism | Expected on M31 (Armv8.0) | Expected on i8mm device |
|---|---|---|---|
| ORT 1.17.1 → 1.22.x | dependency bump | small (kernel + graph-opt improvements) | **large** — KleidiAI int8 GEMM is wired into MLAS and dispatches on `i8mm`/`dotprod` |
| KleidiAI | ships inside modern ORT MLAS; no API call, CPU-feature dispatch | **≈0% — device lacks i8mm/dotprod. Say so.** | primary claim |
| SME2 | requires Armv9.2-A + ORT SME kernels | **N/A — no hardware** | N/A unless an SME2 device is sourced |
| XNNPACK EP | `SessionOptions.addXnnpack()` | plausible for int8 QDQ — must be measured, not assumed | plausible |
| Thread affinity | `addConfigEntry("session.intra_op_thread_affinities", "4;5;6;7")` | **real gain expected** — pins to the 4× A73 cluster instead of A53s | real |
| Thread count sweep | 1,2,4,6,8 | real — 4 is a guess today, not a measurement | real |

**NNAPI EP is deliberately excluded** — deprecated as of Android 15, and a submission built on a
deprecated path is a liability, not an optimisation.

The two-device matrix is a **feature of the submission**, not an apology: it demonstrates that the
optimisation was CPU-feature-gated and measured on both sides of the gate, which is exactly the
engineering rigour the challenge is asking for.

### 2.6 Startup path (fixes #3)

Current 8599 ms decomposes into four serial costs. Attack each:

| Cost | Fix | Mechanism |
|---|---|---|
| Runtime graph optimisation, **every launch** | run `ALL_OPT` **offline**, ship the optimised graph, set `OptLevel.NO_OPT` at runtime | `SessionOptions.setOptimizedModelFilePath()` once on desktop; or `onnxruntime.transformers.optimizer` in the export pipeline |
| 636 MB asset copy (first run) + 636 MB double storage | stop copying — deliver models as an **install-time asset pack**, which lands on disk as real files with real paths via `AssetPackManager.getPackLocation()` | Play Asset Delivery. Fallback if AAB delivery is unavailable: keep the copy, but copy **only the active direction**, on a background thread, behind a determinate progress bar |
| Both directions eagerly considered | load only the direction in use; the other stays cold until first swap | already partly done for HI→EN; make it the rule |
| `warmUp()` serial after session create | overlap warm-up with the remaining UI init; encoder warm-up starts the moment the encoder session exists, without waiting for the decoder | independent futures, not `get()` in sequence |

Additional: with the KV-cache split, `decoder_step.onnx` is the hot graph and `decoder_init.onnx`
runs once — they can be created in parallel with the encoder on the same pool.

Target: **8599 ms → under 3000 ms** on warm start, and no 636 MB duplicate on disk.

### 2.7 Tokenizer decision (#7) — decide with data, not opinion

Current implementation is greedy longest-match over a JSON dict. Real SentencePiece unigram is
**Viterbi over piece log-probabilities** — these produce different segmentations on the same input.
The reference `.model` protobufs are already in `assets/` and are never opened.

Plan: **measure before rewriting.**

1. Offline harness: run the shipped `model.SRC`/`model.TGT` through Python `sentencepiece` over
   FLORES-200 devtest, dump reference id sequences.
2. Port the Kotlin encoder to a JVM test, run the same corpus, compute **exact-sequence match rate**.
3. Translate both id streams through the ONNX graph, compare **BLEU/chrF**.

Decision rule, fixed in advance so the result is not rationalised after the fact:

- ΔchrF < 0.5 → keep the dict tokenizer. Document the deviation. Zero further work.
- ΔchrF ≥ 0.5 → ship real SentencePiece (`sentencepiece` JNI, or a Kotlin unigram-Viterbi port —
  the protobuf is already shipped, so no asset growth either way).

### 2.8 Beam search (#8)

Delete `translateBeam()` and `topKIndices()` — 133 LOC, zero callers, never validated.

Re-add **only** if the Phase-8 quality run shows greedy is materially behind on BLEU, and then only
inside `BenchActivity` behind `RuntimeConfig`, never on the user path. A decode strategy that has
never been measured is not a feature; it is 133 lines of untested risk.

### 2.9 Benchmark suite (#6)

Keep `bench/` — it works and produced §1.4. Extend, do not rewrite.

**In-app** (`bench/Metrics.kt`, structured JSONL to logcat, `BuildConfig.DEBUG`-gated):

| Metric | Definition |
|---|---|
| `ttft_ms` | translate() entry → first decoded token available. The headline latency number |
| `tokens_per_sec` | generated tokens / decode-loop wall time |
| `encode_ms` / `decode_ms` / `detok_ms` | per-stage split, so a regression is attributable |
| `steps` | decode step count, to normalise across input lengths |
| `alloc_bytes` | `Debug.getGlobalAllocSize()` delta per translation — proves the #9/#10 fix |
| `session_create_ms`, `warmup_ms`, `asset_ms` | startup split, proves the #3 fix |

**Host-side** (`bench/*.sh`, extending the existing harness):
- CPU: `/proc/self/stat` utime+stime sampling → CPU-ms per translation
- Thermal: `dumpsys thermalservice`, already collected — keep
- Power: requires the M31 to be **unplugged**. Existing `BASELINE.md` correctly notes adb-over-USB
  charges the device and invalidates the reading. Use `adb tcpip 5555` + Wi-Fi, sample
  `batterystats`. This is a known-open item, not a solved one.

**Quality, offline Python** on FLORES-200 devtest (`eng_Latn`↔`hin_Deva`):
- BLEU + chrF2 via `sacrebleu` (chrF2 is the more reliable of the two for Indic targets)
- COMET (`Unbabel/wmt22-comet-da`) — desktop only, never on device
- WER via `jiwer` on a small read-speech set, for the Vosk ASR path

**Reporting rule:** every claimed improvement is reported as `before → after` with n, median, p95,
and σ, on a named device, reproducible by one command. Numbers without a device name and an n are
not evidence.

### 2.10 Deliberately NOT doing

| Rejected | Why |
|---|---|
| Jetpack Compose migration | Existing XML layouts work and carry zero perf cost. Rewriting the UI adds risk to a submission judged on inference performance. Rung 1 of the ladder: it does not need to exist |
| Hilt / Koin / DI framework | One `object` holder, three collaborators. A DI graph for this is a config file pretending to be architecture |
| Repository / UseCase / Clean-Architecture layering | Would add ~6 interfaces with exactly one implementation each |
| `Flow`/`StateFlow` everywhere | `LiveData` in the ViewModel plus the existing executors is sufficient and smaller. Coroutines are used where they replace an executor, not as a rewrite |
| Foreground `Service` for the engine | Nothing runs while backgrounded. A process-scoped holder gets the same lifetime without a notification and a permission |
| Room for translation history | In-memory ring of ~20 entries, cleared on exit, matching current behaviour. A database for that is a schema migration waiting to happen |
| Custom thread pool / affinity via JNI | ORT's `intra_op_thread_affinities` config entry does it from Java. No native code needed |
| int4 / further quantisation | Out of scope for a rewrite phase, and quality risk is unmeasured. Revisit only if §2.9 has a working quality gate first |

---

## 3. Migration strategy

**Not a strangler pattern.** V4 is an empty project, v3.4.1 stays untouched and runnable as the
comparison baseline. This is a clean-room port with per-phase behavioural parity checks against
a frozen reference.

### 3.1 Rules

1. **v3.4.1 is never modified.** It is the control in the experiment.
2. **Every phase ends green:** compiles, installs, runs, and its benchmark slice is recorded.
3. **One commit per fix** (per the standing workflow), then re-verify.
4. **No phase begins before the previous one is approved.** User-gated, as instructed.
5. **Parity before performance.** A faster app that translates differently has not been optimised;
   it has been broken. Phase 4's parity gate is the hinge of the whole plan.
6. **The model pipeline is a parallel track.** Python export work (Phase 3) can proceed while
   Kotlin porting happens, but Phase 5 cannot start until Phase 3 lands.

### 3.2 The parity gate (Phase 4 exit criterion)

Freeze a 200-sentence set (100 EN, 100 HI) covering the four benchmark length classes.
Run through v3.4.1, save outputs as `parity_reference.json`.

- **Phase 4 (pre-KV port):** V4 output must be **byte-identical** to reference. Same greedy argmax,
  same tokenizer, same post-processing — any difference at this stage is a porting bug and must be
  fixed before continuing, not explained away.
- **Phase 5 (post-KV):** exact match is not required — KV cache changes float accumulation order and
  can flip a near-tie argmax. Gate becomes **≥98% exact match, and chrF2 within 0.3 of reference.**
  Every non-matching sentence is inspected by hand, not waved through.

This is the single most important control in the plan. Without it, "3× faster" is unfalsifiable.

### 3.3 Asset handling during migration

636 MB of models will not be copied into the V4 repo. Options in preference order:

1. Symlink / junction `V4/app/src/main/assets` → the v3.4.1 assets during development
2. Gradle task copying at build time from a path in `local.properties`
3. Asset pack module (the Phase-6 production answer)

`.gitignore` must exclude `*.onnx`, `model.*`, `dict.*.json` from the outset — a 636 MB blob in git
history is unrecoverable without a rewrite.

---

## 4. Risks

| # | Risk | Likelihood | Impact | Mitigation | Trigger to abandon |
|---|---|---|---|---|---|
| R1 | IndicTrans2 uses `trust_remote_code=True` custom modelling code; its decoder may not accept `past_key_values` cleanly, or ONNX export of the cached path may fail | **High** | **Critical** — this is the whole plan's centrepiece | Validate the export **in Phase 3, before any Kotlin KV work**. Verify `use_cache=True` numerically matches the uncached forward in PyTorch first, then export | Export fails after 2 days → fall back to R1-fallback below |
| R1-fallback | If cached export is not achievable | — | — | Two independent fallbacks: (a) chunked re-decode — recompute in blocks of 4 rather than every step, ~4× fewer decoder calls, no export change; (b) switch to a model with first-class ONNX cache support (NLLB-200-distilled-600M, which has an Optimum ONNX config) and report the swap honestly as an engineering trade-off | — |
| R2 | KleidiAI shows ~0% on the M31 because Armv8.0 lacks i8mm/dotprod | **Certain** | Medium | Already planned for: two-device matrix (§2.5), M31 as baseline-of-record, an i8mm device for the KleidiAI axis. Report the CPU-feature gate as a finding | — |
| R3 | No second Arm device available | Medium | High — removes the headline KleidiAI claim | Secure the device **during Phase 2**, not Phase 8. If unavailable, pivot the headline to the KV cache + startup + memory story, which stands entirely on M31 data | — |
| R4 | Two ONNX decoder graphs (init + step) increase APK size further beyond 634 MB | Medium | Medium | Cross-attention K/V weights are shared; the step graph is much smaller than the init graph. Measure immediately post-export. Asset packs also lift the install-size constraint | — |
| R5 | Parity gate fails at Phase 4 — subtle tokenizer or decode divergence | Medium | High | This is exactly what the gate is for. Bisect against v3.4.1 stage by stage: compare token ids, then encoder output tensors, then step-0 logits | — |
| R6 | ORT 1.22 raises minSdk or breaks the Java API surface | Low | Medium | Bump in an isolated commit, run the full bench before touching anything else. Roll back independently if needed | — |
| R7 | `IoBinding` is incompletely exposed in ORT's Java/Android bindings | Medium | Low | `getFloatBuffer()` alone captures most of the copy win. IoBinding is an increment on top, not a dependency | — |
| R8 | Play Asset Delivery unavailable for sideloaded competition APK | Medium | Low | Fallback already specified in §2.6: copy only the active direction, background thread, progress bar. Still removes half the double storage |
| R9 | Vosk models (`model/`, `model-hi/`) have their own extraction cost, folded into the 3665 ms ASR-ready figure | Low | Low | Same asset-pack treatment. Measure separately in Phase 7 so ASR and MT startup costs are not conflated |
| R10 | 60 s idle release (§2.3) causes a visible 3–8 s reload when the user returns | Medium | Medium | Make the idle window a tuned constant, measured against real resume patterns. Only `TRIM_MEMORY_COMPLETE` releases unconditionally. **Leave the knob exposed** — this is a device-dependent tuning value, not a constant to hardcode |
| R11 | Scope creep — 12 problems plus 12 new findings is a large surface for one rewrite | **High** | High | The phase gate is the control. Phases 3–5 are the submission. Phases 7–9 are polish and are droppable without invalidating the result |
| R12 | Benchmark instrumentation itself perturbs what it measures | Low | Medium | `Metrics.kt` is `BuildConfig.DEBUG`-gated and writes JSONL off the hot path. Record one release-build run with instrumentation off as the control |

---

## 5. Feature parity checklist

Nothing on this list may regress. Checked off only when verified on-device, not when the code compiles.

### 5.1 Translation
- [ ] EN→HI typed translation
- [ ] HI→EN typed translation
- [ ] Direction swap, with lazy HI→EN load on first swap
- [ ] Loading overlay + animated dots while models load
- [ ] Confidence score computed (`lastConfidence`, `confidenceLabel()`) — **surface it in the UI this time**; today it is computed and only logged
- [ ] `EnPostProcessor` HI→EN cleanup: standalone "i"→"I", duplicate-word removal, punctuation spacing, sentence capitalisation
- [ ] Repetition penalty (1.1)
- [ ] No-repeat-ngram blocking (n=3)
- [ ] Max-length cap — **and fix N8: signal truncation to the user rather than silently cutting**
- [ ] Failure path returns a distinguishable error state, not a string that looks like a translation (fixes N11)

### 5.2 Speech input
- [ ] Mic capture at 16 kHz, Vosk recognition
- [ ] Live partial results shown while speaking
- [ ] Streaming partial translation, gated: ≥3 words, ≥250 ms, text changed
- [ ] Final-result supersedes-partial discard logic (`isFinalPending`, both check points)
- [ ] Waveform amplitude visualisation
- [ ] Mic pulse animation
- [ ] `RECORD_AUDIO` permission request, and auto-resume of the tapped action on grant
- [ ] Hindi Vosk model loaded lazily on swap, recogniser rebuilt per direction
- [ ] ASR correction (EN phrase table + HI phrase table)
- [ ] "Heard: …" hint when correction changed the raw ASR text

### 5.3 Audio file import
- [ ] Import via drawer and via overflow menu
- [ ] MP3/M4A/OGG/WAV/AAC/3GP decode → 16 kHz mono PCM
- [ ] Resampling
- [ ] Partial-progress callback during transcription
- [ ] **Fixes N7:** use the Hindi model when the direction is HI→EN (current build always uses English)

### 5.4 Output
- [ ] TTS playback of the result, correct voice per direction
- [ ] "Hindi voice not installed" banner, tapping opens the system TTS install screen
- [ ] Output colour states: idle / streaming (dim italic) / result / emergency
- [ ] Translation history, recent-first, tap to recall

### 5.5 Emergency phrases
- [ ] Overlay opens from the emergency button
- [ ] Four categories: Basic, Medical, Safety, Location
- [ ] Phrase list per category
- [ ] Selected-phrase panel showing English + Hindi
- [ ] Speak-phrase button
- [ ] Selecting a phrase populates the main screen

### 5.6 Shell / navigation / first run
- [ ] Onboarding on true first launch
- [ ] Setup (language choice) screen
- [ ] `ui_language` persisted in `SharedPreferences`
- [ ] Bilingual UI (EN/HI) across every string — **migrate to `values-hi/` resources, fixing N6**
- [ ] Language switch dialog
- [ ] Drawer: History / Language / Import Audio
- [ ] Multidex, `largeHeap`
- [ ] ABI splits for arm64-v8a + armeabi-v7a

### 5.7 New in V4 (not parity — additions)
- [ ] Survives rotation without reloading models or losing state
- [ ] Native memory released on background/trim
- [ ] `RuntimeConfig` switchable at runtime
- [ ] `BenchActivity` sweep runner, adb-launchable
- [ ] Structured JSONL metrics
- [ ] TTFT and tokens/sec reported

---

## 6. Refactoring order

Nine phases. Each ends with a working, installable app and a recorded measurement.
**Phases 3–5 are the submission.** 7–9 are polish and may be dropped without invalidating the result.

| Ph | Name | Deliverable | Exit criterion | Fixes |
|---|---|---|---|---|
| **1** | **Engineering plan** | this document | approved by user | — |
| **2** | Skeleton + measurement floor | V4 builds and runs. Kotlin plugin added (N12), `.gitignore` for model blobs, asset strategy chosen, `Metrics.kt`, `BenchActivity`, `bench/` harness re-pointed at V4. Second Arm device secured (R3) | `./run_all.sh` produces a V4 result dir; empty app measured for launch time | #6 (harness), N12 |
| **3** | **ONNX pipeline — KV cache export** ⚠️ | `export_decoder_kv.py` producing `decoder_init.onnx` + `decoder_step.onnx`, int8-quantised, graph pre-optimised offline. Python-side numerical validation | Cached vs uncached PyTorch forward matches to 1e-4; ONNX step graph matches PyTorch cached forward; **desktop ORT shows O(n) decode scaling** | #1, part of #3 |
| **4** | MT core port, **behaviour-frozen** | `Tokenizer`, `OnnxModels`, `MtEngine`, greedy decode — ported from v3.4.1 with **no algorithmic change**. Beam search and dead code not carried over | **Byte-identical output on the 200-sentence parity set.** Non-negotiable | #5 (partial), #8, N1–N4 |
| **5** | **KV cache + zero-copy decode** | `GreedyDecoder` on the two-graph cached path. `getFloatBuffer()` replaces `OnnxTensor.value`. Preallocated buffers, no per-step allocation. IoBinding where available | ≥98% parity, chrF2 within 0.3. **21-word case: 2518 ms → target <900 ms.** `alloc_bytes` per translation down ≥80% | #1, #9, #10 |
| **6** | Lifecycle + ownership + startup | `MtEngineHolder`, `TranslateViewModel`, trim-memory release. Asset-pack or single-direction copy. Pre-optimised graph loaded with `NO_OPT` | Rotation reloads nothing, leaks nothing (verified by heap dump). **Ready time 8599 ms → target <3000 ms.** No 636 MB duplicate on disk | #3, #4, #12, N5 |
| **7** | Arm runtime sweep | ORT 1.22.x. `RuntimeConfig` sweep: EP × threads × affinity, on both devices | Sweep matrix recorded on both devices. Best config chosen **by measurement**. KleidiAI CPU-feature gate documented with data from both sides | #2, #11 |
| **8** | Quality gate + tokenizer decision | FLORES-200 BLEU/chrF2/COMET, WER on the ASR path. Tokenizer parity harness (§2.7). Tokenizer decision executed per the pre-committed rule | Quality numbers published for v3.4.1 and V4. Tokenizer decision made from data. Beam search re-added only if the data demands it | #6, #7, #8 |
| **9** | UI, ASR, TTS, polish | Port `SpeechInput`, `AudioFileInput`, `Speaker`, `WaveformView`, emergency sheet. `values-hi/` string resources. Surface confidence in the UI | Full §5 checklist green on-device | #5, N6, N7, N8, N9, N11 |

### 6.1 Dependency graph

```
Ph1 ──► Ph2 ──┬──► Ph3 (Python, parallel track) ──┐
              │                                    ├──► Ph5 ──► Ph6 ──► Ph7 ──► Ph8
              └──► Ph4 (Kotlin) ───────────────────┘                              │
                                                                          Ph9 ────┘
```

Phase 3 and Phase 4 are independent and may run concurrently. Phase 5 requires both.
Phase 9 is independent of 5–8 after Phase 4 and can slot in wherever convenient.

### 6.2 The one number that matters

If the submission carries a single headline, it is this:

```
21-word EN→HI translation, Samsung Galaxy M31 (Exynos 9611, Armv8.0 NEON):

    v3.4.1   2518 ms   ← O(n²) decode, full re-attention every step
    V4       <900 ms   ← target, O(n) decode with KV cache

    plus:  8599 ms → <3000 ms to ready
           636 MB duplicate on-disk → 0
           ~50 MB → <10 MB transient allocation per translation
           and no 639 MB native leak on rotation
```

Every one of those is measured by the same harness that produced §1.4, on the same device,
reproducible with one command.

---

**Phase 1 complete. No code written. Awaiting approval before Phase 2.**
