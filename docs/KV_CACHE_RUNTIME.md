# KV-Cache Runtime (Phase 6B)

How the Android runtime drives the verified cached ONNX graphs from Phase 6A. This
phase changed **only** the runtime — `OnnxModels`, `MtEngine`, and the assets. The
`Decoder` abstraction (`Decoder.kt`, `GreedyDecoder.kt`, `BeamSearchDecoder.kt`,
`DecodeConfig`), the tokenizer, and the benchmark subsystem are untouched.

## The seam stayed put

The whole cache lives behind `LogitsSource`, the fun-interface introduced in Phase 4:

```
fun interface LogitsSource { fun nextLogits(prefix: LongArray): FloatArray }
```

A decoder still asks one thing — "logits for this prefix" — and knows nothing about
sessions, cache tensors, or init-vs-step. Phase 4 predicted this exact swap: "when
the KV-cache export lands the source changes from re-feed-the-whole-prefix to
feed-one-token-plus-cached-state and NO decoder code changes." That held. The only
new code is `CachedLogitsSource` inside `MtEngine.kt`.

## Session ownership

`OnnxModels` owns three `OrtSession`s per direction (was one encoder + one decoder):

| session | asset (EN→HI) | role |
|---|---|---|
| encoder | `encoder.onnx` | source → `encoder_hidden_states` |
| decoder_init | `decoder_init.onnx` | first step: prefix → logits + fresh cache |
| decoder_step | `decoder_step.onnx` | later steps: 1 token + cache → logits + grown cache |

Lifetime unchanged from Phase 5: `BhashaBridgeApp` constructs one `MtEngine` per
direction at process scope and is the sole caller of `release()`, which now closes
all three sessions. The `OrtEnvironment` remains a process singleton, not closed here.
This is still the structural answer to v3.4.1's L2 leak — one owner, one release, a
real call site.

## Cache tensor mapping

The cache is the MBart 4-tensors-per-layer layout from Phase 6A (self-attn K/V that
grow each step, cross-attn K/V constant for the translation). ONNX carries it as a
flat, ordered list of named tensors:

- `decoder_init` / `decoder_step` **output**: `logits`, then `present.{i}.{decoder|encoder}.{key|value}`.
- `decoder_step` **input**: `decoder_input_ids`, `encoder_attention_mask`, then
  `past_key_values.{i}.{decoder|encoder}.{key|value}` — **no `encoder_hidden_states`**
  (pruned; the graph reuses cached cross-attn K/V).

The mapping is read from the model, never hard-coded: `OnnxModels.pastInputNames` is
the step graph's input names minus the two non-cache inputs, in graph order. Because
the export declared past inputs and present outputs in the same layer-major order,
`pastInputNames[i]` is fed from the previous run's output `i + 1` (output `0` is
`logits`). A `require` asserts `pastInputNames.size == initOutputs − 1`, so a graph
whose ordering ever drifts fails loudly at construction.

## decoder_init → decoder_step flow

`CachedLogitsSource` is created once per `translate()` and owns the cache for that one
translation. The decoder hands it the full prefix each step; it maps that to the graphs:

```
first call (prefix = [start])                  -> decoder_init(prefix)            -> logits + present
prefix == prevPrefix + one token               -> decoder_step(newToken, present) -> logits + present'
prefix does not extend prevPrefix (e.g. beam)  -> decoder_init(prefix)            -> rebuild cache
```

Greedy always extends by exactly one token, so it runs init once then step for every
later token — the intended fast path. A decoder that reorders or shortens prefixes
falls back to init: correct, just not accelerated. That is deliberate; this phase
integrates the cache, it does not tune decode strategy.

Native lifetime: each run's `OrtSession.Result` holds the `present` tensors. They are
fed straight into the next `decoder_step` as `past_key_values`, so the previous Result
is kept open until the next run supersedes it, then closed. `CachedLogitsSource.close()`
(called in `translate`'s `finally`) releases the last one; `encoder_hidden_states` and
the attention mask are closed there too. Cache lifetime is exactly one translation.

## Metrics

`translate` emits one structured line via the frozen `Metrics` API. Wall-clock stages:
`tokenize`, `encoder`, `decode` (total decode). Counters: `tokens`, plus the cache-flow
split `init_us` (single decoder_init) and `step_us` / `steps` (summed decoder_step).
`total_ms` is the whole translation. No benchmarking — timing collection only.

## Validation results

On the SM-M315F (RZ8N93BC18A, Exynos 9611, Android 12), 2026-07-21,
`connectedDebugAndroidTest` **3/3 green** with the fp32 cached graphs (~1.8 GB).

| check | result |
|---|---|
| translation succeeds | ✓ EN→HI produced Devanagari |
| **output identical to uncached runtime** | ✓ **exact**, both sentences (below) |
| cache grows correctly | ✓ `steps == tokens`, one init + one step per token (Metrics) |
| no native leaks | ✓ `release()` in `translate`'s `finally`; cache closed per translation |
| repeated translations succeed | ✓ "Water." ×2 deterministic; two engines, many translates |
| release() works | ✓ closes all three sessions, no throw |

Parity, cached fp32 vs the committed uncached int8 runtime (golden captured just
before the swap, same device, same greedy decoder):

```
'Hello, how are you?'  ->  'हैलो , आप कैसे हैं ?'     (both runtimes, identical)
'Water.'               ->  'पानी ।'                    (both runtimes, deterministic)
```

Exact match across a precision change (fp32 vs int8) — stronger than required, and
consistent with Phase 6A's graph-level proof (fp32 cached == fp32 uncached, greedy
tokens identical, max_abs_diff 9.06e-06).

Cache-flow Metrics (debug build, **not** a benchmark):

```
"Hello, how are you?"  total 2297.8 ms  encoder 1227.4  decode 1021.9   init_us 142801  step_us 782684  steps 6  tokens 6
"Water." (warm)        total  359.9 ms  encoder   59.0  decode  299.3   init_us 112925  step_us 178349  steps 2  tokens 2
```

`steps == tokens` confirms one decoder_init then one decoder_step per generated token.

**Timing note (observation, not a benchmark — Phase 6C owns that):** the fp32 cached
runtime is *slower* than the uncached int8 it replaced (Hello 2298 ms vs 774 ms). That
is precision, not caching: fp32 is 4× the weight data, and quantise/tuning are out of
scope here. The cache's O(1)-per-step win is real but masked by fp32 weight cost until
int8 quantisation lands. Phase 6C measures cached vs uncached greedy at equal precision.

## Known limitations

- **fp32, not int8.** The verified graphs are fp32 (~1.8 GB). Quantise + re-verify is a
  later phase; this phase must ship the *verified* graphs, and quantising forbidden here.
- **EN→HI only.** HI→EN cached graphs are not yet exported (Phase 6A did en_hi; R-PROV).
  `OnnxModels` names the `hi_en_*` cached assets so the export drops straight in.
- **Beam is unaccelerated.** Non-extending prefixes rebuild the cache via init. Greedy
  (the shipped strategy) is unaffected.
