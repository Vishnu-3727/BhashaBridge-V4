# IndicTrans2 KV-Cache ONNX Export (Phase 6A)

Reproducible export of **cached** decoder graphs for IndicTrans2, replacing the
cache-less v3.4.1 export. This document is the contract for
`cached_export.py` (export + utilities) and `verify_cache.py` (verification).

Scope: **Python export pipeline only.** No Android, no runtime change, no
benchmark. That is Phase 6B+.

---

## Status

| Item | State |
|---|---|
| Architecture supports KV cache | **Verified — by source** (see below) |
| Export pipeline written | **Done** — `cached_export.py` |
| Verification written | **Done** — `verify_cache.py` |
| Cache plumbing (flatten/unflatten/shapes) | **Verified — green**, `verify_cache.py --selfcheck`, 9/9 |
| Verified ONNX models produced | **DONE** — `encoder/decoder_init/decoder_step.onnx` (fp32), en_hi |
| Logits/translation match uncached | **Verified — green**, `--onnx-dir` gate **7/7**, max_abs_diff **9.06e-06** |

Ran end-to-end on 2026-07-21 after the account was authenticated (HF token + accepted
IndicTrans2 licence). Both original walls are down: gating (token) and stack (installed
into system Python 3.12 on Windows — no Linux venv needed; see [Environment](#environment)).

Phase 3 marked native `use_cache` "unverified-by-execution". Phase 6A upgrades it
to **verified-by-source**: the IndicTrans2 remote code implements the full MBart
caching contract —

- `IndicTransAttention.forward(... past_key_value=None ...)` accepts and returns
  `past_key_value`, and caches cross-attention K/V.
- `IndicTransDecoderLayer.forward(... past_key_value=None, use_cache=True ...)`
  threads the cache; cross-attn K/V sit at tuple positions 3,4.
- `IndicTransDecoder.forward(... past_key_values=None, use_cache=None ...)`
  returns `next_cache`.

So v3.4.1's decoder graph had no cache because **the export wrapper dropped it**,
not because the model lacks it. `cached_export.py` threads it back.

---

## Graphs

Three graphs. The encoder is unchanged from v3 (no cache); the decoder splits into
an init graph (first token, no cache in) and a step graph (cache in and out).

### `encoder.onnx`
| dir | name | shape | dtype |
|---|---|---|---|
| in | `input_ids` | `[batch, src_len]` | int64 |
| in | `attention_mask` | `[batch, src_len]` | int64 |
| out | `encoder_hidden_states` | `[batch, src_len, hidden]` | float32 |

### `decoder_init.onnx` — first step, no cache in
| dir | name | shape |
|---|---|---|
| in | `decoder_input_ids` | `[batch, dec_len]` |
| in | `encoder_hidden_states` | `[batch, src_len, hidden]` |
| in | `encoder_attention_mask` | `[batch, src_len]` |
| out | `logits` | `[batch, dec_len, vocab]` |
| out | `present.{i}.decoder.key`   | `[batch, heads, dec_len, head_dim]` |
| out | `present.{i}.decoder.value` | `[batch, heads, dec_len, head_dim]` |
| out | `present.{i}.encoder.key`   | `[batch, heads, src_len, head_dim]` |
| out | `present.{i}.encoder.value` | `[batch, heads, src_len, head_dim]` |

### `decoder_step.onnx` — every later step, cache in and out
| dir | name | shape |
|---|---|---|
| in | `decoder_input_ids` | `[batch, 1]` |
| in | `encoder_attention_mask` | `[batch, src_len]` |
| in | `past_key_values.{i}.decoder.key`   | `[batch, heads, past_len, head_dim]` |
| in | `past_key_values.{i}.decoder.value` | `[batch, heads, past_len, head_dim]` |
| in | `past_key_values.{i}.encoder.key`   | `[batch, heads, src_len, head_dim]` |
| in | `past_key_values.{i}.encoder.value` | `[batch, heads, src_len, head_dim]` |
| out | `logits` | `[batch, 1, vocab]` |
| out | `present.{i}.*` | as init, with `decoder.*` length `past_len + 1` |

**No `encoder_hidden_states` input.** The wrapper passes it to the module, but with
past present the decoder reuses cached cross-attn K/V and the graph output does not
depend on it, so torch.onnx prunes it. Verified real inputs: `decoder_input_ids`,
`encoder_attention_mask`, `4*num_layers` past tensors (74 total for 18 layers).
Passing `None` instead of pruning is *not* equivalent — the module then skips
cross-attn and emits 2 tensors/layer, breaking the cache contract; hence "pass, let
ONNX prune".

`i` runs `0 .. num_layers-1`. `hidden`, `heads`, `head_dim`, `num_layers`, `vocab`
are read from the checkpoint config (`encoder_embed_dim`, `decoder_attention_heads`,
`encoder_embed_dim // heads`, `decoder_layers`) — **nothing is hard-coded**.

---

## Cache layout

One tuple per decoder layer, four tensors, in this fixed order:

```
layer i -> ( self_key, self_value, cross_key, cross_value )
```

- **self-attn** K/V: `[batch, heads, decoder_len_so_far, head_dim]` — grows +1 each step.
- **cross-attn** K/V: `[batch, heads, src_len, head_dim]` — computed once in init,
  reused unchanged every step (the source never changes mid-translation).

ONNX cannot carry nested tuples, so this is flattened to a flat, ordered tensor
list: index `= 4*layer + role`, `role ∈ (decoder.key, decoder.value,
encoder.key, encoder.value)`. Total cache tensors per graph = `4 * num_layers`.
`cached_export.flatten_cache` / `unflatten_cache` / `cache_names` are the single
source of truth for this ordering; the ONNX input/output names are generated from
`cache_names`, so names and tensor order cannot drift apart.

---

## Reproducibility

### Environment (the blocker — read first)

<a name="environment"></a>
Two walls existed and are now down:

1. **Gated checkpoint.** `ai4bharat/indictrans2-en-indic-dist-200M` needs an
   accepted HF licence + auth token; anonymous fetch is HTTP 401. **Resolved:**
   the account (`Vishnu-3727`) accepted the licence and authenticated with a token.
2. **Stack.** The Windows dev host had torch only. **Resolved:** the stack was
   installed into system Python 3.12 — **no Linux venv needed**, the export and the
   full gate both ran on Windows.

To reproduce from a clean machine:

```bash
# once: accept the licence at https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M
hf auth login                                   # or: export HF_TOKEN=hf_...
pip install "transformers==4.38.2" onnx onnxruntime sentencepiece torch sympy
```

### Versions actually used (system Python 3.12, Windows, 2026-07-21)

```
python == 3.12.9
torch == 2.7.0+cu128
transformers == 4.38.2      # pinned — matches the remote-code's expected API
onnx == 1.22.0
onnxruntime == 1.27.0
sentencepiece == 0.2.2
huggingface_hub == 0.36.2
# optimum: NOT used — optimum has no config for the custom IndicTrans arch
#          (HF discussion #14). This is why the graphs are hand-exported.
```

`transformers` is the one hard pin: the checkpoint's `trust_remote_code`
`modeling_indictrans.py` targets the 4.38 API. onnx/onnxruntime/hub float — newer
worked. onnx>=1.15 / onnxruntime>=1.17 is the floor.

### Commands

```bash
# 1. Prove the plumbing anywhere (torch only, no model, no network):
python verify_cache.py --selfcheck

# 2. Export the three graphs (needs the gated model):
python cached_export.py --direction en_hi --out onnx_cached
#   writes onnx_cached/{encoder,decoder_init,decoder_step}.onnx  (~1.8 GB fp32, gitignored)

# 3. Full verification gate (needs model + onnxruntime):
python verify_cache.py --onnx-dir onnx_cached --direction en_hi
#   runs the seven checks; non-zero exit = STOP, do not proceed to Android.
#   Result 2026-07-21: 7/7 PASS, max_abs_diff 9.06e-06.
```

The seven `--onnx-dir` checks: (1) model loads, (2) `use_cache=True` executes
eager, (3) `decoder_init` output valid, (4) `decoder_step` accepts prior cache,
(5) cache count/shapes correct, (6) cached logits match uncached within
`LOGIT_ATOL`, (7) greedy token sequences identical.

---

## Known limitations

- **Verified on synthetic input, not translation quality.** Checks 6–7 feed a
  synthetic all-ones source, so they prove **parity** (cached graphs == the
  uncached reference, max_abs_diff 9.06e-06, identical greedy tokens) — not that
  the output is good Hindi. The degenerate `[2, 3973, 3973, ...]` sequence is
  expected from garbage input; real tokenized text is the runtime's job (Phase 6B).
- **fp32, not quantised.** The graphs are ~1.8 GB fp32. int8 quantise + re-verify
  at `LOGIT_ATOL` 1e-3 is the next step, out of Phase 6A scope. (Current fp32
  parity is already 9e-06, well under 1e-4.)
- **EN→HI only.** HI→EN (`--direction hi_en`,
  `indictrans2-indic-en-dist-200M`) is coded but not yet exported/verified — see
  the HI→EN provenance limitation below.
- **opset 14** (v3 used 13). Cache graphs use more shape ops; bump only if an op
  is missing and note it here.
- **int8 tolerance.** `LOGIT_ATOL = 1e-3` is set for the quantised graph. Export
  fp32 first, verify at `1e-4`, then quantise and re-verify at `1e-3` — quantise
  is a later step, out of Phase 6A scope.
- **HI→EN provenance (R-PROV).** `MODEL_NAMES["hi_en"]` points at
  `indictrans2-indic-en-dist-200M`, but the v3 `hi_en_*` ONNX was never traced to
  a named checkpoint. Re-export and re-verify HI→EN from this name before trusting
  it; do not assume it mirrors the en-indic graphs.
- **Cross-attn reuse in the step graph.** The step graph has **no**
  `encoder_hidden_states` input: with `past_key_values` present the decoder reuses
  cached cross-attn K/V and never touches the hidden states, so torch.onnx prunes
  the input — exactly the win. The runtime must feed cross K/V back each step from
  the previous `present`, not recompute them. If a future transformers version
  changes that reuse rule, checks 6–7 catch the drift.
- **No Android wiring.** `MtEngine.logitsFor` still drives the uncached graph.
  Swapping in init+step is Phase 6B and deliberately untouched here.
