# SM-S948B (S26 Ultra) session plan — prepared 2026-08-12, to run 2026-08-13

Everything in this file exists because **2026-08-12 changed the shipping artifact**, and most of the
S26 Ultra's existing results were measured against the format that no longer ships. Re-running is not
box-ticking here: three of the runs below could genuinely come out differently.

## What changed since the S26 Ultra was last measured (§3.38–§3.42, `2a5fb92`)

| change | ledger | why the S26U specifically must re-check it |
|---|---|---|
| Baked artifact is **optimized ONNX over a shared blob**, not `.ort` | §3.47 | The bake is `ALL_OPT` and environment-specific — this device bakes its own. Never run here. |
| `mappedInitializers` **deleted** | §3.47 | It shipped between §3.44 and §3.47 and is gone; the memory profile here is unmeasured. |
| Weight blobs **DEFLATE'd in the APK** | §3.54 | First-launch inflate on much faster storage — cost may be near zero here. |
| Target vocabulary **indexed, not expanded** | §3.56 | Tokenizer was 474 ms on the M31; expect far less on Oryon. |
| **KleidiAI off on SME parts** | §3.40 | ⚠ **The 12.7% regression that justified this was measured on the `.ort` format.** The artifact has changed underneath the decision. |

## Pre-flight

```powershell
$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $ADB devices -l                      # expect SM_S948B
& $ADB shell dumpsys battery | findstr temperature
& $ADB shell cat /proc/cpuinfo | findstr Features      # expect sme, sme2, i8mm, asimddp
```

Build and install both APKs (arm64):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
& $ADB install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
& $ADB install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

**Record battery temperature next to every number.** The same code read 640 ms and 864 ms on
temperature alone (`bhashabridge-thermal-benchmark-rule`). Let the device idle to a stable temperature
between heavy runs, and note it.

---

## Run order — highest value first, so a short session still lands the important results

### 1. Correctness + the headline number (~15 min) — **do this first**

```powershell
foreach ($t in "MtEngineInstrumentedTest","HiEnEngineTest","OptCacheTest","VocabCacheTest") {
  & $ADB shell am instrument -w -e class com.bhashabridge.app.mt.$t `
      com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
}
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.BenchmarkSuiteTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Suite
```

**Compare against:** entry #9 = 99 ms median / 412.8 tok/s; §3.40 (post-KleidiAI-fix) = **78 ms /
565.4 tok/s**. Storage should read **~280 MB, not 473 MB** (§3.47). Output must be `पानी ।`.

### 2. Cold-start series — the C1 + Q21 + Q23 stack (~10 min)

```powershell
1..4 | ForEach-Object {
  & $ADB shell am force-stop com.bhashabridge.app
  & $ADB logcat -c
  & $ADB shell am start -n com.bhashabridge.app/.ui.MainActivity
  Start-Sleep -Seconds 22
  & $ADB logcat -d -s BB.Bench | Select-String engine_init | Select-Object -Last 1
}
```

Run 1 rebuilds the caches (new install ⇒ new `lastUpdateTime` stamp) — **discard it**, it is the bake
launch. Runs 2–4 are the measurement.

**M31 reference:** tokenizer 474 ms (tgt 186, src 324), `sessions:parallel` 1683 ms,
`engine_init` 2304 ms.

### 3. ⚠ KleidiAI re-test on the new artifact (~20 min) — **the one that could change a shipped decision**

`ExecutionPolicy` currently sets `disableKleidiAi = caps.sme` on the strength of §3.39: −10.1%, −13.0%,
−12.7% in favour of OFF, measured on the **`.ort`** artifact. That artifact is gone. §3.42 exonerated
the load path and the shared blob for §3.20's contradiction, which is evidence the finding is about the
kernels rather than the format — but it was not tested against optimized ONNX, and this is the only
device where the flag does anything.

```powershell
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.ExecutionProviderProbeTest#whichProvidersExistAndDoAnyBeatMlas `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Q7
```

That probe runs `cpu_mlas` / `nnapi` / `xnnpack` / `mlas_bf16_fastmath` / `kleidiai_off`, 3 rotated
rounds, n=10, with parity checked per arm. Note it runs `optCache = false`, i.e. **raw graphs** — so
for the shipping-path answer also run the production sweep:

```powershell
& $ADB shell am instrument -w -e rounds 3 -e runs 10 `
    -e class com.bhashabridge.app.mt.ProductionThreadSweepTest#sweepThreadCounts `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

**If KleidiAI-off is no longer a win on this format, `ExecutionPolicy.select` must be revisited before
submission.** That is the single highest-consequence outcome of the session.

### 4. Memory: the Q21 format on a big-RAM device (~10 min)

```powershell
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.SharedWeightRuntimeTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.ExternalInitializerProbeTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Q24 -s BB.Q24B
```

**M31 reference (§3.55):** decoders share **884 KB of 323,756** — i.e. nothing;
`addExternalInitializers` costs **+64.7 MB**. If ORT behaves the same here, the conclusion generalises
beyond one device, which is worth stating in the submission.

### 5. Thread clamp on the only topology that derives 4 (~25 min)

```powershell
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.ProductionThreadSweepTest#sweepExecModeAndDegradation `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

**§3.38 reference:** 8 uniform Oryon cores ⇒ `perfCores/2 = 4`; `intra4` was **+7.4% long / +19.2%
short** against the clamped `intra2` at 2.1× the CPU. This is the only device that can test the clamp's
*bound* rather than its default. Re-confirming it on the new artifact keeps §3.37's KEEP honest.

### 6. Lifecycle under repetition (~5 min)

```powershell
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.DirectionSwitchStressTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Q22
```

**M31 reference (§3.51):** 100 switches, PSS drift −8 MB, native heap drift −30 KB, 0 crashes.

### 7. Optional, if time — pressure behaviour (~15 min)

```powershell
& $ADB shell am instrument -w -e pressureMb 3072 `
    -e class com.bhashabridge.app.mt.PressureReclaimTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

12 GB of RAM here vs 5.7 GB on the M31, so the LMK threshold will differ; `-e pressureMb` exists for
exactly that. §3.50's finding to re-check: zero major faults, zero swap-ins, anon barely reclaimed.

---

## Recording

One file per topic under `bench/results/cross-device/`, following the existing entries' shape: device,
ISA, thermals, arms, per-round medians, and an explicit **caveats** section. Update
`S26U_EXPERIMENTS.md` for anything that supersedes an earlier finding there, and add a §3.x ledger
entry for anything that changes a decision.

**Do not edit an old entry in place** — supersede it and say so, per §0. Entry #9 and §3.38–§3.42 are
part of the audit trail.

## Known trap on this device

`affinityString` returns null on a uniform-IP part (all 8 cores one frequency), so `affinity=true` arms
silently become duplicates of the no-pin arms. That is free repeatability data — two identical arms
measure the run's own noise floor (3.2% and 0.0% previously) — but do not report it as an affinity
result.
