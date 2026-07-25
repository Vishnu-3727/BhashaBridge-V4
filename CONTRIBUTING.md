# Contributing to BhashaBridge V4

Thanks for your interest. This is an on-device Android translation app with a
strict, documented engineering discipline. The rules below keep it that way.

## Project structure

```
app/                      the Android application module
  src/main/java/com/bhashabridge/app/
    ui/                   Activities + ViewModel — renders state, forwards taps, owns no engine
    mt/                   translation runtime — ONNX Runtime sessions, decoder, CPU policy
    speech/               Vosk recognition, audio capture, TTS
    bench/                measurement primitives (Stats, Metrics, SystemStats)
  src/test/               JVM unit tests (hermetic — no device, no model assets)
  src/androidTest/        instrumented + benchmark tests (need a device)
baselineprofile/          Macrobenchmark module that generates the startup Baseline Profile
model_pipeline/           Python: ONNX export, INT8 quantization, benchmark parsing
bench/results/            frozen, committed benchmark evidence (by path, never bulk)
docs/                     architecture rules, optimization reports, validation, lessons
```

Model binaries (~610 MB) are **not** in git — see `.gitignore` and the README build
section for how to stage them locally.

## Coding conventions

- Kotlin official style (`kotlin.code.style=official`); JDK 17.
- The architecture rules in `docs/ARCHITECTURE_RULES.md`, `docs/DEPENDENCY_RULES.md`,
  and `docs/CODING_STANDARDS.md` are **enforced, not aspirational**. Read them before
  a structural change. Key invariant: native resources (ONNX sessions, Vosk models)
  have exactly one owner — `BhashaBridgeApp`, at process scope — and one release
  trigger. UI and ViewModels borrow; they never construct or release an engine.
- Every class carries a short header comment: Purpose / Owns / Lifetime / Thread.
  Match that when you add one.
- Prefer graceful degradation over crashes on the runtime path (a failed load falls
  back; a failed translation returns a UI error state).

## Benchmark rules

- **Never fabricate or hand-edit benchmark numbers.** Every performance claim in the
  repo traces to an on-device run through `BenchmarkSuiteTest` / the `model_pipeline`
  parsers.
- State the device for any number you add. Numbers from different devices are not
  comparable and must not be averaged.
- Preserve benchmark behaviour when refactoring: warmup counts, iteration counts,
  counterbalanced ordering, and the `Stats` maths are load-bearing.
- Profiling is opt-in behind `OrtTuning.profileDir` and must stay zero-overhead when off.

## Commit conventions

- Conventional Commits: `type(scope): subject` — `feat`, `fix`, `perf`, `docs`,
  `build`, `test`, `chore`, `ci`.
- **One logical change per commit.** Do not mix unrelated changes. A dependency
  bump, a behaviour change, and a doc update are three commits.
- A change to the ML stack (ONNX Runtime / Vosk version) moves in its **own** commit,
  gated on a before/after benchmark (rule R7.3).
- Explain *why* in the body, not just *what*.

## Pull request expectations

- CI (build + unit tests) must pass. Instrumented and benchmark tests run on a device,
  not in CI — run them locally and paste the results when your change affects the
  runtime or a measured number.
- Keep PRs focused; a reviewer should be able to hold the whole change in their head.
- If a change is ambiguous or crosses an architecture rule, describe the trade-off in
  the PR before changing behaviour.
- No new runtime dependency without justification — this app ships offline and lean.
