# BhashaBridge V4 — Performance Root-Cause & Optimization Analysis

**Analyst view:** "Where is performance being lost, why, and what to optimize next?"
**Source data:** 8-device cross-device benchmark DB (`CROSS_DEVICE_REPORT.md`), ORT 1.27.0, CPU EP (MLAS), IndicTrans2 200M INT8, EN→HI, 30 iters / 5 warmup, per-sentence stats only (pooled sustained is bimodal-invalid).
**Evidence discipline:** every claim tagged **Measured** (read off-device), **Inferred** (controlled comparison, no direct counter), **Speculative** (plausible, untested). No PMU/energy/migration counters available (unrooted). Nothing fabricated.

Device shorthand: M315F (Exynos 9611, Armv8.0) · g73 (D930) · M14 (Exynos 1330) · A015 / LXX518 (D7300 MT6878, 4×A78) · CPH2603 (D1080 MT6877) · V2338 (SD6 Gen1) · S22U (SD8 Gen1, Armv8.6 **i8mm**, tri-cluster).

---

## 1. Executive Summary

The application is **not compute-bound in the way its ISA tier suggests**. Three independent bottlenecks dominate, each on a different axis, and the highest-value fixes remaining are **code, not silicon**:

1. **Startup is IO + one-time-graph-bake bound**, not inference. Cold engine-init ranges 3.9 s → 17.2 s; the entire cold→warm collapse (−68% to −93%) is the `.ort` mmap cache skipping a one-time bake. Warm/hot are pure mmap reload and cluster tightly (1.0–3.7 s). **Inferred → Measured** (the cold/warm split is measured; the bake-vs-IO share is inferred from the M315F 14.1 s model-load dominating its 17.2 s cold).

2. **Sustained inference on Armv8.2 parts is memory-bandwidth-bound, not clock-bound.** The controlled g73/M14 pair (same A78+A55 IP, same ISA, same policy) has the **higher-clocked device losing by 14%**. Adding threads/clock will not move a bandwidth wall — traffic reduction (int4, tiling, KleidiAI) will.

3. **A top-tier chip is being throttled by a software classifier bug.** The S22 Ultra ran inference **single-threaded on one core** because the perf/eff detector misfiled its three Cortex-A710 mid cores as efficiency cores. This is the single highest-ROI fix in the sweep: **low difficulty, large expected gain, zero hardware dependency.**

The winning "optimization" already shipped was free: **Armv8.0→8.2 dotprod via MLAS runtime dispatch gave up to 4.1× throughput (50.3→207.5 tok/s) with no code change.** The next ISA rung (i8mm) is reached on the S22U but its effect is **confounded** (single-thread + throttle) and remains **unquantified**.

---

## 2. Key Findings

| # | Finding | Tag | Decisive evidence |
|---|---|---|---|
| K1 | ISA generation is the dominant lever, and it's free | Measured (enabler) / Inferred (split) | 50.3 → 207.5 tok/s across Armv8.0→8.2; same APK |
| K2 | Armv8.2 inference is bandwidth-bound | Inferred | M14 clocked +4%, throughput −14% vs g73 |
| K3 | Startup ≠ inference bottleneck | Measured | cold 3.9–17.2 s, warm 1.0–3.7 s; different device winners |
| K4 | mmap memory win is MT6878-platform-specific | Measured (trigger) / Inferred (mechanism) | 60 MB native heap on 2× MT6878, 480–554 on 5 others; OS/kernel/vendor/core-count all falsified |
| K5 | Adaptive policy is deterministic across 3 vendors on big.LITTLE | Measured | byte-identical policy string, pure function of perf-core count |
| K6 | …but the classifier is WRONG on tri-cluster Armv9 | Measured | S22U: X2-only counted perf, 3× A710 misfiled → 1 thread |
| K7 | i8mm reached, effect not isolated | Measured (presence) / Estimated (magnitude) | i8mm=true; leads DB (211.8 tok/s) but single-thread + throttled |
| K8 | Perf-core **count** sets throughput tier | Measured | 4-core 187–208 tok/s; 2-core ~150, OS-independent |
| K9 | Affinity has no attributable throughput gain after 8 devices | Inferred | self-disables on 2-core parts; confounded on 4-core; tightest variance but no A/B |
| K10 | Thermal throttling is real and measured on both Qualcomm parts | Measured | S22U X2 2995→1171, cores 63–68 °C, CV 39% |

---

## 3. Device-by-Device Analysis

Each profile ends with a **bottleneck class** and the evidence forcing that class.

### M315F — Exynos 9611, Armv8.0-A, 4×A73 + 4×A53
- **ISA/caps:** no dotprod, no fp16, no i8mm. MLAS falls to **plain-NEON int8**.
- **Results:** 50.3 tok/s (DB floor), TTFT 1572 ms, long median 894 ms, cold 17197 ms, native heap 520 MB.
- **Strength:** none relative to the field — this is the reference floor.
- **Weakness:** no int8 ISA acceleration; A73 is 2-wide, shallow OoO.
- **Bottleneck: compute-bound (absent int8 ISA).** *Evidence:* 4.1× slower than the same-workload Armv8.2 parts with identical binary; the only variable that changed is dotprod availability + core width. Its cold model-load is 14.1 s of 17.2 s → cold startup here is **kernel/graph-bake + IO bound** secondarily.

### g73 — Dimensity 930, Armv8.2-A, 2×A78 + 6×A55
- **Caps:** dotprod + fp16, no i8mm. Policy: intra=1, affinity OFF (2 perf cores).
- **Results:** 177.5 tok/s, TTFT 265, long median 256, cold 6936, native 543 MB, CV 8%/3%.
- **Strength:** best 2-perf-core throughput; beats the higher-clocked M14.
- **Weakness:** only 1 intra-op thread (policy underuses both A78s); memory not mmap'd (543 MB).
- **Bottleneck: memory-bandwidth-bound.** *Evidence:* see §5 controlled pair — wins despite lower clock, so compute isn't the wall; D930 fabric feeds int8 GEMM faster than Exynos 1330.

### M14 — Exynos 1330, Armv8.2-A, 2×A78 + 6×A55
- **Caps:** dotprod + fp16. Policy: intra=1, affinity OFF. Clock 2288 (observed; 2400 rated, under-runs).
- **Results:** 152.4 tok/s, TTFT 327, long median 307, cold 6118, hot 1487 (fast), native 480 MB.
- **Strength:** **fastest startup among the 2-core parts** (Android 15, kernel 5.15, faster storage/tokenizer).
- **Weakness:** loses inference by 14% to g73 despite +4% clock.
- **Bottleneck: memory-bandwidth-bound (inference); IO/latency-bound (startup, and it wins there).** *Evidence:* the two axes dissociate cleanly — same device is inference-loser, startup-winner.

### A015 & LXX518 — Dimensity 7300 (MT6878), Armv8.2-A, 4×A78 + 4×A55 *(identical silicon, 2 OEMs)*
- **Caps:** dotprod + fp16. Policy: **intra=2, affinity=5,6,7,8 ON** (4 perf cores). Android 16.
- **Results:** 200.5 / 207.5 tok/s, TTFT 242 / 227, long median 232 / 219, cold 4430 / 3904, **native heap 60.5 / 62.1 MB**, CV 2.7%/1.3% (A015 is the cleanest run in the DB).
- **Strength:** DB-best sustained throughput among non-i8mm parts; **mmap effective → −87% native heap**; tightest latency distribution (pinned to A78s, no mid-run migration).
- **Weakness:** policy uses only 2 of 4 A78s (intra=2) — untested headroom to 4; may hit the same bandwidth wall.
- **Bottleneck: mixed — throughput likely approaching bandwidth (as §5), memory NOT a bottleneck here (mmap paid).** *Evidence:* 60 MB native heap with correct output; RSS 216/252 MB (weights demand-paged, kernel-truthful `/proc/self/statm`).

### CPH2603 — Dimensity 1080 (MT6877), Armv8.2-A, 2×A78 + 6×A55
- **Caps:** dotprod. Policy: intra=1, affinity OFF. **Newest OS+kernel in DB** (Android 16, kernel 6.6.118).
- **Results:** 151.9 tok/s†, TTFT 330, long median 304, native 531 MB, CV 19%/19% (worst Armv8.2). **Throttled** (A78 2600 rated → 2400 run → 1430 after).
- **Bottleneck: thermally-bound (big-cluster DVFS) on top of bandwidth-bound.** *Evidence:* `perCoreFreqKhz` shows the A78 pair collapsing; high CV + elevated p95/p99 track it. Throughput used as a **lower bound**, not a ranking. Its 531 MB native heap on the newest OS/kernel is what **kills the "Android 16 triggers mmap" hypothesis** (§5).

### V2338 — Snapdragon 6 Gen 1 (SM6450), Armv8.2-A, 4×A78 + 4×A55 *(first Qualcomm)*
- **Caps:** dotprod. Policy: **intra=2, affinity ON** (4 perf cores). Clock 2208.
- **Results:** 186.8 tok/s, TTFT 256, long median 247, native 554 MB, CV 5.2%/2.2%; no throttle (A78 held 2208). Warm-startup phase **rejected** (877272 ms stall outlier); cold 7960, hot 2458 retained.
- **First populated `/sys/class/thermal`:** cores 54–57 °C, DDR 51.8, skin 43 — Qualcomm exposes sensors MediaTek/Exynos don't.
- **Bottleneck: memory-bandwidth-bound, but *less starved* than the 2-core parts.** *Evidence:* A78@2208 (lowest 4-core clock) still beats all 2-core parts; trails MT6878@2500 roughly in clock proportion → the 4-thread config is not fully bandwidth-capped. mmap **not** effective (554 MB) → this device is what **kills "core count" and "non-MediaTek"** for the mmap finding.

### S22U — Snapdragon 8 Gen 1 (SM8450), Armv8.6-A **i8mm**, 1×X2 + 3×A710 + 4×A510
- **Caps:** dotprod + fp16 + **i8mm** (first in DB). No SVE hwcap, no SME.
- **Policy: intra=1, affinity OFF — WRONG.** Detector counted X2 as the only perf core, misfiled 3× A710 as efficiency → single-threaded inference.
- **Results:** **211.8 tok/s (DB best)**, TTFT 181 (DB best), long median 188 (DB best), short median 53 (DB best) — **all while single-threaded and throttled** (X2 2995→1171, cores 63–68 °C, CV 39%/33% worst in DB). Cold 16205 ms† (throttled bake), **warm/hot 1063/1136 = DB-fastest**. Native heap 113.5 MB (intermediate), RSS 314.
- **Bottleneck: threading-bound (policy underuse) + thermally-bound.** *Evidence:* 3 A710 cores idle for inference by classifier bug; clocks collapse mid-run. Its DB-leading medians on 1 throttled thread are a **floor**, not a ceiling.

---

## 4. Cross-Device Comparison

**Why A beats B, with the compute variables held or noted:**

- **A015/LXX518 (207) > V2338 (187) > g73 (178) > M14 (152):** among dotprod parts, ordering tracks **perf-core count first, then clock**. 4-core parts (207/187) sit above 2-core (178/152). Within 4-core, MT6878@2500 > SD6@2208 roughly by clock. **Measured.**
- **g73 (178) > M14 (152) despite M14's higher clock:** the decisive controlled result — **memory subsystem, not compute.** D930 fabric > Exynos 1330 fabric. **Inferred** (no bandwidth counter; controlled design forces it).
- **S22U leads all despite worst conditions:** single-thread + throttle, yet DB-best medians. Attributable to **X2 IPC + i8mm per-thread**, but the two cannot be separated from each other or from the missing threads. **Estimated.**
- **M315F loses by 4× despite not being the oldest-clocked uniformly:** pure **ISA** — no dotprod. **Measured enabler.**

**Why startup differs:** cold is dominated by the one-time graph bake + tokenizer + storage read. M14 (newer IO stack) wins cold among 2-core parts; S22U loses cold catastrophically (16.2 s) **because the bake ran while the SoC was already at 63–68 °C and downclocking** — a throttled-compute artifact, proven by its **DB-fastest warm/hot (1063/1136)** once the bake is cached. **Measured.**

**Why TTFT differs:** tracks per-thread int8 throughput + clock. S22U 181 (i8mm+X2) < LXX518 227 < M14 327 < M315F 1572 (no dotprod). **Measured.**

**Why memory differs:** binary split on the MT6878 platform (mmap effective) vs everyone else, with the S22U intermediate (i8mm weight repack adds ~50 MB resident). **Measured trigger, Speculative mechanism.**

**Why thread utilization differs:** deterministic policy = f(perf-core count) on big.LITTLE; breaks to 1 thread on tri-cluster. **Measured.**

**ISA trend:**

| ISA | int8 kernel MLAS dispatches | representative tok/s |
|---|---|---|
| Armv8.0 NEON | plain NEON | 50.3 |
| Armv8.2 dotprod | SDOT/UDOT | 152–208 |
| Armv8.6 i8mm | i8mm matmul | 211.8 (1 thread, throttled — understated) |
| SME / SVE2 | — | not present in DB (no device exposes it) |

---

## 5. Root-Cause Analysis

**RCA-1 — Why the M14 loses inference to the g73 (the keystone result).**
Compute factors (core IP, ISA, dotprod, thread count, clock) all favor or tie the M14; only the SoC memory subsystem differs. Throughput still drops 14%. Therefore the int8 GEMM is **waiting on operand delivery, not issue slots** → **memory-bandwidth-bound**. This generalizes: on 2-core/1-thread Armv8.2 configs, more clock ≠ more throughput. **Inferred** (controlled, no PMU).

**RCA-2 — Why cold startup is 3.9–17.2 s but warm is 1.0–3.7 s.**
Cold = storage read + model parse + **graph optimization (one-time bake to `.ort`)** + kernel creation + memory planning + weight packing + tokenizer init. Warm = mmap the pre-baked `.ort` + session create only. The 68–93% collapse is the **bake term vanishing**. On M315F the model-load term alone is 14.1 s of 17.2 s → the bake/parse dominates, IO is secondary. **Measured** (phase split) / **Inferred** (term attribution).

**RCA-3 — Why the S22U ran single-threaded.**
`threads = (perfCores/2).coerceIn(1,4)`; classifier keys "perf" off the top clock tier. On a 3-cluster part only the X2 hits the top tier → perfCores=1 → threads=1 → affinity null. The three A710s (2496 MHz, strong OoO) are lumped with the A510 littles. Root cause: the heuristic assumes 2 clusters. **Measured.** Fix is a third tier in the classifier, not per-device tuning (would re-break the determinism proven in §K5).

**RCA-4 — Why mmap only works on MT6878.**
`use_memory_mapped_ort_model` reduces resident weights on exactly the 2 MT6878 devices and nowhere else across 4 vendors / 5 OS versions. Every device-swappable hypothesis is falsified by a control: Android 16 (CPH2603 newer, fails), kernel recency (6.6 fails), 4-perf-core (V2338 fails), non-MediaTek (3 MediaTek parts also fail). Surviving cause: **something in the MT6878 storage stack / mount / filesystem** governs whether ORT's demand-paging engages. **Measured elimination, Speculative mechanism** — device-swapping is exhausted; only ORT-acceptance instrumentation resolves it.

---

## 6. Bottleneck Classification

| Workload / regime | Bound by | Class | Evidence |
|---|---|---|---|
| Cold startup (all) | graph bake + IO | kernel-bound + storage-bound | model-load 14.1/17.2 s M315F; collapses on warm mmap |
| Inference, Armv8.0 | no int8 ISA | **compute-bound** | 50 tok/s plain-NEON |
| Inference, Armv8.2, 1-thread/2-core | operand delivery | **memory-bandwidth-bound** | higher-clock M14 loses to g73 |
| Inference, Armv8.2, 2-thread/4-core | partially relieved | mixed | V2338@2208 beats all 2-core; not fully capped |
| Inference, S22U | idle mid cores | **threading-bound** | classifier → 1 thread, 3× A710 idle |
| Inference, throttled (CPH2603, S22U) | big-cluster DVFS | **thermally-bound** | clocks collapse, CV 19–39% |
| Memory, entries #1–3, #6–7 | copied initializers resident | memory-capacity (not a perf bound) | 480–554 MB native heap |
| Memory, MT6878 | none — mmap paid | not bound | 60 MB native heap, correct output |
| Affinity | inert/unattributable | not a bottleneck | no A/B; tightest variance only |

---

## 7. Hidden Performance Patterns

- **Higher fabric bandwidth → higher throughput at equal compute** (g73>M14). The clearest hidden relationship; PMU-unconfirmed but controlled. **Inferred.**
- **Perf-core count, not clock, sets the throughput tier** (4-core ≥187, 2-core ≤178) — a step function, then clock modulates within a tier. **Measured.**
- **Startup ⟂ inference:** the two axes anti-correlate across devices (M14 wins startup, loses inference). Optimize independently. **Measured.**
- **Battery-surface temp understates silicon by ~20 °C** (S22U 40.7 battery vs 68 core) — the ≤35 °C gate on 6 devices was conservative, not proof they ran cool inside. **Measured** (2 Qualcomm parts expose sensors).
- **i8mm interacts with the memory footprint** — the repacked int8 matmul weights add ~50 MB resident, turning the clean 60-vs-500 MB binary into a spectrum. **Speculative.**
- **Determinism ≠ correctness:** a vendor-neutral rule that is a strength on big.LITTLE becomes a liability on tri-cluster. Same code, opposite verdict by topology. **Measured.**

---

## 8. Optimization Opportunities

Scored: Gain (Measured/Est/Spec) · Difficulty · Risk · Portability. Not repeating shipped work (ORT upgrade, `.ort`+mmap, adaptive policy, affinity, baseline profile, bench framework).

| # | Opportunity | Target device(s) | Expected gain | Diff | Risk | Portable? |
|---|---|---|---|---|---|---|
| O1 | **Fix perf/eff classifier: 3rd tier, count A710 mids as perf** | S22U (any tri-cluster/Armv9) | **Est. large** (1→≥3 threads) | Low-Med | Low | High (all future flagships) |
| O2 | **Re-bench S22U cool + multithread** to quantify i8mm | S22U | Measurement — unlocks the ~2× question | Low | Low | — |
| O3 | **Traffic reduction: int4 weights / GEMM L2 tiling** | all bandwidth-bound (2-core + M14/CPH2603) | Est. moderate — hits the measured bound | High | Med | High |
| O4 | **KleidiAI dotprod/i8mm microkernels** vs MLAS | all Armv8.2+ | Est. moderate | Med | Low-Med | High |
| O5 | **Raise intra=2→4** on 4-perf-core parts | A015/LXX518/V2338 | Est. moderate (may bandwidth-cap) | Low | Low | Med |
| O6 | **Pre-baked `.ort` in assets** (skip cold bake) | all, esp. S22U (16.2 s throttled bake) | Est. cold-startup only | Low | Low | High |
| O7 | **QNN EP → Hexagon/HTP** | V2338, S22U | Spec. potentially large | High | High | Qualcomm-only |
| O8 | **Instrument ORT mmap acceptance + inspect MT6878 mount** | investigation | Explains −87% memory, enables porting it | Low | Low | — |
| O9 | **Affinity A/B (force null)** | any 4-core | Measurement — settles §K9 | Low | Low | — |
| O10 | XNNPACK EP A/B vs MLAS | M315F (no dotprod) | Spec. modest | Low | Low | High |

**Explicitly hardware-gated (do NOT attempt on current DB):** i8mm on anything below Armv8.6; SME/SVE2 (no device exposes SVE hwcap; SME needs Armv9.2). Naming these prevents wasted effort.

---

## 9. Ranked Optimization Roadmap

1. **O1 — classifier fix.** Highest ROI in the sweep: a low-difficulty code change converts a single-threaded flagship to ≥3 threads and re-enables affinity. Prerequisite for O2. *No hardware needed.*
2. **O2 — cool multithread S22U re-run.** The only path to a defensible i8mm number; today the "~2×" is unproven.
3. **O8 — mmap instrumentation.** Device-swapping is exhausted (4 vendors, 5 OS). Only ORT-acceptance logging + mount inspection resolves the −87% memory mechanism and tells us whether it can be forced on other platforms.
4. **O3 — traffic reduction (int4/tiling).** The correct lever for the *measured* bandwidth bound on 2-core parts; higher effort, broad payoff.
5. **O4 — KleidiAI.** Portable microkernel upgrade over MLAS; moderate, low-risk.
6. **O5 — intra=4 on 4-core parts.** Cheap experiment; likely bandwidth-capped but untested.
7. **O6 — pre-baked `.ort`.** Removes the worst cold-start artifact (S22U 16.2 s).
8. **O7 — QNN EP.** High-ceiling, high-risk Qualcomm accelerator path; last because operator coverage is unknown.
9. **O9 / O10 — measurement/A-B** (affinity, XNNPACK) — cleanups, run opportunistically.

---

## 10. Expected Performance Improvements (evidence-bounded)

Every figure below is bounded by benchmark evidence; where evidence can't bound it, it says so.

- **O1 classifier fix:** the device ran on ~1 core of a 4-strong-core cluster. Ideal thread scaling would be 3–4×; real int8 GEMM scaling is sublinear and this chip is bandwidth-limited and thermally-limited, so **Estimated 1.5–2.5× sustained throughput** on the S22U, *not* the naive 3×. **Estimated — must be confirmed by O2**; the DB has no multithread i8mm point to interpolate from.
- **O2:** produces the number O1 predicts; also isolates i8mm vs dotprod at matched thread count. **Measurement, no gain claim.**
- **O3 int4/tiling:** targets the bound directly; the g73/M14 gap (14%) is a *lower bound* on the bandwidth penalty, so relieving it is worth **at least that** on the starved configs. Magnitude beyond that is **Speculative** without a bandwidth counter.
- **O5 intra=4:** if bandwidth-capped (likely, per §5), **~0%**; if not, up to the thread ratio. The V2338 evidence (4-core less starved than 2-core) says there may be **small** headroom. **Estimated small.**
- **O6 pre-baked `.ort`:** removes a one-time cost only — up to the cold-minus-warm delta (e.g., S22U ~15 s, MT6878 ~2.7 s). **Estimated, startup-only, zero steady-state effect.**
- **O4/O7/O10:** **Speculative** — no in-DB evidence; require the A/B to quantify.

**No throughput improvement should be claimed for affinity (O9) or for mmap on non-MT6878 platforms** — the data forbids it.

---

## 11. Risks

- **O1 regression risk:** a 3rd tier could mis-tier future layouts (e.g., 2×X + 6×mid). Mitigation: tier by *relative* clock bands + core-part IDs, keep the determinism property, add a tri-cluster unit fixture from the S22U `/proc/cpuinfo`. **Low residual.**
- **O3 int4:** accuracy loss on a translation model; needs BLEU/chrF gate before/after. **Med.**
- **O7 QNN EP:** operator coverage gaps can force CPU fallback per-op, *slower* than pure MLAS; high integration cost. **High.**
- **Thermal confound persists** on S22U until a cooled run — any S22U throughput number pre-O2 is a lower bound and must be labeled so.
- **mmap non-portability:** if O8 shows the trigger is a closed MediaTek storage behavior, the −87% win may be **unportable** — set expectations accordingly.

---

## 12. Validation Plan

| Claim to validate | Experiment | Pass criterion |
|---|---|---|
| O1 fixes threading | apply 3rd tier; re-read policy string on S22U | intra≥3, affinity ON, deterministic re-run |
| i8mm real gain (O2) | S22U ≤35 °C, forced multithread, vs a dotprod part at matched threads | isolate per-thread int8 delta with CV <10% |
| Bandwidth bound (RCA-1) | if root ever available: `perf stat` LLC-miss/mem-bw on g73 vs M14 | M14 higher stall/miss at equal issue |
| mmap mechanism (O8) | log ORT mmap-accept flag; `mount`/`stat -f` the `.ort` on MT6878 vs V2338 | flag differs or backing FS/mount differs |
| Affinity effect (O9) | same APK, affinity string forced null, 4-core part | throughput delta within CV → confirm inert |
| int4 accuracy (O3) | BLEU/chrF on held-out set, int8 vs int4 | quality drop within agreed budget |

**Instrumentation still missing (blocks firm conclusions):** ORT mmap-acceptance log; per-op EP-fallback log (for QNN); a rooted device for PMU (bandwidth/migration/energy). Until these exist, RCA-1/RCA-4 stay **Inferred**, not Measured.

---

## 13. Final Engineering Recommendations

1. **Ship O1 (classifier 3rd tier) now.** It is the only place in the sweep where a small, portable code change unblocks a large, currently-wasted hardware capability, and it is a prerequisite for measuring i8mm. Guard the existing big.LITTLE determinism with a regression fixture.
2. **Then O2** — re-bench the S22U cool + multithreaded. Do not quote any i8mm speedup until this exists; the current lead is confounded and must stay labeled a lower bound.
3. **Treat inference as bandwidth-bound on the mainstream (2-core Armv8.2) fleet.** Invest O3/O4 (traffic reduction, KleidiAI), not more threads/clock — the g73/M14 result says clock is already spent.
4. **Resolve mmap by instrumentation (O8), not more devices.** The elimination is complete; the mechanism needs a log line and a `mount` inspection, after which decide if the −87% memory win is portable off MT6878.
5. **Keep affinity, claim nothing for it** until the O9 A/B. Same for XNNPACK/QNN — they are hypotheses until their A/B runs.
6. **Pre-bake the `.ort` into assets (O6)** to kill the throttled cold-bake artifact, especially on hot flagships.

**Bottom line:** the biggest remaining wins are software — a classifier bug starving the best chip, and a bandwidth wall that only traffic reduction can move. The hardware ISA ladder already delivered its 4.1× for free; the job now is to stop leaving the newer silicon's threads and bandwidth on the floor.
