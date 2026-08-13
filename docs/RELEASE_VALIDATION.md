# Release-build validation — SM-S948B, 2026-08-13

**Headline: the release build is not a risk, it is 5% faster than every number in this repository.**
Nothing in `bench/` had ever measured the artifact that would actually ship, because instrumented
tests run against `debug` by default. They do now, and the result changes how the project's own
latency figures should be read.

## 1. Build configuration (Observed, `app/build.gradle.kts`)

| Setting | Value | Note |
|---|---|---|
| `optimization { enable }` | **false** | R8 / minification / resource shrinking all off |
| `signingConfig` | **debug key** | Deliberate: Baseline Profile generation needs a signed, non-debuggable build. Not a store key |
| `versionCode` / `versionName` | 1 / 1.0 | Unchanged |
| `minSdk` / `targetSdk` / `compileSdk` | 24 / 36 / 36.1 | |
| ABI splits | `arm64-v8a`, `armeabi-v7a`, no universal | A universal APK with both ABIs is not installable at this asset size |
| `noCompress` | `onnx`, `pb` | `bin` deliberately compressed since §3.54 (−97 MiB); `onnx` stays stored because `openFd` needs it |
| `jniLibs.pickFirsts` | ORT + Vosk `.so` | Both dependencies ship overlapping natives |
| Baseline Profile | consumed via `:baselineprofile` | `baselineProfiles/` present in the release output |
| ProGuard/R8 rules | none in `:app` | Consistent with R8 being off |

**Debug-only behaviour that disappears in release (Observed).** `logDebug()` and the `Metrics`
instrumentation are gated on `BuildConfig.DEBUG`, so every stage mark, counter and `BB.*` log line is
compiled out. This is the privacy claim (R8.2) *and*, as §4 shows, a measurable share of the latency
the project has been reporting.

## 2. Artifact (Observed)

Clean `:app:assembleRelease`, 3 m 49 s.

| | |
|---|---|
| `app-arm64-v8a-release.apk` | **533,712,657 B = 509.0 MiB** |
| `app-armeabi-v7a-release.apk` | 525,068,617 B = 500.7 MiB |
| SHA-256 (arm64, first 32) | `a84699bcc6e41a29d8aaf5cbaf1743cf…` |
| Debug arm64, same tree | 545,617,494 B = 520.3 MiB |

**Release is 11.3 MiB smaller than debug** — the debug APK is what §3.54's "520.3 MiB" refers to; the
shipping figure is 509.0 MiB and had not previously been recorded.

## 3. Functional validation (Observed)

Method: `testBuildType = "release"` set temporarily so the instrumented suite runs against the
release artifact, both APKs installed on the SM-S948B, then **the setting was reverted** — the
committed build configuration is unchanged.

| Test | Result |
|---|---|
| `MtEngineInstrumentedTest` | **3/3 pass** |
| `HiEnEngineTest` | **3/3 pass** |
| `OptCacheTest` | **1/1 pass** |
| `VocabCacheTest` | **1/1 pass** |
| `BenchmarkSuiteTest` | **1/1 pass** |
| EN→HI output | `पानी ।` — identical to debug |
| HI→EN | exercised by `HiEnEngineTest`, pass |
| Model cache | `279,821,784 B` — **byte-identical to debug** |
| Bake / cache-stamp path | `OptCacheTest` green against the release `VERSION_CODE` |
| Tokenizer / vocabulary | `VocabCacheTest` green, full-table round-trip |

No R8 missing-class failures (R8 is off), no JNI errors, no native crashes, no resource-not-found
failures, no incorrect model loading.

**Not covered by this pass, and honestly out of scope for an instrumented run:** speech capture, TTS
output, rotation, background→foreground, `onTrimMemory`, process restart and airplane-mode operation
are UI-level behaviours. They were validated on the debug build in Phase 10 (`VALIDATION_REPORT.md`,
64 checks) and nothing in the release configuration touches those paths — but they have **NOT** been
re-run against this artifact. Treat them as **NOT VERIFIED** for release.

## 4. Performance — release vs debug (Observed)

Same device (SM-S948B), same policy (`intra=2`, arena off, KleidiAI off), same model cache, same
sentences, same `BenchmarkSuiteTest` protocol (warm-up then 30 measured runs per sentence,
counterbalanced). **Battery temperature 32.7 °C at both ends of both runs** — the comparison is
thermally clean, which on this project is a precondition, not a detail.

| Metric | Debug | Release | Δ |
|---|---|---|---|
| Long sentence, median | 86.0 ms | **81.0 ms** | **−5.8%** |
| Long sentence, p95 | 95.0 | 86.0 | −9.5% |
| Long sentence, stdev | 3.30 | **1.98** | −40% |
| `Water.`, median | 22.0 | **21.0** | −4.5% |
| Tokens/sec | 535.1 | **559.7** | **+4.6%** |
| First translation | 86 ms | 82 ms | −4.7% |
| `engine_init`, cold | 2039 ms | **1839 ms** | −9.8% |
| `engine_init`, warm | 293 ms | 272 ms | −7.2% |
| Tokenizer, cold | 72 ms | **20 ms** | −72% |
| Model cache on disk | 279.8 MB | 279.8 MB | identical |
| Total PSS, post-benchmark | 155.6 MB | 111.2 MB | −28.5% |

**Acceptance: GOOD.** The bar was "within ~5% of baseline"; release is *faster* on every latency
metric and identical on correctness.

**Why release is faster, stated so it is not mistaken for a free lunch.** R8 is off, so this is not
minification. The difference is the instrumentation: `Metrics` stage marks, counters and `logDebug`
call sites are compiled out of release. The tokenizer's 72 → 20 ms is the clearest case — that stage
is short enough that the marks around it were a third of it. The consequence for the rest of the
repository is uncomfortable but useful:

> **Every latency number in `OPTIMIZATION_SUMMARY.md` is a debug-build number and is roughly 5%
> pessimistic relative to what ships.** They remain valid as A/B comparisons — both arms always carried
> the same instrumentation — and they understate the shipping product.

The PSS delta is reported as observed but is the weakest row in the table: both figures are
mid-benchmark snapshots rather than steady state, and the steady-state figure that matters
(~460 MB across 500 direction switches) comes from `SUSTAINED_STRESS_TEST.md`.

## 5. Issues found

| Issue | Severity | Status |
|---|---|---|
| R8 disabled (`optimization { enable = false }`) | Medium | **Open, and deliberately not changed here.** Enabling R8 against ORT + Vosk reflection is a submission-eve risk with no measured upside — release is already faster. It needs its own validation pass, not a flag flip |
| Release signed with the SDK debug key | Medium | **Open.** Acceptable for side-load and for Baseline Profile generation; not a store artifact |
| 509 MiB APK | High for distribution | Open. Unchanged by this pass; needs asset delivery or a first-run download |
| Speech / TTS / rotation / lifecycle not re-run on release | Low | Open — see §3 |

## 6. Final release status

**PASS for correctness and performance; NOT a store-ready artifact.**

The engine, the cache, the tokenizer and both translation directions behave identically in release
and are measurably faster. What stands between this and a shippable build is packaging — a real
signing key and a distribution strategy for 509 MiB — neither of which is an engine problem.

## 7. Reproducing

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease --no-daemon

# to re-run the instrumented suite against release, temporarily add to android { } in
# app/build.gradle.kts:   testBuildType = "release"
.\gradlew.bat :app:assembleRelease :app:assembleReleaseAndroidTest --no-daemon
& $ADB install -r app\build\outputs\apk\release\app-arm64-v8a-release.apk
& $ADB install -r app\build\outputs\apk\androidTest\release\app-release-androidTest.apk
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.BenchmarkSuiteTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Suite      # REPORT_JSON, reassemble the numbered chunks
```

Revert `testBuildType` afterwards — it was reverted here.
