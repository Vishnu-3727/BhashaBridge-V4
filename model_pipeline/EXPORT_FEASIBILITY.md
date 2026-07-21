# IndicTrans2 KV-Cache Export — Feasibility

**Question (Phase 3):** can the current IndicTrans2 models support a *reproducible* KV-cache ONNX
export suitable for V4? Prove or disprove — do not assume.

**Verdict: FEASIBLE, but not free.** A cached decoder graph can be produced, but *only* by
hand-writing a cached-decoder wrapper and exporting it manually. The one-command path
(`optimum-cli … --task text2text-generation-with-past`) does **not** work for this model, and the
v3.4.1 pipeline never attempted caching at all. What blocks the easy path is tooling, not the model.

This document states the evidence for that verdict, the one claim that still needs on-device-env
confirmation, and exactly how to close it.

---

## 1. What is proven, from artifacts and source

| Claim | Basis | Confidence |
|---|---|---|
| The shipped decoder graph has no cache ports | Read both export scripts; wrapper `forward` has no `past_key_values` in, no `present` out (`MODEL_PIPELINE.md` §2) | **Certain** — ground truth |
| The model *architecture* supports caching | HF port is MBart/M2M100-style; `IndicTransDecoder` threads `past_key_values`, and `generate()` depends on it (`INDICTRANS2_ARCHITECTURE.md` §3) | **High** — from lineage; execution pending (§2) |
| `optimum-cli` cannot export it | No Optimum ONNX config is registered for the custom `IndicTrans` architecture; community attempts fail with arg/ValueErrors on both the CLI and manual paths | **High** — corroborated by [ai4bharat/indictrans2-en-indic-1B discussion #14](https://huggingface.co/ai4bharat/indictrans2-en-indic-1B/discussions/14) |
| A manual cached export is the standard fallback | This is exactly the "decoder_with_past" pattern Optimum generates automatically for MBart/M2M100; here it is written by hand instead | **High** |

The disproof of the *easy* path and the proof of the *hard* path are both established. The only link
not executed in this environment is the middle row.

---

## 2. The one claim not yet executed here — and the script to close it

Native `use_cache=True` correctness (steps 6–8 of the brief) **could not be run in this
environment**, and this doc will not pretend it was:

- The `indic_env` venv in v3.4.1 is a **Linux** build (`lib/python3.10`, `torch-2.1.2+cpu` Linux
  wheel); it does not execute on this Windows host.
- The model is **gated**; it is not in any local HF cache and cannot be downloaded unauthenticated.

So the row "architecture supports caching" rests on lineage, not on a run. It is honest to call that
**High confidence, unverified-by-execution**. Here is the exact script that verifies it; it must be
run inside the authenticated Linux `indic_env` before any ONNX cache export is trusted:

```python
# verify_cache.py — run in indic_env, after `huggingface-cli login`
import torch
from transformers import AutoModelForSeq2SeqLM

m = AutoModelForSeq2SeqLM.from_pretrained(
    "ai4bharat/indictrans2-en-indic-dist-200M", trust_remote_code=True).eval()

enc_ids  = torch.ones((1, 8), dtype=torch.long)
enc_mask = torch.ones((1, 8), dtype=torch.long)
enc = m.model.encoder(input_ids=enc_ids, attention_mask=enc_mask).last_hidden_state

# (a) does the decoder even accept the cache args, and return a cache?
dec_in = torch.ones((1, 1), dtype=torch.long)
out = m.model.decoder(input_ids=dec_in, encoder_hidden_states=enc,
                      encoder_attention_mask=enc_mask, use_cache=True)
assert out.past_key_values is not None, "decoder does NOT return past_key_values -> infeasible"
pkv = out.past_key_values
print("layers cached:", len(pkv), " per-layer tensors:", len(pkv[0]))  # expect 4: self k/v + cross k/v

# (b) numerical parity: stepwise-with-cache must equal one-shot-uncached
full = torch.tensor([[2, 100, 200, 300]])   # BOS + 3 tokens
ref = m.lm_head(m.model.decoder(input_ids=full, encoder_hidden_states=enc,
                                encoder_attention_mask=enc_mask).last_hidden_state)
past, logits = None, []
for t in range(full.shape[1]):
    o = m.model.decoder(input_ids=full[:, t:t+1], encoder_hidden_states=enc,
                        encoder_attention_mask=enc_mask, past_key_values=past, use_cache=True)
    past = o.past_key_values
    logits.append(m.lm_head(o.last_hidden_state))
step = torch.cat(logits, dim=1)
print("max abs diff cached-vs-uncached:", (step - ref).abs().max().item())  # must be < 1e-3
```

Two outcomes:

- **Both asserts pass and diff < 1e-3** → native caching is correct; proceed to §3 export. This is
  the expected result given the MBart lineage.
- **Either fails** → the custom code diverges from the MBart contract; feasibility drops to the
  `docs/ENGINEERING_PLAN.md` **R1-fallback** (chunked re-decode, or a model with a first-class ONNX
  cache config such as NLLB-200-distilled).

---

## 3. If §2 passes — the export that produces a cached graph

Hand-write a wrapper that exposes the cache at the graph boundary (the piece the v3.4.1 wrapper
omitted), then export two graphs, mirroring what Optimum's `decoder_with_past` does automatically:

```python
class CachedDecoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.decoder, self.lm_head = m.model.decoder, m.lm_head
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask, *past):
        pkv = to_tuples(past) if past else None          # flatten<->tuple for ONNX
        o = self.decoder(input_ids=input_ids, encoder_hidden_states=encoder_hidden_states,
                         encoder_attention_mask=encoder_attention_mask,
                         past_key_values=pkv, use_cache=True)
        return (self.lm_head(o.last_hidden_state), *flatten(o.past_key_values))
```

- **`decoder_init.onnx`** — `input_ids=[1,1]`, no past in, full `present` out. Runs on step 0.
- **`decoder_step.onnx`** — `input_ids=[1,1]` + `past` in, updated `present` out. Runs every step ≥1.
- Dynamic axes: mark the self-attention K/V cache length dynamic; cross-attention K/V are static per
  translation (compute once, do not re-emit — halves the cache surface, `INDICTRANS2_ARCHITECTURE.md`
  §3). Opset 13 as in the existing scripts, or 14+ if `scaled_dot_product_attention` needs it.
- Validate the ONNX graphs against `verify_cache.py`'s reference logits **before** quantising, then
  re-validate int8 (quant can move a token boundary; the parity gate in `docs/ENGINEERING_PLAN.md`
  is where that is caught).

Expected payoff: decode goes O(n²)→O(n), the per-step logits tensor shrinks from
`[1, seq, vocab]` to `[1, 1, vocab]` (kills the ~4.6 MB/step allocation), and cross-attention K/V is
computed once instead of ~30 times.

---

## 4. Known risks

| # | Risk | Impact | Note |
|---|---|---|---|
| R1 | Custom `modeling_indictrans.py` diverges from the MBart cache contract; `use_cache` returns wrong `past` shapes or wrong numbers | **Critical** — kills the headline | Gated by §2 `verify_cache.py`. Do this *first*, before any Kotlin. Same as `ENGINEERING_PLAN.md` R1 |
| R2 | `torch.onnx.export` traces the cached path but produces a graph ORT rejects (dynamic cache-length axes, `If`/`Loop` from control flow) | High | Try `torch.onnx.dynamo_export` if the TorchScript exporter chokes; validate in ORT immediately |
| R-PROV | HI→EN artifacts have **no export script** and sizes that don't mirror EN→HI (`MODEL_PIPELINE.md` §5) | High | Provenance unrecoverable until re-exported from a named checkpoint. HI→EN cache export cannot be trusted until its *uncached* export is first reproduced |
| R3 | int8 quantisation of a two-graph cached decoder shifts logits enough to change tokens | Medium | Parity-gate int8 separately from fp32; keep fp32 graphs as the reference |
| R4 | Two decoder graphs (init+step) raise total artifact size | Medium | Cross-attention weights are shared; the step graph is smaller. Measure post-export |
| R-GATE | Gated model + Linux-only venv means the whole pipeline is unreproducible on this Windows host | Medium | Reproduce in the authenticated Linux `indic_env`; document the `huggingface-cli login` step as a hard prerequisite |

---

## 5. Recommended next steps (in order — do not skip ahead)

1. **Authenticate + reproduce the uncached baseline.** `huggingface-cli login`, re-run both existing
   scripts for **both** directions in `indic_env`, unify into one committed `export.py` that takes
   direction as an argument. Closes R-PROV. Nothing about caching yet.
2. **Run `verify_cache.py` (§2).** This is the go/no-go for the entire optimization thesis. Pass →
   continue; fail → R1-fallback and re-scope the headline.
3. **Hand-write and export the cached decoder (§3).** Validate fp32 ONNX against reference logits.
4. **Quantise + parity-gate int8.** Only now does anything touch the Android side.
5. **Then, and only then,** Phase 5 rewrites `mt/GreedyDecoder.kt` to drive the two-graph cached
   decoder. That work is out of scope here and is blocked on steps 1–4 succeeding.

Do **not** integrate anything into Android, and do **not** export production models, until step 2
passes. Feasibility is demonstrated on paper and by lineage; step 2 is where it becomes demonstrated
in fact.
