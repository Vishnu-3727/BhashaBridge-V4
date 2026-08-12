"""Share one weight blob across a direction's three INT8 graphs (Phase 13).

**The problem this fixes.** `cached_export.py` writes `decoder_init` and
`decoder_step` as two independent graphs, and `torch.onnx.export` materialises a
full copy of the decoder's weights into each. Hashing the raw tensor bytes shows
what that costs:

    EN->HI  decoder_init 201.8 MB   decoder_step 192.3 MB
            byte-identical in both: 402 tensors, 192.3 MB
            genuinely unique to decoder_step: 0.0 MB

`decoder_step` contains **no unique tensor data at all** -- every byte of it is
already in `decoder_init`. Across both directions that is ~292 MB of the app's
909 MB asset payload spent shipping the same weights twice, and it is almost
exactly the +283 MB V4 grew over v3.4.1 (`docs/V3_VS_V4_COMPARISON.md` §6). The
KV-cache did not cost that disk; the export did.

**What this does.** Rewrites the graphs so their initializers live in one
content-addressed external blob: identical raw bytes are written once and
referenced by every graph that uses them. Content, not name, is the key --
`torch.onnx.export` mints fresh `onnx::MatMul_NNNN` names on every export, so the
same weight matrix appears under different names in the two graphs and a
name-based pass finds only a third of the duplication.

**Two things this deliberately does not do.**

*It does not move small tensors.* Only initializers at or above `--min-bytes`
(default 1 KB) are externalised, matching ORT's own `optimized_model_external_
initializers_min_size_in_bytes` default. Constant-folded shape tensors must be
readable during shape inference, and externalising them fails the load with
`Cannot parse data from external tensors`. Measured, not guessed -- an earlier
version of this script moved everything and broke exactly that way.

*It does not touch the on-device `.ort` bake.* ORT's ORT-format writer inlines
every initializer and ignores the external-initializer session keys (measured:
the baked pair is 397.9 MB with those keys set and 397.9 MB without). So this
shrinks the **shipped assets and the APK**; device steady-state storage is still
the baked `.ort` files. Making the saving survive to device needs the graphs
baked ahead of time on the host, which is a separate change with a portability
question attached -- see MODEL_PIPELINE.md.

Run — **the blob name must differ per direction**, because both directions extract
into the same `filesDir` on device and `OnnxModels.sharedWeights` expects exactly
these two names:

    python dedup_weights.py --src onnx_cached_int8 --out onnx_shared_int8 \
        --blob weights.bin --verify
    python dedup_weights.py --src onnx_cached_hi_en_int8 --out onnx_shared_hi_en_int8 \
        --blob hi_en_weights.bin --verify

The name is written into every graph's `location` field, so it is fixed at this
point and cannot be changed by renaming the file afterwards.

`--verify` runs every graph before and after through ONNX Runtime on random
inputs and asserts the outputs are bit-identical. This transform must never
change a number: it moves bytes, it does not touch the graph.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys

import onnx
from onnx import TensorProto

# Page-aligned, so a future mmap of the blob never straddles a page for a tensor.
ALIGN = 4096

DEFAULT_BLOB = "weights.bin"

# The three graphs a direction ships. Missing ones are skipped, so this runs on a
# partial export without special-casing.
GRAPHS = ("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")


def mb(n: float) -> float:
    return n / 1e6


def _external_names(path: str) -> set[str]:
    """Initializers the producing tool already put in a side file, by name.

    Loaded without external data so this is cheap and cannot fail on a missing
    blob.
    """
    model = onnx.load(path, load_external_data=False)
    return {t.name for t in model.graph.initializer
            if t.data_location == TensorProto.EXTERNAL}


def dedup(src_dir: str, out_dir: str, blob_name: str = DEFAULT_BLOB,
          min_bytes: int = 1024) -> dict:
    """Point every large initializer in `src_dir`'s graphs at one shared blob.

    Returns a stats dict. Graphs are written to `out_dir` alongside the blob;
    ONNX resolves `location` relative to the model file, so the two must ship
    into the same directory on device.
    """
    os.makedirs(out_dir, exist_ok=True)
    blob_path = os.path.join(out_dir, blob_name)

    offsets: dict[str, tuple[int, int]] = {}
    stats = {"unique": 0, "reused": 0, "inline": 0, "graphs": {}, "src": {}}

    present = [g for g in GRAPHS if os.path.exists(os.path.join(src_dir, g))]
    if not present:
        raise SystemExit(f"no INT8 graphs found in {src_dir}")

    with open(blob_path, "wb") as blob:
        pos = 0
        for name in present:
            src_path = os.path.join(src_dir, name)
            stats["src"][name] = os.path.getsize(src_path)

            already_external = _external_names(src_path)
            model = onnx.load(src_path, load_external_data=True)

            for t in model.graph.initializer:
                raw = t.raw_data
                # Keep small tensors and non-raw (typed-field) tensors inline. If
                # the producer already externalised a tensor, honour that decision
                # regardless of size -- it knows its own shape-inference needs.
                if not raw or (len(raw) < min_bytes and t.name not in already_external):
                    stats["inline"] += len(raw)
                    continue

                key = hashlib.sha1(raw).hexdigest()
                if key not in offsets:
                    pad = (-pos) % ALIGN
                    if pad:
                        blob.write(b"\0" * pad)
                        pos += pad
                    blob.write(raw)
                    offsets[key] = (pos, len(raw))
                    pos += len(raw)
                    stats["unique"] += len(raw)
                else:
                    stats["reused"] += len(raw)

                offset, length = offsets[key]
                t.ClearField("raw_data")
                t.data_location = TensorProto.EXTERNAL
                del t.external_data[:]
                for k, v in (("location", blob_name),
                             ("offset", str(offset)),
                             ("length", str(length))):
                    entry = t.external_data.add()
                    entry.key, entry.value = k, v

            out_path = os.path.join(out_dir, name)
            onnx.save(model, out_path)
            stats["graphs"][name] = os.path.getsize(out_path)

    stats["blob"] = os.path.getsize(blob_path)
    return stats


def verify(src_dir: str, out_dir: str) -> None:
    """Assert the rewritten graphs produce bit-identical outputs.

    Random inputs, but every distinct symbolic dim name gets one consistent value
    -- which is the contract symbolic dims already promise -- so the self-attn and
    cross-attn cache lengths stay coherent without this test hard-coding the
    72-tensor cache layout.
    """
    import numpy as np
    import onnxruntime as ort

    def session(path):
        so = ort.SessionOptions()
        so.log_severity_level = 3
        return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

    def feeds(sess):
        rng = np.random.default_rng(0)
        sym = {"batch": 1, "batch_size": 1}
        out = {}
        for i in sess.get_inputs():
            dims = []
            for d in i.shape:
                if isinstance(d, int):
                    dims.append(d)
                else:
                    sym.setdefault(d, 3)
                    dims.append(sym[d])
            out[i.name] = (rng.integers(1, 100, size=dims).astype(np.int64)
                           if "int" in i.type
                           else rng.standard_normal(dims).astype(np.float32))
        return out

    failed = False
    for name in GRAPHS:
        src_path, out_path = os.path.join(src_dir, name), os.path.join(out_dir, name)
        if not os.path.exists(out_path):
            continue
        a, b = session(src_path), session(out_path)
        x = feeds(a)
        ra, rb = a.run(None, x), b.run(None, x)
        identical = len(ra) == len(rb) and all(
            np.array_equal(p, q) for p, q in zip(ra, rb))
        worst = max((float(np.abs(p - q).max()) for p, q in zip(ra, rb)), default=0.0)
        print(f"  {name:<26} {len(ra):>3} outputs  max_abs_diff {worst:g}  "
              f"bit-identical={identical}")
        failed |= not identical

    if failed:
        raise SystemExit("VERIFY FAILED - the rewrite changed a number; do not ship")
    print("  verify OK - every graph is bit-identical to its source")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Share one weight blob across a direction's INT8 graphs.")
    ap.add_argument("--src", default="onnx_cached_int8",
                    help="dir holding the INT8 graphs to rewrite")
    ap.add_argument("--out", default="onnx_shared_int8",
                    help="output dir (graphs + blob ship together)")
    ap.add_argument("--blob", default=DEFAULT_BLOB,
                    help="name of the shared weight file")
    ap.add_argument("--min-bytes", type=int, default=1024,
                    help="smallest initializer to externalise; below this stays "
                         "inline so shape inference can still read it")
    ap.add_argument("--verify", action="store_true",
                    help="run both graph sets and assert bit-identical outputs")
    ap.add_argument("--clean", action="store_true",
                    help="remove the output dir first")
    args = ap.parse_args()

    if args.clean and os.path.isdir(args.out):
        shutil.rmtree(args.out)

    stats = dedup(args.src, args.out, args.blob, args.min_bytes)

    before = sum(stats["src"].values())
    after = stats["blob"] + sum(stats["graphs"].values())

    print(f"\n{args.src} -> {args.out}")
    for name in stats["graphs"]:
        print(f"  {name:<26} {mb(stats['src'][name]):>8.1f} MB -> "
              f"{mb(stats['graphs'][name]):>6.2f} MB  (structure only)")
    print(f"  {args.blob:<26} {'':>8}    {mb(stats['blob']):>6.1f} MB  (shared)")
    print(f"  unique {mb(stats['unique']):.1f} MB written, "
          f"{mb(stats['reused']):.1f} MB deduplicated, "
          f"{mb(stats['inline']):.2f} MB left inline")
    saved = before - after
    print(f"  TOTAL {mb(before):.1f} MB -> {mb(after):.1f} MB "
          f"({mb(saved):.1f} MB saved, -{100 * saved / before:.0f}%)")

    if args.verify:
        print("\nverifying bit-identical output:")
        verify(args.src, args.out)


if __name__ == "__main__":
    sys.exit(main())
