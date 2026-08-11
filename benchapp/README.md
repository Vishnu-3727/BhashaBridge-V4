# Arm CPU smoke test (`:benchapp`)

A standalone Android app that characterises a device's Arm CPU and, when the models are present, the
INT8 inference stack and the speech recogniser BhashaBridge actually runs. One screen, one dial, one
JSON report.

The result screen leads with the single number that matters (translation latency, or the int8 score
when no models are staged), then grouped cards — Inference, Speech, CPU throughput, Load & thermal,
Device, Process — and a bar chart of which cores actually ran the work, big cores in the accent
colour and little cores dimmed.

The three model phases are independent. A device with only the ONNX graphs staged runs translation
and skips speech; a device with only the acoustic model does the reverse; a device with neither still
returns a full CPU, thermal and core-usage characterisation. The banner says which, per phase,
before the run rather than after.

It exists because `app/src/androidTest/…/BenchmarkSuiteTest` — which measures much of the same
ground — needs a host PC, a Gradle daemon and `adb` per device. This installs and runs from the
launcher, so a phone can be characterised by whoever is holding it.

**No permissions. No network. ~34 MB.**

## What it measures

| Group | Measured |
|---|---|
| **Device** | model, board, SoC, Android release/API, ABI |
| **CPU** | architecture, core count, P/E split and the real cpu ids, ISA flags (`neon` `fp16` `dotprod` `i8mm` `sve` `sve2` `sme` `sme2`) |
| **Synthetic** | int8 dot product, fp32 GEMM, int32 dependent chain — each at 1 thread and at the full performance cluster, with the scaling ratio |
| **Tokens** | tokens in, tokens generated (counted by decoding to EOS, not re-encoding output) |
| **Inference** | end-to-end translate, encoder, `decoder_init`, `decoder_step` per token, tokens/second, session load |
| **Speech** | Vosk recognition of a fixed utterance: **realtime factor**, median and p95 latency, model load, and the decode width that produced them |
| **Leak** | file descriptors, threads and native heap across 12 back-to-back recognition sessions |
| **ISA proof** | KleidiAI A/B — the measured worth of Arm's INT8 microkernels on this silicon |
| **Sustained** | 60 s continuous load, median per 10 s window, latency drift |
| **Thermal** | battery temperature series, peak, rise; thermal zones where readable |
| **Power** | charge drain, mean current, and the plug state that says whether those are valid |
| **Cores** | share of this process's thread-instants per core, marked P/E; peak clock per core; sustained clock against the run's own peak |
| **Threads** | peak thread count, process CPU ticks |
| **Memory** | full `SystemStats` snapshot before and after |

## Running it

```bash
./gradlew :benchapp:assembleDebug
adb install -r benchapp/build/outputs/apk/debug/benchapp-arm64-v8a-debug.apk
```

Pick an intensity, then press **RUN**. Without models it runs the CPU phase only and says so.

### Intensity presets

One dial, four steps. Every budget moves together — a long soak after an 8-iteration latency sample
proves something the sample is too small to attribute.

| Preset | Per synthetic kernel | MT iterations | Speech iterations | Sustained | ~Full run | Answers |
|---|---|---|---|---|---|---|
| **Light** | 1.5 s | 8 | 3, no leak churn | — | ~1 min | is this device broken or merely slow |
| **Standard** | 4 s | 20 | 5 | 60 s | ~3 min | the comparable number (**every published baseline**) |
| **Heavy** | 8 s | 40 | 10 | 180 s | ~6 min | does it throttle, and how far |
| **Torture** | 15 s | 60 | 15 | 600 s | ~14 min | what it still delivers with its thermal budget spent |

Speech gets a quarter of the MT iteration count deliberately: one recognition takes 1.5–2 s against
~0.5 s for a translation, and its run-to-run spread is far tighter (stdev under 2% on the M31 against
5–10% for MT). Matching the counts would triple the phase's wall time to sharpen a median that is
already sharp. Light skips the leak churn for the same reason it skips the soak — it is a fixed cost
that says nothing about speed.

**Reports are only comparable within one preset.** The synthetic ops/sec is a rate, so it does not
scale with the budget — but a Torture run measures it on a hot core and a Light run on a cold one,
which is the difference the preset exists to expose. The preset travels in the JSON
(`preset.{name,syntheticMinMs,mtIterations,sustainedSeconds}`) and on the first row of the Device
card. A report without that block predates presets and is a Standard run.

With no models staged, the soak budget moves into the synthetic phase — otherwise Torture on an
un-staged device would heat the phone for 90 s and call it a soak. The KleidiAI A/B is a separate
checkbox, not part of the dial: it is an ISA question, not an intensity one.

### Enabling the inference phase

The APK ships no models — that is what keeps it small enough to hand around. Push the graphs once
per device; the app prints the exact path on screen:

```bash
D=/storage/emulated/0/Android/data/com.bhashabridge.bench/files/models
adb shell mkdir -p $D
for f in encoder_int8.onnx decoder_init_int8.onnx decoder_step_int8.onnx dict.SRC.json dict.TGT.json; do
  adb push app/src/main/assets/$f $D/$f
done
```

### Enabling the speech phase

The acoustic model is a directory, and it stages the same way — `AssetFolder.unpack` returns early
when `filesDir` already holds the model, so the production loader runs unmodified against a
sideloaded copy and never learns this APK ships no model.

```bash
D=/storage/emulated/0/Android/data/com.bhashabridge.bench/files/models
adb shell mkdir -p $D
adb push app/src/main/assets/model $D/          # English, 56 MB
adb push app/src/main/assets/model-hi $D/       # Hindi, 81 MB — only for a HI→EN run
```

The audio is **not** sideloaded: an 85 KB WAV ships in the APK, because two phones can only be
compared on the same waveform and a phone recording a quiet office is not a controlled input.

Reopen the app; it will report which phases are staged. Run length is then the preset's. **Clear**
removes the staged copies, the acoustic models and the baked `.ort` cache (~470 MB of graphs plus up
to 137 MB of models).

> On Git Bash / MSYS, prefix `adb` commands with `MSYS_NO_PATHCONV=1` or the device path is rewritten
> into a Windows path and the push silently lands somewhere else.

### Getting the report out

**Export** opens the share sheet (via `FileProvider`, no storage permission). Or:

```bash
adb pull /storage/emulated/0/Android/data/com.bhashabridge.bench/files/reports/
```

Schema is `bb-smoke/1`. `device`, `systemBefore` and `systemAfter` keep the same shape as
`BenchmarkSuiteTest`'s `bb-bench/1`, so anything reading those blocks works unchanged.

## How it shares code with `:app`

The module compiles **`:app`'s own** `mt/` and `bench/` sources — `MtEngine`, `OnnxModels`,
`Tokenizer`, `GreedyDecoder`, `CpuCapabilities`, `ExecutionPolicy`, `SystemStats`, `Stats`. A
benchmark that reimplements what it measures reports on the reimplementation; this cannot drift,
because a `Sync` task mirrors those files on every build and deletes anything removed upstream.

Consequences worth knowing:

- The module's `namespace` is `com.bhashabridge.app` — the shared files import
  `com.bhashabridge.app.BuildConfig`. The **`applicationId`** is `com.bhashabridge.bench`, so it
  installs alongside the real app rather than replacing it.
- Edit the shared files in `app/src/main/java`. Edits under `benchapp/build/shared-inference-src`
  are overwritten on the next build.
- `ui/` and `BhashaBridgeApp.kt` are excluded — the first needs layouts and string resources, the
  second is the app's process-scoped resource owner and this module owns its resources per phase.
  `speech/` **is** shared: it was excluded originally on the grounds that it "needs Vosk", and it
  does, but that turned out to be the only thing it needs — not one file under it references `R`, a
  layout or an Activity. Excluding it meant this app could characterise a phone's CPU, thermal
  envelope and translation latency while saying nothing about the recogniser, on a device where the
  recogniser is half the pipeline.

Models are found without changing `:app`: `ModelStore` hard-links the staged graphs into `filesDir`,
which is exactly where `OnnxModels` already looks before falling back to assets. `Tokenizer.load`
gained the same file-then-asset rule so the shipping tokenizer works here too.

## Reading the numbers

- **`optCache = false`.** The production loader bakes an ALL_OPT `.ort` once per install and mmaps it
  NO_OPT after; that changes *load* time, not the executed graph. Disabling it keeps every run's
  session build identical, so `Session load` here is **not** the app's warm-start figure.
- **The speech headline is a ratio, not a latency, and the threshold is 1.0.** Recognition runs
  *while the person is still talking*, so nobody waits for it the way they wait for a translation —
  the only question is whether it keeps up. Below 1.0× it does, and the tail after they stop is all
  they wait for. Above 1.0× the backlog grows for as long as they keep speaking, so a phone at 1.9×
  turns a ten-second sentence into nine seconds of silence afterwards. That is a cliff, not a
  gradient, which is why the card reports distance from 1.0 and flags anything at or above it.
- **A realtime factor without its decode width is not comparable.** `max-active` and `beam` are the
  recogniser's speed/accuracy dial and the shipped English and Hindi models do not use the same
  values, so the card and the JSON both carry the width that produced the number. See
  `docs/OPTIMIZATION_SUMMARY.md` §3.34, where the Hindi model measured 1.91× at 10 dB SNR before
  being narrowed.
- **The leak row is fd-led on purpose.** `AudioRecord`, each audio effect, `MediaCodec` and
  `MediaExtractor` all hold a kernel object with a descriptor apiece, so a missed release is a
  straight line in `/proc/self/fd` that no allocator can blur. The native-heap delta beside it
  routinely goes *negative* — Android returns pages lazily — and is reported for completeness, not
  as the verdict.
- **KleidiAI ≈ 1.00× is a result, not a failure.** It means the core has no microkernel path for
  these shapes. Measured 0.99× on the SM-M315F, which is ARMv8.0 with NEON only — no `dotprod`, no
  `i8mm`, nothing for KleidiAI to dispatch to.
- **Core share is this process, not the system.** `/proc/stat` has been SELinux-blocked for apps
  since Android 8 and reads back empty rather than failing. The shares come from every thread's
  `processor` field in `/proc/self/task/*/stat`, which is the better question anyway: it says where
  *the benchmark* ran, not whether a core was busy with the launcher.
- **Battery drain is only valid unplugged.** The digest marks it `INVALID` and names the power source
  when it is not.
- **Thermal zones are usually unreadable.** `/sys/class/thermal` is SELinux-blocked on most devices;
  the app stops after the first refusal rather than flooding the kernel audit log. Battery
  temperature is not restricted and remains the thermal signal.
- **Synthetic kernels do not prove ISA use.** They are Kotlin; instruction selection is ART's. They
  give a fair relative score and a real thermal load. The only ISA claim this app makes is the
  KleidiAI A/B.

## Baseline: SM-M315F (Exynos 9611, ARMv8.0, Android 12) — Standard preset

```
int8_dot      175.3M op/s 1t → 564.5M op/s 4t (3.22× scaling)
fp32_gemm     269.1M op/s 1t → 797.3M op/s 4t (2.96× scaling)
Tokens        16 in → 12 out
Translate     651.0 ms median, p95 683.0 ms
Encoder       83.0 ms · decoder_init 50.0 ms · decoder_step 40.0 ms/token
Throughput    18.3 tokens/s
KleidiAI      1.02×
Sustained     656.0 → 655.0 ms (1.0× drift), clock held 92% of peak
Speech        1469.0 ms median for 2.645 s of audio → 0.56× realtime
              max-active 3000 · beam 10.0 · model load 1255 ms
Leak          12 sessions · fds +1 · threads 0 · native +24 KB → clean
```

Two independent cross-checks, and they are the point of the shared-source arrangement rather than
decoration:

- Translate lands on top of `docs/OPTIMIZATION_SUMMARY.md`'s separately measured 667 ms median /
  686 ms p95 at 12 tokens.
- Speech reads **0.555×** here against **0.547×** from `AsrTuningBenchmarkTest` on the same device
  (§3.33) — a 1.5% gap between an instrumented test driven over `adb` and an app a person taps.

Two different harnesses, same device, same answers.
