# Third-party notices

BhashaBridge V4 bundles and depends on the components below. Each keeps its own licence; this file
records what is used and where it came from. Licence texts are not reproduced here — follow the
links for the authoritative terms, and confirm them against the exact artifact versions you ship.

## Runtime libraries

| Component | Version | Licence | Used for |
|---|---|---|---|
| [ONNX Runtime (Android)](https://github.com/microsoft/onnxruntime) | 1.17.1 | MIT | Executes the encoder and both decoder graphs; MLAS supplies the INT8 kernels |
| [Vosk](https://github.com/alphacep/vosk-api) | 0.3.47 | Apache-2.0 | On-device speech recognition |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.7.3 | Apache-2.0 | Concurrency for the UI and speech pipelines |
| AndroidX (core-ktx, appcompat, activity, constraintlayout, drawerlayout, lifecycle, recyclerview, cardview) | see `gradle/libs.versions.toml` | Apache-2.0 | Platform support libraries |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.11.0 | Apache-2.0 | Theme and widgets |
| [JUnit 4](https://junit.org/junit4/) | 4.13.2 | EPL-1.0 | Tests only, not shipped |
| AndroidX Test / Espresso | see catalog | Apache-2.0 | Tests only, not shipped |

## Models

| Model | Origin | Licence | Notes |
|---|---|---|---|
| **IndicTrans2** distilled 200M (en→indic) | [ai4bharat/indictrans2-en-indic-dist-200M](https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M) | MIT | Re-exported to ONNX with KV-cache ports and dynamically quantized to INT8 by `model_pipeline/`. The shipped graphs are derivatives of this checkpoint. |
| IndicTrans2 vocabularies (`dict.*.json`) | same checkpoint | MIT | Converted to flat JSON for the on-device tokenizer |
| **Vosk small English (Indian)** — `assets/model/` | [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) | Apache-2.0 | Bundled README: "Indian English model for mobile Vosk applications" |
| **Vosk small Hindi** — `assets/model-hi/` | [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) | Apache-2.0 | Bundled README: "Hindi small model for Vosk"; published WER 14.96–39.08% by test set |

Model binaries are **not** committed to this repository (see `.gitignore`); they are staged locally
during a build. Redistributing them in an APK carries the obligations of the licences above.

## Attribution notes

- IndicTrans2 is the work of [AI4Bharat](https://ai4bharat.iitm.ac.in/). If you publish results
  based on it, cite their paper as their repository requests.
- Vosk is the work of [Alpha Cephei](https://alphacephei.com/).
- The correction tables in `speech/AsrCorrector.kt` are hand-written for this project and carry no
  third-party terms.

## Assets created for this project

The BhashaBridge wordmark (`res/drawable/logo_bhashabridge.png`), the launcher icons, and the
emergency phrase pairs were produced for this project and are covered by its own licence.
