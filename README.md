# BhashaBridge V4

Offline English ↔ Hindi speech translation for Android. Everything — recognition, translation and
speech — runs on the device. The app requests no network permission.

Built for the **Arm AI Optimization Challenge** (Mobile AI track). The engineering story is the
runtime: a transformer decoder that shipped with no KV cache was re-exported, quantized, tuned and
made capability-aware, and every step is backed by on-device measurements across nine Arm devices.

**Judges start here → [`SUBMISSION.md`](SUBMISSION.md)** — the challenge criteria mapped to the
evidence, one section per category.

| | |
|---|---|
| ![Translation screen](docs/images/main-screen.png) | ![Emergency phrases](docs/images/emergency-phrases.png) |

## What it does

- **Type or speak** in either direction — English → Hindi and Hindi → English, both running from
  verified cached INT8 graphs.
- **Live speech**: Vosk recognition with a running waveform and streaming translation of partial
  results, then spoken output through the system TTS voice.
- **Emergency phrases**: 32 human-translated pairs across four categories, instant, no model on the
  path — for the case where a translation must be right and immediate.
- **Audio import**: transcribe and translate a recorded file.
- **History**, **app language** (English / हिंदी), and a **Model & device** panel that reports the
  detected CPU and the ORT policy derived from it.

## Measured performance

Baseline device is the SM-M315F (Exynos 9611, 4×Cortex-A73 + 4×Cortex-A53, Armv8.0-A, Android 12) —
a 2020 budget phone, and the device most numbers in this repo were measured on. Rows marked
*(cross-device)* come from the nine-device campaign.

| | |
|---|---|
| Translation EN→HI, 12 tokens | **640 ms** median, p95 671 ms, stdev 23 ms |
| Translation HI→EN, 12 tokens | **76.7 ms** median *(S26 Ultra)* |
| Time to first token | 78.5 / 107.1 / 139.5 ms at 2 / 6 / 12 tokens |
| KV-cache speedup | 1.06× @2 tokens → **2.12× @12 tokens** |
| tokens/sec | rises 14.9 → 21.6 with length (uncached *falls* 13.9 → 9.5) |
| Engine ready (first translation) | 27.0 s → **~5.1 s** across the startup work |
| Model size | 1869 MB fp32 → **472 MB INT8** (3.96×) |
| Process memory | ~605–670 MB PSS steady state, one direction |
| Same APK across the ecosystem *(cross-device)* | **50.3 → 412.8 tokens/sec**, Armv8.0 → Armv9, no recompile |

Full evidence: [`SUBMISSION.md`](SUBMISSION.md) for the criteria map,
[`docs/OPTIMIZATION_SUMMARY.md`](docs/OPTIMIZATION_SUMMARY.md) for every optimization kept and
reverted.

> Numbers on this device move with its temperature: the same build read 640–864 ms on the 12-token
> sentence across one afternoon as the phone warmed. Comparisons under ~10% are not readable without
> a temperature. See `SUBMISSION.md` § *Benchmark method*.

## Build

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
./gradlew assembleDebug          # or installDebug with a device attached
```

Requires Android SDK 36, JDK 17, and a device or emulator on API 24+.

**Model assets are not in git** (909 MB). `app/src/main/assets/` must contain:

| Asset | Source |
|---|---|
| `encoder_int8.onnx`, `decoder_init_int8.onnx`, `decoder_step_int8.onnx` | EN→HI — produced by `model_pipeline/cached_export.py` + `quantize_cached.py` |
| `hi_en_encoder_int8.onnx`, `hi_en_decoder_init_int8.onnx`, `hi_en_decoder_step_int8.onnx` | HI→EN — same pipeline, from `ai4bharat/indictrans2-indic-en-dist-200M` |
| `dict.SRC.json`, `dict.TGT.json`, `dict.SRC_HI.json`, `dict.TGT_EN.json` | IndicTrans2 vocabularies |
| `model/`, `model-hi/` | Vosk small English (Indian) and Hindi models |

Regenerating the ONNX graphs is documented in
[`model_pipeline/EXPORT_WITH_CACHE.md`](model_pipeline/EXPORT_WITH_CACHE.md).

## Architecture

```
MainActivity / WelcomeActivity      renders state, forwards taps
        ▼
TranslateViewModel                  owns direction, translation, speech, history
        ▼
BhashaBridgeApp                     process-scoped owner of every native resource
        ▼
MtEngine → encoder / decoder_init / decoder_step   (INT8, KV-cached, ONNX Runtime)
```

No Activity can reach the runtime; native resources are owned once, at process scope, with a single
release trigger. The rules are written down and enforced, not assumed —
[`docs/ARCHITECTURE_RULES.md`](docs/ARCHITECTURE_RULES.md),
[`docs/DEPENDENCY_RULES.md`](docs/DEPENDENCY_RULES.md),
[`docs/CODING_STANDARDS.md`](docs/CODING_STANDARDS.md).

## Documentation

| Document | What it covers |
|---|---|
| [SUBMISSION](SUBMISSION.md) | **Start here.** The challenge criteria mapped to the evidence, one section each |
| [OPTIMIZATION_SUMMARY](docs/OPTIMIZATION_SUMMARY.md) | The submission's technical report: every optimisation, kept and reverted |
| [CROSS_DEVICE_REPORT](bench/results/cross-device/CROSS_DEVICE_REPORT.md) | Nine devices, Armv8.0 → Armv9, with its corrections and retractions |
| [V3_VS_V4_COMPARISON](docs/V3_VS_V4_COMPARISON.md) | What the rewrite bought, measured — including where it is worse |
| [AUDIT_2026-08-06](docs/AUDIT_2026-08-06.md) | Engineering audit of `:app`, and the defects it found |
| [KV_CACHE_RUNTIME](docs/KV_CACHE_RUNTIME.md) | How the cached decoder runs on device |
| [CACHE_BENCHMARK](docs/CACHE_BENCHMARK.md) | Cached vs uncached, measured |
| [ORT_TUNING](docs/ORT_TUNING.md) | One-variable-at-a-time SessionOptions sweep |
| [ARM_PLATFORM_OPTIMIZATION](docs/ARM_PLATFORM_OPTIMIZATION.md) | CPU detection and the policy derived from it |
| [UI_RECONSTRUCTION](docs/UI_RECONSTRUCTION.md) | The presentation layer, and what deviates from v3.4.1 |
| [VALIDATION_REPORT](docs/VALIDATION_REPORT.md) | Functional and performance validation results |
| [JUDGE_SCORECARD](docs/JUDGE_SCORECARD.md) | Self-assessment against the challenge criteria |
| [LESSONS_FROM_V3](docs/LESSONS_FROM_V3.md) | What the previous version got wrong, and why V4 is shaped this way |

## Known limitations

- **909 MB of assets.** Too large for Play without asset delivery or a first-run download; side-load
  works today. This limits reach more than any technical factor here.
- **~5 s from launch to first translation** — the tokenizer parse and three ONNX sessions, behind a
  progress screen. Down from 27 s, still the worst user-facing number in the project.
- **Release builds cannot ship.** Signed with the SDK debug key, R8 disabled, `versionCode` never
  incremented. See `docs/AUDIT_2026-08-06.md` H4.
- **Portrait only** — the landscape layout was found unusable and the orientation is locked rather
  than left broken.
- **Speech accuracy and TTS latency are unmeasured.** No word-error rate against human voices; the
  bundled Vosk Hindi model publishes 14.96–39.08% WER depending on test set.

## Licence and attribution

This project's own source is available under the MIT licence. Bundled and depended-on components
keep their own licences — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
