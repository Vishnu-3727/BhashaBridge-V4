"""INT8 dynamic quantization of the cached IndicTrans2 graphs (Phase 6C).

Converts the fp32 cached graphs from Phase 6A/6B to INT8, matching V3's production
approach: ONNX Runtime **dynamic** quantization (`quantize_dynamic`, QInt8 weights,
no calibration dataset). V3 shipped `encoder_model_int8.onnx` / `decoder_model_int8.onnx`
at ~4x smaller than fp32 with no committed quantize script; dynamic QInt8 is the method
that reproduces both the naming and that size ratio.

Dynamic quantization quantizes the weights of MatMul/Gemm-type ops to INT8 and inserts
dynamic (de)quantization around them; activations are quantized at run time from their
observed range, so no calibration data is needed. Crucially it does NOT touch the graph's
inputs or outputs — the KV-cache tensors stay float and the signatures are unchanged, so
the Phase 6B runtime and the cache ordering keep working untouched.

    onnx_cached/encoder.onnx       -> onnx_cached_int8/encoder_int8.onnx
    onnx_cached/decoder_init.onnx  -> onnx_cached_int8/decoder_init_int8.onnx
    onnx_cached/decoder_step.onnx  -> onnx_cached_int8/decoder_step_int8.onnx

Run (system Python 3.12, no model/network needed — pure graph transform):

    python quantize_cached.py --src onnx_cached --out onnx_cached_int8
    python verify_cache.py --onnx-dir onnx_cached_int8   # parity gate
"""

from __future__ import annotations

import argparse
import os

from onnxruntime.quantization import QuantType, quantize_dynamic

# fp32 source name -> int8 output name. Output names are the Phase 6C contract.
GRAPHS = {
    "encoder.onnx": "encoder_int8.onnx",
    "decoder_init.onnx": "decoder_init_int8.onnx",
    "decoder_step.onnx": "decoder_step_int8.onnx",
}


def quantize(src_dir: str, out_dir: str) -> None:
    """Dynamic-quantize all three cached graphs to INT8, weights QInt8."""
    os.makedirs(out_dir, exist_ok=True)
    for src, dst in GRAPHS.items():
        src_path = os.path.join(src_dir, src)
        dst_path = os.path.join(out_dir, dst)
        print(f"Quantizing {src} -> {dst} ...")
        quantize_dynamic(
            model_input=src_path,
            model_output=dst_path,
            weight_type=QuantType.QInt8,  # V3-equivalent: weights-only INT8, no calibration
        )
        fp32_mb = os.path.getsize(src_path) / 1e6
        int8_mb = os.path.getsize(dst_path) / 1e6
        print(f"  {fp32_mb:.1f} MB -> {int8_mb:.1f} MB  ({fp32_mb / int8_mb:.2f}x smaller)")
    print(f"Done. Wrote {len(GRAPHS)} INT8 graphs to {out_dir}")


def main() -> None:
    ap = argparse.ArgumentParser(description="INT8-quantize the cached IndicTrans2 graphs.")
    ap.add_argument("--src", default="onnx_cached", help="dir with the fp32 cached graphs")
    ap.add_argument("--out", default="onnx_cached_int8", help="output dir for INT8 graphs")
    args = ap.parse_args()
    quantize(args.src, args.out)


if __name__ == "__main__":
    main()
