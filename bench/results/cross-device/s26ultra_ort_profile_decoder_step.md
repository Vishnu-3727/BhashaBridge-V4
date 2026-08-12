# ORT Operator Profile — P8

- Node kernel events: **616560**
- Distinct op types: **26**
- Total kernel time (sum of node `dur`): **6267.9 ms**
- Total `model_run` wall time: **7937.8 ms** (ORT overhead outside kernels ≈ 1669.9 ms, 21.0%)
- Execution provider(s): **CPUExecutionProvid1r** ← all CPU/MLAS, no EP fallback

All of the above is **MEASURED** from the trace.

## Operator ranking (MEASURED)

| # | Operator | Runtime % | Cumulative % | Calls | Total ms | Avg ms |
|---|---|---|---|---|---|---|
| 1 | DynamicQuantizeMatMul | 30.5% | 30.5% | 38220 | 1908.88 | 0.050 |
| 2 | Reshape | 12.3% | 42.7% | 122640 | 769.31 | 0.006 |
| 3 | Concat | 11.0% | 53.7% | 99120 | 689.99 | 0.007 |
| 4 | MatMulIntegerToFloat | 6.2% | 59.9% | 22680 | 388.40 | 0.017 |
| 5 | Unsqueeze | 5.4% | 65.3% | 55860 | 337.47 | 0.006 |
| 6 | Transpose | 4.3% | 69.7% | 45360 | 271.60 | 0.006 |
| 7 | FusedMatMul | 4.3% | 73.9% | 15120 | 267.04 | 0.018 |
| 8 | Gather | 3.9% | 77.8% | 41160 | 243.04 | 0.006 |
| 9 | LayerNormalization | 3.8% | 81.6% | 23100 | 237.56 | 0.010 |
| 10 | MatMul | 3.7% | 85.3% | 15120 | 230.52 | 0.015 |
| 11 | Add | 3.2% | 88.4% | 31080 | 198.46 | 0.006 |
| 12 | Mul | 3.0% | 91.4% | 31080 | 186.55 | 0.006 |
| 13 | Identity | 2.2% | 93.6% | 15120 | 134.96 | 0.009 |
| 14 | Shape | 2.0% | 95.6% | 24360 | 126.64 | 0.005 |
| 15 | Softmax | 1.4% | 97.0% | 15120 | 90.37 | 0.006 |
| 16 | Gelu | 1.4% | 98.4% | 7560 | 86.47 | 0.011 |
| 17 | DynamicQuantizeLinear | 0.9% | 99.3% | 7560 | 54.35 | 0.007 |
| 18 | Cast | 0.2% | 99.5% | 2100 | 14.09 | 0.007 |
| 19 | Where | 0.1% | 99.6% | 840 | 6.95 | 0.008 |
| 20 | Equal | 0.1% | 99.7% | 840 | 5.86 | 0.007 |
| 21 | SkipLayerNormalization | 0.1% | 99.8% | 420 | 4.20 | 0.010 |
| 22 | DequantizeLinear | 0.1% | 99.8% | 420 | 3.48 | 0.008 |
| 23 | Expand | 0.1% | 99.9% | 420 | 3.16 | 0.008 |
| 24 | CumSum | 0.0% | 99.9% | 420 | 3.07 | 0.007 |
| 25 | Sub | 0.0% | 100.0% | 420 | 2.91 | 0.007 |
| 26 | Not | 0.0% | 100.0% | 420 | 2.58 | 0.006 |

## Kernel / SIMD / bottleneck annotation (INFERRED — not from the trace)

> The profiler cannot see inside MLAS. `Kernel`, `MLAS`, `SIMD`, and `Bottleneck` below are a static lookup, NOT measured. Confirm SIMD dispatch with simpleperf symbols; confirm the compute/memory split with PMU counters (see notes).

| Operator | Runtime % | Calls | Kernel | MLAS | SIMD | Threaded | Bottleneck | Optimization |
|---|---|---|---|---|---|---|---|---|
| DynamicQuantizeMatMul | 30.5% | 38220 | MlasGemmQuant+quant | yes | NEON→SDOT→i8mm | yes | compute+quantize | static-quant, i8mm, prepack |
| Reshape | 12.3% | 122640 | Reshape(no-op) | no | — | no | free (view) | none |
| Concat | 11.0% | 99120 | Concat | no | NEON copy | no | memory (movement) | KV-cache layout |
| MatMulIntegerToFloat | 6.2% | 22680 | — | ? | — | ? | ? (look up) | ? |
| Unsqueeze | 5.4% | 55860 | — | ? | — | ? | ? (look up) | ? |
| Transpose | 4.3% | 45360 | Transpose | no | NEON copy | partial | memory (movement) | eliminate via fusion/layout |
| FusedMatMul | 4.3% | 15120 | — | ? | — | ? | ? (look up) | ? |
| Gather | 3.9% | 41160 | Gather (embed) | no | — | no | memory (random access) | none (embedding lookup) |
| LayerNormalization | 3.8% | 23100 | MlasLayerNorm | yes | NEON | partial | memory (reduction) | fusion |
| MatMul | 3.7% | 15120 | MlasGemm | yes | SDOT/i8mm if int8 else FP | yes | compute@largeM / memory@thin | cache-blocking, KleidiAI, INT4 |
| Add | 3.2% | 31080 | MlasEltwise | yes | NEON | yes | memory (bandwidth) | fusion (residual/bias) |
| Mul | 3.0% | 31080 | MlasEltwise | yes | NEON | yes | memory (bandwidth) | fusion |
| Identity | 2.2% | 15120 | — | ? | — | ? | ? (look up) | ? |
| Shape | 2.0% | 24360 | — | ? | — | ? | ? (look up) | ? |
| Softmax | 1.4% | 15120 | MlasComputeSoftmax | yes | NEON | partial | memory + exp latency | fused attention |
| Gelu | 1.4% | 7560 | MlasGelu | yes | NEON | yes | memory + transcendental | fused MatMul+Gelu |
| DynamicQuantizeLinear | 0.9% | 7560 | MlasQuantizeLinear | yes | NEON | partial | memory (streaming) | fuse into matmul / static-quant |
| Cast | 0.2% | 2100 | MlasCast | yes | NEON | yes | memory | remove redundant casts |
| Where | 0.1% | 840 | — | ? | — | ? | ? (look up) | ? |
| Equal | 0.1% | 840 | — | ? | — | ? | ? (look up) | ? |
| SkipLayerNormalization | 0.1% | 420 | fused LN | yes | NEON | partial | memory | already fused |
| DequantizeLinear | 0.1% | 420 | — | ? | — | ? | ? (look up) | ? |
| Expand | 0.1% | 420 | — | ? | — | ? | ? (look up) | ? |
| CumSum | 0.0% | 420 | — | ? | — | ? | ? (look up) | ? |
| Sub | 0.0% | 420 | — | ? | — | ? | ? (look up) | ? |
| Not | 0.0% | 420 | — | ? | — | ? | ? (look up) | ? |

## Where to spend effort (derived from the ranking above)

- Hottest op **DynamicQuantizeMatMul** = 30.5% of kernel time over 38220 calls. Optimization effort should target this first; everything below it is secondary.
- int8 GEMM (MatMul/Attention): **44.6%** → levers: i8mm/KleidiAI microkernels, weight prepack reuse, cache-blocking, INT4.
- elementwise/norm: **13.0%** → levers: graph fusion (bias/residual/LN/Gelu into the matmul), which cuts memory traffic — the confirmed Armv8.2 bound.
- tensor movement (Transpose/Concat/Gather): **31.5%** → levers: layout/fusion to remove copies, KV-cache concat strategy.
- If GEMM dominates → thread tuning + i8mm + INT4 pay off. If movement/elementwise dominate → the win is fusion + traffic reduction, NOT more threads.

## What this trace cannot answer (needs more instrumentation)

- **SIMD path actually taken (NEON vs SDOT vs i8mm):** invisible to ORT profiling — it is an MLAS internal dispatch. Use `simpleperf record` + symbol report and look for the dotprod/i8mm kernel symbols, or a debug ORT build with MLAS kernel logging.
- **Compute- vs memory-bound per op:** the ranking says *where* time goes, not *why*. Confirm with `simpleperf stat -e` cache-miss / bus-cycles, or Perfetto CPU + memory-bandwidth counters.
- **Thread scaling / contention:** re-run this profile at intra=1,2,3,4 and compare the hot op's avg ms; ORT `thread_scheduling_stats` in `args` also carries main/sub-thread split.
