# SM-S948B — Thread bound, execution mode, and the KleidiAI A/B re-measured

**Date:** 2026-08-12 · **Device:** Samsung Galaxy S26 Ultra (SM-S948B), Snapdragon 8 Elite Gen 5, Android 16
**Topology:** `ARMv9 cores=8(perf=8[0–7],eff=0[])` — 8× uniform Oryon (`CPU part 0x002`), 6 × 3628 MHz + 2 × 4742 MHz
**ISA:** `dotprod i8mm sve sve2 sme` (`smei8i32 smef16f32 smeb16f32 smef32f32`), **no sme2**
**Policy under test:** `arm-adaptive(threads=2)` — `perfCores=8` → `8/2 = 4` → **clamped to 2**; affinity **OFF** (no LITTLE cluster to pin against)
**Harness:** `ProductionThreadSweepTest` (`07885a0`), EN→HI, greedy, n=30/arm/sentence, 3 rotated rounds
**Raw log:** `s26ultra_thread_sweep_2026-08-12.log`
**Companion to:** `S26U_EXPERIMENTS.md` (2026-07-31, entry #9) — §3 below **contradicts** its KleidiAI result

> **This is the device that reaches the clamp.** It is the only topology in the nine-device database
> that derives 4 intra-op threads before `coerceIn(1, 2)` truncates it, so it is the only device on
> which §3.21's *bound* — as opposed to the claim underneath it — can be tested at all.

---

## 0. Read the controls first

Affinity is unavailable on a uniform-IP part (`efficiencyCoreIds` is empty, so `affinityString`
returns null). That is not a defect here — it hands the run **two free duplicate pairs**, arms whose
configurations are byte-identical and which therefore measure this run's repeatability directly:

| duplicate pair | long medians | apart |
|---|---|---|
| `SHIPPING` ≡ `intra2_noAff` | 95 / 92 ms | **3.2%** |
| `intra4_aff` ≡ `intra4_noAff` | 102 / 102 ms | **0.0%** |

**The repeatability floor for the LADDER suite is ~3%.** Nothing smaller is reported as a result.

| suite | entry temp | end | drift ratio | verdict |
|---|---|---|---|---|
| LADDER | 34.9 °C | 36.3 °C | 1.08 | usable, drift noted |
| EXECMODE | **36.3 °C** (gate fired) | 36.3 °C | **1.17** | **within-suite only; do not join** |
| KLEIDI (hot) | 36.3 °C (gate fired) | 36.3 °C | 1.02 | usable |
| KLEIDI (cold re-run) | 33.2 °C | 34.3 °C | 1.07 | usable |

EXECMODE's `SHIPPING` control read **118 ms against LADDER's 95 ms — 24% apart**, so no number
crosses between those two suites. This is the mechanism the thermal rule exists for: same code, same
device, same session, 24% apart on temperature alone.

---

## 1. The clamp's bound — §3.21 is correct, and now MEASURED on the part it was written for

LADDER suite, 34.9 → 36.3 °C, drift 1.08:

| arm | long | Δ | short | Δ | stdev | p95 | CPU-ms/tx | cores busy | migrations |
|---|---|---|---|---|---|---|---|---|---|
| **SHIPPING** (intra2) | **95** | — | **26** | — | 3.73 | 100 | 158 | 2.49 | 131 |
| `intra1` | 89 | −6.3% | 24 | −7.7% | 5.07 | 99 | **64** | 1.05 | 30 |
| `intra2_noAff` *(≡ SHIPPING)* | 92 | −3.2% | 25 | −3.8% | 3.47 | 99 | 154 | 2.46 | 139 |
| `intra3` | 93 | −2.1% | 26 | 0.0% | 3.23 | 100 | 243 | 3.83 | 420 |
| `intra4_aff` *(no pin available)* | 102 | **+7.4%** | 31 | **+19.2%** | 4.76 | 110 | 329 | 4.72 | 714 |
| `intra4_noAff` | 102 | **+7.4%** | 30 | +15.4% | 5.17 | 111 | 330 | 4.74 | 693 |

**4 threads loses on the only device that would ever derive it.** +7.4% long and +15–19% short against
both intra2 arms, consistent in every round (`intra4` round medians 99/104/105 against SHIPPING's
90/95/98), at **2.1× the CPU**. The old `[1,4]` clamp would have shipped exactly this configuration
to this part. **§3.21's bound is confirmed, not merely its underlying claim.**

`intra3` is also worse than 2 once CPU is counted: −2.1% long is inside the 3% floor, and it costs
+54% CPU to buy it.

### 1.1 One thread may beat two here — not settled

`intra1` is the one arm that could move policy: −6.3% long / −7.7% short against `SHIPPING`, lower in
all three rounds, at **40% of the CPU** (64 vs 158 ms/tx). But against its duplicate control
`intra2_noAff` the gap is only −3.3% / −4.0%, i.e. at the repeatability floor, and its stdev is
*worse* (5.07 vs 3.73). Under the standing rule — >5% on both sentences, no p95/stdev regression — this
**does not clear the bar**, and it is recorded as open rather than acted on. A dedicated cold
`intra1` vs `intra2` A/B with more rounds is the experiment that would settle it.

---

## 2. Execution mode and degradation (thermally compromised)

EXECMODE, entered at 36.3 °C with drift 1.17. **Within-suite comparisons only:**

| arm | long | Δ | short | Δ | stdev | CPU-ms/tx |
|---|---|---|---|---|---|---|
| SHIPPING | 118 | — | 31 | — | 9.99 | 193 |
| `intra2_parallel_inter1` | 116 | −1.7% | 31 | 0.0% | 4.86 | 197 |
| `intra2_parallel_inter2` | 123 | +4.2% | 35 | +12.9% | 6.15 | 319 |
| `intra6_noAff` | 137 | +16.1% | 46 | +48.4% | 16.05 | 562 |
| `intra8_noAff` | 167 | **+41.5%** | 45 | +45.2% | 24.05 | 646 |

Same shape as the SM-M315F: **PARALLEL mode is free** (`inter1` is inside noise and burns the same
CPU), the **second inter-op thread costs** (+4.2% / +12.9% and +65% CPU), and oversubscription past
the core count collapses. The magnitudes are smaller than the M31's (+14.1% / +37.4% for `inter2`) and
should not be quoted precisely — this suite ran hot.

---

## 3. KleidiAI — the direction from entry #9 does not reproduce

`mlas.disable_kleidiai` forces MLAS's own kernels. Entry #9 (2026-07-31) measured this at two thread
counts, twice, and found **KleidiAI on was faster at all four points, by 4–9%**. Re-measured on the
current build at two thread counts in **two different thermal states**:

| | KleidiAI **on** | KleidiAI **off** | Δ |
|---|---|---|---|
| intra2 (= shipping), 36.3 °C | 119 ms | **107 ms** | **off is 10.1% faster** |
| intra2 (= shipping), 33.2 °C | 92 ms | **80 ms** | **off is 13.0% faster** |
| intra4, 36.3 °C | 128 ms | 121 ms | off is 5.5% faster |
| intra4, 33.2 °C | 96 ms | 93 ms | off is 3.1% faster |

**Four points, both thermal states, all the same direction — and it is the opposite of entry #9's.**
For comparison, entry #9's table: `intra4` 102 vs 107 (on faster by 4.9%), `intra1` 111 vs 115 (on
faster by 3.6%).

The evidence quality is not symmetric between the two sessions. Entry #9 said so itself: both of its
A/B runs were "thermally degraded (stdev 12–25 ms)" and it declined to pin the magnitude tighter than
4–9%. The runs here have **stdev 2.4–9.5 ms**, per-round medians that do not overlap between arms
(hot: on 120/119/120 against off 107/108/108), and a drift ratio of 1.02 on the hot run.

Two secondary observations, both consistent across all four points:

- **The effect is much larger at 2 threads than at 4** (10–13% against 3–6%). Speculative mechanism,
  not measured: per-thread SME entry cost — streaming-mode transitions and ZA save/restore — amortises
  over less work when fewer workers each do more of it. This matters because **the shipping
  configuration on this device is 2 threads**, i.e. the worst case of the two.
- **KleidiAI costs 25–56 MB of PSS** (676 vs 652 MB at intra2; 685 vs 629 MB at intra4), presumably
  its prepacked SME-format weights.

### What changed since entry #9

Not the ISA, and not ORT — both sessions ran ORT 1.27.0 on this silicon. The build moved by roughly
thirty commits, and the suspect worth naming is §3.30's shared weight blob plus the `.ort` opt-cache
path, either of which could change what MLAS is handed and therefore which kernel wins. **That is a
hypothesis, not a finding** — nothing here isolates it.

**What is safe to say:** on this device and this build, KleidiAI's SME kernels are a **regression at
the shipping thread count**, worth 10–13%. What is not yet safe to say is that the same holds on other
SME parts, or that entry #9 was wrong rather than measuring a build that behaved differently.

**SME is still live.** This is a claim about the kernels being *faster*, not about them running:
`s26ultra_simpleperf_sme.txt` shows the hottest symbol is KleidiAI's
`kai_run_matmul_clamp_f32_qai8dxp1vlx4_qsi8cxp4vlx4_1vlx4vl_sme_mopa`, with 83.2% of samples inside
`libonnxruntime.so`. That evidence is unaffected.

---

## 4. Verdict

- **KEEP the thread policy.** `(perfCores / 2).coerceIn(1, 2)` is now measured on the one topology that
  reaches its bound, and 4 threads is +7.4% / +19.2% there. §3.21 moves from INFERRED to **MEASURED**.
- **`intra1` on this part: open**, not actioned — it does not clear the >5%-on-both bar against its own
  duplicate control.
- **KleidiAI on SME silicon: a shipping decision, not a benchmark note.** Disabling it is worth 10–13%
  at the configuration this device actually runs. `disableKleidiAi` is currently a benchmark-only knob;
  making `ExecutionPolicy` set it on SME parts is a policy change and needs a second SME device, or at
  minimum a third cold run, before it ships.
