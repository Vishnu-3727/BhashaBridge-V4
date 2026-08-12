#!/usr/bin/env python3
"""BhashaBridge EN->HI on a Raspberry Pi 5, as a cross-platform check of the INT8 graphs.

A faithful port of the Android runtime's decode contract -- Tokenizer.encode/decode,
GreedyDecoder, CachedLogitsSource -- so the output can be compared token-for-token with the
phone. Anything that differs here is a port bug, not a platform difference, which is why the
parity check runs before any timing.

Not a port of: the .ort/.opt.onnx bake, mapped initializers, the vocabulary cache. This loads the
raw .onnx graphs with ALL_OPT every run, so the load numbers are NOT comparable with the phone's
warm path -- only inference is.
"""
import argparse
import json
import os
import re
import statistics
import time

import numpy as np
import onnxruntime as ort

MARK = "▁"                       # SentencePiece word-boundary marker
SPECIAL = {0, 1, 2, 3}
LANG_TAG = re.compile(r"^[a-z]{2,3}_[A-Z][a-z]{3,}$")
START = EOS = 2                       # IndicTrans2 (mBART family) reuses </s> as BOS
MAX_STEPS = 128
MIN_TARGET_LEN = 14
REP_PENALTY = 1.1
NO_REPEAT_NGRAM = 3


def load_dicts(d):
    with open(os.path.join(d, "dict.SRC.json"), encoding="utf-8") as f:
        src = json.load(f)
    with open(os.path.join(d, "dict.TGT.json"), encoding="utf-8") as f:
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


def encode(text, src):
    ids = [src.get("eng_Latn", 4), src.get("hin_Deva", 15)]
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
    def __init__(self, model_dir, intra, providers=None, disable_kleidi=False):
        so = ort.SessionOptions()
        so.intra_op_num_threads = intra
        so.inter_op_num_threads = 1
        so.enable_cpu_mem_arena = False           # matches ExecutionPolicy
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        if disable_kleidi:
            so.add_session_config_entry("mlas.disable_kleidiai", "1")
        t0 = time.perf_counter()
        mk = lambda n: ort.InferenceSession(os.path.join(model_dir, n), so,
                                            providers=providers or ["CPUExecutionProvider"])
        self.enc = mk("encoder_int8.onnx")
        self.init = mk("decoder_init_int8.onnx")
        self.step = mk("decoder_step_int8.onnx")
        self.load_ms = (time.perf_counter() - t0) * 1000
        step_inputs = [i.name for i in self.step.get_inputs()]
        self.past_names = [n for n in step_inputs
                           if n not in ("decoder_input_ids", "encoder_attention_mask")]
        n_present = len(self.init.get_outputs()) - 1
        assert len(self.past_names) == n_present, \
            f"cache mismatch: {len(self.past_names)} past vs {n_present} present"

    def translate(self, text, src, id_to_piece):
        ids = encode(text, src)
        mask = np.ones_like(ids)
        hidden = self.enc.run(None, {"input_ids": ids, "attention_mask": mask})[0]

        cap = target_cap(ids.shape[1])
        generated = [START]
        past = None
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
            apply_repetition_penalty(row, generated, REP_PENALTY)
            block_repeated_ngrams(row, generated, NO_REPEAT_NGRAM)
            nxt = int(np.argmax(row))
            if nxt == EOS or len(generated) >= cap:
                break
            generated.append(nxt)
        return decode_ids(generated[1:], id_to_piece), len(generated) - 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=os.path.expanduser("~/bb-pi/models"))
    ap.add_argument("--intra", type=int, default=2)
    ap.add_argument("--runs", type=int, default=15)
    ap.add_argument("--no-kleidi", action="store_true")
    args = ap.parse_args()

    src, id_to_piece = load_dicts(args.models)
    eng = Engine(args.models, args.intra, disable_kleidi=args.no_kleidi)

    short, long = "Water.", "The weather is very nice today and I want to go outside."
    out_s, _ = eng.translate(short, src, id_to_piece)
    out_l, tok_l = eng.translate(long, src, id_to_piece)
    print(f"PARITY short='{out_s}'")
    print(f"PARITY long='{out_l}' tokens={tok_l}")

    for label, text in (("short", short), ("long", long)):
        for _ in range(3):                      # warm up, excluded
            eng.translate(text, src, id_to_piece)
        times = []
        for _ in range(args.runs):
            t0 = time.perf_counter()
            _, tok = eng.translate(text, src, id_to_piece)
            times.append((time.perf_counter() - t0) * 1000)
        times.sort()
        med = statistics.median(times)
        print(f"BENCH {label} intra={args.intra} kleidi={'off' if args.no_kleidi else 'on'} "
              f"n={len(times)} median={med:.1f} min={times[0]:.1f} "
              f"p95={times[int(len(times) * 0.95) - 1]:.1f} "
              f"stdev={statistics.stdev(times):.1f} tokens={tok} "
              f"tok_per_s={tok / (med / 1000):.1f}")
    print(f"LOAD graphs_ms={eng.load_ms:.0f}")


if __name__ == "__main__":
    main()
