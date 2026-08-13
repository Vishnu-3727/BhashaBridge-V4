"""Translation-quality evaluation: BLEU and chrF for the shipping INT8 graphs against their FP32 reference.

This exists because token parity is not a quality measurement. §3.6 quantized the cached graphs and
gated them on `verify_cache.py --atol 1.0` (7/7, greedy tokens identical on a handful of synthetic
inputs); that proves the export did not break, not that the model still translates. This script runs a
real corpus through both precisions under the *same decode contract the app uses* and scores them.

The decode contract is ported from `bench/results/cross-device/bb_pi.py`, which is itself the port of
`Tokenizer.encode/decode` + `GreedyDecoder` used for the Raspberry Pi 5 entry. Anything that changes
here must change there too, or the two stop being comparable.

Usage
-----
    python quality_eval.py --direction en_hi \
        --models ../app/src/main/assets --suffix _int8 \
        --dicts ../app/src/main/assets \
        --src wmt14.en.txt --out hyp.int8.en_hi.txt --limit 200

    python quality_eval.py --direction en_hi \
        --models onnx_cached --suffix "" \
        --dicts ../app/src/main/assets \
        --src wmt14.en.txt --out hyp.fp32.en_hi.txt --limit 200

Then score with sacrebleu (chrF2 is the more reliable metric for a Devanagari target):

    sacrebleu ref.txt -i hyp.int8.en_hi.txt -m bleu chrf --chrf-word-order 2
"""
import argparse
import json
import os
import re
import sys
import time

import numpy as np
import onnxruntime as ort

MARK = "▁"                  # SentencePiece word-boundary marker
SPECIAL = {0, 1, 2, 3}
LANG_TAG = re.compile(r"^[a-z]{2,3}_[A-Z][a-z]{3,}$")
START = EOS = 2                  # IndicTrans2 (mBART family) reuses </s> as BOS
MAX_STEPS = 128
MIN_TARGET_LEN = 14
REP_PENALTY = 1.1
NO_REPEAT_NGRAM = 3

# Tokenizer.kt: the tag ids differ by vocabulary file, which is why they are looked up, not constants.
DIRECTIONS = {
    "en_hi": dict(src_dict="dict.SRC.json", tgt_dict="dict.TGT.json",
                  tags=("eng_Latn", 4, "hin_Deva", 15), prefix=""),
    "hi_en": dict(src_dict="dict.SRC_HI.json", tgt_dict="dict.TGT_EN.json",
                  tags=("hin_Deva", 8, "eng_Latn", 4), prefix="hi_en_"),
}


def load_dicts(d, cfg):
    with open(os.path.join(d, cfg["src_dict"]), encoding="utf-8") as f:
        src = json.load(f)
    with open(os.path.join(d, cfg["tgt_dict"]), encoding="utf-8") as f:
        tgt = json.load(f)
    return src, {v: k for k, v in tgt.items()}


def greedy_encode(text, src):
    """Longest-match-first subword split, window 20, unmatched char -> <unk>."""
    unk = src.get("<unk>", 3)
    out, pos = [], 0
    while pos < len(text):
        matched = False
        for end in range(min(len(text), pos + 20), pos, -1):
            piece = src.get(text[pos:end])
            if piece is not None:
                out.append(piece)
                pos = end
                matched = True
                break
        if not matched:
            out.append(unk)
            pos += 1
    return out


def encode(text, src, cfg):
    a_name, a_def, b_name, b_def = cfg["tags"]
    ids = [src.get(a_name, a_def), src.get(b_name, b_def)]
    for word in re.split(r"\s+", text.strip()):
        if not word:
            continue
        lower = word.lower()
        title = lower[:1].upper() + lower[1:]
        upper = lower.upper()
        tid = src.get(MARK + lower, src.get(MARK + title, src.get(MARK + upper)))
        if tid is not None:
            ids.append(tid)
        else:
            ids.extend(greedy_encode(MARK + lower, src))
    ids.append(src.get("</s>", 2))
    return np.array([ids], dtype=np.int64)


def decode_ids(ids, id_to_piece):
    pieces = []
    for i in ids:
        if i in SPECIAL:
            continue
        p = id_to_piece.get(int(i))
        if p is None or LANG_TAG.match(p):
            continue
        pieces.append(p)
    return "".join(pieces).replace(MARK, " ").strip()


def apply_repetition_penalty(logits, prefix, penalty):
    if penalty == 1.0:
        return
    seen = set()
    for tok in prefix:                        # first occurrence only, as the Kotlin does
        if tok in seen:
            continue
        seen.add(tok)
        t = int(tok)
        if 0 <= t < logits.shape[0]:
            logits[t] = logits[t] / penalty if logits[t] > 0 else logits[t] * penalty


def block_repeated_ngrams(logits, prefix, n):
    if n <= 0 or len(prefix) < n:
        return
    suffix_start = len(prefix) - (n - 1)
    for i in range(0, len(prefix) - n + 1):
        if all(prefix[i + k] == prefix[suffix_start + k] for k in range(n - 1)):
            blocked = int(prefix[i + n - 1])
            if 0 <= blocked < logits.shape[0]:
                logits[blocked] = -1e9


def target_cap(source_len):
    return min(max(MIN_TARGET_LEN, (source_len * 16) // 10 + 8), MAX_STEPS)


class Engine:
    """Three sessions, one direction, one precision. `suffix` picks fp32 ("") or int8 ("_int8")."""

    def __init__(self, model_dir, prefix, suffix, intra):
        so = ort.SessionOptions()
        so.intra_op_num_threads = intra
        so.inter_op_num_threads = 1
        so.enable_cpu_mem_arena = False                    # matches ExecutionPolicy
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        def mk(stem):
            path = os.path.join(model_dir, f"{prefix}{stem}{suffix}.onnx")
            if not os.path.exists(path):
                sys.exit(f"missing model: {path}")
            return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

        t0 = time.perf_counter()
        self.enc = mk("encoder")
        self.init = mk("decoder_init")
        self.step = mk("decoder_step")
        self.load_s = time.perf_counter() - t0
        step_inputs = [i.name for i in self.step.get_inputs()]
        self.past_names = [n for n in step_inputs
                           if n not in ("decoder_input_ids", "encoder_attention_mask")]
        n_present = len(self.init.get_outputs()) - 1
        assert len(self.past_names) == n_present, \
            f"cache mismatch: {len(self.past_names)} past vs {n_present} present"

    def translate(self, text, src, id_to_piece, cfg):
        """Returns (text, generated_ids, max_abs_logit) — ids and logit scale for the parity check."""
        ids = encode(text, src, cfg)
        mask = np.ones_like(ids)
        hidden = self.enc.run(None, {"input_ids": ids, "attention_mask": mask})[0]

        cap = target_cap(ids.shape[1])
        generated = [START]
        past = None
        peak_logit = 0.0
        for _ in range(MAX_STEPS):
            if past is None:
                out = self.init.run(None, {
                    "decoder_input_ids": np.array([generated], dtype=np.int64),
                    "encoder_hidden_states": hidden,
                    "encoder_attention_mask": mask,
                })
            else:
                feed = {"decoder_input_ids": np.array([[generated[-1]]], dtype=np.int64),
                        "encoder_attention_mask": mask}
                for i, name in enumerate(self.past_names):
                    feed[name] = past[i + 1]
                out = self.step.run(None, feed)
            past = out
            row = np.array(out[0][0, -1, :], dtype=np.float32)
            peak_logit = max(peak_logit, float(np.max(np.abs(row))))
            apply_repetition_penalty(row, generated, REP_PENALTY)
            block_repeated_ngrams(row, generated, NO_REPEAT_NGRAM)
            nxt = int(np.argmax(row))
            if nxt == EOS or len(generated) >= cap:
                break
            generated.append(nxt)
        return decode_ids(generated[1:], id_to_piece), generated[1:], peak_logit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--direction", choices=sorted(DIRECTIONS), required=True)
    ap.add_argument("--models", required=True, help="directory holding the .onnx graphs")
    ap.add_argument("--suffix", default="_int8", help='"_int8" (shipping) or "" (fp32 reference)')
    # The shipping assets carry the direction in the filename (`hi_en_encoder_int8.onnx`); the
    # export directories do not (`onnx_cached_hi_en/encoder.onnx`). Same graphs, different naming.
    ap.add_argument("--prefix", default=None, help='override the filename prefix; "" for export dirs')
    ap.add_argument("--dicts", required=True, help="directory holding dict.*.json")
    ap.add_argument("--src", required=True, help="source corpus, one sentence per line")
    ap.add_argument("--out", required=True, help="where to write hypotheses")
    ap.add_argument("--ids-out", help="optional: write generated id sequences as JSON lines")
    ap.add_argument("--limit", type=int, default=0, help="first N sentences (0 = all)")
    ap.add_argument("--intra", type=int, default=4)
    args = ap.parse_args()

    cfg = DIRECTIONS[args.direction]
    src_vocab, id_to_piece = load_dicts(args.dicts, cfg)
    lines = [l.rstrip("\n") for l in open(args.src, encoding="utf-8")]
    if args.limit:
        lines = lines[:args.limit]          # deterministic head of the corpus, never a sample

    prefix = cfg["prefix"] if args.prefix is None else args.prefix
    eng = Engine(args.models, prefix, args.suffix, args.intra)
    print(f"{args.direction} suffix='{args.suffix}' models={args.models} "
          f"load={eng.load_s:.1f}s sentences={len(lines)}", flush=True)

    t0 = time.perf_counter()
    hyps, id_rows, peak = [], [], 0.0
    for i, line in enumerate(lines, 1):
        text, ids, pl = eng.translate(line, src_vocab, id_to_piece, cfg)
        hyps.append(text)
        id_rows.append(ids)
        peak = max(peak, pl)
        if i % 25 == 0 or i == len(lines):
            rate = i / (time.perf_counter() - t0)
            print(f"  {i}/{len(lines)}  {rate:.2f} sent/s  eta {(len(lines)-i)/rate:.0f}s", flush=True)

    with open(args.out, "w", encoding="utf-8") as f:
        for h in hyps:
            f.write(h + "\n")
    if args.ids_out:
        with open(args.ids_out, "w", encoding="utf-8") as f:
            for row in id_rows:
                f.write(json.dumps(row) + "\n")
    print(f"wrote {args.out}  elapsed={time.perf_counter()-t0:.0f}s  peak_abs_logit={peak:.3f}")


if __name__ == "__main__":
    main()
