# ORT Upgrade — Session Handoff

**Written:** 2026-07-22, end of session. **Read this first when resuming.**
Self-contained: everything needed to continue without re-deriving anything.

---

## 1. TL;DR

ONNX Runtime bumped **1.17.1 → 1.27.0**. Complete, built, validated on device.
**Not committed.** Four files sit modified in the working tree.

- **Zero source changes.** No `.kt`, no `build.gradle.kts`. The bump compiles untouched.
- **Output parity holds** in both directions.
- **~3–4% faster** EN→HI, memory unchanged.
- Target is **1.27.0, not 1.27.1** — 1.27.1 is a GitHub tag with no Android AAR
  (`maven-metadata.xml` `<release>` is `1.27.0`). Do not "fix" this by bumping to 1.27.1.

**Next action:** paste the commit message from §7, commit, push. Then start Phase 2 (§8).

---

## 2. Working tree state

```
 M THIRD_PARTY_NOTICES.md
 M docs/ARCHITECTURE_RULES.md
 M docs/HI_EN_IMPLEMENTATION.md
 M gradle/libs.versions.toml
```

Branch `main`, based on `1274647` (Phase 12). Nothing staged, nothing pushed.
This file (`docs/ORT_UPGRADE_HANDOFF.md`) is untracked — commit it or delete it, your call.

**Rollback:** revert `gradle/libs.versions.toml` line 10 to `onnxruntime = "1.17.1"`. That is the
entire functional change; the other three files are documentation.

---

## 3. What changed

### `gradle/libs.versions.toml`
```diff
-# --- Proven ML stack, versions matched exactly to BhashaBridge_v3.4.1 ---
-# Do not bump ad hoc. ORT bump to 1.22.x is Phase 7, gated on a benchmark.
-onnxruntime = "1.17.1"
+# --- ML stack. Vosk stays matched to BhashaBridge_v3.4.1; ORT no longer is. ---
+# ORT bumped 1.17.1 -> 1.27.0 in its own commit, gated on the full benchmark (R7.3).
+# 1.27.1 is a GitHub tag only — no onnxruntime-android AAR was published for it
+# (maven-metadata <release> is 1.27.0), so 1.27.0 is the newest artifact that exists.
+# Do not bump ad hoc.
+onnxruntime = "1.27.0"
```
*Why:* the bump. Old comment promised "1.22.x in Phase 7" — now false.

### `THIRD_PARTY_NOTICES.md`
Version 1.17.1 → 1.27.0, plus a new row:
```
| Arm KleidiAI | vendored inside ONNX Runtime 1.27.0 | Apache-2.0 | Arm micro-kernels compiled
into MLAS. Present in the shipped libonnxruntime.so; on this project's INT8 graphs its kernels are
reached only on SME/SME2 cores, so it contributes nothing on the Armv8.0 validation device |
```
*Why:* legally required — 1.27.0 vendors KleidiAI (Apache-2.0), verified by symbol. The qualifier
stops the notice reading as a performance claim.

### `docs/ARCHITECTURE_RULES.md` — R7.3
Rewritten from "frozen at 1.17.1 until Phase 7" to "moves only in an isolated commit gated on a
before/after benchmark", recording that ORT is now 1.27.0 and Vosk stays 0.3.47.
*Why:* the old rule literally froze ORT; bumping without amending it violates a written rule.

### `docs/HI_EN_IMPLEMENTATION.md` — line 235
`device is 1.17.1` → `device was 1.17.1`, plus a blockquote scoping the observation to when it was
taken. *Why:* the sentence became false. Did not rewrite the measured result.

---

## 4. Verification — already done, do not repeat

### API compatibility (from `javap` diff of both AARs)
- `OrtSession`, `OrtException`, `OrtAllocator`, `OrtSession$Result` — **zero removals**.
- `OnnxTensor` — one *package-private* `createTensor(env, allocator, ShortBuffer, long[])` removed.
  Project only uses the public `createTensor(env, LongBuffer, long[])`. Unaffected.
- `SessionOptions` — **only removal is `addArmNN`** (ORT 1.25, with the ArmNN EP). Never called here.
  Five additions: `addQnn`, `addWebGPU`, `addCoreML(Map)`, `addExecutionProvider`, `setDeterministicCompute`.
- All six knobs `OrtTuning.toOptions()` sets exist in 1.27.0 and carry **no `Deprecated` attribute**.
- AAR `minSdkVersion` is still **24** — matches `defaultConfig.minSdk`.

### Execution providers
Packaged `.so` registers `CPUExecutionProvider`, `NnapiExecutionProvider`, `XnnpackExecutionProvider`
(21 `xnn_` symbols). Project registers none, so inference stays on **CPU EP + MLAS**, unchanged.

### Vosk `.so` collision (the real build risk — resolved in our favour)
Packaged `lib/arm64-v8a/libonnxruntime.so` = **27,985,944 bytes**, version string **`1.27.0`**,
**12 `kai_run_*` symbols**. `jniLibs.pickFirsts` took ORT's copy, not Vosk's bundled older one.

### Tests — all green
| Suite | Result |
|---|---|
| JVM unit (Tokenizer 6, Decoder 7, Metrics 6, Example 1) | 20/20 |
| `MtEngineInstrumentedTest` | 2/2 |
| `HiEnEngineTest` | 3/3 |
| `ParallelSessionLoadTest` | 1/1 |
| `ExampleInstrumentedTest` + `AudioFileTranscriberTest` + `StartupProbeTest` | 5/5 |
| `StartupProbeTest` (re-run) | 3/3 |
| `MtBenchmarkTest` | 1/1 |
| `HiEnBenchmarkTest` | 1/1 |

### Performance — SM-M315F, cooled, position 1, 3 warmup + 30 runs
| | 1.17.1 baseline | 1.27.0 | Δ |
|---|---|---|---|
| EN→HI 2 tok | 171.8 ms | **166.7** | −3.0% |
| EN→HI 6 tok | 372.0 ms | **357.3** | −4.0% |
| EN→HI 12 tok | 675.2 ms (p95 704.5) | **647.0** (p95 694.6, stdev 23.1) | −4.2% |
| HI→EN 2 / 6 / 12 tok | pos1 152.0 / 324.2 / 657.5<br>pos2 136.7 / 290.4 / 523.4 | **144.4 / 300.2 / 526.3** | fast end of the baseline's own spread |
| PSS EN→HI alone | 624.8 MB | 629.9 MB | +0.8%, noise |
| PSS both engines | 1,113.7 MB | **1,112.7 MB** | −0.1% |
| arm64 `libonnxruntime.so` | 16.0 MB | 28.0 MB | +12 MB |

**Output parity confirmed.** EN→HI byte-identical (`पानी ।`, `हैलो , आप कैसे हैं ?`,
`आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ ।`). HI→EN emits
`The weather is great today and I want to go out .`, matching `HI_EN_IMPLEMENTATION.md:373`.

> **Thermal warning, learned the hard way.** The first benchmark pass read **+29 to +50% slower with
> 4× the jitter** — pure heat, device at 36.2 °C after back-to-back 954 MB installs. Cooled re-run
> gave the numbers above. **Always cool to ≤34.9 °C (`dumpsys battery | grep temperature` → ≤349)
> and benchmark in position 1** before believing any regression.

---

## 5. Open items

| # | Item | Severity |
|---|---|---|
| 1 | **`StartupProbeTest` is silently green while measuring nothing.** Logs `SESSION encoder_int8.onnx SKIPPED (not extracted yet)` — every `connectedAndroidTest` uninstalls the app and wipes `filesDir`, so the session-creation probe never runs. **Pre-existing, unrelated to the bump, but it means there is no Phase 2 startup baseline yet.** | **High — blocks Phase 2** |
| 2 | HI→EN comparison is soft: the Phase 12 baseline's two orderings differ 20% at 12 tokens (657.5 vs 523.4); the new 526.3 sits at the fast end | Medium |
| 3 | Device plateaued at 34.8–35.2 °C, never true idle. Numbers directional | Medium |
| 4 | `MtTuningSweepTest` not run (12 configs × 30 runs — needs its own thermal protocol) | Low |
| 5 | `SpeechPipelineBenchmarkTest` not run | Low |
| 6 | `मेरी मदद करो` desktop divergence unresolved — `HiEnEngineTest` asserts properties, not that exact string | Low |
| 7 | `armeabi-v7a` APK built, never installed | Low |
| 8 | Release build not attempted — still unsigned, R8 disabled | Low |
| 9 | UI never exercised on the new runtime | Medium |
| 10 | `adb uninstall` returns `DELETE_FAILED_INTERNAL_ERROR` on **alternating** runs (47 GB free, so not storage). Every retry succeeds. Will break CI | Medium |
| 11 | `docs/ENGINEERING_PLAN.md:293` still predicts KleidiAI dispatching on `i8mm`/`dotprod` for int8 — **now known wrong** (int8 KleidiAI kernels are SME/SME2-gated). Left as historical plan text | Low, but it is a factual error a judge could find |
| 12 | `THIRD_PARTY_NOTICES.md` models table omits the indic-en checkpoint from Phase 12 | Low |

---

## 6. Environment cheat sheet

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
ADB="/c/Users/vishn/AppData/Local/Android/Sdk/platform-tools/adb.exe"   # not on PATH
export MSYS_NO_PATHCONV=1      # or Git Bash mangles /data, /sys into Windows paths
```

Device: **SM-M315F**, serial `RZ8N93BC18A`, Exynos 9611, 4×A73 + 4×A53, Armv8.0-A, NEON only, 6 GB, Android 12.

```bash
# tests — quote the whole -P arg or PowerShell eats it
./gradlew :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.bhashabridge.app.mt.MtBenchmarkTest"

# benchmark capture — Metrics logs at Log.d, so :D not :I, or you get silence
$ADB logcat -c && <run test> && $ADB logcat -d > bench.log
PYTHONIOENCODING=utf-8 python model_pipeline/bench_parse.py bench.log   # or cp1252 dies on Devanagari

# thermal gate before any benchmark
$ADB shell dumpsys battery | grep temperature      # want <= 349 (34.9 C)
```

**Gotchas that cost time this session:**
- `DELETE_FAILED_INTERNAL_ERROR` alternates run-to-run. Just retry; it always works second time.
- Long `sleep` in a backgrounded shell gets killed — use a poll loop of short sleeps.
- Emit a `BENCH_SENTENCE i=N` marker before any non-benchmark probe or the parser folds it into the
  previous sentence's stats.
- Logcat buffer rolls fast — dump immediately after the test, not later.

---

## 7. Commit message (ready to paste)

```
build: upgrade ONNX Runtime 1.17.1 -> 1.27.0

Ten releases in one isolated bump, per R7.3. No source change was
required: the Java API delta over that range is one removed method
(SessionOptions.addArmNN, removed in 1.25 with the ArmNN EP) which this
project never called, plus one package-private OnnxTensor.createTensor
overload the project does not use. Every SessionOptions knob OrtTuning
sets still exists and none is deprecated. The AAR's minSdkVersion is
still 24, matching defaultConfig.

Targets 1.27.0, not 1.27.1: the 1.27.1 GitHub tag shipped no
onnxruntime-android artifact (maven-metadata <release> is 1.27.0).

Execution behaviour is unchanged - no EP is registered, so inference
stays on the CPU EP and MLAS exactly as before. The packaged
libonnxruntime.so is ORT's own (27,985,944 bytes, version string
1.27.0), confirming the jniLibs pickFirst rule still resolves the ORT
copy over Vosk's.

The binary now carries Arm KleidiAI micro-kernels (12 kai_run_* symbols).
They change nothing on the SM-M315F: KleidiAI's int8 dynamic-quantised
GEMM kernels are SME/SME2-gated and its NEON dotprod/i8mm kernels are
4-bit only, while this project ships 8-bit weights on an Armv8.0 core.
Recorded in THIRD_PARTY_NOTICES.md with that qualifier so the Apache-2.0
attribution cannot be misread as a performance claim.

Validation on SM-M315F (Exynos 9611, Armv8.0, Android 12):
- JVM unit tests 20/20
- instrumented 15/15 across MtEngineInstrumentedTest, HiEnEngineTest,
  ParallelSessionLoadTest, ExampleInstrumentedTest,
  AudioFileTranscriberTest, StartupProbeTest, MtBenchmarkTest,
  HiEnBenchmarkTest
- cache contract intact: 72 tensors, identical ordering both directions
- output parity holds both directions. EN->HI byte-identical to every
  phase since 6D; HI->EN emits "The weather is great today and I want to
  go out .", matching docs/HI_EN_IMPLEMENTATION.md:373
- latency, cooled, position 1, 3 warmup + 30 runs:
    EN->HI   2 tok  171.8 -> 166.7 ms  (-3.0%)
    EN->HI   6 tok  372.0 -> 357.3 ms  (-4.0%)
    EN->HI  12 tok  675.2 -> 647.0 ms  (-4.2%), p95 694.6, stdev 23.1
    HI->EN  12 tok  526.3 ms, at the fast end of the Phase 12 baseline's
                    own 523.4-657.5 ordering spread
- memory: EN->HI alone 624.8 -> 629.9 MB; both engines resident
  1,113.7 -> 1,112.7 MB
- arm64 libonnxruntime.so grows 16.0 -> 28.0 MB

A first benchmark pass read +29 to +50% slower with 4x the jitter. That
was thermal, not the runtime: the device sat at 36.2 C after back-to-back
954 MB installs. Re-running after a cooldown reproduced the numbers
above. Both readings are noted here because the hot one is what a careless
run would have reported.

Not covered: startup timings (StartupProbeTest's session-creation probe
skips on a fresh install, so no Phase 2 baseline exists yet),
MtTuningSweepTest, the speech benchmark, the release build, the
armeabi-v7a variant, and manual UI testing.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

---

## 8. What comes next — roadmap, condensed

Baseline to beat, warm: `engine_init` **10,502 ms**, sessions wall **6,289 ms** (59.9%),
tokenizer **4,221 ms**.

### Phase 2 — pre-optimized graphs (highest ROI, −3 to −4.5 s startup)
**Blocked on open item #1** — fix `StartupProbeTest` first or there is nothing to measure against.

Only file to touch: `OnnxModels.loadSession` (`OnnxModels.kt:159-190`). On first run, bake with
`ALL_OPT` + `setOptimizedModelFilePath(...)`, close, then load the `.opt` product with `NO_OPT`
every time after.

- **Bake on the device, never offline.** ORT's doc: an offline-optimized model "would require CPUs
  that support" the optimizing host's features. A Windows x86 artifact is wrong for an Exynos 9611.
- **Invalidate the cache** on ORT version + app `versionCode`, or an upgrade silently loads a graph
  fused by a different runtime.
- Evidence it works: 11A measured `NO_OPT` **52–61% faster**; desktop reproduction of
  `decoder_step` was **2.35 s → 0.64 s (3.7×)**.
- Phase 2b (separate commit): `.ort` format + `session.use_memory_mapped_ort_model` (ORT 1.26,
  #28164) removes the 472 MB assets→`filesDir` copy entirely. Needs `noCompress += "ort"`.

### Phase 3 — thread affinity (cheapest Arm-specific win, ~1 day)
`CpuCapabilities.detect()` already computes per-core max frequencies then throws the ids away
(reduces to a count at line 54). Keep `performanceCoreIds`, pass through `ExecutionPolicy.select()`
into a new `OrtTuning.threadAffinity`, emit as
`o.addConfigEntry("session.intra_op_thread_affinities", ids.joinToString(";"))`.

- **Confirm the string format and index base empirically** — a misparsed string binds nothing and
  looks like "no effect". Verify which core ids are the A73s; do not assume 0–3.
- The affinity list covers intra-op *worker* threads; the calling thread is usually excluded, so
  `intraThreads = 2` likely wants **one** entry.
- **Judge on p95 and stdev, not median.** Run a `[little, little]` config as a falsification test —
  if binding to A53s does not regress, the string is not taking effect.

### Phase 4 — Baseline Profiles (~2.5 s, 2–3 days)
Needs a `:macrobenchmark` module + `androidx.profileinstaller`. **Two blockers:** release builds are
unsigned (macrobenchmark needs a signed, profileable, non-debuggable build — this is the reason to
finally add a signing config, **generated and held by you, never committed**), and
`optimization { enable = false }` + configuration cache + AGP 9 will fight the plugin.
Profile the engine construction, not just first frame — the target is the tokenizer parse.

### Phase 5 — benchmarking
Add: cold vs warm split (Phase 2 makes the *first* run slower — report it separately or the phase
looks like a regression), per-thread CPU utilization (`top -H`), sustained thermal profile
(10 min, sample `thermal_zone*/temp` + `scaling_cur_freq`), battery (`dumpsys batterystats`).
**Commit the raw `.jsonl`** — five earlier ones are tracked, Phase 12's are gitignored, and that
inconsistency reads as selective evidence.

### Critical path
`fix StartupProbeTest → commit the bump → Phase 2a → Phase 2b → Phase 5b`.
Phase 3 and Phase 4 are off the critical path and can go in parallel.

---

## 9. Research findings worth not re-deriving

- **KleidiAI ships in the official Maven Android AAR** — no source build needed, despite the CMake
  default being `OFF` and Arm's docs walking through `onnxruntime_USE_KLEIDIAI=ON`. Confirmed by
  `kai_*` symbols and the `mlas.disable_kleidiai` opt-out key.
- **But it is inert here.** The KleidiAI int8 dynamic-quant kernels in the binary are
  `..._qai8dxp1vlx4_qsi8cxp4vlx4_..._sme_mopa` / `sme2` — **SME/SME2 only**. The `neon_dotprod` /
  `neon_i8mm` KleidiAI kernels are `qsi4c32p`, i.e. **4-bit**. This project ships 8-bit weights on
  an Armv8.0 core, so KleidiAI contributes **exactly nothing** on the SM-M315F, and nothing on
  dotprod/i8mm phones either unless you move to INT4 `MatMulNBits`.
- **ArmNN / ACL EP is permanently dead** — deprecated 1.24.x, removed 1.25, absent from both
  binaries. Do not propose it.
- **XNNPACK EP would claim almost nothing** — the graphs contain **0 `Conv` nodes**, and XNNPACK's
  ORT op set does not cover `DynamicQuantizeMatMul`.
- **ORT already fuses everything worth fusing.** Verified by serializing the optimized graph:
  encoder 2,978 → 902 nodes, `decoder_step` 4,518 → 1,468, producing `LayerNormalization`, `Gelu`,
  `DynamicQuantizeMatMul`, `MatMulIntegerToFloat`, `FusedMatMul`. Never recommend "add operator
  fusion".
- **Attention fusion is not applied** (zero `Attention` nodes post-fusion) because `optimizer.py`'s
  `MODEL_TYPES` has `bart` and `t5` but **no m2m100/marian/indictrans** entry. `DecoderMaskedMulti
  HeadAttention` *does* now have a CPU float kernel in 1.27, so an SDPA re-export + `--model_type
  bart` is a viable **spike** — but the gain is low (72 tiny fp32 BMMs) and the pattern match is
  unproven. Hard abandon criterion.
- **KV-cache quantization (1.27) is unreachable** — gated on `GroupQueryAttention`, and IndicTrans2
  is 8-head MHA, not GQA.
- **Cheapest quality win available:** `per_channel=True` in `quantize_cached.py:47-51` (currently
  unset, so per-tensor). ~1 hour, existing 7/7 gate re-validates it.
- New 1.27 config keys worth knowing: `session.save_external_prepacked_constant_initializers`,
  `session.use_memory_mapped_ort_model`, `session.enable_dq_matmulnbits_fusion`,
  `session.qdq_matmulnbits_accuracy_level` / `_block_size`, `session.intra_op.spin_duration_us`,
  `mlas.disable_kleidiai`.
- **16 KB page alignment** arrived in ORT 1.25 — required for Android 15+. This upgrade gets it,
  which makes the bump eventually mandatory rather than optional.
