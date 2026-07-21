# IndicTrans2 — Architecture (200M distilled) and where caching lives

Purpose: establish what the model *is*, so `EXPORT_FEASIBILITY.md` can argue about what can be
exported from it. Scoped to the `-dist-200M` checkpoints BhashaBridge uses.

---

## 1. Lineage

IndicTrans2 is a Fairseq-trained encoder–decoder Transformer. The Hugging Face port
(`modeling_indictrans.py`, shipped in the gated model repo and loaded via `trust_remote_code=True`)
is a standard **MBart/M2M100-style** implementation — its attention and decoder-layer code follow the
`transformers` BART/MBart pattern (the community port carries the usual
`# Copied from transformers.models.bart…` provenance). This lineage is the single most important
fact for export feasibility, because MBart's decoder is one that `transformers` and `optimum` already
cache and export elsewhere.

Config (200M dist): `encoder_embed_dim = decoder_embed_dim = 512`, encoder/decoder layers as per the
distilled config, learned positional embeddings, separate source/target SentencePiece vocabularies
(hence the split `dict.SRC`/`dict.TGT`).

---

## 2. The two subsystems the scripts wrap

```
IndicTransForConditionalGeneration
├── model.encoder   (IndicTransEncoder)  ── wrapped by EncoderWrapper
├── model.decoder   (IndicTransDecoder)  ── wrapped by DecoderWrapper
└── lm_head         (Linear 512→vocab)   ── appended in DecoderWrapper
```

- **Encoder:** embeds source ids + positions, runs *N* self-attention layers, emits
  `last_hidden_state [1, src, 512]`. Stateless across decode steps — computed once.
- **Decoder layer**, per block: masked **self-attention** over generated tokens → **cross-attention**
  over `encoder_hidden_states` → feed-forward. The self-attention is causal; the cross-attention
  attends to the (fixed) encoder output.

---

## 3. Where a KV cache would live — and why the current export has none

Two distinct caches exist in an MBart-style decoder layer:

1. **Self-attention K/V** — grows by one row per generated token. Recomputing it every step is the
   O(n²) waste. This is the cache that matters.
2. **Cross-attention K/V** — computed from `encoder_hidden_states`, which is **constant** for a whole
   translation. In a cached decoder it is computed on step 0 and reused unchanged; the current
   uncached graph recomputes it every step too.

Native `IndicTransDecoder.forward` in the HF port follows the MBart contract: it accepts
`past_key_values` and `use_cache`, threads a `(self_k, self_v, cross_k, cross_v)` tuple through each
layer, and returns the updated stack. This is not optional plumbing — **`model.generate()` relies on
it**, so caching demonstrably works in native PyTorch (this is the claim `EXPORT_FEASIBILITY.md` §2
marks for on-device-env verification, since the gated model could not be executed here).

The v3.4.1 export throws all of that away at the wrapper boundary. `DecoderWrapper.forward` calls
`self.decoder(input_ids=…, encoder_hidden_states=…, encoder_attention_mask=…)` with **no
`use_cache`, no `past_key_values`**, and returns only `logits`. `torch.onnx.export` traces exactly
the tensors that cross the wrapper's signature — so the cache tensors, which never appear at the
wrapper boundary, never appear in the graph. The model can cache; the exported artifact cannot.
**The cache was removed by the wrapper, not absent from the model.**

---

## 4. Consequence for generation

The Kotlin greedy loop is forced into the only shape the graph allows: re-feed the whole prefix,
read the last logit row, append, repeat. For a 21-word output that is ~30 decoder invocations, each
re-attending an ever-longer prefix and each recomputing the identical cross-attention K/V. That cost
structure — not the runtime, not the quantisation, not the CPU — is what puts the 21-word translation
at 2518 ms. Fixing it is a Python re-export, evaluated next.
