"""Verification for the cached IndicTrans2 export (BhashaBridge V4, Phase 6A).

Two modes, deliberately separated so the bug-prone plumbing can be proven on any
host while the model-dependent numerics are proven only where the gated model runs:

    python verify_cache.py --selfcheck
        Model-free. Needs only torch. Proves the cache flatten/unflatten contract
        (count, order, round-trip, self-len growth, cross-len constancy) that both
        cached_export.py and the ONNX graphs depend on. Runs on the Windows dev host.

    python verify_cache.py --onnx-dir onnx_cached --direction en_hi
        Full gate. Needs the gated model + transformers + onnxruntime, i.e. the
        authenticated Linux indic_env. Runs the seven Phase 6A checks:

          1. model loads
          2. use_cache=True executes in eager PyTorch
          3. decoder_init output valid (logits + present shapes/count)
          4. decoder_step accepts the previous cache
          5. cache tensor count and shapes correct
          6. cached logits match the uncached graph within tolerance
          7. greedy translations identical, cached vs uncached

Exit code is non-zero if any check fails. On failure: STOP — do not proceed to
Android integration (Phase 6A stop rule).
"""

from __future__ import annotations

import argparse
import sys
from typing import List, Tuple

import torch

from cached_export import (
    CACHE_ROLES,
    MODEL_NAMES,
    cache_names,
    flatten_cache,
    load_model,
    unflatten_cache,
    _dims,
)

LOGIT_ATOL = 1e-3  # int8/float export tolerance; tighten to 1e-4 for the fp32 graph.


# --------------------------------------------------------------------------- #
# Mode A: model-free plumbing self-check (torch only, runs anywhere)           #
# --------------------------------------------------------------------------- #

class _FakeCachedDecoder:
    """Minimal stand-in that honours the MBart past_key_values contract.

    Each call appends one row to every layer's self-attn K/V and echoes the
    cross-attn K/V unchanged — exactly the shape behaviour the real decoder has,
    with none of the weights. Enough to exercise the flatten/unflatten plumbing.
    """

    def __init__(self, num_layers: int, num_heads: int, head_dim: int, enc_len: int):
        self.L, self.H, self.D, self.enc = num_layers, num_heads, head_dim, enc_len

    def step(self, past: Tuple[Tuple[torch.Tensor, ...], ...] | None):
        present = []
        for layer in range(self.L):
            if past is None:
                self_k = torch.randn(1, self.H, 1, self.D)
                self_v = torch.randn(1, self.H, 1, self.D)
                cross_k = torch.randn(1, self.H, self.enc, self.D)
                cross_v = torch.randn(1, self.H, self.enc, self.D)
            else:
                pk, pv, ck, cv = past[layer]
                self_k = torch.cat([pk, torch.randn(1, self.H, 1, self.D)], dim=2)
                self_v = torch.cat([pv, torch.randn(1, self.H, 1, self.D)], dim=2)
                cross_k, cross_v = ck, cv  # cross cache reused unchanged
            present.append((self_k, self_v, cross_k, cross_v))
        return tuple(present)


def selfcheck() -> bool:
    """Prove the cache plumbing model-free. Returns True on pass."""
    L, H, D, ENC = 4, 8, 64, 7
    ok = True

    def check(name: str, cond: bool):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
        ok = ok and cond

    dec = _FakeCachedDecoder(L, H, D, ENC)

    # init: no past in, full cache out
    present0 = dec.step(None)
    flat0 = flatten_cache(present0)
    check("init cache count == 4 * num_layers", len(flat0) == 4 * L)
    check("cache_names count matches flat count",
          len(cache_names("present", L)) == len(flat0))
    check("cache_names unique + ordered",
          cache_names("present", L) == [f"present.{i}.{r}"
                                        for i in range(L) for r in CACHE_ROLES])

    # round-trip flatten -> unflatten is identity
    rebuilt = unflatten_cache(flat0, L)
    rt = all(torch.equal(a, b)
             for lp, lr in zip(present0, rebuilt) for a, b in zip(lp, lr))
    check("flatten/unflatten round-trips", rt)

    # step: consumes init present, grows self len by 1, keeps cross len
    present1 = dec.step(rebuilt)
    self_len0 = present0[0][0].shape[2]
    self_len1 = present1[0][0].shape[2]
    cross_len0 = present0[0][2].shape[2]
    cross_len1 = present1[0][2].shape[2]
    check("step accepts previous cache (count stable)",
          len(flatten_cache(present1)) == 4 * L)
    check("self-attn cache grows +1 per step", self_len1 == self_len0 + 1)
    check("cross-attn cache length constant", cross_len1 == cross_len0 == ENC)

    # shape roles: self [B,H,len,D], cross [B,H,enc,D]
    sk = present1[0][0]
    ck = present1[0][2]
    check("self K/V shape [B,H,dec,D]",
          sk.shape[:2] == (1, H) and sk.shape[3] == D)
    check("cross K/V shape [B,H,enc,D]",
          ck.shape[1] == H and ck.shape[2] == ENC and ck.shape[3] == D)

    print(f"\nself-check: {'PASS' if ok else 'FAIL'}")
    return ok


# --------------------------------------------------------------------------- #
# Mode B: full gate (needs gated model + onnxruntime; runs in indic_env)       #
# --------------------------------------------------------------------------- #

def _greedy_uncached(model, enc_hidden, enc_mask, bos: int, eos: int, max_len: int):
    """Reference greedy loop through the eager decoder with use_cache=False."""
    ids = torch.tensor([[bos]], dtype=torch.long)
    logits_trace = []
    for _ in range(max_len):
        out = model.model.decoder(
            input_ids=ids,
            encoder_hidden_states=enc_hidden,
            encoder_attention_mask=enc_mask,
            use_cache=False,
        )
        step_logits = model.lm_head(out.last_hidden_state)[:, -1, :]
        logits_trace.append(step_logits)
        nxt = int(step_logits.argmax(-1))
        ids = torch.cat([ids, torch.tensor([[nxt]])], dim=1)
        if nxt == eos:
            break
    return ids, logits_trace


def _greedy_onnx_cached(sessions, enc_hidden, enc_mask, num_layers, bos, eos, max_len):
    """Greedy loop through decoder_init.onnx then decoder_step.onnx."""
    import numpy as np

    init, step = sessions
    ids = np.array([[bos]], dtype=np.int64)
    eh = enc_hidden.numpy()
    em = enc_mask.numpy()

    present_names = cache_names("present", num_layers)
    out = init.run(None, {
        "decoder_input_ids": ids,
        "encoder_hidden_states": eh,
        "encoder_attention_mask": em,
    })
    logits, cache = out[0], out[1:]
    logits_trace = [torch.from_numpy(logits[:, -1, :])]
    seq = [bos, int(logits[0, -1].argmax())]

    past_names = cache_names("past_key_values", num_layers)
    for _ in range(max_len - 1):
        if seq[-1] == eos:
            break
        feed = {  # step graph has no encoder_hidden_states (cross K/V come from past)
            "decoder_input_ids": np.array([[seq[-1]]], dtype=np.int64),
            "encoder_attention_mask": em,
        }
        feed.update(dict(zip(past_names, cache)))
        out = step.run(None, feed)
        logits, cache = out[0], out[1:]
        logits_trace.append(torch.from_numpy(logits[:, -1, :]))
        seq.append(int(logits[0, -1].argmax()))
    return seq, logits_trace


def _find(onnx_dir: str, prefix: str) -> str:
    """The one .onnx in onnx_dir whose name starts with prefix (fp32 or _int8)."""
    import glob
    import os
    hits = sorted(glob.glob(os.path.join(onnx_dir, prefix + "*.onnx")))
    if not hits:
        raise FileNotFoundError(f"no {prefix}*.onnx in {onnx_dir}")
    return hits[0]


def full_gate(direction: str, onnx_dir: str, atol: float = LOGIT_ATOL) -> bool:
    """Run the seven Phase 6A checks. Returns True only if all pass.

    `atol` is the check-6 logit tolerance: keep the fp32 default for the fp32 graphs,
    raise it for int8 (dynamic quantization shifts logits far more than fp32 export).
    Check 7 (token identity) is the decisive parity gate regardless of `atol`.
    """
    import os
    import numpy as np
    import onnxruntime as ort

    torch.set_grad_enabled(False)  # inference only; keeps tensors .numpy()-able
    ok = True

    def check(name: str, cond: bool):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
        ok = ok and cond

    # (1) model loads
    model = load_model(direction)
    check("1. model loads", model is not None)
    num_layers, num_heads, head_dim = _dims(model)
    bos = model.config.decoder_start_token_id
    eos = model.config.eos_token_id

    # Encoder pass to get real hidden states (short synthetic source).
    src = torch.ones((1, 6), dtype=torch.long)
    src_mask = torch.ones((1, 6), dtype=torch.long)
    enc_hidden = model.model.encoder(
        input_ids=src, attention_mask=src_mask
    ).last_hidden_state

    # (2) use_cache=True executes eager
    try:
        eager = model.model.decoder(
            input_ids=torch.tensor([[bos]]),
            encoder_hidden_states=enc_hidden,
            encoder_attention_mask=src_mask,
            use_cache=True,
        )
        check("2. use_cache=True executes", eager.past_key_values is not None)
    except Exception as e:  # noqa: BLE001
        check(f"2. use_cache=True executes ({e})", False)
        return False

    # ONNX sessions — discover by prefix so fp32 (decoder_init.onnx) and int8
    # (decoder_init_int8.onnx) dirs both work with no rename.
    init = ort.InferenceSession(_find(onnx_dir, "decoder_init"))
    step = ort.InferenceSession(_find(onnx_dir, "decoder_step"))

    # (3) decoder_init output valid
    init_out = init.run(None, {
        "decoder_input_ids": np.array([[bos]], dtype=np.int64),
        "encoder_hidden_states": enc_hidden.numpy(),
        "encoder_attention_mask": src_mask.numpy(),
    })
    logits0, cache0 = init_out[0], init_out[1:]
    check("3. decoder_init logits shape [1,1,vocab]",
          logits0.shape[0] == 1 and logits0.shape[1] == 1)

    # (5) cache tensor count and shapes
    check("5a. present count == 4*layers", len(cache0) == 4 * num_layers)
    self_ok = all(cache0[4 * i].shape[1] == num_heads and cache0[4 * i].shape[3] == head_dim
                  for i in range(num_layers))
    cross_ok = all(cache0[4 * i + 2].shape[2] == enc_hidden.shape[1]
                   for i in range(num_layers))
    check("5b. self-attn cache shape [B,H,*,D]", self_ok)
    check("5c. cross-attn cache length == src_len", cross_ok)

    # (4) decoder_step accepts previous cache
    nxt = int(logits0[0, -1].argmax())
    feed = {  # step graph has no encoder_hidden_states (cross K/V come from past)
        "decoder_input_ids": np.array([[nxt]], dtype=np.int64),
        "encoder_attention_mask": src_mask.numpy(),
    }
    feed.update(dict(zip(cache_names("past_key_values", num_layers), cache0)))
    step_out = step.run(None, feed)
    logits1, cache1 = step_out[0], step_out[1:]
    grew = cache1[0].shape[2] == cache0[0].shape[2] + 1
    check("4. decoder_step accepts cache and self-len grows +1", grew)

    # (6) cached logits match uncached within tolerance
    ref_ids, ref_logits = _greedy_uncached(model, enc_hidden, src_mask, bos, eos, 24)
    onnx_seq, onnx_logits = _greedy_onnx_cached(
        (init, step), enc_hidden, src_mask, num_layers, bos, eos, 24)
    n = min(len(ref_logits), len(onnx_logits))
    max_diff = max(
        float((ref_logits[i] - onnx_logits[i]).abs().max()) for i in range(n)
    ) if n else float("inf")
    check(f"6. cached logits match uncached (max_abs_diff={max_diff:.2e} <= {atol})",
          max_diff <= atol)

    # (7) translations identical
    ref_seq = ref_ids[0].tolist()
    check(f"7. token sequences identical (ref={ref_seq} onnx={onnx_seq})",
          ref_seq == onnx_seq)

    print(f"\nfull gate: {'PASS' if ok else 'FAIL'}")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description="Verify the cached IndicTrans2 export.")
    ap.add_argument("--selfcheck", action="store_true",
                    help="model-free plumbing check (torch only)")
    ap.add_argument("--onnx-dir", help="dir with encoder/decoder_init/decoder_step .onnx")
    ap.add_argument("--direction", choices=MODEL_NAMES, default="en_hi")
    ap.add_argument("--atol", type=float, default=LOGIT_ATOL,
                    help=f"check-6 logit tolerance (default {LOGIT_ATOL}; raise for int8)")
    args = ap.parse_args()

    if args.selfcheck:
        return 0 if selfcheck() else 1
    if args.onnx_dir:
        return 0 if full_gate(args.direction, args.onnx_dir, args.atol) else 1
    ap.error("pass --selfcheck or --onnx-dir")


if __name__ == "__main__":
    sys.exit(main())
