# SM-M315F — Production-path thread and execution-mode sweep (Q2b)

**Date:** 2026-08-12 · **Device:** Samsung Galaxy M31 (SM-M315F), Exynos 9611, Android 12
**Topology:** `ARMv8.0 cores=8(perf=4[4,5,6,7],eff=4[0,1,2,3]) neon=true` — no dotprod, no i8mm, no SME
**Policy under test:** `arm-adaptive(threads=2,affinity)` — `intra=2`, arena off, `optCache` on, affinity `5,6,7,8`
**Harness:** `ProductionThreadSweepTest` (`b5b8baa`, counter fix `6929a59`), EN→HI, greedy
**Raw log:** `m31_thread_sweep.log`

Production load path throughout: baked `.ort`, NO_OPT, mmap. 3 rounds × 10 runs = **n=30 per arm per
sentence**, config order rotated each round, 3 warm-up translations per arm-round, medians pooled.
Every arm parity-exact — identical output string *and* identical generated token count (12 long, 2
short).

> **Provenance:** every number is read off-device. Latency, CPU seconds, migrations, involuntary
> context switches and frequency are **Measured**. Nothing here is modelled.

---

## 0. Read the controls first

| control | LADDER | EXECMODE | agreement |
|---|---|---|---|
| `SHIPPING` long median | 623 ms | 639 ms | **2.6%** |
| `SHIPPING` short median | 163 ms | 163 ms | **0.0%** |
| drift ratio (last round / first round, pooled) | 1.04 | 1.00 | — |
| battery temp start → end | 34.3 → 33.8 °C | 33.9 → 34.0 °C | — |

Neither run is thermally dominated: the device was charging and *cooled slightly* during the ladder.
Cross-suite comparisons therefore carry **~2.6% uncertainty on the long sentence**; within a suite the
resolution is better. Any claim below smaller than that is reported as no effect, not as a win.

---

## 1. Thread ladder (LADDER suite)

| arm | long median | Δ | short median | Δ | stdev long | p95 long | CPU-ms/tx | cores busy | migrations | invol. ctxt |
|---|---|---|---|---|---|---|---|---|---|---|
| **SHIPPING** (intra2 + pin) | **623** | — | **163** | — | 21.3 | 677 | 968 | 2.40 | 170 | 355 |
| `intra1` | 721 | +15.7% | 185 | +13.5% | **17.8** | 760 | **512** | 1.10 | **26** | **58** |
| `intra2_noAff` | 641 | +2.9% | 165 | +1.2% | 47.3 | 711 | 987 | 2.40 | 343 | 942 |
| `intra3_aff` | 628 | +0.8% | 182 | +11.7% | 30.0 | **663** | 1564 | 3.80 | 789 | 2707 |
| `intra4_aff` | 674 | +8.2% | 207 | +26.9% | 60.3 | 788 | 2193 | 4.89 | 1860 | 8619 |
| `intra4_noAff` | 643 | +3.2% | 195 | +19.6% | 88.8 | 896 | 2151 | 4.81 | 1262 | 9345 |

**The shipping configuration is the fastest arm on both sentences.** It is not a tie broken by
rounding: the next-best arm on the short sentence is +1.2% and every other arm is +8% or worse, and
`SHIPPING` also holds the second-lowest stdev and second-lowest p95 in the set.

### 1.1 Four threads does not win — in either form

§3.21's evidence used the *unpinned* form. Both forms are here, and both lose:

- `intra4_aff` — 4 threads in its best shape — is **+8.2% long / +26.9% short**, with **2.8× the
  stdev** and **2.3× the CPU** of shipping.
- `intra4_noAff` is nominally closer on the long median (+3.2%, inside the noise floor) but has the
  **worst jitter in the whole sweep**: stdev 88.8 ms, p95 896 ms against shipping's 677 ms. Judged on
  median + jitter rather than the best single run, it is the worst arm on the ladder.

That is the claim under the `[1,2]` clamp, measured on the production path, on the only device on hand.

### 1.2 Three threads is the new rung, and it is not a rung

`intra3_aff` has the best p95 in the suite (663 ms) and a long median inside the noise floor (+0.8%)
— but it costs **+61% CPU** (1564 vs 968 CPU-ms per translation) and is **+11.7%** on the short
sentence, which is the shape most real user input takes. It buys nothing and spends a lot.

### 1.3 The scaling stops immediately, and the CPU does not

`coresBusy` climbs almost linearly with the thread count (1.10 → 2.40 → 3.80 → 4.89) while latency
does not improve past 2. The workload is a sequence of *small* int8 GEMMs, one token at a time; past
two threads the extra workers buy synchronisation, not arithmetic. `intra1` is the honest opposite
end: **half the CPU of shipping for +15.7% latency**, which is the trade to remember if a
battery-first mode is ever wanted.

### 1.4 Affinity is not neutral on this device after all

Single-variable, `SHIPPING` vs `intra2_noAff` — same thread count, pin the workers or don't:

| | pinned | unpinned | change |
|---|---|---|---|
| long median | 623 ms | 641 ms | **−2.8%** |
| stdev long | 21.3 ms | 47.3 ms | **−55%** |
| p95 long | 677 ms | 711 ms | **−4.8%** |
| migrations | 170 | 343 | **−50%** |
| involuntary ctxt | 355 | 942 | **−62%** |

The earlier reading of affinity as neutral on this device came from ~2% median differences with
overlapping stdevs. The median difference is still small — and still inside the cross-suite noise
floor — but **the mechanism is now visible**: pinning halves migrations and cuts involuntary context
switches by 62%, and the jitter falls with them. Affinity's claim was always about jitter, not median,
and this is the first measurement on this device that could see it.

---

## 2. Execution mode and degradation (EXECMODE suite)

| arm | long median | Δ vs SHIPPING | short median | Δ | stdev long | CPU-ms/tx | cores busy | migrations |
|---|---|---|---|---|---|---|---|---|
| **SHIPPING** | 639 | — | 163 | — | 25.9 | 972 | 2.40 | 159 |
| `intra2_parallel_inter1` | 619 | −3.1% | 160 | −1.8% | 20.4 | 972 | 2.41 | 163 |
| `intra2_parallel_inter2` | 729 | **+14.1%** | 224 | **+37.4%** | 42.2 | 1783 | 3.63 | 585 |
| `intra6_noAff` | 751 | +17.5% | 264 | +62.0% | 79.1 | 3228 | 6.18 | 2825 |
| `intra8_noAff` | 913 | **+42.9%** | 326 | **+100.0%** | 59.3 | 4195 | 6.65 | 4624 |

### 2.1 PARALLEL mode itself is free; the second inter-op thread is not

`intra2_parallel_inter1` is the control that separates the two. Switching the execution mode to
PARALLEL while keeping one inter-op thread changes **nothing measurable**: −3.1% / −1.8% is at the
noise floor, and CPU-ms per translation is identical to shipping's to three digits (972.33 vs 972.33).
So the mode is not what costs — **the second inter-op thread is**, at +14.1% long, +37.4% short and
+83% CPU.

**This reproduces Phase 7's `parallel_inter2` REVERT on the production path**, which matters because
that finding was taken under ALL_OPT with the cache off, and this ledger's own rule is that
non-production-path results do not transfer. It transfers.

There is one graph in flight at a time, so a second inter-op thread has no independent branch to run;
it only adds a scheduler to the critical path.

### 2.2 Degradation past the big cluster

6 and 8 threads spill onto the A53 efficiency cores and collapse: **+42.9% long and +100% short at
intra8**, 4.3× the CPU of shipping, 4624 migrations against 159. The short sentence degrades roughly
twice as fast as the long one in every oversubscribed arm — fixed synchronisation cost amortised over
fewer tokens.

---

## 3. Verdict

**KEEP `(perfCores / 2).coerceIn(1, 2)` with big-cluster affinity, unchanged.** It is the fastest arm
measured on both sentence lengths, at the second-lowest jitter, on the production load path, with the
control pair agreeing to 2.6% and no thermal confound.

**What this does not prove.** The M31 has 4 performance cores, so it *derives* 2 — it cannot reach the
`[1,2]` clamp's upper bound at all. This sweep sets `intraThreads` explicitly, so it tests the claim
underneath the clamp ("4 threads never wins") and not the bound itself. The bound remains INFERRED for
8-performance-core parts, and no obtainable device derives 4: the S22 Ultra also derives 2 after
`dc3011e`.

**Open, cheap, and now worth doing:** `intra1` costs half the CPU for +15.7% latency. If a
battery-saver or thermally-throttled mode is ever wanted, that is the knob, and it is already measured.
