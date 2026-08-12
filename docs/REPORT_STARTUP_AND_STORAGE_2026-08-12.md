# Engine startup and storage: four experiments, two shipped

**Date:** 2026-08-12 · **Device:** Samsung SM-M315F (Exynos 9611, Armv8.0, 4 big + 4 little, Android 12)
· **Runtime:** ONNX Runtime 1.27.0 · **Direction measured:** EN→HI

This report covers one session of work on ONNX Runtime session startup. It is a condensed, readable
version of ledger entries **§3.44–§3.47** in `OPTIMIZATION_SUMMARY.md`, which remain the primary record
(full method, rejected hypotheses, per-round numbers).

---

## 1. Summary

Four experiments ran. **Two shipped, two were measured and rejected** — both rejections are recorded on
purpose, because the numbers bound the design space and stop the same idea being retried.

| # | Experiment | Result | Shipped |
|---|---|---|---|
| Q18 | Where do the 2.15 s of session load go? | Load is **not I/O**; the removable half is an initializer copy | ✅ `55cfed7` |
| Q19 | Bake the prepacked weights | Refused by the format that ships; +324 MB to buy 70 ms elsewhere | ❌ no-gain |
| Q20 | Raw vs optimized ONNX vs ORT format | Optimization is worth ~20% of inference; **format is worth none of it** | ❌ probe only |
| Q21 | Ship the optimized-ONNX artifact | **−193 MB storage, −324 MB memory**, inference unchanged | ✅ this change |

**Net effect on the product**

| Metric | Start of day | End of day | Change |
|---|---|---|---|
| Cold start (`engine_init`) | 6190 ms | ~5400–5600 ms | **−0.6 s** |
| Session load (`sessions:parallel`) | 2183 ms | ~1633–1708 ms | **−0.5 s** |
| Model cache on disk (EN→HI) | 472.9 MB | **279.8 MB** | **−193.1 MB (−41%)** |
| Process memory (PSS, after 60 translations) | 783.2 MB | **459.6 MB** | **−323.6 MB (−41%)** |
| Throughput | 73.26 tok/s | 73.28 tok/s | unchanged |
| Translation output | — | — | **byte-identical throughout** |

---

## 2. Q18 — Where the session load actually goes

**Question.** `sessions:parallel` cost 2.15 s of a ~6 s cold start and had never been broken down.

**Method.** `OrtLoadProbeTest`: the three shipping `.ort` graphs, production options as the baseline,
7 arms × 3 **rotated** rounds, sessions closed immediately so only construction is timed. Rotation is
the point — an earlier experiment (§3.27) mistook a warm page cache for a 61% speedup.

**Finding.** Reading all 473 MB off storage takes **335 ms**, so load is not I/O-bound. Per
decoder-sized graph it splits into roughly **790 ms of MLAS prepacking**, **730 ms of copying
initializers into the session allocator**, and ~690 ms of graph/flatbuffer residual. The encoder
(75 MB, ~0.88 s) is free — it hides behind the two decoders, so the critical path is one decoder load.

| arm | median | vs baseline |
|---|---|---|
| serial load | 3733 ms | +69% |
| baseline (path load, NO_OPT, mmapped) | 2205 ms | — |
| `use_device_allocator_for_initializers` | 2108 ms | −4% (inside spread) |
| `intra_op.allow_spinning=0` | 1917 ms | −13% (spreads overlap) |
| **mapped initializers** | **1477 ms** | **−33%** |
| `disable_prepacking` (diagnostic only) | 1414 ms | −36% |

**Shipped.** The initializer copy is removable and the mechanism already existed (Q14), shipped off
because its load-time effect had never been separated from the page cache. Rotating the arms separated
it. Real app, cold, **arms interleaved** over six launches at a flat 34.7–34.8 °C:

| median | before | after |
|---|---|---|
| `sessions:parallel` | 2183 ms | **1633 ms** (−25.2%) |
| `engine_init` | 6190 ms | **5589 ms** (−9.7%) |

Ranges non-overlapping in both arms; parity exact; translate latency neutral.

**Also settled:** loading the three graphs serially is **69% worse**, so parallel session init still
earns its place. This does not contradict the earlier finding that a *fourth* thread (the tokenizer)
hurt — these three loads are the same work split up, not a new competitor for the same cores.

---

## 3. Q19 — Baking the prepacked weights (rejected)

**Question.** Prepacking was the largest remaining piece of load (~790 ms), and the two decoders prepack
byte-identical tensors. ORT can share prepacked buffers between sessions via
`PrepackedWeightsContainer`, which the Java API does not expose; `addConfigEntry` does reach
`session.save_external_prepacked_constant_initializers`, so that route was priced.

**Result — it fails twice over.**

1. **The shipping format refuses the flag**, and says so:
   `Serializing optimized model in ORT format with external pre-packed constant initializers is not
   supported. Ignoring the flag.` The artifacts came out byte-for-byte identical to the unflagged ones.
2. **The format that accepts it loses anyway.** On optimized ONNX the prepacked data really is written
   — +324 MB (+69%) — and buys 70 ms, on a load path already 7% slower than the shipping one.

| arm | load median | on disk |
|---|---|---|
| ORT format (ships) | 1441 ms | 472,948,560 B |
| ORT format + prepacked flag | 1434 ms | 472,948,560 B — *identical, flag dropped* |
| optimized ONNX + external initializers | 1615 ms | 471,246,292 B |
| the same + prepacked | 1545 ms | **797,385,456 B** |

**Note for anyone revisiting this:** the ~790 ms from the `disable_prepacking` diagnostic **overstates**
what a saved prepack file can recover. That arm removes all packing work; the file covers only part of
it. 70 ms is the real ceiling on this route.

---

## 4. Q20 — The artifact matrix

**Question.** Q18 and Q19 both varied *how one artifact is loaded*. Nobody had varied *the artifact*.

**Method.** `GraphFormatMatrixTest` drives seven artifacts through the **real engine** — real tokenizer,
real translations — measuring four things per arm: startup, memory, **first** inference, and
steady-state inference (n=12 after warm-up). Arms rotated per round. 32.9 °C throughout; every arm
produced identical output.

| arm | load ms | first ms | steady ms | native heap | PSS | disk |
|---|---|---|---|---|---|---|
| raw ONNX, optimized every launch | 5771/4748 | 745/670 | 637/614 | 422 MB | 476 MB | 281 MB |
| raw ONNX, optimizers off | 4494/5041 | 829/812 | **773/755** | 434 MB | 472 MB | 281 MB |
| **optimized ONNX, shared blob** | **2796/2834** | 683/734 | 641/610 | 413 MB | **449/446 MB** | **280 MB** |
| optimized ONNX, per-graph external | 3595/3702 | 676/778 | 622/633 | 411 MB | 446/458 MB | 471 MB |
| the same, prepacked | 3687/3083 | **1517/1000** | 629/607 | **38 MB** | 457/447 MB | 797 MB |
| ORT format, path load | 4577/3887 | 620/663 | 618/614 | 560 MB | 631 MB | 473 MB |
| ORT format, mapped (shipping then) | **2712/2760** | 643/666 | 619/608 | 408 MB | 785/779 MB | 473 MB |

**Three findings.**

1. **Graph optimization is worth ~20% of steady-state inference** (755–773 ms unoptimized against
   607–641 ms optimized) — and **the artifact format is worth none of it.** All six optimized arms tie
   within their own spread. A 2.6× range in file size changes decode speed by nothing.
2. **The smallest file is not the fastest, and the biggest is worst where it counts.** The 797 MB
   prepacked arm pays **1000–1517 ms on first inference**, 50–130% over every other arm, faulting its
   prepacked buffers in from disk. Its native heap is trivial (38 MB) because the weights stay in the
   mapping — storage traded for first-run latency, badly.
3. **`optimized ONNX + shared blob` matches the shipping artifact on load and steady latency** while
   costing 193 MB less on disk and 333 MB less PSS. It wins because three ~1 MB graphs of pure
   structure all point at **one** weight blob, where the ORT-format writer re-inlines the weights into
   every graph.

---

## 5. Q21 — Shipping the optimized-ONNX cache

### 5.1 What changed

The bake now writes **optimized ONNX** and nothing else. It deliberately does *not* ask for external
initializer files, because ORT's ONNX writer already **preserves the source graph's external-data
references** — so the output is three ~1 MB graphs still pointing at the single `weights.bin`.

Six changes in `OnnxModels.kt`, one in `ExecutionPolicy.kt`:

| # | Change | Why it matters |
|---|---|---|
| 1 | `bakeOptions` writes `.opt.onnx`; `loadOptions` is a plain NO_OPT path load | The format switch itself |
| 2 | **The shared blob is permanent, not bake scratch** — `init` extracts it instead of deleting it | The baked graphs resolve every initializer through it. Getting this backwards breaks every launch after the first |
| 3 | `purgeLegacy` deletes the superseded `.ort` pair | Otherwise an upgrading install keeps 473 MB of dead flatbuffer — the saving silently not saved |
| 4 | `cacheStamp` gained a leading `CACHE_FORMAT` token | The new artifact reuses the file names of an abandoned earlier cache; a stale file that merely *looks* current is the exact failure this key exists to prevent |
| 5 | `extractAsset`'s storage pre-check returns to `graph × 2` | The bake's output is graph-sized again, not blob-sized |
| 6 | `mappedInitializers` deleted with its machinery and one test | It was Q18's win and is **ORT-format-only**; it has no meaning for an ONNX graph whose weights live in a file ORT maps itself |

### 5.2 Verification

- `MtEngineInstrumentedTest` 3/3 · `HiEnEngineTest` 3/3 (exercises the HI→EN blob) · `OptCacheTest` 1/1
  including a new assertion that **the blob survives a warm launch** · JVM unit suite green.
- **The upgrade path was exercised for real.** The phone was holding the old `.ort` cache. The first
  launch purged it, baked the new graphs (encoder 1800 ms, decoder_step 3192 ms, decoder_init 3296 ms,
  concurrently) and translated correctly.
- Output identical everywhere: `पानी ।` and the long test sentence unchanged.

### 5.3 Results

| | `.ort` + mapped initializers (was) | optimized ONNX (now) |
|---|---|---|
| **cache on disk, EN→HI** | 472,948,560 B | **279,821,784 B** |
| **total PSS after 60 translations** | 783,152 KB | **459,554 KB** |
| native PSS | 366,597 KB | 358,785 KB |
| live app PSS (`dumpsys meminfo`) | — | 474,769 KB |
| throughput | 73.261 tok/s | 73.283 tok/s |
| sustained median | 617 ms | 616 ms |
| short / long sentence median | 159 / 630 ms | 158 / 627 ms |
| suite warm / hot model load | 1607 / 1622 ms | 1513 / 1529 ms |
| real-app cold `sessions:parallel` | 1633 ms | 1708 ms |
| battery temperature | 34.8 °C | 32.9–33.1 °C |

- **Storage −193.1 MB (−40.8%)** for EN→HI, and **−317 MB** across both directions once HI→EN is used
  (a 219.5 MB blob instead of a 334 MB `.ort` trio).
- **Memory −323.6 MB (−41.3%)**, confirmed by two independent instruments.
- **Inference untouched** — 0.03% on throughput, 1 ms on the sustained median.
- **Startup: neutral, and stated as neutral.** The two harnesses disagree in sign: the suite's warm and
  hot loads improved 94 and 93 ms, while the real-app cold launch got 75 ms worse. Both new
  measurements were taken ~1.8 °C *cooler* than the baseline, so temperature does not explain the
  regression half. ±5% with a flipping sign is below what this setup resolves, and an interleaved cold
  A/B is impossible across a format change — the two artifacts cannot coexist in one build, which is
  how Q18 controlled its own comparison.

### 5.4 One safety consequence

The bake is `ALL_OPT`, and ORT warns that such output *"should only be used in the same environment the
model was optimized in"*. That is satisfied because the bake runs on the device that will run the graph
— **provided a cloud restore or a device-to-device transfer never carries the files to another phone.**
`filesDir` is already excluded from both in `backup_rules.xml` and `data_extraction_rules.xml`; those
exclusions are now load-bearing rather than merely a quota optimization, and both files say so.

---

## 6. What is still open

| Item | Why |
|---|---|
| **Q4 — the tokenizer parse** | Now the largest startup component by a wide margin: **~2.9 s serial**, against ~1.7 s for all three model loads. Untouched by all four experiments. It must **not** simply be parallelised — that was measured and reverted (§3.29). |
| **Q15 — behaviour under memory pressure** | Cheaper and more meaningful now: the shipping config is the file-backed one, so the question is whether 276 MB of clean blob pages survive pressure better than 559 MB of anonymous heap did. |
| **Startup A/B across formats** | Deliberately left NOT MEASURED. Closing it needs two builds and an interleaved cold protocol. |

---

## 7. Reproducing any of this

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $ADB install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
& $ADB install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# any single harness (logcat tags: BB.Q18, BB.Q19, BB.Q20, BB.Suite, BB.Bench)
& $ADB shell am instrument -w -e class com.bhashabridge.app.mt.OrtLoadProbeTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

Cold-launch numbers come from the real app, not a test: force-stop, launch, and read the `engine_init`
line from `BB.Bench`. Always record battery temperature next to any timing — the same code has read
640 ms and 864 ms on temperature alone.

**Probes added this session** (`app/src/androidTest/.../mt/`): `OrtLoadProbeTest` (load-path arms),
`PrepackedBakeTest` (bake formats), `GraphFormatMatrixTest` (the artifact matrix, driven through the
real engine via the benchmark-only `OrtTuning.graphDir`).
