"""IndicTrans2 KV-cache ONNX export pipeline (BhashaBridge V4, Phase 6A).

Exports three graphs from the gated IndicTrans2 checkpoint:

    encoder.onnx
        in : input_ids, attention_mask
        out: encoder_hidden_states

    decoder_init.onnx                 (first decoder step, no cache in)
        in : decoder_input_ids, encoder_hidden_states, encoder_attention_mask
        out: logits, present_key_values

    decoder_step.onnx                 (every later step, cache in and out)
        in : decoder_input_ids, encoder_hidden_states, encoder_attention_mask,
             past_key_values
        out: logits, present_key_values

This replaces v3.4.1's cache-less export (translation_build/export_indictrans2_onnx.py
and export_decoder_dynamic.py). Those wrappers called the decoder as

    self.decoder(input_ids, encoder_hidden_states, encoder_attention_mask)

with no `use_cache` and no `past_key_values`, so the exported graph physically
could not cache: every step re-attended the whole growing prefix (O(n^2)). The
IndicTrans2 remote code *does* implement the MBart caching contract
(IndicTransAttention/DecoderLayer/Decoder all thread past_key_value/use_cache and
cache cross-attention K/V) — the wrapper, not the model, dropped it. This file
threads it back.

Cache layout (MBart / M2M100 lineage, one tuple per decoder layer, four tensors):

    layer i -> ( self_key, self_value, cross_key, cross_value )

    self_key/self_value : [batch, n_heads, decoder_len_so_far, head_dim]   (grows +1 per step)
    cross_key/cross_value: [batch, n_heads, encoder_len,       head_dim]   (constant per translation)

ONNX cannot carry nested tuples, so the nested past_key_values is flattened to a
flat, ordered list of named tensors (optimum's decoder_with_past convention):

    past_key_values.{i}.decoder.key / .decoder.value / .encoder.key / .encoder.value   (inputs)
    present.{i}.decoder.key / .decoder.value / .encoder.key / .encoder.value            (outputs)

RUN ENVIRONMENT
    Not runnable on the Windows dev host: the checkpoint is gated (needs an HF
    token + accepted licence) and the stack is a Linux venv. Run inside the
    authenticated `indic_env` (see model_pipeline/EXPORT_WITH_CACHE.md for the
    exact commands and pinned versions). The pure-Python cache plumbing in this
    file is exercised model-free by `verify_cache.py --selfcheck`, which needs
    only torch.
"""

from __future__ import annotations

import argparse
from typing import List, Tuple

import torch

# Per-direction gated checkpoints. EN->HI is the primary (what v3.4.1 shipped);
# HI->EN is listed for completeness — its v3 provenance is unverified (R-PROV in
# EXPORT_FEASIBILITY.md), so re-export from this named checkpoint to trust it.
MODEL_NAMES = {
    "en_hi": "ai4bharat/indictrans2-en-indic-dist-200M",
    "hi_en": "ai4bharat/indictrans2-indic-en-dist-200M",
}

# A decoder layer's cache is these four roles, in this fixed order. The flat ONNX
# tensor list is (layer, role) in row-major order, so index = 4*layer + role.
CACHE_ROLES = ("decoder.key", "decoder.value", "encoder.key", "encoder.value")


# --------------------------------------------------------------------------- #
# Utilities (shared by the export wrappers and by verify_cache.py)             #
# --------------------------------------------------------------------------- #

def cache_names(prefix: str, num_layers: int) -> List[str]:
    """Return the flat, ordered ONNX tensor names for one cache.

    `prefix` is "past_key_values" for step inputs or "present" for outputs.
    Order matches `flatten_cache` exactly: layer-major, role-minor.
    """
    return [f"{prefix}.{layer}.{role}"
            for layer in range(num_layers)
            for role in CACHE_ROLES]


def flatten_cache(past: Tuple[Tuple[torch.Tensor, ...], ...]) -> List[torch.Tensor]:
    """Nested past_key_values -> flat tensor list, layer-major, role-minor.

    Input is the tuple-of-tuples HuggingFace returns as `past_key_values`
    (each inner tuple is self_key, self_value, cross_key, cross_value). Output
    ordering is the contract the ONNX graph's input/output names rely on.
    """
    flat: List[torch.Tensor] = []
    for layer in past:
        if len(layer) != len(CACHE_ROLES):
            raise ValueError(
                f"expected {len(CACHE_ROLES)} tensors per layer, got {len(layer)}"
            )
        flat.extend(layer)
    return flat


def unflatten_cache(
    flat: List[torch.Tensor], num_layers: int
) -> Tuple[Tuple[torch.Tensor, ...], ...]:
    """Flat tensor list -> nested past_key_values. Inverse of `flatten_cache`.

    The step graph receives cache tensors as flat positional args (ONNX cannot
    pass nested tuples); this rebuilds the structure the decoder expects.
    """
    r = len(CACHE_ROLES)
    expected = num_layers * r
    if len(flat) != expected:
        raise ValueError(f"expected {expected} cache tensors, got {len(flat)}")
    return tuple(
        tuple(flat[layer * r: layer * r + r]) for layer in range(num_layers)
    )


def load_model(direction: str):
    """Load a gated IndicTrans2 seq2seq model in eval mode for `direction`.

    `direction` is "en_hi" or "hi_en". Needs transformers, trust_remote_code,
    and an authenticated (gated-licence-accepted) HF environment.
    """
    from transformers import AutoModelForSeq2SeqLM  # local import: heavy + env-gated

    name = MODEL_NAMES[direction]
    print(f"Loading {name} ...")
    model = AutoModelForSeq2SeqLM.from_pretrained(name, trust_remote_code=True)
    model.eval()
    return model


def _dims(model) -> Tuple[int, int, int]:
    """(num_layers, num_heads, head_dim) read from config — never hard-coded."""
    cfg = model.config
    num_layers = cfg.decoder_layers
    num_heads = cfg.decoder_attention_heads
    head_dim = cfg.encoder_embed_dim // num_heads
    return num_layers, num_heads, head_dim


# --------------------------------------------------------------------------- #
# Export wrappers                                                             #
# --------------------------------------------------------------------------- #

class EncoderWrapper(torch.nn.Module):
    """Encoder graph: (input_ids, attention_mask) -> encoder_hidden_states.

    Identical in behaviour to v3.4.1's EncoderWrapper — the encoder has no cache,
    so it is unchanged. Kept here so the whole pipeline lives in one file.
    """

    def __init__(self, model):
        super().__init__()
        self.encoder = model.model.encoder

    def forward(self, input_ids, attention_mask):
        return self.encoder(
            input_ids=input_ids, attention_mask=attention_mask
        ).last_hidden_state


class DecoderInitWrapper(torch.nn.Module):
    """First decoder step: no cache in, full cache out.

    Runs the decoder with use_cache=True and no past, returning next-token logits
    plus the freshly built present_key_values (self-attn K/V for the primed
    tokens, cross-attn K/V for the whole encoder sequence). Flattened output:
    (logits, *present).

    **The hidden state is sliced to its last position before `lm_head`** (§9 Q1).
    Greedy decoding only ever reads the last row, but the unsliced graph ran the
    122,672-wide output projection over *every* prefix position and returned a
    `[1, dec_len, vocab]` tensor the runtime immediately threw away — ORT
    allocating and filling `dec_len x 122,672` floats to have one row read. The
    slice moves that discard from the runtime into the graph, where it also
    shrinks ORT's own allocation rather than only our copy of it.

    Only the logits are sliced. `past_key_values` must still cover every prefix
    position or the self-attention cache would be short its earlier keys, so
    `out.past_key_values` is passed through untouched.
    """

    def __init__(self, model):
        super().__init__()
        self.decoder = model.model.decoder
        self.lm_head = model.lm_head

    def forward(self, decoder_input_ids, encoder_hidden_states, encoder_attention_mask):
        out = self.decoder(
            input_ids=decoder_input_ids,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            use_cache=True,
        )
        # [:, -1:, :] not [:, -1, :] — keeps the rank at 3, so the output contract
        # stays [batch, 1, vocab] and every consumer's indexing is unchanged.
        logits = self.lm_head(out.last_hidden_state[:, -1:, :])
        return (logits, *flatten_cache(out.past_key_values))


class DecoderStepWrapper(torch.nn.Module):
    """Every later step: cache in, cache out.

    Takes one new token plus the flat past cache, feeds the decoder use_cache=True
    with the rebuilt nested past, and returns logits plus the grown present. Self
    K/V gain one row; cross K/V are reused from past unchanged (the whole point).
    `num_layers` is captured so the flat positional args can be unflattened.

    `encoder_hidden_states` is passed in but becomes a *pruned* graph input: with
    past present the decoder reuses cached cross-attn K/V and its output does not
    depend on the hidden states, so torch.onnx drops it from the step graph's
    inputs (verified: step inputs are decoder_input_ids, encoder_attention_mask,
    past only). It must still be passed to the module, though — passing None makes
    the decoder skip cross-attn and emit only 2 tensors/layer instead of 4,
    breaking the cache contract. So: pass it, let ONNX prune it.
    """

    def __init__(self, model, num_layers: int):
        super().__init__()
        self.decoder = model.model.decoder
        self.lm_head = model.lm_head
        self.num_layers = num_layers

    def forward(self, decoder_input_ids, encoder_hidden_states,
                encoder_attention_mask, *past_key_values):
        past = unflatten_cache(list(past_key_values), self.num_layers)
        out = self.decoder(
            input_ids=decoder_input_ids,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            past_key_values=past,
            use_cache=True,
        )
        logits = self.lm_head(out.last_hidden_state)
        return (logits, *flatten_cache(out.past_key_values))


# --------------------------------------------------------------------------- #
# Export driver                                                               #
# --------------------------------------------------------------------------- #

GRAPH_NAMES = ("encoder", "decoder_init", "decoder_step")


def export(direction: str, out_dir: str, opset: int = 14,
           graphs: tuple[str, ...] = GRAPH_NAMES) -> None:
    """Export encoder.onnx, decoder_init.onnx, decoder_step.onnx for `direction`.

    Uses tiny dummy inputs only to trace shapes; every real dimension is dynamic.
    opset 14 (v3 used 13) — cache-heavy graphs want the newer shape ops; bump
    only if an op is missing, and record it in EXPORT_WITH_CACHE.md.

    `graphs` restricts the run to a subset. The three exports share only the
    loaded checkpoint, so re-exporting one after a wrapper change does not
    require spending twenty minutes rewriting the two that did not change.
    """
    import os

    os.makedirs(out_dir, exist_ok=True)
    model = load_model(direction)
    num_layers, num_heads, head_dim = _dims(model)
    hidden = model.config.encoder_embed_dim
    print(f"layers={num_layers} heads={num_heads} head_dim={head_dim} hidden={hidden}")

    # One consistent source length for every dummy: input_ids, attention_mask and
    # encoder_hidden_states must agree, or the encoder's self-attn mask check trips.
    enc_len = 8
    ids = torch.ones((1, enc_len), dtype=torch.long)
    mask = torch.ones((1, enc_len), dtype=torch.long)
    dummy_hidden = torch.ones((1, enc_len, hidden), dtype=torch.float)

    def emit(name, *args, **kwargs):
        """Export `name` only if it was asked for, so a one-graph change is cheap."""
        if name not in graphs:
            print(f"Skipping {name}.onnx (not selected)")
            return
        print(f"Exporting {name}.onnx ...")
        torch.onnx.export(*args, **kwargs)

    # --- encoder -----------------------------------------------------------
    emit(
        "encoder",
        EncoderWrapper(model), (ids, mask),
        os.path.join(out_dir, "encoder.onnx"),
        input_names=["input_ids", "attention_mask"],
        output_names=["encoder_hidden_states"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "src_len"},
            "attention_mask": {0: "batch", 1: "src_len"},
            "encoder_hidden_states": {0: "batch", 1: "src_len"},
        },
        opset_version=opset,
    )

    # --- decoder_init ------------------------------------------------------
    dec_ids = torch.ones((1, 1), dtype=torch.long)
    present_names = cache_names("present", num_layers)
    # decoder self-attn cache axis 2 grows; cross-attn cache axis 2 is src_len.
    present_axes = {}
    for i in range(num_layers):
        present_axes[f"present.{i}.decoder.key"] = {0: "batch", 2: "past_len"}
        present_axes[f"present.{i}.decoder.value"] = {0: "batch", 2: "past_len"}
        present_axes[f"present.{i}.encoder.key"] = {0: "batch", 2: "src_len"}
        present_axes[f"present.{i}.encoder.value"] = {0: "batch", 2: "src_len"}
    emit(
        "decoder_init",
        DecoderInitWrapper(model), (dec_ids, dummy_hidden, mask),
        os.path.join(out_dir, "decoder_init.onnx"),
        input_names=["decoder_input_ids", "encoder_hidden_states",
                     "encoder_attention_mask"],
        output_names=["logits", *present_names],
        dynamic_axes={
            "decoder_input_ids": {0: "batch", 1: "dec_len"},
            "encoder_hidden_states": {0: "batch", 1: "src_len"},
            "encoder_attention_mask": {0: "batch", 1: "src_len"},
            # No `1: "dec_len"`: the wrapper slices to the last position, so this
            # axis is 1 whatever the prefix length. Declaring it dynamic would let
            # ORT keep a symbolic dim it can never need.
            "logits": {0: "batch"},
            **present_axes,
        },
        opset_version=opset,
    )

    # --- decoder_step ------------------------------------------------------
    past_names = cache_names("past_key_values", num_layers)
    # Build a dummy past: self len 1, cross len enc_len.
    dummy_past = []
    for _ in range(num_layers):
        dummy_past.append(torch.ones((1, num_heads, 1, head_dim)))          # self key
        dummy_past.append(torch.ones((1, num_heads, 1, head_dim)))          # self value
        dummy_past.append(torch.ones((1, num_heads, enc_len, head_dim)))    # cross key
        dummy_past.append(torch.ones((1, num_heads, enc_len, head_dim)))    # cross value
    past_axes = {}
    for i in range(num_layers):
        past_axes[f"past_key_values.{i}.decoder.key"] = {0: "batch", 2: "past_len"}
        past_axes[f"past_key_values.{i}.decoder.value"] = {0: "batch", 2: "past_len"}
        past_axes[f"past_key_values.{i}.encoder.key"] = {0: "batch", 2: "src_len"}
        past_axes[f"past_key_values.{i}.encoder.value"] = {0: "batch", 2: "src_len"}
    # encoder_hidden_states is passed to the module but torch.onnx prunes it from
    # the step graph's inputs (output is independent of it when past is present),
    # so the real decoder_step.onnx inputs are decoder_input_ids +
    # encoder_attention_mask + past. See DecoderStepWrapper docstring.
    emit(
        "decoder_step",
        DecoderStepWrapper(model, num_layers),
        (torch.ones((1, 1), dtype=torch.long), dummy_hidden, mask, *dummy_past),
        os.path.join(out_dir, "decoder_step.onnx"),
        input_names=["decoder_input_ids", "encoder_hidden_states",
                     "encoder_attention_mask", *past_names],
        output_names=["logits", *present_names],
        dynamic_axes={
            "decoder_input_ids": {0: "batch", 1: "dec_len"},
            "encoder_hidden_states": {0: "batch", 1: "src_len"},
            "encoder_attention_mask": {0: "batch", 1: "src_len"},
            "logits": {0: "batch", 1: "dec_len"},
            **past_axes,
            **present_axes,
        },
        opset_version=opset,
    )
    print(f"Done. Wrote {', '.join(g + '.onnx' for g in graphs)} to {out_dir}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Export cached IndicTrans2 ONNX graphs.")
    ap.add_argument("--direction", choices=MODEL_NAMES, default="en_hi")
    ap.add_argument("--out", default="onnx_cached")
    ap.add_argument("--opset", type=int, default=14)
    ap.add_argument("--graphs", nargs="+", choices=GRAPH_NAMES, default=list(GRAPH_NAMES),
                    help="subset to export; the rest are left as they are on disk")
    args = ap.parse_args()
    export(args.direction, args.out, args.opset, tuple(args.graphs))


if __name__ == "__main__":
    main()
