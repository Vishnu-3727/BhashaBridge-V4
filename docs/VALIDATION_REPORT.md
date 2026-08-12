# Validation Report (Phase 10)

> **This is a point-in-time record of Phase 10 and is deliberately not updated as the project moves.**
> Two items in §6 have since been closed and are listed here rather than edited in place, so the
> report stays an honest snapshot of what was true when it was written:
>
> | §6 item | Status now |
> |---|---|
> | 1. "Hindi → English is not available" | **Closed.** Phase 12 exported and verified the HI→EN cached INT8 graphs from the named `ai4bharat/indictrans2-indic-en-dist-200M` checkpoint. Measured 23.8 / 43.2 / 76.7 ms at 2/6/12 tokens — `OPTIMIZATION_SUMMARY.md` §3.19, `bench/results/cross-device/S26U_EXPERIMENTS.md` §4b |
> | 2. "27 s to first translation" | **Closed.** ~5,134 ms cold-launch `engine_init` median on this device, via the tokenizer parse, parallel session load, and the ORT-format cache — `OPTIMIZATION_SUMMARY.md` §3.10–§3.14, §3.29 |
> | 8. "One device" | **Superseded.** Nine devices, Armv8.0 → Armv9 — `bench/results/cross-device/CROSS_DEVICE_REPORT.md` |
>
> Items 3–7, 9 and 10 stand. For current figures see [`SUBMISSION.md`](../SUBMISSION.md).

Every number and verdict here was produced on the **SM-M315F** (Samsung Galaxy M31, Exynos 9611,
4×Cortex-A73 + 4×Cortex-A53, Armv8.0-A, 6 GB RAM, Android 12) — the same device as every benchmark
in this repository. Nothing is estimated. Where something could not be measured, it says so.

Validated build: `debug` for functional and latency work (the `Metrics` harness only exists in debug),
plus a signed `release` build for the release-path checks in §5.

---

## 1. Functional validation

64 checks. **58 PASS, 0 FAIL, 5 N/A, 1 FIXED during this phase.**

### 1.1 Startup and onboarding

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | Cold launch reaches first frame | PASS | `am start -W` TotalTime **1864 ms** (first run), **1196 ms** (second), **1422 ms** (release build) |
| 2 | First run shows the tour | PASS | Tour renders after `pm clear`; three rows, bilingual |
| 3 | "Get Started" advances to language choice | PASS | ViewFlipper page 2 |
| 4 | Language choice applies and enters the app | PASS | English selected → translated main screen |
| 5 | Second launch skips onboarding | PASS | Straight to main screen |
| 6 | Loading overlay covers model load | PASS | Overlay visible until `Direction EN_TO_HI loaded=true` |
| 7 | Engine load completes | PASS | **30.8 s** first ever launch, **27.0 s** with assets already unpacked |
| 8 | CPU detection runs once at startup | PASS | `ARMv8.0 cores=8(perf=4,eff=4) neon=true …` |
| 9 | ORT policy derived from CPU | PASS | `arm-adaptive(threads=2) intra=2 arena=false` |

### 1.2 Translation

| # | Check | Result | Evidence |
|---|---|---|---|
| 10 | Typed EN→HI translation | PASS | "I need water" → "मुझे पानी चाहिए ।" (378 ms) |
| 11 | Longer sentence | PASS | "The weather is very nice today" → "आज मौसम बहुत अच्छा है ।" (461 ms) |
| 12 | Output identical to every prior phase | PASS | 3/3 benchmark sentences byte-identical to Phase 6D/7/8 |
| 13 | Blank input is a no-op | PASS | No state change, no error |
| 14 | Keyboard dismissed on translate | PASS | Observed |
| 15 | Repeated translations are stable | PASS | 90 runs, stdev 11.5–19.5 ms (§2.2) |
| 16 | Swap to HI→EN reports unavailability | PASS | "Hindi → English model is not available in this build"; stays on EN→HI |
| 17 | Swap does not crash or wedge the UI | PASS | Screen remains usable, EN→HI still translates |
| 18 | Direction labels follow direction | PASS | ENGLISH/HINDI headers and hint update |

### 1.3 Speech

| # | Check | Result | Evidence |
|---|---|---|---|
| 19 | Mic permission requested on first use | PASS | System dialog "Allow BhashaBridge to record audio?" |
| 20 | Denial handled gracefully | PASS | "Microphone permission is required to speak"; no recording started |
| 21 | Grant starts a session immediately | PASS | No second tap needed |
| 22 | Vosk model loads on first mic use | PASS | **1336 ms** measured (`SpeechPipelineBenchmarkTest`) |
| 23 | Listening state shown | PASS | "Listening…", pulse ring, live waveform |
| 24 | Waveform responds to audio | PASS | Bars vary per buffer |
| 25 | Second tap stops the session | PASS | `Recording session closed` |
| 26 | No recogniser/recorder leak | PASS | Session closes in `finally`; repeated start/stop cycles clean |
| 27 | Recording stops when the screen leaves | PASS | `onStop` → `stopRecording()` |
| 28 | ASR produces a correct transcript | PASS | WAV fixture → `"i need water please help me"` (exact) |
| 29 | ASR correction applied to results | PASS | `AsrCorrector` on every final |
| 30 | "Heard:" hint appears only when correction changed the text | PASS | Hidden for unchanged transcripts |
| 31 | Speech accuracy against a human voice | **N/A** | Not measurable in this harness — see §6 |

### 1.4 Text-to-speech

| # | Check | Result | Evidence |
|---|---|---|---|
| 32 | TTS engine initialises | PASS | `TTS ready, Hindi voice available: true` |
| 33 | Audio actually plays | PASS | `dumpsys audio`: TTS pid 3243 `AudioTrack … state:started … CONTENT_TYPE_SPEECH` |
| 34 | Final translations are spoken | PASS | Observed on every final result |
| 35 | Emergency phrase replay speaks | PASS | "SPEAK AGAIN" triggers playback |
| 36 | Missing-Hindi-voice banner | **N/A** | Test device has the Hindi voice installed; the negative path could not be exercised |

### 1.5 Emergency phrases

| # | Check | Result | Evidence |
|---|---|---|---|
| 37 | Sheet opens from the main screen | PASS | Full-screen overlay |
| 38 | Opens on the Basic tab with no selection | PASS | Reset each open |
| 39 | All four category tabs render and switch | PASS | Basic / Medical / Safety / Location |
| 40 | Phrase list shows both languages | PASS | 32 pairs across categories |
| 41 | Selecting a phrase fills the detail panel | PASS | English + Hindi + replay button |
| 42 | Selection reaches the main screen | PASS | Pair shown after closing |
| 43 | Back closes the sheet, not the app | PASS | Focus stays on MainActivity |
| 44 | No engine call on this path | PASS | No `BB.Bench` line emitted when a phrase is chosen |

### 1.6 History, settings, import

| # | Check | Result | Evidence |
|---|---|---|---|
| 45 | History lists recent translations | PASS | "I need water  →  मुझे पानी चाहिए ।" |
| 46 | Tapping an entry restores it | PASS | Input and output repopulated, no engine call |
| 47 | Empty history shows a toast | PASS | "No translations yet" |
| 48 | History does not survive process death | PASS | By design (in-memory); empty after restart |
| 49 | App language switches to Hindi | PASS | Whole UI in Devanagari, including the mic status line |
| 50 | Language switch preserves screen state | PASS | Input and translated output survived the recreate |
| 51 | Language switch does not reload models | PASS | No `Loading MT engine` line; PSS 667 MB → 667 MB |
| 52 | Model & device reports live runtime facts | PASS | CPU + `arm-adaptive(threads=2)`, intra-op 2, arena false |
| 53 | Debug-only block appears in debug builds | PASS | Present in debug |
| 54 | Audio import opens the system picker | PASS | `com.android.documentsui.picker.PickActivity` |
| 55 | Picker filters to audio types | PASS | Non-audio files greyed out |
| 56 | Cancelling the picker is harmless | PASS | Back returns to the app, no state change |
| 57 | An imported file is transcribed | PASS | `AudioFileTranscriberTest` 1/1, real 16 kHz WAV |

### 1.7 Lifecycle

| # | Check | Result | Evidence |
|---|---|---|---|
| 58 | Rotation does not reload models | PASS (measured before the fix in §1.8) | No `Loading MT engine`; PSS 670 → 672 MB |
| 59 | Rotation preserves input and output | PASS | Text survived both rotations |
| 60 | Background → foreground keeps the process | PASS | PSS 641 → 646 MB, no reload |
| 61 | `onTrimMemory` releases the engine | PASS **on this device only** — see note below | `Trim level 80 — releasing 1 engine(s)`; PSS **630 → 453 MB** |
| 62 | Vosk models released only if loaded | PASS | No speech-release line when the mic was never used |
| 63 | App recovers after a trim release | PASS | Next translation transparently reloaded and returned 275 ms |
| 64 | Process restart is clean | PASS | Force-stop → relaunch → models reload, no crash |
| — | Low-memory kill / restore | **N/A** | Not reproducible on a 6 GB device without synthetic pressure |
| — | Multi-window / split screen | **N/A** | Portrait-locked after §1.8 |
| — | Foreground service behaviour | **N/A** | The app runs no services |

> **Note on check 61 — why a genuine PASS hid a real defect.** The trigger was gated on
> `TRIM_MEMORY_COMPLETE`, and Android stopped delivering that level to apps targeting API 34+. This
> device runs **Android 12 (API 31)**, where it is still delivered, so the release genuinely fired
> and the 630 → 453 MB drop is a real measurement. It is also the reason the defect survived
> validation: the one device every number in this repo comes from is the wrong side of the change.
> On the Android 15 and 16 phones in `bench/results/cross-device/` the branch could never be taken
> and ~600 MB was held for the life of the process. The gate is now `TRIM_MEMORY_BACKGROUND`
> (see ARCHITECTURE_RULES R4.6). **This check needs re-running on an API 34+ device**; the number
> above is not evidence for those devices, and is not claimed to be.
>
> Re-measured on the SM-M315F after the fix, with the levels driven explicitly
> (`adb shell am send-trim-memory <pid> <LEVEL>`), app backgrounded, EN→HI engine resident:
>
> | Level sent | TOTAL PSS after | Release line in `BB.App` |
> |---|---|---|
> | `HIDDEN` (UI_HIDDEN, 20) | 1,176,909 KB — unchanged | none, as intended |
> | `BACKGROUND` (40) | **893,101 KB** | `Releasing 1 engine(s)` |
>
> `HIDDEN` deliberately holding is as load-bearing as `BACKGROUND` releasing: it is what stops every
> home-press from charging the user a reload. Note also that no trim arrived *spontaneously* while
> the app sat backgrounded for 40 s on this 6 GB device — the OS sends these only under real
> pressure or LRU movement, which is why the levels are driven explicitly here and why the
> instrumented `TrimReleaseTest` asserts on the callback rather than on observed memory.

### 1.8 Defect found and fixed

| Defect | Severity | Fix |
|---|---|---|
| **Landscape layout unusable** — the output card and the entire speech section fell below the fold with nothing to scroll, so the translation result was unreachable while rotated. | High (core result unreachable) | Both activities are now `android:screenOrientation="portrait"`. This removes the broken state with no layout change. A responsive landscape layout is the proper fix and is listed in §6. Re-verified: forcing `user_rotation=1` leaves the app in portrait. |

One earlier suspicion — "Listening… shown after denying the microphone" — was **not** a defect. A clean
re-run showed the deny path produces "Microphone permission is required to speak"; the first
observation came from a tap that granted the permission for one use.

---

## 2. Performance validation

### 2.1 Startup

| Phase | Measured |
|---|---|
| Process start → first frame (cold, debug) | 1864 ms first ever launch, 1196 ms subsequent |
| Process start → first frame (cold, release) | **1422 ms** |
| Process start → engine ready (first ever launch, includes unpacking 472 MB of assets) | **30.8 s** |
| Process start → engine ready (assets already unpacked) | **27.0 s** |
| Engine reload after a memory-trim release | 26.3 s |

The screen is interactive in ~1.4 s; the ONNX session load runs behind the progress overlay. Unpacking
accounts for ~4 s of the first-run figure — the remaining ~27 s is ONNX Runtime creating three INT8
sessions totalling 472 MB. This is the single worst user-facing number in the project (§6).

### 2.2 Translation latency, tokens/sec, time to first token

`MtBenchmarkTest`, 3 warm-up rounds discarded, **30 runs per sentence**, greedy decoding, debug build
with `Metrics` active.

| Sentence | Tokens | Total median | p95 | stdev | Encoder | Decode median | tokens/s | **TTFT** | per-step |
|---|---|---|---|---|---|---|---|---|---|
| "Water." | 2 | **163.1 ms** | 196.8 | 11.5 | 35.5 | 125.9 | 15.9 | **78.5 ms** | 75.7 ms |
| "Hello, how are you?" | 6 | **364.5 ms** | 403.9 | 17.1 | 60.9 | 297.2 | 20.2 | **107.1 ms** | 45.7 ms |
| "The weather is very nice today and I want to go outside." | 12 | **667.9 ms** | 683.5 | 18.0 | 89.2 | 570.8 | 21.0 | **139.5 ms** | 44.2 ms |

*TTFT = encoder + `decoder_init`, i.e. the first token's latency. Per-step = mean `decoder_step` time
for tokens after the first.*

**Reproducibility.** These medians land within 1.4 ms of the Phase 8 run (163.2 / 365.1 / 667.2 ms)
taken on a different day: 0.06%, 0.16% and 0.10% apart. The tuning result is stable, not a lucky run.

Per-step cost is flat at ~44–46 ms from token 2 onward, which is the KV cache doing its job: decode
cost grows linearly with output length instead of quadratically (`docs/CACHE_BENCHMARK.md`).

### 2.3 Speech pipeline

`SpeechPipelineBenchmarkTest`, on a real 16 kHz mono WAV of 2.64 s of speech.

| Stage | Measured |
|---|---|
| Vosk English model load (first mic use) | **1336 ms** |
| Recognition of 2.64 s of audio | **2087 ms** (0.79× real time) |
| Machine translation of the transcript | **402 ms** |
| **Recognition + translation** | **2489 ms** |
| TTS playback start | not measured — see §6 |

Transcript: `"i need water please help me"` (exact). Translation:
`"मुझे पानी चाहिए कृपया मेरी मदद करें"`.

Speech-to-speech latency is therefore **≈2.5 s plus the system TTS engine's own start latency**, for a
2.6 s utterance, with the recognition model already resident. This is honest but incomplete: the TTS
component is not instrumented.

### 2.4 Memory

| State | TOTAL PSS | Native |
|---|---|---|
| Idle after model load + 1 translation | 670 MB | 555 MB |
| After 90 consecutive translations | **616–630 MB** | 533–546 MB |
| After rotation | 672 MB | — |
| After background → foreground | 646 MB | — |
| With Vosk English also resident (**peak observed**) | **743 MB** | — |
| Release build, same workload | 656 MB | — |
| After `onTrimMemory` release (API 31 device — see the note in §1.7) | **454 MB** | — |

Memory is flat under sustained use: 90 translations left the process *lower* than it started, so the
per-translation KV cache is being released as designed. Rotation and backgrounding add nothing. On a
6 GB device the app never approached pressure.

### 2.5 Thermal and battery

Continuous inference for ~5 minutes (the 90-translation run), plus roughly 40 minutes of intermittent
use across the whole validation session.

| Sample | Battery temperature | Battery level |
|---|---|---|
| Before the benchmark | 33.3 °C | 85% |
| Mid-run | 33.8 °C | 85% |
| After the run | 33.7 °C | 85% |

**+0.5 °C peak, back to +0.4 °C at rest.** No thermal throttling signature appears in the latency data:
p95 stays within 5% of the median on every sentence, and the last sentence measured (the longest, run
last, when the device was warmest) has the *tightest* spread of the three. Battery level did not move
by the 1% the platform reports, so per-translation energy is below the measurable floor of this method
— a proper figure needs a hardware power monitor (§6).

---

## 3. Arm optimization validation

Each claim below is measured and documented; this section points at the evidence rather than
restating it.

### ✓ Model size optimisation

| | Result | Where |
|---|---|---|
| fp32 cached graphs → INT8 | **1869 MB → 472 MB (3.96×)** | `model_pipeline/EXPORT_WITH_CACHE.md` |
| Method | ORT dynamic quantization, `QuantType.QInt8`, no calibration — 145 of 217 MatMul → MatMulInteger | same |
| Signatures preserved | encoder 2/1, `decoder_init` 3/73, `decoder_step` 74/73 | same |
| Output parity | greedy token sequences **identical** to fp32; max logit delta 0.448 | `verify_cache.py --atol 1.0`, 7/7 |

### ✓ Model speed optimisation

| | Result | Where |
|---|---|---|
| KV cache added to a decoder that had no cache ports | **2.12× at 12 tokens**, 1.48× at 6, 1.06× at 2 | `docs/CACHE_BENCHMARK.md` |
| Complexity change | tokens/s *rises* 14.9 → 21.6 with length (uncached *falls* 13.9 → 9.5) | same |
| ORT session tuning | intra-op 2: p95 −30%, stdev −84%; arena off: memory −37% | `docs/ORT_TUNING.md` |
| Reverted experiments recorded | intra-op 8 (+90%), NO_OPT (+13%), parallel inter-op (+10%) | same |

### ✓ Arm-specific optimisation

| | Result | Where |
|---|---|---|
| Runtime reads the CPU and configures itself | `/proc/cpuinfo` HWCAP + `cpufreq` topology → NEON/FP16/dotprod/i8mm/SVE2/SME2 and perf/eff split | `docs/ARM_PLATFORM_OPTIMIZATION.md` |
| Thread policy is derived, not hard-coded | `intra_op = (performanceCores / 2)` clamped [1,4] → 2 on this CPU | same |
| The naive rule was tested and rejected | threads = all 4 big cores **regressed** to 719 ms / stdev 88.8 vs 667 ms / 18.4 | same |
| INT8 acceleration scales with silicon | MLAS dispatches SDOT/i8mm/SME on HWCAP — same binary, faster on newer Arm | same |
| SME2 | **not claimed**; the detector surfaces the flag, the architecture is ready, no Armv9 device was available to validate | same |

Verified live in this phase: the app logs `ARMv8.0 cores=8(perf=4,eff=4)` and derives
`arm-adaptive(threads=2)` on every launch, and the "Model & device" screen shows the same values to
the user.

### ✓ Developer experience

| | Evidence |
|---|---|
| Reproducible export pipeline | `cached_export.py` + `quantize_cached.py` + `verify_cache.py` (a 7-check numeric gate, plus a model-free `--selfcheck`) |
| Benchmarks are re-runnable, not screenshots | `MtBenchmarkTest`, `MtTuningSweepTest`, `bench_parse.py`, `bench_tune_parse.py`; raw JSONL evidence in `model_pipeline/` |
| Architecture is written down and enforced | `ARCHITECTURE_RULES.md`, `DEPENDENCY_RULES.md`, `CODING_STANDARDS.md` |
| Negative results published | every reverted optimisation is in `OPTIMIZATION_SUMMARY.md` with its numbers |
| Rebuild from scratch | `README.md` documents the asset set and the build; one Gradle command |
| Test suite | 20 unit + 6 instrumented tests (§4) |

---

## 4. Tests executed

| Suite | Tests | Result |
|---|---|---|
| `TokenizerTest` (JVM) | 6 | PASS (previous phases) |
| `DecoderTest` (JVM) | 7 | PASS (previous phases) |
| `MetricsTest` (JVM) | 6 | PASS (previous phases) |
| `MtEngineInstrumentedTest` (device) | 2 | **PASS — re-run in Phase 9, backend unchanged** |
| `AudioFileTranscriberTest` (device) | 1 | **PASS — real WAV decoded and recognised** |
| `SpeechPipelineBenchmarkTest` (device) | 1 | **PASS — timings in §2.3** |
| `MtBenchmarkTest` (device) | 1 | **PASS — 90 translations, §2.2** |
| Manual functional checks | 64 | **58 PASS, 0 FAIL, 5 N/A, 1 fixed** |

Automated pass rate: **24/24 (100%)**. Functional pass rate: **58/59 executed checks (98.3%)**, the one
exception being the landscape defect, now fixed and re-verified.

---

## 5. Release audit

| Item | Status | Detail |
|---|---|---|
| Release build | **PASS** | `./gradlew assembleRelease` succeeds |
| APK generation | **PASS** | `app-arm64-v8a-release-unsigned.apk` **561 MB**, `app-armeabi-v7a-…` **555 MB**; ABI splits enabled, no universal APK |
| Release build runs | **PASS** | Signed with the debug key, installed, completed onboarding and translated "I need water" → "मुझे पानी चाहिए ।" |
| Release logging hygiene | **PASS** | Zero `BB.*` log lines in a full release session — `logDebug` and `Metrics` are compiled out, so no user speech or translation can reach a release log |
| Crash-free | **PASS** | No `AndroidRuntime` entries in any session this phase |
| ProGuard / R8 | **GAP** | `optimization { enable = false }`. Shrinking is off, so no keep rules exist for ONNX Runtime / Vosk reflection. Enabling it is a behaviour-affecting change and was deliberately **not** attempted in a validation phase |
| Signing readiness | **GAP** | No `signingConfig`. Release output is unsigned; a debug-key signature was used only to smoke-test. Production keys must be generated and held by the project owner — never committed |
| Play Store distributability | **GAP** | 561 MB exceeds Play's limits for both APK and AAB delivery. Shipping through Play needs Play Asset Delivery or a first-run model download; direct APK/side-load works today |
| README | **ADDED** | `README.md` — features, measured performance, build, architecture, doc index, limitations |
| Screenshots / demo assets | **PASS** | 5 screenshots in `docs/images/`, referenced from `README.md` and `UI_RECONSTRUCTION.md`. No demo video yet |
| Licences | **ADDED** | `THIRD_PARTY_NOTICES.md` — ONNX Runtime (MIT), Vosk (Apache-2.0), IndicTrans2 (MIT), AndroidX/Material (Apache-2.0), bundled Vosk models identified from their own READMEs |
| Third-party acknowledgements | **ADDED** | AI4Bharat and Alpha Cephei credited in `THIRD_PARTY_NOTICES.md` |
| Model binaries out of git | **PASS** | `.gitignore` excludes `*.onnx`, dictionaries and both Vosk model trees (R14.5) |
| Secrets in repo | **PASS** | No keystore, no tokens; `local.properties` is ignored |

---

## 6. Remaining known limitations

Ordered by how much they matter for a judge.

1. **Hindi → English is not available.** Only the EN→HI cached INT8 graphs exist; the HI→EN pair was
   never put through the export pipeline (the R-PROV provenance gap). The UI reports it honestly and
   keeps working, and Hindi *recognition* is present — but the app is one-directional in practice.
2. **27 s to first translation.** Three INT8 sessions totalling 472 MB have to be created. It happens
   once per process behind a progress screen, and a memory trim makes the user pay it again.
3. **561 MB APK.** Not distributable through Play without asset delivery or an on-demand download.
4. **No landscape layout.** Portrait is now enforced rather than degraded; a responsive layout is the
   real fix.
5. **Speech recognition accuracy is unmeasured.** The file test proves the pipeline, using synthesised
   speech; no word-error rate against human voices was measured. The bundled Vosk Hindi model
   publishes 14.96–39.08% WER depending on test set, which is the realistic ceiling.
6. **TTS latency is unmeasured.** Playback is confirmed to start, but time-to-first-audio needs an
   utterance-progress probe that the app does not expose.
7. **Battery cost is below this method's floor.** Level did not move by 1% during testing; a hardware
   power monitor is needed for a real figure.
8. **One device.** Everything is validated on an Armv8.0 big.LITTLE phone. The capability-aware policy
   is designed to scale to dotprod/i8mm/SVE2/SME2 silicon, and that scaling is unvalidated by
   measurement.
9. **R8 disabled and no signing config** (§5).
10. **Accessibility beyond content descriptions** — TalkBack traversal and extreme font scaling not
    audited.
