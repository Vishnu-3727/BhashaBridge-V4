# BhashaBridge V4 — Model Pipeline (as inherited from v3.4.1)

What actually produces the ONNX files the app loads. This is reconstructed from the two export
scripts and the artifacts in `BhashaBridge_v3.4.1/translation_build/` and `app/src/main/assets/`.
Where a fact comes from an artifact rather than a script, it is labelled **inferred** — those are the
provenance gaps, not established pipeline steps.

Nothing here is Android code and nothing here changes it. This is Python-side reconstruction only.

---

## 1. The model

One model, one family, both directions:

- **EN→HI:** `ai4bharat/indictrans2-en-indic-dist-200M`, loaded `trust_remote_code=True`.
- **HI→EN:** the mirror checkpoint `ai4bharat/indictrans2-indic-en-dist-200M` — **inferred** (see §5).

It is **not Marian.** Marian would get a cached ONNX decoder for free from `optimum-cli`; IndicTrans2
does not (see `EXPORT_FEASIBILITY.md`). Every reference to the pipeline being "Marian" is wrong and
inverts the difficulty.

The model repos are **gated** on Hugging Face (`Access to model … is restricted`). Reproducing the
pipeline requires an authenticated `huggingface-cli login` with access granted to the AI4Bharat repos.

---

## 2. The two scripts, exactly as written

Both live in `translation_build/`. Both load the full model and wrap two submodules.

### `export_indictrans2_onnx.py` — the encoder + a fixed-shape decoder

- `EncoderWrapper` wraps `model.model.encoder`.
  `forward(input_ids, attention_mask) -> last_hidden_state`.
  Exported with `input_ids`/`attention_mask` dynamic on axis 1 (`seq_len`). Opset 13.
- `DecoderWrapper` wraps `model.model.decoder` + `model.lm_head`.
  `forward(input_ids, encoder_hidden_states, encoder_attention_mask) -> logits`.
  Only `input_ids` and `logits` are dynamic on axis 1. `encoder_hidden_states` is exported at the
  **fixed dummy length 16** — a latent bug carried by the second script.

### `export_decoder_dynamic.py` — a re-export of the decoder only

Identical `DecoderWrapper`, but the export marks `encoder_hidden_states` and
`encoder_attention_mask` dynamic too. This is the decoder graph the app actually ships. Its existence
is the tell: the fixed-16 encoder length in the first script was found to be wrong and patched by a
second script rather than a one-line fix to the first.

**The decisive fact for the whole optimization plan:** neither wrapper's `forward` takes
`past_key_values` and neither returns `present`/`next_cache`. The traced graph has no cache tensors
at any port. It therefore **cannot** do KV caching — every decode step re-attends the entire prefix
from scratch. This is a property of the *export wrapper*, not of IndicTrans2 (see
`INDICTRANS2_ARCHITECTURE.md` §3 and `EXPORT_FEASIBILITY.md`).

---

## 3. Encoder — inputs and outputs

| Port | Shape | Dtype | Notes |
|---|---|---|---|
| `input_ids` (in) | `[1, seq_len]` | int64 | SentencePiece ids, dynamic length |
| `attention_mask` (in) | `[1, seq_len]` | int64 | 1 = token, 0 = pad |
| `encoder_hidden_states` (out) | `[1, seq_len, 512]` | float32 | `hidden = encoder_embed_dim = 512` for the 200M dist model |

Run once per translation. Not on the hot path.

---

## 4. Decoder — inputs and outputs (shipped graph)

| Port | Shape | Dtype | Notes |
|---|---|---|---|
| `input_ids` (in) | `[1, decoder_seq]` | int64 | **the entire generated prefix so far**, re-fed every step |
| `encoder_hidden_states` (in) | `[1, encoder_seq, 512]` | float32 | constant across all steps of one translation |
| `encoder_attention_mask` (in) | `[1, encoder_seq]` | int64 | constant across all steps |
| `logits` (out) | `[1, decoder_seq, vocab]` | float32 | only the last row is used; the rest is recomputed waste |

The cost model this forces on the runtime is the headline problem V4 exists to fix:

- Step *t* feeds a prefix of length *t*, so decode is **O(n²)** in sequence length.
- The graph emits logits for **every** prefix position each step; the Kotlin side reads only row
  `t-1`. Materialising `[1, decoder_seq, vocab]` as boxed arrays is the ~4.6 MB/step allocation
  defect noted in `docs/ENGINEERING_PLAN.md`.

A cached decoder would take `input_ids = [1, 1]` (last token only) plus `past_key_values`, and emit
`[1, 1, vocab]` plus updated `present`. That is the export `EXPORT_FEASIBILITY.md` evaluates.

---

## 5. Every generated artifact, and where it comes from

Sizes are the on-disk bytes in v3.4.1.

### EN→HI — reproducible from the two scripts

| Artifact | Size | Produced by |
|---|---|---|
| `encoder_model.onnx` | 294 MB | `export_indictrans2_onnx.py` (fp32) |
| `decoder_model.onnx` | 806 MB | `export_decoder_dynamic.py` (fp32) |
| `encoder_model_int8.onnx` | 74.9 MB | ORT dynamic int8 quant of the above |
| `decoder_model_int8.onnx` | 203 MB | ORT dynamic int8 quant of the above |
| `dict.SRC.json` / `dict.TGT.json` | 645 KB / 3.4 MB | token→id maps, from the model tokenizer |
| `model.SRC` / `model.TGT` | 759 KB / 3.3 MB | SentencePiece models |

The quantisation step itself is **not scripted** in `translation_build/` — the fp32 graphs and their
int8 forms both exist, and the int8 files carry the `onnx.quantize` producer tag, so the step was
`onnxruntime.quantization` dynamic quant, but the exact call (per-channel? op exclusions?) is
**unrecorded**. First reproducibility gap.

### HI→EN — no script exists at all

| Artifact | Size | Produced by |
|---|---|---|
| `hi_en_encoder_int8.onnx` | 121.6 MB | **unknown — no export script** |
| `hi_en_decoder_int8.onnx` | 111.8 MB | **unknown — no export script** |
| `dict.SRC_HI.json` / `dict.TGT_EN.json` | 3.1 MB / 580 KB | — |
| `model.SRC_HI` | 3.3 MB | — |

The HI→EN ONNX files share the same internal node naming (`onnx::Unsqueeze_302`, …) and the same
`onnx.quantize` producer as the EN→HI files, so they were **almost certainly** produced by running
the same two scripts against `indictrans2-indic-en-dist-200M`. But:

- **No such script is checked in.** The direction that ships has no reproducible provenance.
- The sizes do **not** mirror EN→HI (encoder 121.6 MB vs 74.9 MB; decoder 111.8 MB vs 203 MB). That
  is a real discrepancy, not rounding — the HI→EN encoder is *larger* and its decoder *smaller* than
  their EN→HI counterparts. Until the export is re-run and the sizes reproduced, the exact source
  checkpoint and quant settings for HI→EN are **unverified**. This is risk R-PROV in
  `EXPORT_FEASIBILITY.md`.

---

## 6. What "reconstruct the pipeline" therefore means for V4

1. Authenticate to the gated AI4Bharat repos.
2. Re-run both scripts for **both** directions from named checkpoints, in the pinned `indic_env`
   (transformers 4.38.2, torch 2.1.2+cpu, python 3.10), producing a committed `export.py` that takes
   the direction as an argument — closing the HI→EN provenance gap.
3. Script the quantisation call explicitly so int8 artifacts are reproducible bit-for-bit-ish.
4. Only then attempt the cached-decoder export (`EXPORT_FEASIBILITY.md`), because the parity gate in
   `docs/ENGINEERING_PLAN.md` needs a reproducible *uncached* baseline to diff against.

Artifacts themselves are never committed to git (`ARCHITECTURE_RULES.md` R14.5 — model binaries in
history are unrecoverable). `model_pipeline/` holds scripts and docs; the `.onnx` outputs stay out.
