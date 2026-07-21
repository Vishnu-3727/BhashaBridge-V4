# KV-Cache Benchmark: INT8 Uncached vs INT8 Cached (Phase 6D)

The first fair KV-cache benchmark. Both runtimes at **equal precision (INT8)**, same
device, sentences, tokenizer, decoder, and build config — so the only variable is the
cache. Greedy only; beam disabled.

## Method

- **Device:** SM-M315F (RZ8N93BC18A), Exynos 9611, Android 12, arm64-v8a (Armv8.0 — NEON,
  no dotprod/i8mm).
- **A — INT8 uncached:** the pre-6B single-decoder runtime (`00c256e`) + V3's int8 graphs
  (`encoder_model_int8.onnx` 74.9 MB, `decoder_model_int8.onnx` 203.5 MB). Re-feeds the whole
  prefix every step.
- **B — INT8 cached:** the Phase 6B runtime + the Phase 6C int8 cached graphs
  (`encoder_int8` 74.9 MB, `decoder_init_int8` 203.6 MB, `decoder_step_int8` 194.0 MB).
- **Harness:** `MtBenchmarkTest` (androidTest), 3 warmup + **30 measured** runs per sentence,
  one `Metrics` JSON line per translation, parsed by `model_pipeline/bench_parse.py`.
- Identical `GreedyDecoder`, `DecodeConfig`, tokenizer, and dictionaries throughout.

## Parity

Output **identical**, both runtimes, all three sentences:

```
"Water."                                                    -> "पानी ।"
"Hello, how are you?"                                       -> "हैलो , आप कैसे हैं ?"
"The weather is very nice today and I want to go outside."  -> "आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ ।"
```

Translation parity confirmed — the cache changes speed, not output.

## Results (median of 30 runs)

### Total latency

| sentence | tokens | uncached | cached | speedup | improvement |
|---|---|---|---|---|---|
| Water. | 2 | 184.5 ms | 174.4 ms | 1.06× | 5.5% |
| Hello, how are you? | 6 | 526.4 ms | 355.2 ms | 1.48× | 32.5% |
| The weather… | 12 | 1353.6 ms | 637.4 ms | **2.12×** | **52.9%** |

### Decode latency (where the cache acts)

| sentence | tokens | uncached | cached | speedup | improvement |
|---|---|---|---|---|---|
| Water. | 2 | 143.7 ms | 133.8 ms | 1.07× | 6.9% |
| Hello… | 6 | 459.5 ms | 296.7 ms | 1.55× | 35.4% |
| The weather… | 12 | 1260.6 ms | 554.3 ms | **2.27×** | **56.0%** |

### Spread (total_ms) and throughput

| sentence | uncached p95 / stdev | cached p95 / stdev | uncached tok/s | cached tok/s |
|---|---|---|---|---|
| Water. | 274.9 / 34.6 | 213.5 / 22.6 | 13.9 | 14.9 |
| Hello… | 703.1 / 76.6 | 524.6 / 60.3 | 13.1 | 20.2 |
| The weather… | 1762.8 / 192.7 | 864.0 / 96.1 | 9.5 | **21.6** |

Encoder latency is unchanged (uncached 30.6 / 53.3 / 81.8 ms vs cached 32.2 / 53.0 / 71.4 ms) —
the cache does not touch the encoder, as expected. Cached decode splits into `decoder_init`
(one call: ~110–140 ms) + `decoder_step` (per token), with `steps == tokens` confirming one
init + one step per generated token.

### Memory (process PSS after load + 90 translations)

| runtime | total PSS | native PSS |
|---|---|---|
| INT8 uncached | 623.9 MB | 524.3 MB |
| INT8 cached | 981.5 MB | 887.4 MB |
| **delta** | **+357.6 MB (+57%)** | **+363.1 MB** |

The cost of caching: a third session (`decoder_step`, 194 MB) plus the retained cache tensors
and a larger ORT arena. Fits the 6 GB device with headroom (no OOM across the run).

## Does it match the Phase 3 prediction?

**Yes, cleanly.** Phase 3 (`EXPORT_FEASIBILITY.md`, `MODEL_PIPELINE.md`) predicted the uncached
decoder is **O(n²)** — each step re-attends the entire growing prefix — while the cached decoder
is **O(n)**, each step O(1) work over cached K/V. The prediction was that the benefit **grows with
output length**. The data is exactly that shape:

- **Throughput.** Uncached tokens/sec *falls* as output grows (13.9 → 13.1 → 9.5) — the O(n²)
  drag. Cached tokens/sec *rises and flattens* (14.9 → 20.2 → 21.6) — O(1)-per-step amortising the
  fixed overhead. This crossover is the signature of the complexity change.
- **Speedup vs length.** 1.06× at 2 tokens, 1.48× at 6, 2.12× at 12 — monotonic with length,
  as O(n²)/O(n) = O(n) implies. Extrapolating, longer real sentences widen the gap further.
- **Short-output caveat.** At 2 tokens the win is within noise (5.5%): the per-step cache overhead
  (feeding 72 cache tensors, the larger step graph) nearly cancels the tiny O(n²) saving when there
  is almost no prefix to re-attend. Expected, and irrelevant — 2-token outputs are already fast.

## Decision

**A — KV-cache is beneficial. Keep the cached runtime.**

Evidence, measured only:

- **Faster at every length, materially so past trivial outputs:** 1.48× at 6 tokens, **2.12× at 12**,
  and widening. Real sentences (10–30+ tokens) land in the high-benefit region.
- **Lower variance:** cached p95 and stdev are smaller at every length — more predictable latency.
- **Parity exact** — no quality cost.
- **Cost is memory, not correctness:** +358 MB PSS, affordable on the target's 6 GB. If a
  lower-memory device becomes a target, that is the trade to revisit — but on the SM-M315F the
  cache is a clear win.

The int8 cached runtime is retained as the production path.

## Scope notes

- Greedy only; beam not benchmarked (disabled), per phase scope.
- No ONNX Runtime tuning, thread affinity, XNNPACK/NEON/SME, or RuntimeConfig — only the graph
  assets were switched. Those optimisations are later phases.
- Armv8.0 device: int8 has no dotprod/i8mm acceleration here, so both runtimes pay full int8 cost;
  the cache win is purely the reduced op count, not any ISA feature. A newer core would likely
  shift absolute numbers but not the O(n²)→O(n) relationship.
