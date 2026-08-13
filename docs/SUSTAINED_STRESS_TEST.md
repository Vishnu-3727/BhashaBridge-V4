# Sustained load — 500 direction switches and 1,024 consecutive translations

Two separate experiments, deliberately not conflated: one hammers the **lifecycle** (build → translate
→ evict, 500 times), the other hammers the **engine** (1,024 translations through engines that are
never rebuilt). They fail in different ways and mixing them would hide both.

**Device for both:** SM-S948B (S26 Ultra), Snapdragon 8 Elite Gen 5, 8× Oryon uniform IP, 12 GB RAM,
Android 16 (SDK 36), arm64-v8a. Policy `arm-adaptive(threads=2,noKleidiAI) intra=2, arena=false,
affinity=OFF`. Debug build, ORT 1.27.0, shipping optimized-ONNX cache (279.8 MB). EN→HI and HI→EN
from the shipping INT8 graphs.

---

## Experiment A — 500 direction switches (`DirectionSwitchStressTest -e cycles 500`)

Each cycle builds an engine for the opposite direction, translates, and lets the previous direction be
evicted — the path §3.24b hardened. Alternates EN→HI / HI→EN throughout.

**Conditions:** battery 79%, on USB, **31.8 → 33.6 °C**.

### Result — PASS

| | value | 100-cycle run (§3.51) |
|---|---|---|
| Cycles | 500 | 100 |
| **Failures** | **0** | 0 |
| PSS drift, first → last decile | **−13 MB** | −8 MB |
| Native heap drift | **+1 MB** | −30 KB |
| Peak PSS | 531.0 MB | — |
| Peak native heap | 366.7 MB | — |
| Build (engine construct) | median **408 ms**, p95 502, p99 539, min 319, max 634, sd 55.3 | median 373 |
| Translate | median **91 ms**, p95 114, p99 119, min 65, max 131, sd 13.5 | median 88 |
| Output correctness | stable across all 500 — EN→HI and HI→EN strings unchanged | stable |

**Interpretation.** 500 full engine teardown/rebuild cycles moved the native heap by 1 MB and PSS
*down* by 13 MB. There is no leak on the lifecycle path, and the eviction ordering (evict-before-build,
plus the deferred `evictWhenIdle` for the borrowed case) holds under 5× the previous stress.

`mapped_model_mb` tracked direction correctly throughout (138 MB EN→HI, 94 MB HI→EN), so no
direction's mapping outlived its engine.

The build-time spread (319–634 ms) is wider than the 100-cycle run's and tracks device temperature
rather than cycle count — see Experiment B, where the mechanism is isolated.

---

## Experiment B — 1,024 consecutive translations, engines never rebuilt

`ProductionThreadSweepTest#sweepOneVsTwo -e rounds 16 -e runs 8`: 4 arms × 16 rounds × 8 runs × 2
sentences = **1,024 translations**, of which **512 at the shipping configuration** (the two `intra2`
arms). Rounds rotate so no arm keeps the coolest slot. Two arms are byte-identical duplicates of the
other two, which makes the run self-controlling.

**Conditions:** battery 79%, **unplugged** (`not_charging`), **32.5 → 35.1 °C**.

### Result — PASS on stability, with a measured and attributed slowdown

| arm | n | median | p95 | stdev | CPU ms/tx | round 1 → 16 |
|---|---|---|---|---|---|---|
| **intra2_a (shipping)** | 128 | **89.0** | 106.0 | 7.24 | 150.5 | 82 → 111 |
| **intra2_b (shipping)** | 128 | **90.0** | 99.0 | 4.57 | 150.6 | 86 → 93 |
| intra1_a | 128 | 96.0 | 118.0 | 8.70 | 67.5 | 88 → 122 |
| intra1_b | 128 | 95.0 | 119.0 | 8.98 | 67.5 | 90 → 120 |

Pooled drift across the whole run: **first round 86.5 ms → last round 111.5 ms, ratio 1.29.**

### The slowdown is CPU frequency, and here is the evidence

The brief for this experiment says not to label a slowdown "thermal throttling" without proving it.
The harness records the big-cluster frequency once per round, and over the 16 rounds of the shipping
arm it reads:

```
2942 → 2856 → 2769 → 2769 → 2769 → 2611 → 2697 → 2611 → 2611
     → 2611 → 2611 → 2395 → 2395 → 2280 → 2280 → 2092 MHz
```

**A −29% clock drop against a +29% latency rise, monotonic, over a +2.6 °C battery rise.** The two
move together and the ratio is ~1:1, which is what a frequency-bound workload looks like.

Everything that would indicate a different cause is flat:

| Candidate cause | Evidence against it |
|---|---|
| Memory leak | PSS 483.9 → 466.0 MB (**down**) across 1,024 translations |
| Native allocation growth | Native heap stable; Experiment A shows +1 MB over 500 rebuilds |
| GC pressure | No trend in involuntary context switches within an arm |
| Model / cache corruption | Reference output re-verified per arm; identical throughout |
| Threading / migration | `coresBusy` constant at 2.44–2.45 for both shipping arms |
| Lifecycle | No engine rebuild occurs inside this experiment at all |

**Conclusion: DVFS under sustained load on an unplugged device. Not a defect, and not a leak.**

### Two secondary findings

**1. The shipping configuration is also the thermally steadier one.** `intra2_b` degraded 86 → 93 ms
(+8%) where `intra1_a` degraded 88 → 122 ms (+39%). Two threads finish each translation sooner and
leave the cores idle longer; one thread holds a core hot for 50% longer per token. The clamp that was
chosen on cool-device latency turns out to also be the better choice under sustained load — that was
not the reason it was chosen, and it is worth recording.

**2. `intra2` beats `intra1` again, in a second independent session.** 89/90 ms against 96/95 ms
(−6.5%), n=128 per arm, duplicate-arm floors of 1.1% and 1.2%. This replicates the post-fix result
from the same day (§3.57 correction) on an unplugged device at a different starting temperature.

---

## Pass/fail against the stated criteria

| Criterion | Experiment A | Experiment B |
|---|---|---|
| No crashes | **PASS** (0/500) | **PASS** |
| No ANRs | **PASS** | **PASS** |
| Bounded memory | **PASS** (−13 MB PSS) | **PASS** (−17.9 MB PSS) |
| No unexplained native growth | **PASS** (+1 MB / 500 rebuilds) | **PASS** |
| No severe performance collapse | **PASS** | **PASS** — +29%, attributed, recoverable |
| No model corruption | **PASS** | **PASS** |
| Stable translation correctness | **PASS** | **PASS** |

## Limitations

- Experiment A ran **on USB power**, which keeps the device warm and makes battery-drain fields
  meaningless. Experiment B ran unplugged, which is why its thermal curve is the more honest one.
- Both used a fixed sentence pair rather than a varied corpus, so these measure engine and lifecycle
  stability, **not** robustness to input diversity. `TruncationCorpusTest` covers length variation
  separately.
- Neither experiment measures energy. Temperature stability is not energy efficiency; the only energy
  figure in this project is §3.43's 0.87–0.94 J/translation on the SM-M315F.
- The 16-round frequency series is the big cluster's, sampled once per round — enough to attribute the
  trend, not enough to model the governor.

## Reproducing

```powershell
& $ADB shell am instrument -w -e cycles 500 `
    -e class com.bhashabridge.app.mt.DirectionSwitchStressTest `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB.Q22

& $ADB shell am instrument -w -e rounds 16 -e runs 8 `
    -e class com.bhashabridge.app.mt.ProductionThreadSweepTest#sweepOneVsTwo `
    com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
& $ADB logcat -d -s BB_PROD_SWEEP
```

Unplug the device for the second one, and record the battery temperature at both ends.
