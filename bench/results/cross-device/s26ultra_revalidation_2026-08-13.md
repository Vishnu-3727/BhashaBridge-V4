# SM-S948B — re-validation on the optimized-ONNX artifact (2026-08-13)

**Device:** Samsung Galaxy S26 Ultra (SM-S948B), Snapdragon 8 Elite Gen 5, 8× Oryon (uniform IP,
2 × 4742 MHz + 6 × 3629 MHz DVFS tiers, one `CPU part`), 12 GB RAM, Android 16 (SDK 36), arm64-v8a
**Build:** `main` @ `029a88c`, debug, ORT 1.27.0 · EN→HI unless stated
**Detected:** `ARMv9 cores=8(perf=8[0..7],eff=0[]) neon i8mm sve2 sme` — `sme2=false`
**Policy:** `arm-adaptive(threads=2,noKleidiAI) intra=2 arena=false affinity=OFF kleidiAI=OFF`
**Thermals:** 31.6 °C at session start, 32.3–36.7 °C across runs, on USB the whole session
**Executes:** `S26U_SESSION_PLAN.md` (`029a88c`), all seven blocks

> **Why this session exists.** 2026-08-12 replaced the shipping artifact — `.ort` flatbuffer →
> optimized ONNX over a shared blob (§3.47) — and compressed the APK weight blobs (§3.54). Entry #9
> and §3.38–§3.42 were all measured on the format that no longer ships, and one shipping default
> (`disableKleidiAi = caps.sme`) rested entirely on those numbers.
>
> **Headline: nothing had to change.** Every re-tested decision survived the artifact change. Two
> M31-only findings now generalise to a second, much newer SoC. The provenance rule is unchanged —
> every number below was read off-device, and the two degenerate arms are called out as degenerate
> rather than reported as results.

---

## 1. Correctness and the headline number

`MtEngineInstrumentedTest` 3, `HiEnEngineTest` 3, `OptCacheTest` 1, `VocabCacheTest` 1 — **8/8 pass**.
`BenchmarkSuiteTest` 1/1. Sample output `पानी ।`, long-sentence output
`आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ ।` — unchanged.

Full report: `s26ultra_suite_2026-08-13.json` (schema `bb-bench/1`), 32.7 °C both ends.

| metric | 2026-08-13 (optimized ONNX) | entry #9 (`.ort`) | §3.40 (`.ort`, post-KleidiAI-fix) | SM-M315F |
|---|---|---|---|---|
| long sentence, median | **86.0 ms** (n=30, stdev 3.3) | 99 ms | 78 ms | 640–680 ms |
| tokens/s | **535.1** | 412.8 | 565.4 | 73.0 |
| `Water.`, median | **22.0 ms** (stdev 0.80) | — | — | — |
| first translation | 86 ms | — | — | — |
| tokenizer (cold) | **72 ms** | — | — | 474 ms |
| engine init (suite cold) | 2039 ms | — | — | 2304 ms |
| model cache on disk | **279.8 MB** | 473 MB | 473 MB | 280 MB |

**Storage confirms §3.47 on this device:** 279,821,784 B where the `.ort` pair cost 473 MB — the
−193 MB is not an M31 artifact. The tokenizer number confirms §3.56 (target vocabulary indexed, not
expanded) at 72 ms against the M31's 474 ms.

The 86 ms vs §3.40's 78 ms is **not** a regression claim in either direction: different artifact,
and this run entered at 32.7 °C against §3.40's 31.8 °C. What the session can say about that gap is
in §3 below, where the same arm is measured with in-run controls.

## 2. Cold start — the C1 + Q21 + Q23 stack

Four `force-stop` → launch cycles, 20 s settle, `BB.Bench engine_init`. The bake had already run
during the instrumented tests, so `baked:*=0` on all four and **no launch needed discarding**.

| launch | `engine_init` | tokenizer src / tgt | `sessions:parallel` | `cache_contract` | batt |
|---|---|---|---|---|---|
| 1 | 389.3 ms | 58.6 / 23.6 | 305.3 | 1.78 | 32.8 °C |
| 2 | 386.2 ms | 76.5 / 31.1 | 276.8 | 1.69 | 32.7 °C |
| 3 | **352.2 ms** | 44.6 / 44.1 | 261.9 | 1.60 | 32.8 °C |
| 4 | 354.1 ms | 58.0 / 32.8 | 261.7 | 1.56 | 32.7 °C |

Median ≈ **370 ms against the M31's 2304 ms — 6.2×**. Graph stubs are 961,925 / 1,263,288 /
1,210,779 B (encoder / decoder\_init / decoder\_step) with the weights in the 276.4 MB shared blob,
i.e. the Q21 format loaded exactly as designed. Session creation dominates at ~250 ms of the ~350 ms.

## 3. ⚠ KleidiAI on the new artifact — **the decision holds**

The session's highest-consequence question. `ProductionThreadSweepTest#sweepKleidiAiVsCache`,
`-e threads 2` (the shipping thread count, where §3.39 put the signal), 3 rounds × 10, 32.8 → 34.0 °C,
**drift 1.04**.

| arm | LONG median | SHORT | cpu ms/tx | PSS | round medians |
|---|---|---|---|---|---|
| `cacheON_kleidiON` | 96.0 | 26.0 | 154.2 | 544 MB | 92 · 96 · 96 |
| `cacheON_kleidiON_recheck` | 95.0 | 25.0 | 154.8 | 552 MB | 94 · 96 · 96 |
| **`cacheON_kleidiOFF`** | **86.0** | **23.0** | 141.7 | 471 MB | 82 · 86 · 89 |
| `cacheON_kleidiOFF_recheck` | 88.0 | 24.0 | 146.0 | 489 MB | 87 · 88 · 89 |
| `cacheOFF_kleidiON` | 95.0 | 26.0 | 155.0 | 539 MB | 94 · 96 · 97 |
| `cacheOFF_kleidiOFF` | 86.0 | 23.0 | 143.0 | 511 MB | 84 · 86 · 88 |

**KleidiAI off wins −8.9% on the production path (optimized ONNX) and −9.5% on raw ALL\_OPT graphs.**
Same sign under both load paths, so the artifact format was never the mechanism — which is what
§3.42 predicted when it exonerated the load path for §3.20's contradiction. The byte-identical
recheck pairs disagree by 1.0% and 2.3%, so the effect is ~4× the run's own floor. It remains **not a
latency-for-energy trade**: off is also ~8% less CPU and ~55–70 MB less PSS.

Magnitude is softer than §3.39's −10.1 / −13.0 / −12.7% on `.ort`; direction and mechanism are
unchanged. **`ExecutionPolicy.select`'s `disableKleidiAi = caps.sme` needs no change before
submission.**

### 3a. The degenerate arms in `sweepKleidiAi` — read as a control, not an A/B

`sweepKleidiAi` builds its first pair off `base = ExecutionPolicy.current`, which **already carries
`disableKleidiAi = true`** on an SME part since §3.40 shipped. `SHIPPING` and `SHIPPING_noKleidiAI`
are therefore byte-identical configurations, and reporting their difference as a KleidiAI result
would be wrong. Taken as what they are — a repeatability control — they read 90.0 vs 88.0 (2.2%) and,
in the recheck pair, 89.0 vs 89.0 (0%). *(Test-shape note, not a defect: the arm labels predate
§3.40 shipping the flag.)*

What that run does contribute (33.5 → 35.3 °C, drift 1.08):

| arm | LONG median | cpu ms/tx | coresBusy | migrations |
|---|---|---|---|---|
| `SHIPPING` (intra2) | 90.0 | 146.5 | 2.46 | 125 |
| `SHIPPING_recheck` | 89.0 | 149.0 | 2.46 | 170 |
| `intra4` (KleidiAI on) | 98.0 | 318.5 | 4.67 | 819 |
| `intra4_noKleidiAI` | 98.0 | 318.8 | 4.69 | 801 |

At `intra4` the KleidiAI delta is **zero** (98 vs 98), consistent with §3.39's "smallest at 4 threads"
(−3.1 to −5.5% there). The flag only matters at the thread count the policy actually ships.

## 4. Execution providers — nothing beats MLAS, and the reason is still the export

`ExecutionProviderProbeTest`, both tests, 2/2 pass. `PROVIDERS_COMPILED_IN [CPU, NNAPI, XNNPACK,
WEBGPU]`. Arms run `optCache = false` (raw graphs), 3 rotated rounds, parity checked per arm.

| arm | round medians (ms) |
|---|---|
| `cpu_mlas` | 89 / 97 / 96 |
| `xnnpack` | 89 / 96 / 100 |
| `mlas_bf16_fastmath` | 87 / 93 / 99 |
| `kleidiai_off` | 84 / 97 / 94 |
| `nnapi` | **191 / 229 / 222** |

NNAPI is 2.3× slower, matching §3.52's +125% on the M31.

**Node placement settles the generalisation.** Counting provider assignments in the `decoder_step`
traces:

| arm | XNNPACK nodes | NNAPI nodes | CPU nodes |
|---|---|---|---|
| CPU | 0 | 0 | 17,616 |
| XNNPACK | **0** | 0 | 17,616 |
| NNAPI | 0 | 1,308 | 23,700 |

The XNNPACK arm is **byte-identical in placement to the CPU arm** — it claims nothing, so its
latency parity with MLAS is not a tie, it *is* MLAS. §3.52 called this a property of the export
(the hot GEMMs are `com.microsoft` contrib ops: `DynamicQuantizeMatMul`, `MatMulIntegerToFloat`)
rather than of the CPU. That claim was single-device; a Snapdragon 8 Elite Gen 5 now does exactly
the same thing, so **it generalises**.

## 5. Memory — both M31 findings hold on 12 GB of RAM

**Q24 `SharedWeightRuntimeTest`** (blob 276,385,792 B):

| arm | heap Δ | anon Δ |
|---|---|---|
| `decoder_init` alone | 149,536 KB | 152,720 KB |
| `decoder_step` alone | 138,426 KB | 117,184 KB |
| `encoder` alone | 63,240 KB | 43,776 KB |
| `init+step` | 286,784 KB | 266,768 KB |
| `all_three` | 350,030 KB | 322,588 KB |

`shared_saving = 1,178 KB of 351,202 KB` (M31 §3.55: 884 KB of 323,756 KB). `init+step` together is
within 0.4% of the two alone, so ORT deduplicates nothing across sessions on either device. The fix
is still a merged decoder export.

**Q24B `ExternalInitializerProbeTest`:** baseline 159,878 KB vs `addExternalInitializers` 220,046 KB
— the API **costs +58.8 MB** where a working mechanism would have saved ~61 MB (M31: +64.7 MB). Same
sign, same magnitude, two unrelated SoCs.

## 6. Thread clamp — the bound re-confirms on the new artifact

`sweepThreadCounts` (`LADDER`), 34.0 → 35.7 °C, drift 1.10:

| arm | LONG median | cpu ms/tx | coresBusy | migrations |
|---|---|---|---|---|
| `SHIPPING` (intra2) | 91.0 | 153.7 | 2.46 | 151 |
| `intra1` | 95.0 | **64.0** | 1.01 | 42 |
| `intra2_noAff` | 97.0 | 156.2 | 2.41 | 171 |
| `intra3` | 100.0 | 253.0 | 3.69 | 976 |
| `intra4_aff` | 102.0 | 331.7 | 4.69 | 857 |
| `intra4_noAff` | 104.0 | 340.7 | 4.71 | 771 |

`sweepExecModeAndDegradation` (`EXECMODE`) entered at 35.1 °C — **the test itself warned that this is
not a cold device**, so its between-arm deltas carry that caveat; drift 1.14:

| arm | LONG median | cpu ms/tx | nonvol ctxt |
|---|---|---|---|
| `SHIPPING` | 96.0 | 156.2 | 447 |
| `intra2_parallel_inter1` | 99.0 | 165.2 | 340 |
| `intra2_parallel_inter2` | 100.0 | 255.0 | 822 |
| `intra6` | 114.0 | 465.8 | 4,329 |
| `intra8` | **153.0** | 577.2 | 9,365 |

Monotonic degradation above 2 threads, at 1.6–3.7× the CPU. **§3.37's KEEP and §3.38's bound both
survive the artifact change**, and this is still the only device in the fleet whose topology derives
4 before the clamp.

**Caveat on the `LADDER` run, stated rather than buried:** `SHIPPING` and `intra2_noAff` are
byte-identical on a uniform-IP part (`affinityString` returns null with no efficiency cluster, so
`affinity=true` cannot pin anything), and they disagree by **6.6%** — that run's noise floor is
wide, and `intra1` at 95.0 sits inside it. §7 measures that pair properly instead.

## 7. `intra1` vs `intra2`, counterbalanced — replication of §3.39, not a new decision

`sweepOneVsTwo`, `-e rounds 4 -e runs 10` (n=40/arm), **34.6 → 34.6 °C, drift 1.04**:

| arm | long | short | stdev | cpu ms/tx |
|---|---|---|---|---|
| `intra1` (a / b) | **92 / 92** | 25 / 25 | 4.41 / 2.36 | 63.4 / 63.3 |
| `intra2` (a / b) | 94 / 94 | 25 / 25 | 2.92 / 1.98 | 152.6 / 152.1 |

Both duplicate pairs land on **identical medians — the floor is 0%.** `intra1` is **−2.1% long at
42% of the CPU**, same direction as §3.39's −4.6% at 41% on the `.ort` artifact, weaker at a 4 °C
warmer entry. §3.39's disposition is unchanged and is **not** reopened here: sub-threshold on
latency, and the same arm is **+15.7% on the SM-M315F** (§3.37), so it stays a device-class
observation with no predicate any current detector supplies. The durable part remains the CPU column
— what a battery-saver mode on this silicon class would look like.

## 8. Lifecycle and pressure

**Q22 `DirectionSwitchStressTest`**, 100 EN↔HI switches, `FAILURES 0`:

| | value | M31 (§3.51) |
|---|---|---|
| PSS drift (first → last decile) | **−1 MB** | −8 MB |
| native heap drift | **0 MB** | −30 KB |
| peak PSS | 529.4 MB | — |
| build, median | 373.0 ms (min 334, max 1711 — first cycle) | — |
| translate, median | 88.0 ms (p95 101) | — |

**Q15 `PressureReclaimTest -e pressureMb 3072`**, `mem_available` driven 4.07 GB → 2.58 GB:

| stage | translate ms | translate majflt | pswpin | `rss_anon` |
|---|---|---|---|---|
| baseline | 82 | 0 | 0 | 479,280 KB |
| pressure 1024 MB | 85 | 0 | 0 | 459,112 KB |
| pressure 2048 MB | 85 | 0 | 0 | 398,352 KB |
| pressure 3072 MB | 86 | 0 | 0 | 400,472 KB |
| peak pressure | 84 | 4 | 0 | 392,772 KB |
| released | 91 | 10 | 0 | 400,180 KB |

`ort_rss` stays at ~3.6 MB throughout. **§3.50 re-confirms on a 12 GB device with a different LMK
threshold:** zero swap-ins at every stage, major faults only in single digits and only after peak,
latency flat within 11% across a 1.5 GB swing in available memory.

---

## 9. What this session did and did not settle

| question the plan raised | outcome |
|---|---|
| Does KleidiAI-off still win on optimized ONNX? | **Yes, −8.9% / −9.5% under both load paths.** `ExecutionPolicy` unchanged |
| Did the Q21 format cost memory here? | No. Cache 279.8 MB, `ort_rss` 3.6 MB, PSS ~470–550 MB across arms |
| Does the clamp's bound still hold? | Yes — monotonic degradation to `intra8` at 153 ms |
| Do §3.55's memory findings generalise? | **Yes**, both of them, on unrelated silicon |
| Is XNNPACK's zero-node result device-specific? | **No** — 0 nodes here too, placement identical to CPU |
| `intra1` vs `intra2` | Replicated at −2.1% / 42% CPU; still sub-threshold, still not a rule |

**Caveats.** Every run was on USB power, which keeps the device warm and invalidates
`SystemStats`' battery-drain fields; entry temperatures ranged 32.7–35.1 °C and the `EXECMODE` run
tripped the harness's own not-cold warning. `affinity=true` arms are duplicates of their no-pin
partners on this uniform-IP part and are never read as affinity results. `sme2=false` on this part,
so nothing here speaks to SME2. Absolute latencies are not comparable between `optCache` settings
and were not compared that way.

**Not run this session:** `AffinityBenchmarkTest` (nothing to pin), `SustainedEnergyTest` (invalid on
cable), `OrtProfilingTest` (§1 of `S26U_EXPERIMENTS.md` already established the profiler cannot see
the kernel), the speech suites, and the JVM unit suite.
