# BhashaBridge V4 — Project Structure

The complete hierarchy. Four subsystem folders, one root package with a hard three-file cap.
Every folder below is justified against `ARCHITECTURE_RULES.md` §0: *what breaks today if this does
not exist?*

---

## The whole thing

```
BhashaBridgeV4/
├── docs/                          ← these documents
├── bench/                         ← host-side benchmark harness (shell + Python, not app code)
├── model_pipeline/                ← ONNX export + quantisation (Python, not app code)
└── app/src/main/
    ├── assets/                    ← model binaries, never committed
    ├── res/                       ← layouts, strings (en + hi), drawables
    └── java/com/bhashabridge/app/
        ├── BhashaBridgeApp.kt     ← Application. The process-scoped owner.
        ├── Direction.kt           ← EN_TO_HI | HI_TO_EN. The one shared type.
        ├── ui/
        ├── mt/
        ├── speech/
        └── bench/
```

Four folders. One entry point. Compare V3.4.1: eighteen Kotlin files in a single flat package, with
no folder telling you which of them mattered.

---

## Root package — `com.bhashabridge.app`

**Why it exists.** Two things are genuinely shared by every subsystem: the app's entry point, and the
translation direction. `Direction` is used by `mt` (which model), `speech` (which Vosk model, which
TTS voice), and `ui` (which labels). Putting it inside `mt/` would force `speech` to depend on `mt`
for a two-value enum — a dependency edge bought for nothing.

**What belongs inside.**
- `BhashaBridgeApp.kt` — the `Application`. Sole creator and destroyer of all native resources
  (`ARCHITECTURE_RULES.md` R4.4). Exposes lookup, not logic.
- `Direction.kt` — the enum, plus whatever trivially derives from it.

**Hard cap: three files.** This is the load-bearing part of the rule. "Shared" is not a filter —
everything can be argued into it. A number is a filter. When a fourth file wants in, someone has to
justify it out loud, which is exactly the conversation V3.4.1 never had before `FileUtils.kt`
appeared as the home for code that belonged nowhere.

**What must NEVER go inside.**
- Extension functions, string helpers, date formatters, `Constants.kt` — the attic starter kit.
- Anything used by only one subsystem. One user means it belongs to that user.
- Business logic of any kind. `BhashaBridgeApp` returns an engine; it never translates.

> **Note on a Phase 1 change.** `ENGINEERING_PLAN.md` §2.3 proposed a separate `MtEngineHolder`
> object. R10.1 rejects it: a holder that holds one thing is a synonym for the `Application` that
> already exists, and `Holder` is a shape name. The `Application` *is* the process-scoped owner.
> One fewer class, one fewer indirection, identical behaviour.

---

## `ui/` — everything the user sees or touches

**Why it exists.** Android's entry points (`Activity`) and the state that outlives configuration
changes (`ViewModel`) are one concern: presenting the app. They change together, for the same
reasons — a new screen affects both, a new model does not affect either.

**What belongs inside.**
- `MainActivity.kt` — view binding, listener wiring, rendering state. Nothing else.
- `TranslateViewModel.kt` — the **only** orchestrator. Decides what to translate and when, holds
  UI state, survives rotation. Owns no native resource (R4.4).
- `OnboardingActivity.kt`, `SetupActivity.kt` — first-run flow.
- `WaveformView.kt` — custom view, mic amplitude.
- `EmergencySheet.kt` — the emergency-phrase UI.
- `EmergencyPhrases.kt` — the static phrase table. It is presentation content with no behaviour, and
  it has exactly one consumer, one folder away.

**What must NEVER go inside.**
- `OrtSession`, `OrtEnvironment`, Vosk `Model`, `TextToSpeech` — or any reference to one. This is
  R4.4, and it is the single rule that would have prevented V3.4.1's 639 MB rotation leak.
- Tokenization, decoding, audio buffers, ONNX anything.
- User-visible strings as Kotlin literals. They go in `res/values/` and `res/values-hi/`.
  V3.4.1 shipped a `values-hi/strings.xml` and then bypassed it with inline `if (isHindi) "…" else "…"`
  pairs scattered through 961 lines, so the resource system existed and governed nothing.
- Orchestration inside `MainActivity`. The Activity renders; the ViewModel decides (R3.3).

---

## `mt/` — machine translation

**Why it exists.** The subsystem this app is judged on. It is the only place ONNX Runtime is
referenced, and the only place with a hot loop.

**What belongs inside.**
- `MtEngine.kt` — the subsystem's single public entry. `translate(text): TranslationResult`.
- `OnnxModels.kt` — session creation and the `release()` that `BhashaBridgeApp` calls. Nothing else
  may call it (R4.3).
- `GreedyDecoder.kt` — the decode loop. Held to zero-allocation-per-step (R11.2).
- `Tokenizer.kt` — text ↔ token ids.
- `RuntimeConfig.kt` — execution provider, thread count, affinity, KV on/off. One data class, swept
  by `bench/`.

**What must NEVER go inside.**
- Any `android.view`, `android.widget`, or `Context`-for-UI reference. `mt/` takes a `Context` only
  to reach assets, and nothing else.
- User-facing strings, including error messages. `mt/` returns typed failures (R12.3).
- A second decode strategy that nothing calls. Beam search is not being ported: 133 lines,
  zero callers, never validated. It returns only if Phase 8 quality data demands it, and then only
  behind `RuntimeConfig`, never on the user path.
- Sub-folders (R1.4). Five flat files is the whole subsystem.

---

## `speech/` — audio in and audio out

**Why it exists.** ASR and TTS are one subsystem: the app's audio boundary. They share the direction
concept, the same permission surface, and the same "is the model/voice actually installed" problem.

This folder is why `tts/` does not exist. The Phase 1 sketch had `tts/Speaker.kt` alone in its own
folder — R1.3 killed it, correctly.

**What belongs inside.**
- `SpeechInput.kt` — mic capture and Vosk recognition. In V3.4.1 this was split across
  `AudioCaptureController` and `VoskModelLoader`, which were separate classes that could not function
  without each other and were constructed with references to each other's executors.
- `AudioFileInput.kt` — imported file → 16 kHz mono PCM.
- `Speaker.kt` — TTS output.
- `TextCleanup.kt` — the ASR correction phrase tables.

**What must NEVER go inside.**
- Any reference to `mt/`. Speech produces text and consumes text; it does not know translation exists.
  `ui/` connects them. This keeps the dependency graph a tree (`DEPENDENCY_RULES.md`).
- View manipulation. `speech/` emits events; `ui/` decides what they look like.
- Ownership of the Vosk `Model`. Created and destroyed by `BhashaBridgeApp` (R4.4). `SpeechInput`
  borrows it and must not close it (R4.3).
- A `SpeechManager.kt`. V3.4.1's was 302 lines with zero references anywhere in the codebase. The
  name is banned by R10.1 and the file is not being ported.

---

## `bench/` — measurement

**Why it exists.** The Arm submission is judged on evidence. R13.1 requires instrumentation to land
before the optimization it measures, which means it needs a real home from the start, not a
`// TODO: measure this` in the decode loop.

**What belongs inside.**
- `Metrics.kt` — stage timers, TTFT, tokens/sec, allocation deltas. Structured JSONL to logcat.
- `BenchActivity.kt` — debug-only sweep runner, `adb`-launchable, exercises the `RuntimeConfig`
  matrix.

**What must NEVER go inside.**
- Anything reachable from a release build. `BuildConfig.DEBUG`-gated, and `BenchActivity` is
  `debug`-source-set only.
- Anything the app's behaviour depends on. Delete this folder and the app must still work
  identically — that is the test of whether measurement stayed separate from function.
- Timing code that lives in the hot path. R11.2 and R13.3 both forbid it.

---

## Non-app directories

### `docs/`
These five documents plus `ENGINEERING_PLAN.md`. Design decisions and rules only.

**Never:** per-phase status updates, changelogs, or reports narrating what the code does. V3.4.1
accumulated eleven root-level markdown reports — `Architecture_Reverse_Engineering_Report.md`,
`INDEPENDENT_AUDIT.md`, `SRP_ANALYSIS_MIGRATION_PLAN.md`, and more — describing code that kept
changing underneath them. Documentation that describes rather than governs goes stale silently and
then actively misleads. Git history is the changelog.

### `bench/` (repo root, host-side)
The existing shell/Python harness that produced the frozen baseline. It works. It is extended, not
rewritten.

### `model_pipeline/` (repo root)
ONNX export, KV-cache split, quantisation, offline graph optimisation. Python. This is where
Problem 1 is actually fixed — the decoder cannot cache because
`export_decoder_dynamic.py` exports a `forward()` with no `past_key_values` parameter. No amount of
Kotlin changes that.

**Never:** a checked-in Python virtualenv. V3.4.1 committed `translation_build/indic_env/` with the
full contents of numpy and onnx, headers and all.

### `app/src/main/assets/`
Model binaries at runtime. Covered by `.gitignore` from the first commit (R14.5).

---

## Growth rules

**Adding a file to an existing folder** — no approval needed if it obeys the folder's scope above.

**Adding a fifth subsystem folder** — requires appending a justification section to this document
and explicit approval. The bar: name the subsystem, name what breaks without it, and show it needs
two or more files (R1.3).

**Adding a file to the root package** — requires displacing one of the three, or a written argument
for raising the cap. Deliberately the hardest change in the project to make.

---

## Expected size

| Area | V3.4.1 | V4 target |
|---|---:|---:|
| `ui/` | — | ~750 |
| `mt/` | — | ~600 |
| `speech/` | — | ~520 |
| `bench/` | — | ~240 |
| root | — | ~60 |
| **Total Kotlin** | **3640** | **~2170** |

~40% smaller, with rotation-safety, explicit ownership, and instrumentation that V3.4.1 did not have.
The reduction is not cleverness — roughly 600 of the removed lines are code V3.4.1 was already not
executing.

These are targets, not quotas. Coming in under them by deleting something unjustified is a good
outcome. Coming in under them by cramming three responsibilities into one file is not
(`CODING_STANDARDS.md` §1).
