# BhashaBridge V4 — Cross-Device Benchmark Report

**Generated:** 2026-07-23 · **Updated:** 2026-07-31 (entry #9 added — first ARMv9 / SVE2 / SME part, and the first run under the uniform-IP classifier fix; §5's platform-exclusive memory finding is **corrected** by it)
**Benchmark schema:** `bb-bench/1` (unified `BenchmarkSuiteTest`, Phase 5)
**ONNX Runtime:** 1.27.0 · **EP:** CPU (MLAS) · **App:** BhashaBridge V4
**Model:** IndicTrans2 distilled 200M, INT8, 3 graphs (encoder / decoder_init / decoder_step), KV-cache, EN→HI
**Method:** 30 iterations, 5 warmup, counterbalanced sentence order; startup measured cold/warm/hot; system snapshot before/after via `SystemStats` (unrooted).

> **Provenance rule:** every number here was produced on-device by the shipped benchmark and reassembled from logcat `REPORT_JSON`. Nothing is fabricated. Analytical claims are tagged **Measured / Estimated / Speculative**. `nr_migrations`, `energyCounter` and SoC thermal zones are not readable unrooted and are honestly absent.

Raw entries (append-only, never overwritten):
- `bench/results/phase5/bench_report.sample.json` — SM-M315F (entry #1)
- `bench/results/cross-device/moto_g73_dimensity930.json` — moto g73 (entry #2)
- `bench/results/cross-device/m14_exynos1330.json` — SM-E146B (entry #3)
- `bench/results/cross-device/a015_dimensity7300.json` — Nothing A015 (entry #4)
- `bench/results/cross-device/lava_lxx518_dimensity7300.json` — LAVA LXX518 (entry #5)
- `bench/results/cross-device/oppo_cph2603_dimensity7050.json` — OPPO CPH2603 (entry #6)
- `bench/results/cross-device/vivo_v2338_snapdragon6gen1.json` — vivo V2338 (entry #7, first Qualcomm)
- `bench/results/cross-device/samsung_s22ultra_snapdragon8gen1.json` — Samsung S22 Ultra (entry #8, first i8mm / Armv9)
- `bench/results/cross-device/s26ultra_sd8elitegen5.json` — Samsung S26 Ultra (entry #9, first **ARMv9 / SVE2 / SME**, first uniform-IP CPU)

**Same-device follow-up experiments for entry #9 live in [`S26U_EXPERIMENTS.md`](S26U_EXPERIMENTS.md)** — operator profiling, the intra-op thread sweep, the speech pipeline, and the affinity A/B. Two of them returned results that bear on shipping defaults; §10 below is updated from them.

---

## 1. Devices Under Test

| Field | #1 SM-M315F | #2 moto g73 5G | #3 SM-E146B | #4 A015 | #5 LXX518 | #6 CPH2603 | #7 V2338 | #8 SM-S908E | #9 SM-S948B |
|---|---|---|---|---|---|---|---|---|---|
| Device | Samsung Galaxy M31 | Motorola moto g73 5G | Samsung Galaxy M14 5G | Nothing A015 (board `Tetris`) | LAVA LXX518 | OPPO CPH2603 | vivo V2338 | **Samsung Galaxy S22 Ultra** | **Samsung Galaxy S26 Ultra** |
| SoC | Exynos 9611 | MediaTek Dimensity 930 (mt6855) | Exynos 1330 (erd8535) | MediaTek Dimensity 7300 (MT6878) | MediaTek Dimensity 7300 (MT6878) | MediaTek Dimensity 1080/7050 (MT6877) | Qualcomm Snapdragon 6 Gen 1 (SM6450) | **Qualcomm Snapdragon 8 Gen 1 (SM8450)** | **Qualcomm Snapdragon 8 Elite Gen 5 (SM8850, board `canoe`)** |
| OS / API | Android 12 / 31 | Android 14 / 34 | Android 15 / 35 | **Android 16 / 36** | **Android 16 / 36** | **Android 16 / 36** | **Android 16 / 36** | **Android 16 / 36** | **Android 16 / 36** |
| Kernel | not captured | 5.10.240-android12 | 5.15.180-android13 | **6.1.162-android14** | **6.1.138-android14** | **6.6.118-android15** | 5.10.246-android12 | 5.10.236-android12 | **6.12.30-android16 (newest in DB)** |
| Microarchitecture | 4× Cortex-A73 + 4× Cortex-A53 | 2× Cortex-A78 + 6× Cortex-A55 | 2× Cortex-A78 + 6× Cortex-A55 | **4× Cortex-A78 + 4× Cortex-A55** | **4× Cortex-A78 + 4× Cortex-A55** | 2× Cortex-A78 + 6× Cortex-A55 | **4× Cortex-A78 + 4× Cortex-A55** | **1× Cortex-X2 + 3× Cortex-A710 + 4× Cortex-A510** | **8× Qualcomm Oryon — uniform IP, every core part `0x002` (2 prime + 6 performance)** |
| Core layout | A53 cpu0-3, A73 cpu4-7 | A55 cpu0-5, A78 cpu6-7 | A55 cpu0-5, A78 cpu6-7 | A55 cpu0-3, A78 cpu4-7 | A55 cpu0-3, A78 cpu4-7 | A55 cpu0-5, A78 cpu6-7 | A55 cpu0-3, A78 cpu4-7 | A510 cpu0-3, A710 cpu4-6, X2 cpu7 | **Oryon perf cpu0-5, Oryon prime cpu6-7 — no little cluster** |
| **Arm ISA** | **Armv8.0-A** | **Armv8.2-A** | **Armv8.2-A** | **Armv8.2-A** | **Armv8.2-A** | **Armv8.2-A** | **Armv8.2-A** | **Armv8.6-A** (cores are Armv9.0; detector reports 8.6, no SVE hwcap) | **ARMv9** (SVE2 hwcap present — first in DB) |
| dotprod (SDOT/UDOT) | **no** | **yes** | **yes** | **yes** | **yes** | **yes** | **yes** | **yes** | **yes** |
| fp16 (asimdhp) | no | yes | yes | yes | yes | yes | yes | yes | yes |
| **i8mm** / SME | no / no | no / no | no / no | no / no | no / no | no / no | no / no | **YES** / no | **YES** / **YES** (`sme`, `smei8i32`, `smef16f32`, `smeb16f32`, `smef32f32`; **no `sme2`**) |
| SVE / SVE2 | no | no | no | no | no | no | no | no (hwcap not exposed) | **`sve`, `sve2`, `svei8mm`, `svebf16`** |
| Perf-core max clock | A73 1664 MHz | A78 2200 MHz | A78 2400 rated (**2288 observed**) | **A78 2500 MHz** | **A78 2500 MHz** | A78 2600 rated (**2400 observed, throttled**) | A78 **2208 MHz** | X2 2995 rated (**2054 observed, throttled**); A710 2496 | prime **4742 rated**; perf **3629 rated (3187 observed)** |
| Eff-core max clock | A53 910 MHz | A55 2000 MHz | A55 2002 MHz | A55 2000 MHz | A55 2000 MHz | A55 2000 MHz | A55 1805 MHz | A510 1785 MHz | **none — no efficiency cluster exists** |
| RAM | 6 GB | 8 GB | 6 GB | 5.3 GB (`MemTotal` 5545376 kB) | 7.2 GB (`MemTotal` 7560216 kB) | 7.4 GB (`MemTotal` 7719276 kB) | 7.3 GB (`MemTotal` 7641764 kB) | 11 GB (`MemTotal` 11473784 kB) | 11.4 GB (`MemTotal` 11389624 kB) |
| Page size | not captured | not captured | not captured | 4096 B | 4096 B | 4096 B | 4096 B | 4096 B | 4096 B |
| NPU | none | MediaTek APU (unused) | Exynos NPU (unused) | MediaTek APU (unused) | MediaTek APU (unused) | MediaTek APU (unused) | Qualcomm Hexagon (unused) | Qualcomm Hexagon (unused) | Qualcomm Hexagon (unused) |
| **Adaptive policy chosen** | intra=2, **affinity=5,6,7,8 ON** | intra=1, **affinity OFF** | intra=1, **affinity OFF** | intra=2, **affinity=5,6,7,8 ON** | intra=2, **affinity=5,6,7,8 ON** | intra=1, **affinity OFF** | intra=2, **affinity=5,6,7,8 ON** | **intra=1, affinity OFF** (see note) | **intra=4, affinity OFF** (uniform IP — nothing to pin away from) |

ISA/feature flags are **Measured** from on-device `CpuCapabilities.detect()` and `/proc/cpuinfo` (A015 CPU parts: `0xd41` ×4 = A78, `0xd05` ×4 = A55; feature string carries `asimddp`, `asimdhp`, `atomics`, `lrcpc`, and **no** `i8mm`/`sve`). The Exynos 1330 not reaching its 2400 rated clock (ran 2288 under load) is **Measured** from `perCoreFreqKhz`.

**Adaptive-policy note (Measured):** the policy code produced its two established configurations across the seven big.LITTLE (2-cluster) devices. 4 perf cores (M315F, A015, LXX518, V2338) → `(4/2)=2` intra-op threads + affinity pinning. 2 perf cores (930, 1330, CPH2603) → `(2/2)=1` thread, affinity disabled. Byte-identical per branch across MediaTek, Exynos and Qualcomm (see §6d).

**Policy classification breaks on the tri-cluster S22 Ultra (Measured — new failure mode).** The detector reported `perf=1[7], eff=7[0–6]` — it counted **only the single Cortex-X2 as a performance core** and lumped the **three Cortex-A710 mid cores into "efficiency"** alongside the four A510s. With 1 perf core the policy selected `(1/2)→coerce→1` intra-op thread and **affinity OFF**. So on the most capable CPU in the database, inference ran **single-threaded on one core**, leaving three strong A710s unused for intra-op parallelism. This is a genuine limitation of the perf/eff heuristic on Arm's 3-tier (prime + mid + little) Armv9 layout — the mid tier is misfiled as little. Flagged as the top optimization opportunity for this device in §8, and it means the S22U's throughput below is a **single-thread** figure, not directly comparable to the 2-thread runs.

**Entry #9 broke the classifier a second way — and is the first run with the fix in place (Measured).** The S22U fix (`dc3011e`) generalised the rule to *"the lowest frequency tier is efficiency, every tier above it is performance."* The S26 Ultra is the first CPU in the database with **no little cluster at all**: 8 identical Oryon cores (`CPU part 0x002` on every one), DVFS-split 6 performance @ 3629 MHz + 2 prime @ 4742 MHz. Under that rule the bottom tier — six full-size performance cores — was classified as *efficiency*, giving `perfCores = 2` → `(2/2) = 1` intra-op thread and affinity disabled. Same single-threaded-flagship outcome as the S22U, from the opposite direction.

**Frequency ratio cannot separate the two cases**, which is what forced the fix to key on something else: the Dimensity 930's genuine A55/A78 split sits at 2000/2200 = **0.91**, *higher* than this Oryon part's 3629/4742 = **0.77**. Any ratio threshold sparing the Oryon would collapse a real big.LITTLE. The fix (`e581a45`) therefore gates the frequency rule on **core IP**: a frequency tier is only an efficiency cluster if its cores are a different `CPU part`. Uniform IP ⇒ no little cluster ⇒ every core is performance, and affinity is skipped for lack of a big/LITTLE split. Partial `cpuinfo` falls back to the frequency-only rule, so a truncated read cannot fake uniformity. **Verified on device:** `CPU ARMv9 cores=8(perf=8[0…7],eff=0[]) … sme=true sme2=false` → `arm-adaptive(threads=4) intra=4 arena=false affinity=OFF`. Entry #9 is therefore the database's **first multithreaded run on top-tier silicon**, and unlike the S22U it is not a single-thread figure.

**Entry #4 closes the missing matrix cell.** Until now, every device with dotprod had only 2 perf cores (affinity OFF), and the only device with affinity ON had no dotprod. The A015 is the first part that is **Armv8.2 + dotprod + 4 perf cores**, so it runs intra=2 **with affinity active**. See §6c.

**Entry #5 is a replication, deliberately.** The LAVA LXX518 is the **same SoC (MT6878), same core layout, same clocks, same ISA, same Android 16, same 4 KB page size** as entry #4, from a different OEM, with a different kernel patch level (6.1.138 vs 6.1.162) and 36% more RAM. It exists to test whether the entry-#4 memory result (§5) was device-specific or platform-level, and whether the policy selects identically on independently-built firmware. Both questions are answered in §6d.

**Entry #6 is the de-confounder.** The OPPO CPH2603 is **Android 16 / SDK 36, kernel 6.6.118 — the newest OS *and* newest kernel in the database** — but on a **different MediaTek SoC: MT6877 (Dimensity 1080/7050), 2× A78 + 6× A55**. It was added specifically to separate "Android 16 / kernel 6.x" from "the MT6878 platform" as the cause of the §5 memory result. It also re-tests policy determinism on the **2-perf-core branch** (should match g73/M14). Result: **the memory win did NOT occur** here (native heap 531 MB, like the old devices) despite the newest OS/kernel — see §5, which is now corrected. Note its A78 cluster **throttled** (rated 2600 MHz, observed 2400, ended at 1430) — the only device in the database with clear DVFS throttling.

---

## 2. Benchmark Validation

| Gate | #1 M315F | #2 g73 | #3 M14 | #4 A015 | #5 LXX518 | #6 CPH2603 | #7 V2338 | #8 S22U | #9 S26U |
|---|---|---|---|---|---|---|---|---|---|
| Battery temp at run | 33.1 °C | 35.0 °C | 33.6 °C | **31.0 °C** | **35.0 °C** | 34.0 °C | 34.0 °C | **40.7 °C (hottest)** | **30.0 → 30.8 °C (coolest)** |
| Thermal drift over run | none | none | none | **0.0 °C** | **0.0 °C** | none | none | rising, throttling | **+0.8 °C battery, no throttle** |
| Battery level | 85% | 54% | 77% | 64→65% | 98% | 78→80% | 34→39% | 45→46% | 40% (flat) |
| Charging | USB (not_charging) | **USB charging** | **AC charging** | **USB charging** | **USB charging** | **USB charging** | **AC charging** | **AC charging** | **USB charging** |
| Iterations / warmup | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 | 30 / 5 |
| Counterbalanced | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| Per-sentence CV | 25% / 16% | 8% / 3% | 5% / 5% | **2.7% / 1.3%** | 5.9% / 4.3% | 19% / 19% | 5.2% / 2.2% | 39% / 33% | 12.2%† / **5.1%** |
| Perf cores at max clock after run | no | yes | no | **yes (2500)** | **yes (2500)** | **NO — throttled** | yes (2208) | **NO — heavy throttle (X2 2995→1171)** | perf cores rose to 3187 (rated 3629); **primes never engaged** |
| SoC thermal zones | empty | empty | empty | empty | empty | empty | populated | **populated (CPU 63–68 °C)** | **populated (CPU 47–58 °C after)** |
| Migrations / energy | unavailable | unavailable | unavailable | 0 (main only) | same | same | mig n/a; energy n/a | same | mig 0 (main only); energy n/a |

†The S26U's short-sentence CV is a **timer-resolution artifact, not instability**: its "Water." median is 32 ms and the harness records whole milliseconds, so one tick is 3.1% of the measurement and the stdev is only 3.9 ms. Its long-sentence CV (5.1% on a 106 ms median) is the trustworthy stability figure. No other device is fast enough for this to matter.

**Entry #9 is the cleanest run in the database — accepted without caveat.** It is the only entry that is simultaneously the **coolest** (battery 30.0 → 30.8 °C; CPU sensors 47–58 °C after the run, versus the S22U's 63–68 °C *before* its), **unthrottled** (perf-core frequency **rose** 2746 → 3187 MHz across the run rather than falling), and **multithreaded on top-tier silicon** (intra=4, per §1). This is exactly the run §10 of the previous revision asked for — a cool, multithreaded flagship — and it means entry #9's throughput is a genuine figure rather than the lower bound the S22U's had to be.

**But the two prime cores never engaged (Measured, with a stated limit).** `perCoreFreqKhz` reads **883 MHz on cpu6-7 in both the before and after snapshots** while cpu0-5 ran 2746–3187 MHz. With affinity OFF and 4 intra-op threads, the scheduler kept the work on the six performance Oryons and left the two 4742 MHz primes parked. So entry #9's headline numbers were achieved **without the fastest cores on the chip**. The limit on this claim: `perCoreFreqKhz` is two instantaneous samples, not a duty-cycle measurement, so "never engaged" is **Inferred** from both samples agreeing plus the absence of any prime-clock excursion — not observed continuously. Either way it is an opportunity, not a defect (§8).

**Verdict:** five of six runs are thermally valid (≤35 °C, no throttle); the CPH2603 (#6) throttled its big cluster and is handled specially below. Entry #4 is the **statistically cleanest** run in the database: CV 2.7%/1.3%, zero thermal drift, perf cores still at 2500 MHz after the run — throughput was not clock-limited or thermally clipped.

**Entry #5 accepted, with a stated caveat.** It started at **35.0 °C — the joint-warmest in the database** — and at 98% battery on USB. Temperature did not drift during the run (35.0 → 35.0) and both A78 clusters held 2500 MHz to the end, so there is **no evidence of throttling**, and the run is not rejected. But its CV (5.9%/4.3%) is ~3× entry #4's on identical silicon, so the small #5-vs-#4 deltas in §6d are reported as within-noise rather than as a ranking.

**Entry #6 accepted for memory/policy, discounted for throughput ranking.** The CPH2603's `perCoreFreqKhz` shows its A78 pair ran at **2400 MHz (rated 2600)** during the run and dropped to **1430 MHz** by the after-snapshot — the only device in the database with **clear DVFS throttling of the big cluster**. That is consistent with its high CV (19%/19%, worst among Armv8.2 parts) and elevated p95/p99 (398/421 ms). Consequences for how #6 is used: its **memory footprint and selected policy are hardware/OS facts and fully trusted** (they do not depend on sustained clock); its **throughput/latency numbers are treated as a throttled lower bound**, not as this SoC's ceiling, and are **not** used to rank it against the non-throttled parts.

**Entry #7 accepted, with ONE metric rejected as an outlier.** The V2338's **warm** startup reads `modelLoadMs = 877272 ms` (~14.6 minutes) — physically impossible for a mmap reload that is 900 ms elsewhere, and bracketed by a normal cold (7960 ms) and a normal hot (2458 ms) on the *same* run. This is a single-phase device stall (background activity / GC / storage contention on a freshly-set-up phone at 34% battery), not a translation-stack cost. **The warm-startup row for V2338 is rejected** and shown as `—` in §3; every other V2338 metric (cold/hot startup, all inference, memory, thermal) is internally consistent and retained. Inference in particular is clean: per-sentence CV 5.2%/2.2%, A78 held 2208 MHz throughout, no throttle. This is the value of per-phase measurement — one corrupt phase is isolated and dropped rather than poisoning the entry.

**First populated thermal zones (Qualcomm exposes `/sys/class/thermal`).** Unlike all six MediaTek/Exynos parts, the SM6450 exposed real SoC sensors during the run: **CPU cores 54–57 °C, DDR 51.8 °C, GPU ~50 °C, skin 43 °C** (battery-surface was 34 °C, which is why the earlier battery-only thermal proxy understates true silicon temperature by ~20 °C). No throttling despite 55 °C cores — consistent with the A78 holding 2208 MHz. This is the first direct confirmation that the "battery temp ≤35 °C" gate used on entries #1–#6 was conservative, not that those devices ran cool internally.

**Entry #8 is the most thermally stressed run in the database — accepted for capability facts, throughput treated as a throttled lower bound.** The S22 Ultra started at **40.7 °C battery (hottest run)** with CPU-core sensors at **63–68 °C**, and its clocks fell hard during the run (X2 2995→1171 MHz, A710 ~1651→633; the X2 was already only 2054 at the *before* snapshot). Consequences: CV is **39%/33%, the worst in the database**; p95/p99 (332/390 ms) tower over the tight A78 parts. So how #8 is used: its **capability facts are fully trusted and clock-independent** — i8mm present, the tri-cluster policy misclassification, the memory profile; its **throughput/latency is a throttled lower bound**. Notably it still posts the **best median tokens/sec in the database despite running single-threaded and throttled** — that is a floor on what the X2 + i8mm can do, not a ceiling, and it is *not* used to rank against the cooler parts.

**First Armv9 / i8mm silicon temperatures.** Qualcomm sensors during the run: CPU cores **63–68 °C** (cpu-1-8 = X2 at 68.4 °C), DDR 63.9, GPU 62, skin/ac 54.6. This is a genuinely hot run and explains the throttling and variance directly — the framework's thermal instrumentation earned its place here.

**Caveats:** #2–#8 all ran while charging; the **pooled** sustained median is statistically invalid on every device (bimodal short/long mix) — only per-sentence figures are used below.

---

## 3. Startup (Measured, ms)

`engineInit = tokenizerMs + modelLoadMs`. Cold = cache cleared (bake `.ort`); warm = mmap `.ort`; hot = repeat warm.

| Phase | metric | M315F | g73 | M14 | A015 | LXX518 | CPH2603 | V2338 | S22U | **S26U** |
|---|---|---|---|---|---|---|---|---|---|---|
| Cold | tokenizer | 3098 | 1544 | 964 | 843 | 799 | 905 | 1037 | 859 | **556** |
| Cold | model-load | 14099 | 5392 | 5154 | 3587 | 3105 | 3913 | 6923 | 15346† | **1916** |
| Cold | **engine-init** | **17197** | **6936** | **6118** | 4430 | 3904 | 4818 | 7960 | 16205† | **2472** |
| Warm | engine-init | 3687 | 1953 | 1822 | 1275 | 1239 | 1360 | — (rejected) | 1063 | **923** |
| Hot | engine-init | 6424 | 1905 | 1487 | 1242 | 1175 | 1193 | 2458 | 1136 | **928** |

**Entry #9 takes every startup row (Measured).** Cold engine-init 2472 ms is **37% faster than the previous best** (LXX518 3904) and **7.0× faster than the M315F baseline**; warm 923 ms and hot 928 ms are likewise the fastest recorded. Its cold model-load of 1916 ms is the single most striking figure — the one-time ALL_OPT graph bake, which cost the M315F 14.1 s and the S22U 15.3 s under throttle, completes here in under two seconds. Warm ≈ hot (923 vs 928) confirms again that "hot" is a repeat-warm tier, not a distinct one.

Cold→warm on the devices with a valid warm phase is the Phase 2A/2B cache win (−78% M315F, −71% A015, −68% LXX518, −72% CPH2603, **−93% S22U**). The three MediaTek Android 16 devices sit in a 3.9–4.8 s cold band; the two Qualcomm devices are **slower at cold startup** (V2338 7960 ms; S22U **16205 ms†**). †The S22U cold figure is inflated by thermal throttling during the one-time graph bake (cold model-load 15346 ms while the SoC was already at 63–68 °C and downclocking) — its **warm/hot are the fastest in the database (1063/1136 ms)** once the `.ort` cache exists and the bake is skipped. So the huge cold number is a throttled-bake artifact, not a steady-state cost: this device is simultaneously the slowest cold-start and the fastest warm-start. Confirms cold startup is IO + one-time-compute bound (and here CPU-throttle-sensitive), while warm is pure mmap reload. "hot" remains a repeat-warm tier, not a distinct one.

---

## 4. Inference (Measured)

| Metric | M315F | g73 | M14 | A015 | LXX518 | CPH2603† | V2338 | S22U†‡ | **S26U** |
|---|---|---|---|---|---|---|---|---|---|
| TTFT / first translation (ms) | 1572 | 265 | 327 | 242 | 227 | 330 | 256 | 181 | **110** |
| tokens/sec | 50.3 | 177.5 | 152.4 | 200.5 | 207.5 | 151.9† | 186.8 | 211.8†‡ | **412.8** |
| short "Water." median (ms) | 247 | 68 | 76 | 59 | 57 | 78 | 64 | 53 | **32** |
| short stdev | 65.3 | 5.3 | 4.1 | 1.6 | 3.5 | 15.7 | 3.4 | 26.0 | 3.9 |
| long sentence median (ms) | 894 | 256 | 307 | 232 | 219 | 304 | 247 | 188 | **106** |
| long stdev | 144.0 | 7.7 | 15.6 | **3.1** | 9.5 | 59.6 | 5.5 | 76.0 | 5.4 |
| threads / perf clock | 2 / 1664 | 1 / 2200 | 1 / 2288 | 2 / 2500 | 2 / 2500 | 1 / 2400† | 2 / 2208 | 1 / X2 2054† | **4 / Oryon 3187** |

†CPH2603 and S22U ran throttled (§2). ‡S22U additionally ran **single-threaded** (policy misclassification, §1) and was the only **i8mm** part until entry #9.

**Entry #9 nearly doubles the database record on every inference metric (Measured).** 412.8 tok/s against the previous best of 211.8 (**+95%**), long-sentence median 106 ms against 188 (**−44%**), first translation 110 ms against 181 (**−39%**). Against the Armv8.0 M315F baseline the regression harness reports **+720% throughput** with 14 metrics improved and 0 regressed. Unlike the S22U's, these are **not** a lower bound: the run was cool, unthrottled and multithreaded (§2).

**What cannot be attributed, and why (Estimated).** Entry #9 differs from the S22U on **four axes at once** — 4 intra-op threads vs 1, Oryon vs Cortex-X2 microarchitecture, cool-and-unthrottled vs 63–68 °C and downclocking, and SVE2/SME present vs absent. The 2× is therefore **real but unattributed**; none of the four can be isolated from this single run. In particular:

- **SME is present, executing, and worth ~4–9% — NOT the 2× (Measured; see `S26U_EXPERIMENTS.md` §2c).** `simpleperf` shows 83.2% of CPU inside `libonnxruntime.so`, and the hottest 40-byte loop (21.7% of all ORT time) disassembles to KleidiAI's **SME int8 kernel** — `smopa za0.s, p2/m, p2/m, z4.b, z8.b`, signed 8-bit outer-product into 32-bit accumulators. So SME *is* dispatched. But disabling it via `mlas.disable_kleidiai` costs only **4–9%** across two runs and both thread counts. **The 2× is therefore microarchitecture + thread count + thermal headroom, not the ISA rung.**
- **The thread-count jump alone is a plausible large share** — this is the first 4-thread run in the database, against a field of 1- and 2-thread runs.
- **The prime cores contributed nothing** (§2), so this figure is what six of eight Oryon cores produced.

Output correctness verified on all nine — `sampleOutput` = `पानी ।` for "Water.".

The 4-perf-core / 2-perf-core split still holds among the Armv8.2 parts (4-core 187–208 tok/s; 2-core 152–178, clean across three vendors). The **S22U is a different regime** and is deliberately not slotted into that split:

**On i8mm (the marquee question — Measured presence, Estimated effect, NOT the clean ~2× the report previously hoped for).** The S22U is the first device where `i8mm=true`, so MLAS can dispatch its i8mm int8-matmul kernel. It posts the **best medians in the database** (211.8 tok/s, TTFT 181, long-sentence median 188) — but every confounder points the *wrong* way for isolating i8mm: it ran **single-threaded** (1 vs 2 elsewhere) **and throttled** (X2 at 2054→1171 MHz), yet still led. That a **1-thread throttled** run beats **2-thread unthrottled** A78 runs is the strongest *indirect* evidence in the database that i8mm + the X2's IPC deliver a large per-thread int8 speedup. But the isolated i8mm share cannot be measured: it cannot be disabled at runtime, the thread count differs, the microarchitecture differs (X2 ≫ A78), and thermal throttling caps the result. **Verdict:** i8mm is active and the device leads on median throughput — call the per-thread gain **large but unquantified (Estimated)**; the specific "~2× int8 GEMM" figure is **not demonstrated** by this single confounded run. Its high tail (p95 332, p99 390, CV 39%/33%) is thermal, not silicon.

---

## 5. Memory / Storage (Measured)

| Metric | M315F | g73 | M14 | A015 | LXX518 | CPH2603 | V2338 | S22U | **S26U** |
|---|---|---|---|---|---|---|---|---|---|
| Peak PSS (MB) | 617 | 659 | 567 | **162** | **184** | 657 | 714 | 231 | **178** |
| RSS (MB) | 674 | 739 | 616 | **216** | **252** | 712 | 778 | 314 | **257** |
| Native heap (MB) | 520 | 543 | 480 | **60.5** | **62.1** | 531 | 554 | 113.5 | **74.5** |
| Java heap (MB) | 52.9 | 64.7 | 41.6 | 42.3 | 61.8 | 71.1 | 57.7 | 67.0 | 53.0 |
| Private dirty (MB) | 588 | 622 | 539 | **131** | **152** | 632 | 620 | 210 | **155** |
| `.ort` cache (MB) | 451 | 451 | 451 | 451 | 451 | 451 | 451 | 451 | 451 |
| source `.onnx` in filesDir | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

The storage win (source-copy eliminated, single `.ort`) holds on all nine devices.

### 5a. Entry #9 falsifies the MT6878-platform finding — CORRECTION

**The previous revision concluded that mmap effectiveness "tracks the Dimensity 7300 / MT6878 platform, NOT the OS version," having falsified OS, kernel, vendor and core-count. Entry #9 falsifies that conclusion too.** The S26 Ultra is a **Qualcomm** part, not MediaTek, and it lands at **74.5 MB native heap / 257 MB RSS / 178 MB PSS** — inside the "effective" band (60–62 MB / 216–252 MB), nowhere near the 480–554 MB group that includes two other Qualcomm devices.

| Group | Devices | Native heap | RSS |
|---|---|---|---|
| mmap **not** effective | M315F, g73, M14, CPH2603, V2338 | 480–554 MB | 616–778 MB |
| mmap **effective** | A015, LXX518 (MT6878), **S26U (Qualcomm SM8850)** | 60.5 / 62.1 / **74.5** MB | 216 / 252 / **257** MB |
| intermediate | S22U | 113.5 MB | 314 MB |

So "only MT6878 gets it" is dead. What the nine-device picture now supports is weaker and differently shaped — **the effective and intermediate group is exactly the set of devices with the newest kernels and/or the i8mm path**, and the surviving candidate explanations are:

| Hypothesis | Status after entry #9 |
|---|---|
| Dimensity 7300 / MT6878 platform | **Rejected** — the S26U is Qualcomm and gets it |
| Android 16 / SDK 36 | Still rejected — CPH2603 and V2338 are Android 16 and do not |
| **Kernel recency** | **Revived, and now the best single fit** — the three effective devices run 6.1.138 / 6.1.162 / **6.12.30**; every ineffective device runs 5.10–5.15 except the CPH2603 |
| **i8mm / SVE weight-repack path** | **Open** — the two i8mm devices (S22U 113.5, S26U 74.5) both sit below the plain-dotprod group, suggesting the execution path interacts with residency |
| Vendor / core count / RAM / page size | Rejected earlier and by entry #9 |

**The CPH2603 remains the one clean counter-example to the kernel hypothesis** (kernel 6.6.118, native heap 531 MB), so kernel recency is **not** sufficient on its own either. Honest status: **the mechanism is still unexplained, and the platform-specific reading published in the previous revision was wrong.** Entry #9's value here is negative-but-real — it removes a conclusion the database had over-committed to on a two-device sample. The resolution remains instrumentation, not more devices: log whether ORT accepted the mmap path and inspect the `.ort` file's backing mount (§10).

### 5b. Superseded reasoning (kept for provenance)

> **⚠ SUPERSEDED by §5a.** Everything from here to the end of §5 was written against the 8-device
> sample and concluded that the mmap win was exclusive to the MT6878 platform. Entry #9 (Qualcomm
> SM8850, 74.5 MB native heap) falsifies that. The elimination logic below is still valid for the
> hypotheses it tested — it is the *conclusion* that did not survive, including every sentence below
> asserting that "the MT6878 parts are the only ones". Retained unedited because the database is
> append-only and because the chain of falsified hypotheses is the useful part.

**The S22 Ultra is a third, intermediate memory profile (Measured) that softens — but does not overturn — the MT6878-exclusive reading.** Native heap 113.5 MB sits between the ~60 MB MT6878 group and the 480–554 MB group; RSS 314 MB and PSS 231 MB are **far below** the non-effective group (616–778 / 657–714) and only ~100 MB above the effective group. So on RSS/PSS the S22U looks much more like the mmap-*effective* group: the 451 MB of weights are largely **not** fully resident. The most likely reading (**Speculative**): mmap *is* partly taking effect here too, but the **i8mm path repacks int8 weights into its matmul-kernel layout**, and that prepacked buffer is resident, adding ~50 MB of native heap that the plain-dotprod path does not carry. Net: the clean "60 MB vs 500 MB" binary from entries #1–#7 becomes a spectrum once i8mm enters. This does not change §5's core finding (the MT6878 parts are still the only ones near-fully mmap'd, and the two other Qualcomm/MediaTek non-i8mm parts are still ~500 MB) — but it means the *mechanism* interacts with the execution path, which is one more reason the mmap-acceptance instrumentation in §10 is the right next step rather than more devices.

**Finding — mmap effectiveness tracks the Dimensity 7300 / MT6878 platform, NOT the OS version (Measured; corrected by entry #6).** Native heap splits cleanly into two groups:

| Group | Devices | Native heap | RSS |
|---|---|---|---|
| mmap **not** effective | M315F, g73, M14, CPH2603, V2338 | 480–554 MB | 616–778 MB |
| mmap **effective** | **A015, LXX518** (both MT6878) | 60.5 / 62.1 MB | 216 / 252 MB |
| **intermediate** (i8mm path) | S22U | 113.5 MB | 314 MB |

The ~451 MB of initializers are resident in the malloc arena on the first group and demand-paged from the file on the second. On the two MT6878 devices the *same APK with the same session options* runs at a **−87% to −89% native-heap** drop while producing correct output.

Two independent reasons the effective group is not an artifact:
- RSS is read from `/proc/self/statm` (kernel-truthful), so the 451 MB of weights are genuinely **not resident**.
- Native heap is a **malloc** metric, and page reclaim does not shrink a malloc arena — so this is not "more aggressive reclaim under memory pressure," and RAM ordering rules that out anyway (the A015 has the **least** RAM, 5.3 GB, yet ties the 7.2 GB LXX518, while the 8 GB g73 sits at 543 MB).

**Entries #6 and #7 jointly falsify every hypothesis except the platform one.** The two effective devices are exactly the two MT6878 parts. Two independent controls now rule out the alternatives:
- **CPH2603** (entry #6): newest OS *and* newest kernel in the database (Android 16, kernel 6.6.118), different MediaTek SoC (MT6877) → native heap **531 MB**, not effective.
- **V2338** (entry #7): **first non-MediaTek device — Qualcomm SM6450 — also Android 16**, and it is a **4-perf-core part like the two effective devices** (so the split is not about core count either) → native heap **554 MB**, not effective.

| Hypothesis for the mmap win | Status after entries #6 + #7 |
|---|---|
| Android 16 / SDK 36 | **Rejected twice** — CPH2603 *and* V2338 are both Android 16 and neither gets it |
| Kernel recency | **Rejected** — CPH2603 (6.6) and the effective devices (6.1) contradict it |
| 4-perf-core layout | **Rejected** — V2338 is 4-perf-core and does not get it; both branches appear in each group |
| Non-MediaTek / vendor | **Rejected** — the effective set is MediaTek, but so are three of the ineffective devices |
| 16 KB pages / RAM / OEM firmware | Rejected earlier — all 4 KB |
| **Dimensity 7300 / MT6878 platform** (storage stack, mount/filesystem, or vendor ORT/EP config baked into that platform) | **Only surviving hypothesis** — the two effective devices are exactly, and only, the two MT6878 parts |

**Interpretation (Estimated, now strongly constrained):** `session.use_memory_mapped_ort_model` takes effect specifically on the MT6878 platform and nowhere else in a 7-device, 4-vendor, 5-OS sample — most plausibly a storage/filesystem difference (how the `.ort` file is mounted and demand-paged) baked into that specific platform, not anything OS-, kernel-, vendor-, or core-count-driven.

**Evidence gap:** the app still does not log whether ORT accepted the mmap path, so the mechanism is inferred from footprint. The *trigger* is now isolated to the MT6878 platform with two independent controls; the *reason within that platform* remains unconfirmed. Definitive next step: instrument ORT mmap acceptance and inspect the `.ort` file's backing filesystem/mount on an MT6878 device vs any other. Treat "mmap reduces memory" as **specific to the Dimensity 7300 / MT6878 platform, demonstrated on two devices and falsified as an OS/kernel/vendor/core-count effect** — not a general property of the build.

---

## 6. Headline Cross-Device Result

| Device | ISA | dotprod | perf cores | perf clock | intra / affinity | tokens/sec | long median | cold startup |
|---|---|---|---|---|---|---|---|---|
| SM-M315F (9611) | Armv8.0 | **no** | 4 | 1664 | 2 / ON | 50.3 | 894 | 17197 |
| SM-E146B (1330) | Armv8.2 | yes | 2 | 2288 | 1 / OFF | 152.4 | 307 | 6118 |
| moto g73 (930) | Armv8.2 | yes | 2 | 2200 | 1 / OFF | 177.5 | 256 | 6936 |
| **A015 (D7300)** | Armv8.2 | yes | **4** | 2500 | **2 / ON** | 200.5 | 232 | 4430 |
| **LXX518 (D7300)** | Armv8.2 | yes | **4** | 2500 | **2 / ON** | 207.5 | 219 | 3904 |
| CPH2603 (D1080) | Armv8.2 | yes | 2 | 2400† | 1 / OFF | 151.9† | 304† | 4818 |
| V2338 (SD6 Gen1) | Armv8.2 | yes | **4** | 2208 | **2 / ON** | 186.8 | 247 | 7960 |
| S22U (SD8 Gen1) | **Armv8.6+i8mm** | yes | 1‡ | X2 2054† | 1 / OFF‡ | 211.8†‡ | 188†‡ | 16205† |
| **S26U (SD8 Elite Gen 5)** | **ARMv9+i8mm+SVE2+SME** | yes | **8** | Oryon 3187 | **4 / OFF** | **412.8** | **106** | **2472** |

†throttled ‡single-threaded/misclassified (§1–§2) — treat S22U throughput as a lower bound. Entry #9 carries **no such qualifier**: cool, unthrottled, multithreaded. Effects visible:

### 6a. ISA step — Armv8.0 → Armv8.2 (dotprod): **+200–250% throughput** — Measured enabler, Estimated magnitude
The Exynos 9611 is Armv8.0 with no dotprod, so MLAS runs plain-NEON int8. The A78 devices are Armv8.2 with dotprod, so MLAS dispatches its **SDOT/UDOT int8 GEMM kernel at runtime**. tokens/sec goes 50 → 152–207 (**4.1× at the top end, LXX518**); even the throttled 2-core CPH2603 clears 150. This is the dominant jump and it came **for free** — same binary, no recompile, ORT runtime feature-dispatch. The isolated share of dotprod vs the A78 microarchitecture (6-wide vs A73 2-wide, deeper OoO) cannot be separated without disabling dotprod at runtime (not possible), so the *enabler* is Measured, the *exact split* is Estimated.

### 6a′. The ISA ladder's next rung — i8mm (Armv8.6): reached, but not cleanly isolated
§10 asked for years for an i8mm part to test whether MLAS's i8mm int8-matmul kernel (~2× GEMM over dotprod, in theory) shows up. Entry #8 is that part. What the ISA ladder now looks like, Measured:

| ISA rung | example | int8 kernel MLAS dispatches | tokens/sec |
|---|---|---|---|
| Armv8.0 (NEON) | M315F | plain NEON | 50.3 |
| Armv8.2 (dotprod) | LXX518 | SDOT/UDOT | 207.5 (2 threads) |
| **Armv8.6 (i8mm)** | S22U | **i8mm matmul** | 211.8 (**1 thread, throttled**) |
| **ARMv9 (i8mm + SVE2 + SME)** | **S26U** | **i8mm; SVE2/SME dispatch unverified** | **412.8 (4 threads, cool)** |

The i8mm kernel *is* dispatched (`i8mm=true` detected). But the S22U's headline number understates the rung badly, because it ran **single-threaded and throttled** while the dotprod leader ran 2 threads cool. The honest statement: **matching a cool multi-thread dotprod device on ~1 throttled thread is strong indirect evidence i8mm (plus X2 IPC) is a big per-thread win, but the clean "~2×" is not demonstrated** — it needs the policy fix (§8) and a cool run before i8mm's contribution can be read off the total. Enabler **Measured**, magnitude **Estimated**, "2×" **not shown**.

### 6a″. Entry #9 delivers the cool multithreaded run — and still cannot isolate the ISA
The previous revision named exactly one experiment as its top priority: *fix the classifier, then re-run capable silicon cool and multithreaded.* Entry #9 is that run, on newer silicon than the S22U. The result is a **2× jump over the whole previous field** (412.8 vs 211.8 tok/s) — and it remains **unattributable to the ISA**, because entry #9 improved four variables simultaneously (threads 1→4, Oryon vs X2, cool vs throttled, SVE2/SME added).

What entry #9 *does* settle, and what it does not:

| Question | Status |
|---|---|
| Does the classifier fix convert a mis-scored flagship into a multithreaded run? | **Answered — Measured.** intra=4, verified in the on-device policy log (§1) |
| Is there large headroom above the Armv8.2 plateau? | **Answered — Measured.** 412.8 vs a 152–208 tok/s field |
| Is the i8mm/SME kernel responsible for that headroom? | **Answered — NO.** Isolated by `mlas.disable_kleidiai`: worth **4–9%**, not the 2× (`S26U_EXPERIMENTS.md` §2c) |
| Does SME (without SME2) engage at all in ORT 1.27.0? | **Answered — YES.** `simpleperf` + disassembly show KleidiAI's `smopa` int8 SME kernel is the hottest loop in the app |

The clean isolation requires holding the device fixed and varying one axis — an intra-thread sweep (1/2/4/6) on *this* device, plus the operator profiling from `c5dff5f`. That is a same-device experiment, not another phone (§10).

**Both were run, and the answer is in (see `S26U_EXPERIMENTS.md`).** The thread sweep on the production path shows `intra1`/`intra2` beating shipping `intra4` by ≈5%; `simpleperf` proves KleidiAI's SME int8 kernel is executing and is the hottest loop in the app; and the `mlas.disable_kleidiai` A/B prices that kernel at **4–9%**. **So the ISA rung is worth single digits and the 2× belongs to the Oryon core, the thread count and the thermal headroom.** §6a′'s hoped-for "~2× from i8mm/SME" is answered in the negative — by measurement, not inference.

### 6b. Within Armv8.2 — the controlled A78 pair (g73 vs M14): clock does NOT explain throughput
This is the most informative comparison in the database: **same core IP (A78+A55), same ISA, same dotprod, same policy.** They differ mainly in SoC vendor, memory subsystem, and clock.

| Metric | g73 (930) | M14 (1330) | Δ | Winner |
|---|---|---|---|---|
| Perf clock observed | 2200 | 2288 | +4% | M14 (faster) |
| tokens/sec | 177.5 | 152.4 | −14.1% | **g73** |
| TTFT | 265 | 327 | +23.4% | **g73** |
| long median | 256 | 307 | +20% | **g73** |
| cold startup | 6936 | 6118 | −11.8% | **M14** |
| hot startup | 1905 | 1487 | −21.9% | **M14** |

**The M14 has the higher clock yet loses on inference by 14%.** Compute factors (core, ISA, clock) favor the M14, so they are not the cause. The differentiator is **non-compute: memory bandwidth / SoC fabric** — the Dimensity 930 (8 GB) feeds the int8 GEMM faster than the Exynos 1330 (6 GB, lower-tier fabric). **Estimated** (no PMU counters unrooted), but the controlled design makes it a strong inference: hold compute equal-or-better and throughput still drops → **inference is memory-bandwidth-bound, not core-clock-bound**, on these Armv8.2 parts.

Startup dissociates the opposite way — the M14 wins on startup (Android 15, kernel 5.15, faster storage/tokenizer). **Startup is IO/single-thread-latency bound; sustained inference is memory-bandwidth bound.** Two bottlenecks, two winners.

### 6c. The affinity cell finally closes — and affinity still cannot be credited
Entry #4 is the first device that is Armv8.2 **and** has 4 perf cores, so the adaptive policy selected `intra=2` **with affinity pinning active** (`affinity=5,6,7,8`, 1-based → cpu4-7, exactly the four A78s). Every earlier dotprod device had 2 perf cores and ran affinity OFF.

| | g73 (930) | M14 (1330) | **A015 (7300)** |
|---|---|---|---|
| ISA / dotprod | v8.2 / yes | v8.2 / yes | v8.2 / yes |
| perf cores | 2 | 2 | **4** |
| intra-op threads | 1 | 1 | **2** |
| affinity | OFF | OFF | **ON, pinned to A78s** |
| perf clock | 2200 | 2288 | 2500 |
| tokens/sec | 177.5 | 152.4 | **200.5** |

A015 leads, but **the comparison is confounded** — it simultaneously changes thread count (1→2), core count (2→4), clock (+14%), SoC generation, and memory subsystem. The +13% over the g73 cannot be attributed to affinity, or even to the second thread, from this data.

What *can* be said (Measured): pinning to the A78 cluster produced the **tightest latency distribution in the database** — long-sentence stdev 3.1 ms (1.3% CV) versus 7.7 ms on the g73 and 15.6 ms on the M14, with perf cores still at 2500 MHz at the end of the run. That is consistent with the scheduler not migrating the GEMM threads onto A55s mid-run. **Estimated**, because `nr_migrations` is unreadable unrooted (the reported `0` covers the main thread only), so migration suppression is inferred from variance, not observed.

**Verdict on affinity after nine devices:** correct, cheap, self-disabling on the three 2-big-core parts, on the tri-cluster S22U (misclassified to 1 perf core → disabled) *and* on the uniform-IP S26U (no little cluster to pin away from → correctly disabled), active on the four 4-big-core parts, and still **never shown to improve throughput**. An on-device A/B (same APK, affinity string forced null) is the one experiment that would settle it; nothing in the current database can. Entry #9 adds a twist: there, affinity being OFF is *correct by the rule* yet leaves the two fastest cores idle (§2) — so the S26U wants the opposite experiment, pinning **to** the top tier rather than away from a little one.

### 6d. Replication on identical silicon — A015 vs LXX518 (Measured)
Entries #4 and #5 are the same SoC, cores, clocks, ISA, OS major version and page size, from **two unrelated OEMs**. This is the database's reproducibility check.

| | A015 | LXX518 | Δ |
|---|---|---|---|
| SoC / cores / clock | MT6878, 4×A78+4×A55, 2500 | identical | — |
| Kernel | 6.1.162 | 6.1.138 | different patch level |
| RAM | 5.3 GB | 7.2 GB | +36% |
| **Policy selected** | intra=2, affinity=5,6,7,8 | **intra=2, affinity=5,6,7,8** | **identical** |
| Native heap | 60.5 MB | 62.1 MB | +2.6% |
| tokens/sec | 200.5 | 207.5 | +3.5% |
| long median | 232 | 219 | −5.6% |
| cold engine-init | 4430 | 3904 | −11.9% |
| long-sentence CV | 1.3% | 4.3% | 3× wider |

Three things this establishes:

1. **The adaptive policy is deterministic across OEM firmware (Measured).** Same silicon, independently built ROMs, byte-identical policy string. The rules key off hardware, not vendor — the strongest available evidence that §1's policy derivation is a rule and not a fit.
2. **The §5 memory result replicates (Measured).** 60.5 → 62.1 MB native heap on a device with 36% more RAM. See §5.
3. **The inference/startup deltas are within-noise and are not a ranking (Estimated).** The LXX518 leads on medians, but it also ran 4 °C warmer with 3× the latency variance, and the +3.5% throughput gap is smaller than its own run-to-run spread. On identical silicon the honest conclusion is **parity**, not "the LAVA is faster". The one delta large enough to be worth noting is cold startup (−11.9%), plausibly storage/firmware — **Speculative**, single pair.

**Policy determinism confirmed on both branches and across three CPU vendors (entries #6 + #7).** The CPH2603 (2× A78, MT6877) selected `intra=1, affinity OFF` — byte-identical to g73 and M14 on the 2-perf-core branch. The **V2338 (Qualcomm SM6450, 4× A78)** selected `intra=2, affinity=5,6,7,8 ON` — byte-identical to the A015/LXX518 (MediaTek) and M315F (Exynos) on the 4-perf-core branch. Seven **2-cluster** devices, five OEMs, **three silicon vendors (MediaTek, Exynos, Qualcomm)**, five OS/kernel combinations — and the policy string is a pure function of perf-core count every time. It carries **zero device-specific and zero vendor-specific state**: detection reads `/proc/cpuinfo` topology, which is vendor-neutral, and the cross-vendor agreement is exactly what proves that.

**The determinism is real but the *classifier* is wrong on 3-cluster parts (entry #8).** The same vendor-neutral detection that is a strength on big.LITTLE becomes a **liability** on Arm's prime+mid+little Armv9 layout: it counted the S22 Ultra's Cortex-X2 as the only perf core and the three Cortex-A710 mid cores as "efficiency," so the policy correctly-and-deterministically produced the *wrong* config (1 thread). Determinism ≠ correctness: the rule is stable, but the perf/eff threshold needs a third tier. This is the single highest-value code fix surfaced by the whole cross-device sweep — see §8 and §9.

---

## 7. Bottleneck Classification

| Workload | Bound by | Evidence |
|---|---|---|
| Cold startup | one-time graph optimization (model-load) + IO | model-load is 14.1s of 17.2s cold on M315F; drops to warm mmap; M14 (newer IO) wins |
| Sustained inference (Armv8.0) | absent int8 ISA acceleration | 50 tok/s, plain-NEON int8, no dotprod |
| Sustained inference (Armv8.2) | **memory bandwidth** | higher-clock M14 loses to g73; controlled pair holds compute equal |
| Memory footprint (entries #1–#3) | copied initializers | 480–543 MB native heap regardless of mmap |
| Memory footprint (MT6878 only) | **not bound — mmap effective** | 60.5 / 62.1 MB native heap on the two D7300 parts; 480–554 MB on the five non-i8mm others; S22U intermediate 113 MB (i8mm repack, §5) |
| Sustained inference (throttled) | big-cluster DVFS | CPH2603 (A78→1430) and S22U (X2→1171, cores 63–68 °C) both throttled; high CV |
| **Thread parallelism (S22U)** | **policy underuse** | tri-cluster misclassified → 1 intra-op thread; 3 A710 cores idle for inference |
| **Thread parallelism (S26U, pre-fix)** | **policy underuse — fixed** | uniform-IP CPU scored perf=2 → 1 thread; `e581a45` gives perf=8 → intra=4 |
| **Prime-core utilisation (S26U)** | **scheduler placement** | primes idle at 883 MHz throughout; 412.8 tok/s came from the 6 perf Oryons alone |
| Sustained inference (ARMv9, cool, 4-thread) | **not yet identified** | no throttle, no clock ceiling hit, 5.1% CV — the bandwidth wall seen on Armv8.2 is not visibly binding here |
| Thread affinity | not a bottleneck | inert or unattributable on all nine |

---

## 8. Optimization Opportunities (per device, unique, ranked)

Not repeating already-implemented work (ORT upgrade, `.ort`+mmap, adaptive policy, affinity, baseline profile, benchmark framework).

**SM-M315F / Exynos 9611 (Armv8.0):** the interesting int8 accelerations are hardware-gated and unavailable.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| XNNPACK EP A/B vs MLAS | yes | Speculative modest | Low | Low |
| Pre-baked `.ort` in assets (skip cold bake) | yes | Estimated (~14s one-time) | Low | Low |
| mmap initializers to cut native heap | yes | Estimated memory only | Low | Med |
| dotprod / i8mm / SME / KleidiAI | **NO — needs Armv8.2+/8.6/v9** | — | — | — |

**moto g73 / Dimensity 930 (Armv8.2):**
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| intra=2 on both A78 (policy underuses 2 big cores) | yes | Estimated moderate (if not bandwidth-capped) | Low | Low |
| KleidiAI dotprod microkernels | yes | Estimated moderate over MLAS | Medium | Low-Med |
| NNAPI → MediaTek APU | yes | Speculative; operator-coverage risk | High | High |
| i8mm / SME | **NO — needs Armv8.6 / v9.2** | — | — | — |

**SM-E146B / Exynos 1330 — and OPPO CPH2603 / MT6877 (Armv8.2, 2× A78, bandwidth-bound):** same opportunity set; the CPH2603 adds a device-specific one — its A78 cluster throttled during the run, so a thermal/sustained-clock investigation (why it dropped to 1430 MHz) is the first lever there before any code change.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| Reduce memory traffic (int4 weights / GEMM tiling for L2 reuse) — **best lever for this chip** | yes | Estimated (directly targets the measured bound) | High | Med |
| KleidiAI dotprod microkernels | yes | Estimated moderate | Medium | Low-Med |
| intra=2 on both A78 | yes | Estimated small/none (bandwidth-bound) | Low | Low |
| NNAPI → Exynos NPU | yes | Speculative; coverage risk | High | High |
| i8mm / SME | **NO — needs Armv8.6 / v9.2** | — | — | — |

**A015 and LXX518 / Dimensity 7300 (Armv8.2, 4× A78, Android 16, mmap already effective):** identical hardware and identical selected policy, so one opportunity table covers both. The memory lever is already paid here; remaining levers are compute and thread scaling.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| Raise intra-op to 4 (policy uses 2 of 4 A78s) — untested headroom unique to this 4-big-core part | yes | Estimated moderate; may be bandwidth-capped as in §6b | Low | Low |
| Affinity A/B (force null) to finally attribute the pin | yes | Measurement, not speed | Low | Low |
| KleidiAI dotprod microkernels | yes | Estimated moderate over MLAS | Medium | Low-Med |
| Instrument ORT mmap acceptance, then re-run #2/#3 | yes | Explains the −87…−89% memory result, now replicated on two OEMs | Low | Low |
| NNAPI → MediaTek APU | yes | Speculative; operator-coverage risk | High | High |
| i8mm / SME | **NO — needs Armv8.6 / v9.2** | — | — | — |

**vivo V2338 / Snapdragon 6 Gen 1 (Armv8.2, 4× A78, Qualcomm — first non-MediaTek):** unique because it is the only device where Qualcomm-specific EPs exist. mmap is **not** effective here (§5), so the memory lever is unpaid but not addressable without the MT6878 platform trick.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| **QNN EP → Hexagon DSP/HTP** (Qualcomm-only; the int8 model is a natural fit) | yes | Speculative, potentially large on a supported op set | High | High |
| KleidiAI dotprod microkernels | yes | Estimated moderate over MLAS | Medium | Low-Med |
| Investigate slow cold model-load (6923 ms, worst of the Android 16 parts) — storage/IO, not CPU | yes | Estimated (startup only) | Medium | Low |
| Raise intra-op to 4 (uses 2 of 4 A78s; V2338 is *less* bandwidth-starved than the 2-core parts, §4) | yes | Estimated moderate | Low | Low |
| NNAPI → Qualcomm accelerator | yes | Speculative; QNN EP is the better Qualcomm path | High | High |
| i8mm / SME | **NO — needs Armv8.6 / v9.2** | — | — | — |

**Samsung S22 Ultra / Snapdragon 8 Gen 1 (Armv8.6, i8mm, tri-cluster X2+A710+A510):** the only i8mm device — its opportunities are the highest-leverage in the database because two of them are *code bugs limiting a top-tier chip*.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| **Fix perf/eff classifier for 3-cluster (count A710 mids as perf)** — would move intra=1→≥3 and re-enable affinity; §1/§6d | yes | **Estimated large** (device ran single-threaded) | Low-Med | Low |
| **Re-benchmark cool + multithread to quantify i8mm** — the one run that isolates §6a′ | yes | Measurement (unlocks the ~2× question) | Low | Low |
| QNN EP → Hexagon (Qualcomm-only) | yes | Speculative, potentially large | High | High |
| SME / SVE2 | **NO — SVE hwcap not exposed; SME needs Armv9.2** | — | — | — |
| Investigate throttled cold bake (16.2 s at 63–68 °C) — ship a pre-baked `.ort` in assets to skip it | yes | Estimated (cold startup only) | Low | Low |

**Samsung S26 Ultra / Snapdragon 8 Elite Gen 5 (ARMv9, i8mm + SVE2 + SME, 8× uniform Oryon):** the fastest device in the database and the only one that can answer the SME question. Its opportunities are measurement-first — the silicon is already ahead of what the stack knows how to exploit.
| Opportunity | Applicable | Gain | Difficulty | Risk |
|---|---|---|---|---|
| ~~Intra-thread sweep~~ **DONE** — `intra1`/`intra2` beat shipping `intra4` by ≈5% long / ≈13% short on the production path | — | **Measured**, see `S26U_EXPERIMENTS.md` §2b | — | — |
| ~~ORT operator profiling for SME/SVE2 dispatch~~ **DONE — cannot answer it**; MLAS's SIMD choice is invisible to ORT profiling. Needs `simpleperf` | — | **Negative result**, §1 there | — | — |
| ~~Pin to the 2 prime cores~~ **DONE — NO GAIN.** `intra2_primePin` 99 ms vs plain `intra2` 99 ms. The idle primes were not costing throughput; opportunity withdrawn | — | **Negative result**, §2b C3 | — | — |
| Revisit `threads = perfCores/2` for uniform-IP parts — measured ≈5% on the table here, but **not applied**: one device, one 8-perf-core topology, versus a nine-device-validated rule | yes | Estimated small; needs a second 8-perf-core part | Low | Med (over-fit risk) |
| `simpleperf` symbol capture — the only remaining route to the SME/i8mm question | yes | Measurement | Medium | Low |
| QNN EP → Hexagon (Qualcomm-only) | yes | Speculative, potentially large | High | High |
| KleidiAI microkernels — **first device where the SME-gated int8 path could engage** | yes | Speculative; gated on whether base SME (no SME2) suffices | Medium | Low-Med |

---

## 9. Conclusions

1. **The stack scales with Arm ISA generation for free.** The single biggest gain (Armv8.0 → 8.2 dotprod, up to **4.1×** throughput: 50.3 → 207.5 tok/s) required no code change — MLAS runtime feature-dispatch delivered it on the newer parts. This is a stronger competition story than any hand-tuned pin.
2. **On modern Armv8.2 parts, inference is memory-bandwidth-bound, not clock-bound.** Proven by the controlled g73/M14 pair: the higher-clocked device is 14% slower. The next real inference win is **traffic reduction** (int4, tiling, KleidiAI), not more threads or higher clocks.
3. **Startup and inference have different bottlenecks** (IO/latency vs bandwidth) and different device winners — optimize them separately. Entry #4 is the exception that wins both, because it improves the ISA, the storage stack and the OS at once.
4. **Affinity still has no attributable gain after nine devices** — self-disabled on the 2-big-core parts, on the tri-cluster S22U (via misclassification) and on the uniform-IP S26U (correctly, no little cluster), active-but-confounded on the four 4-big-core parts (§6c). Keep it; do not claim a throughput gain without an A/B. On the S26U the open question inverts: pin *to* the idle prime tier.
5. **~~The mmap memory win is specific to the Dimensity 7300 / MT6878 platform~~ — RETRACTED by entry #9 (§5a).** The Qualcomm SM8850 reaches **74.5 MB native heap / 257 MB RSS**, inside the effective band, on non-MediaTek silicon. The platform-exclusive reading was an over-commitment to a two-device sample. Current status: **the mechanism is unexplained.** Kernel recency is the best-fitting surviving candidate (effective devices run 6.1.138 / 6.1.162 / 6.12.30; the ineffective ones mostly 5.10–5.15) but the CPH2603 (kernel 6.6.118, 531 MB) is a clean counter-example, so it is not sufficient either. This conclusion has now been revised three times — "mmap never reduces memory" (#1–#3), "Android 16 triggers it" (#4–#5), "MT6878 only" (#6–#8) — which is itself the argument for resolving it by instrumentation rather than by adding a tenth device.
6. **The classifier has now failed twice on top-tier silicon, in opposite directions, and both are fixed (Measured, #5–#9).** On the seven 2-cluster devices the policy string is a pure function of perf-core count — §1's rule is not a fit. But the S22 Ultra's prime+mid+little layout misfiled three A710 mids as efficiency (→ 1 thread), and the S26 Ultra's **uniform-IP** 8× Oryon misfiled six performance cores as efficiency (→ would have been 1 thread). `dc3011e` fixed the first by splitting at the bottom tier; `e581a45` fixed the second by gating that split on core IP, because **frequency ratio provably cannot separate the cases** (the D930's real A55/A78 split is 0.91, *higher* than the Oryon's 0.77). The lesson generalises: **a frequency tier is only an efficiency cluster if its cores are different silicon.** Determinism ≠ correctness, and each new CPU topology has cost one classifier bug — worth expecting a third.
7. **i8mm reached (#8), then ARMv9 + SVE2 + SME reached cool and multithreaded (#9) — the ceiling moved 2×, the cause is still not isolated.** Entry #9 posts **412.8 tok/s against a 152–208 field** with no throttle, no thread handicap and a 5.1% CV, and takes every startup row as well (cold 2472 ms, warm 923 ms). But it improved four variables at once (threads 1→4, Oryon vs X2, cool vs throttled, SVE2/SME added), so **no share of it is attributable to the ISA**, and whether ORT dispatched an SME or SVE2 kernel at all **was not measured**. The headroom above Armv8.2 is now Measured and large; its *cause* is Estimated. The remaining experiments are same-device (thread sweep + operator profiling), not another phone.
8. **The fastest run in the database never used the fastest cores on the chip.** The S26U's two 4742 MHz prime Oryons sat at 883 MHz throughout while the six 3629 MHz performance cores did the work — 412.8 tok/s is a **6-core figure**. Affinity is OFF there precisely because uniform IP means there is no little cluster to pin away from, which is correct by the current rule and still leaves the best cores idle. Pinning the top frequency tier is a new, cheap, untested lever (§8).
8. **Perf-core count sets the throughput tier; within a tier, clock still moves it.** 4-perf-core Armv8.2 parts reach 187–208 tok/s; 2-perf-core parts top out ~150 regardless of OS. The V2338 (A78 @ 2208) trails the MT6878 parts (@ 2500) roughly in clock proportion, so the 2-thread runs are less bandwidth-starved than the 2-core §6b pair — bandwidth-bound is a property of the 1-thread/2-core config, not the workload universally.
9. **The framework's honesty mechanisms earned their keep on the three stressed entries.** #6 throttled (caught from `perCoreFreqKhz`, throughput demoted to a lower bound); #7 had one corrupt phase (877 s warm stall, isolated and rejected by per-phase measurement); #8 throttled hard *and* was policy-misclassified (both flagged, capability facts kept, throughput demoted). No device discarded wholesale; no bad number trusted.
10. **Real silicon temperatures now available on the two Qualcomm parts.** V2338 cores 54–57 °C, S22U cores **63–68 °C** while their battery surfaces read 34 / 40.7 °C — confirming the battery-temp gate on the six MediaTek/Exynos parts was **conservative** (~20 °C under true silicon temp), and pinpointing thermal as the direct cause of the S22U's variance.

## 10. Next Experiment Wanted

**Hardware coverage is now complete for the questions this database was built to answer** — Armv8.0 → ARMv9, four silicon vendors, 2- / 4- / tri-cluster and uniform-IP topologies, dotprod → i8mm → SVE2 + SME. Item #4 of the previous revision (an SME-exposing part) is **closed by entry #9**. Every remaining item is an experiment on hardware already in hand, and **another phone would add nothing**:

**Items 1 and 2 have now been run** — see [`S26U_EXPERIMENTS.md`](S26U_EXPERIMENTS.md). Both returned partly negative results, which changes what is worth doing next:

1. ~~Intra-thread sweep on the S26U.~~ **DONE.** Result: `intra1`/`intra2` beat the shipping `intra4` by ≈10% drift-corrected, and `cpuArena=false` (also shipping) costs ≈12% on this device. But the sweep runs the **non-production load path** (`optCache` off, ALL_OPT, source `.onnx`), so this is evidence, not proof. **The replacement item is a production-path A/B at intra 1/2/4** — it bears directly on a shipping default and is the highest-value open experiment.
2. ~~ORT operator profiling to detect SME/SVE2 dispatch.~~ **DONE, and it cannot answer the question.** MLAS's internal SIMD dispatch is invisible to ORT profiling. It did establish that the workload is **not GEMM-dominated** (int8 GEMM ≈45%, tensor movement ≈31%, ORT dispatch overhead ≈21%), which redirects effort toward **fusion and traffic reduction rather than more threads**. **The replacement item is `simpleperf record` + symbol capture**, the only remaining route to the SME question.
3. ~~Pin the idle prime cores.~~ **DONE — no gain.** Workers pinned to the two idle 4742 MHz Oryons measured 99 ms against plain `intra2`'s 99 ms on the production path. The idle prime tier was not costing throughput, so this opportunity is **closed, not pending**. Also settled on the same run: `cpuArena=false` (shipping) is **correct** — turning the arena on is 1.9% slower, refuting the non-production sweep's claim that it cost 12%.
4. **Instrument ORT mmap acceptance** and inspect the `.ort` backing mount. §5a retracted the platform-exclusive finding; the conclusion has now been rewritten three times on footprint inference alone. Device-swapping is *proven* exhausted — only a log line resolves it.
5. **QNN-EP experiment on any of the three Qualcomm devices** — Hexagon/HTP remains the one accelerator path never tested (§8).
6. **Regenerate the Baseline Profile.** Blocked since Phase 4 because ART needs API 33+ to collect and the only phone was API 31. The S26U is **API 36**, so `:app:generateReleaseBaselineProfile` can finally run — the hand-authored `baseline-prof.txt` can be replaced with a real one.

**If a device is ever wanted again:** only an **SME2**-exposing part would add a genuinely new capability cell, and only after items 1–2 establish whether base SME does anything for this workload. Adding a tenth Armv8.2 or i8mm phone would produce a row and no knowledge.
