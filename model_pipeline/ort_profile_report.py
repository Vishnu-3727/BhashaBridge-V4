#!/usr/bin/env python3
"""P8 operator-level profiling report.

Ingests one or more ONNX Runtime profiling traces (the Chrome-trace JSON that
`SessionOptions.enableProfiling` / `OrtSession.endProfiling` produce, captured on-device by
`OrtProfilingTest`) and reports exactly where inference time goes: per-operator runtime, %, call
count, cumulative %, average, and a ranking — plus a static op annotation table.

Provenance discipline (matches the benchmark reports):
  * MEASURED, straight from the trace: op type, kernel time, call count, execution provider,
    graph execution order, tensor sizes.
  * INFERRED, from a static knowledge table, NOT from the trace: which MLAS kernel runs an op, the
    SIMD path (NEON / SDOT / i8mm), and compute- vs memory-bound. The profiler cannot see inside
    MLAS — those columns are clearly tagged and must be confirmed with simpleperf / Perfetto
    (see the notes the report prints).

Usage:
    python ort_profile_report.py TRACE.json [TRACE2.json ...] [--out report.md] [--by-node]
    python ort_profile_report.py --selftest

The decoder_step trace is the hot loop (runs once per generated token); profile that one to see the
steady-state cost. encoder / decoder_init run once per sentence.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from dataclasses import dataclass, field


# ---- trace parsing (MEASURED) --------------------------------------------------------------------

def load_events(paths: list[str]) -> list[dict]:
    """Concatenate the event lists of every trace file. ORT emits either a bare JSON array or
    `{"traceEvents": [...]}` depending on version; accept both."""
    events: list[dict] = []
    for p in paths:
        with open(p, "r", encoding="utf-8") as f:
            doc = json.load(f)
        events.extend(doc["traceEvents"] if isinstance(doc, dict) else doc)
    return events


@dataclass
class OpAgg:
    op: str
    calls: int = 0
    total_us: int = 0
    providers: set[str] = field(default_factory=set)
    nodes: set[str] = field(default_factory=set)

    @property
    def avg_us(self) -> float:
        return self.total_us / self.calls if self.calls else 0.0


def aggregate(events: list[dict]) -> tuple[dict[str, OpAgg], int, list[dict]]:
    """Group node kernel-time events by op type. Returns (by_op, model_run_us, node_events).

    A node's compute event has cat=="Node" and name ending "_kernel_time"; its `dur` is microseconds
    and `args.op_name` is the ONNX op type. `_fence_before`/`_fence_after` events are ~0 and excluded.
    Session-level "model_run" events give the total wall time per inference (compute + ORT overhead).
    """
    by_op: dict[str, OpAgg] = {}
    node_events: list[dict] = []
    model_run_us = 0
    for e in events:
        cat, name, dur = e.get("cat"), e.get("name", ""), int(e.get("dur", 0) or 0)
        if cat == "Node" and name.endswith("_kernel_time"):
            args = e.get("args", {})
            op = args.get("op_name") or "<unknown>"
            agg = by_op.setdefault(op, OpAgg(op))
            agg.calls += 1
            agg.total_us += dur
            if args.get("provider"):
                agg.providers.add(args["provider"])
            agg.nodes.add(name[: -len("_kernel_time")])
            node_events.append(e)
        elif name == "model_run":
            model_run_us += dur
    return by_op, model_run_us, node_events


# ---- op annotation (INFERRED — static knowledge, not from the trace) -----------------------------
# Columns: kernel, uses_MLAS, SIMD path (hwcap-dispatched at runtime), likely bottleneck, thread-
# scalable, cache-sensitive, candidate optimization. Keyed by ONNX op type as it appears in the
# quantized IndicTrans2 graphs. Anything not listed prints with "—" and must be looked up.
ANNOT: dict[str, dict[str, str]] = {
    "MatMul":                 dict(kernel="MlasGemm",           mlas="yes", simd="SDOT/i8mm if int8 else FP", bound="compute@largeM / memory@thin", threaded="yes", cache="yes", opt="cache-blocking, KleidiAI, INT4"),
    "MatMulInteger":          dict(kernel="MlasGemmQuant(int8)", mlas="yes", simd="NEON→SDOT→i8mm", bound="compute (or memory for per-token GEMV)", threaded="yes", cache="yes", opt="i8mm/KleidiAI, prepack, INT4"),
    "QLinearMatMul":          dict(kernel="MlasGemmQuant(int8)", mlas="yes", simd="NEON→SDOT→i8mm", bound="compute (or memory for per-token GEMV)", threaded="yes", cache="yes", opt="i8mm/KleidiAI, prepack, INT4"),
    "DynamicQuantizeLinear":  dict(kernel="MlasQuantizeLinear", mlas="yes", simd="NEON", bound="memory (streaming)", threaded="partial", cache="no", opt="fuse into matmul / static-quant"),
    "DynamicQuantizeMatMul":  dict(kernel="MlasGemmQuant+quant", mlas="yes", simd="NEON→SDOT→i8mm", bound="compute+quantize", threaded="yes", cache="yes", opt="static-quant, i8mm, prepack"),
    "Add":                    dict(kernel="MlasEltwise",        mlas="yes", simd="NEON", bound="memory (bandwidth)", threaded="yes", cache="stream", opt="fusion (residual/bias)"),
    "Mul":                    dict(kernel="MlasEltwise",        mlas="yes", simd="NEON", bound="memory (bandwidth)", threaded="yes", cache="stream", opt="fusion"),
    "Softmax":                dict(kernel="MlasComputeSoftmax", mlas="yes", simd="NEON", bound="memory + exp latency", threaded="partial", cache="row", opt="fused attention"),
    "LayerNormalization":     dict(kernel="MlasLayerNorm",      mlas="yes", simd="NEON", bound="memory (reduction)", threaded="partial", cache="row", opt="fusion"),
    "SkipLayerNormalization": dict(kernel="fused LN",           mlas="yes", simd="NEON", bound="memory", threaded="partial", cache="row", opt="already fused"),
    "Gelu":                   dict(kernel="MlasGelu",           mlas="yes", simd="NEON", bound="memory + transcendental", threaded="yes", cache="stream", opt="fused MatMul+Gelu"),
    "FastGelu":               dict(kernel="MlasGelu",           mlas="yes", simd="NEON", bound="memory", threaded="yes", cache="stream", opt="already fused"),
    "Attention":              dict(kernel="fused Attention",    mlas="yes", simd="SDOT/i8mm", bound="mixed", threaded="yes", cache="yes", opt="already fused; flash-style tiling"),
    "Gather":                 dict(kernel="Gather (embed)",     mlas="no",  simd="—", bound="memory (random access)", threaded="no", cache="no", opt="none (embedding lookup)"),
    "Transpose":              dict(kernel="Transpose",          mlas="no",  simd="NEON copy", bound="memory (movement)", threaded="partial", cache="no", opt="eliminate via fusion/layout"),
    "Reshape":                dict(kernel="Reshape(no-op)",     mlas="no",  simd="—", bound="free (view)", threaded="no", cache="—", opt="none"),
    "Concat":                 dict(kernel="Concat",             mlas="no",  simd="NEON copy", bound="memory (movement)", threaded="no", cache="no", opt="KV-cache layout"),
    "Cast":                   dict(kernel="MlasCast",           mlas="yes", simd="NEON", bound="memory", threaded="yes", cache="stream", opt="remove redundant casts"),
}


def annot(op: str) -> dict[str, str]:
    return ANNOT.get(op, dict(kernel="—", mlas="?", simd="—", bound="? (look up)", threaded="?", cache="?", opt="?"))


# ---- report --------------------------------------------------------------------------------------

def render(by_op: dict[str, OpAgg], model_run_us: int, node_count: int, by_node: bool) -> str:
    ordered = sorted(by_op.values(), key=lambda a: a.total_us, reverse=True)
    total_kernel_us = sum(a.total_us for a in ordered)
    providers = sorted({p for a in ordered for p in a.providers})
    out: list[str] = []
    w = out.append

    w("# ORT Operator Profile — P8\n")
    w(f"- Node kernel events: **{node_count}**")
    w(f"- Distinct op types: **{len(ordered)}**")
    w(f"- Total kernel time (sum of node `dur`): **{total_kernel_us/1000:.1f} ms**")
    if model_run_us:
        overhead = model_run_us - total_kernel_us
        w(f"- Total `model_run` wall time: **{model_run_us/1000:.1f} ms** "
          f"(ORT overhead outside kernels ≈ {overhead/1000:.1f} ms, {pct(overhead, model_run_us):.1f}%)")
    w(f"- Execution provider(s): **{', '.join(providers) or 'n/a'}** "
      f"{'← all CPU/MLAS, no EP fallback' if providers == ['CPUExecutionProvider'] else '← check for fallback'}")
    w("\nAll of the above is **MEASURED** from the trace.\n")

    w("## Operator ranking (MEASURED)\n")
    w("| # | Operator | Runtime % | Cumulative % | Calls | Total ms | Avg ms |")
    w("|---|---|---|---|---|---|---|")
    cum = 0
    for i, a in enumerate(ordered, 1):
        cum += a.total_us
        w(f"| {i} | {a.op} | {pct(a.total_us, total_kernel_us):.1f}% | {pct(cum, total_kernel_us):.1f}% "
          f"| {a.calls} | {a.total_us/1000:.2f} | {a.avg_us/1000:.3f} |")

    w("\n## Kernel / SIMD / bottleneck annotation (INFERRED — not from the trace)\n")
    w("> The profiler cannot see inside MLAS. `Kernel`, `MLAS`, `SIMD`, and `Bottleneck` below are a "
      "static lookup, NOT measured. Confirm SIMD dispatch with simpleperf symbols; confirm the "
      "compute/memory split with PMU counters (see notes).\n")
    w("| Operator | Runtime % | Calls | Kernel | MLAS | SIMD | Threaded | Bottleneck | Optimization |")
    w("|---|---|---|---|---|---|---|---|---|")
    for a in ordered:
        an = annot(a.op)
        w(f"| {a.op} | {pct(a.total_us, total_kernel_us):.1f}% | {a.calls} | {an['kernel']} | {an['mlas']} "
          f"| {an['simd']} | {an['threaded']} | {an['bound']} | {an['opt']} |")

    if by_node:
        w("\n## Per-node breakdown (MEASURED)\n")
        w("| Op | Distinct nodes | Total ms |")
        w("|---|---|---|")
        for a in ordered:
            w(f"| {a.op} | {len(a.nodes)} | {a.total_us/1000:.2f} |")

    w("\n## Where to spend effort (derived from the ranking above)\n")
    w(rank_levers(ordered, total_kernel_us))

    w("\n## What this trace cannot answer (needs more instrumentation)\n")
    w("- **SIMD path actually taken (NEON vs SDOT vs i8mm):** invisible to ORT profiling — it is an "
      "MLAS internal dispatch. Use `simpleperf record` + symbol report and look for the dotprod/i8mm "
      "kernel symbols, or a debug ORT build with MLAS kernel logging.")
    w("- **Compute- vs memory-bound per op:** the ranking says *where* time goes, not *why*. Confirm "
      "with `simpleperf stat -e` cache-miss / bus-cycles, or Perfetto CPU + memory-bandwidth counters.")
    w("- **Thread scaling / contention:** re-run this profile at intra=1,2,3,4 and compare the hot "
      "op's avg ms; ORT `thread_scheduling_stats` in `args` also carries main/sub-thread split.")
    return "\n".join(out) + "\n"


def rank_levers(ordered: list[OpAgg], total_us: int) -> str:
    """Map the measured hotspot to candidate levers — only meaningful AFTER the data exists (this
    runs on real trace output), so it reports what the numbers point at, not a blind guess."""
    if not ordered:
        return "_No kernel events — nothing to rank._"
    lines = []
    top = ordered[0]
    share = pct(top.total_us, total_us)
    lines.append(f"- Hottest op **{top.op}** = {share:.1f}% of kernel time over {top.calls} calls. "
                 "Optimization effort should target this first; everything below it is secondary.")
    matmul_us = sum(a.total_us for a in ordered if "MatMul" in a.op or a.op == "Attention")
    elt_us = sum(a.total_us for a in ordered if a.op in ("Add", "Mul", "Softmax", "LayerNormalization",
                 "SkipLayerNormalization", "Gelu", "FastGelu", "Cast"))
    move_us = sum(a.total_us for a in ordered if a.op in ("Transpose", "Concat", "Gather", "Reshape"))
    lines.append(f"- int8 GEMM (MatMul/Attention): **{pct(matmul_us, total_us):.1f}%** → levers: i8mm/"
                 "KleidiAI microkernels, weight prepack reuse, cache-blocking, INT4.")
    lines.append(f"- elementwise/norm: **{pct(elt_us, total_us):.1f}%** → levers: graph fusion "
                 "(bias/residual/LN/Gelu into the matmul), which cuts memory traffic — the confirmed "
                 "Armv8.2 bound.")
    lines.append(f"- tensor movement (Transpose/Concat/Gather): **{pct(move_us, total_us):.1f}%** → "
                 "levers: layout/fusion to remove copies, KV-cache concat strategy.")
    lines.append("- If GEMM dominates → thread tuning + i8mm + INT4 pay off. If movement/elementwise "
                 "dominate → the win is fusion + traffic reduction, NOT more threads.")
    return "\n".join(lines)


def pct(part: float, whole: float) -> float:
    return 100.0 * part / whole if whole else 0.0


# ---- self-test -----------------------------------------------------------------------------------

def selftest() -> int:
    events = [
        {"cat": "Node", "name": "n1_kernel_time", "dur": 100, "args": {"op_name": "MatMul", "provider": "CPUExecutionProvider"}},
        {"cat": "Node", "name": "n2_kernel_time", "dur": 300, "args": {"op_name": "MatMul", "provider": "CPUExecutionProvider"}},
        {"cat": "Node", "name": "n3_kernel_time", "dur": 100, "args": {"op_name": "Softmax", "provider": "CPUExecutionProvider"}},
        {"cat": "Node", "name": "n1_fence_before", "dur": 0, "args": {"op_name": "MatMul"}},  # excluded
        {"cat": "Session", "name": "model_run", "dur": 600},
    ]
    by_op, model_run_us, nodes = aggregate(events)
    assert len(nodes) == 3, nodes
    assert by_op["MatMul"].calls == 2 and by_op["MatMul"].total_us == 400, by_op["MatMul"]
    assert by_op["Softmax"].total_us == 100
    assert model_run_us == 600
    assert abs(by_op["MatMul"].avg_us - 200.0) < 1e-9
    assert abs(pct(400, 500) - 80.0) < 1e-9
    report = render(by_op, model_run_us, len(nodes), by_node=True)
    assert "MatMul" in report and "80.0%" in report
    assert "INFERRED" in report and "simpleperf" in report
    print("selftest OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="ONNX Runtime operator profile report (P8).")
    ap.add_argument("traces", nargs="*", help="ORT profiling JSON file(s)")
    ap.add_argument("--out", help="write the markdown report here (default: stdout)")
    ap.add_argument("--by-node", action="store_true", help="add a per-node distinct-count table")
    ap.add_argument("--selftest", action="store_true", help="run the built-in self-check and exit")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if not a.traces:
        ap.error("no trace files given (use --selftest to verify the tool)")

    by_op, model_run_us, nodes = aggregate(load_events(a.traces))
    if not nodes:
        print("No node kernel events found. Was profiling enabled and endProfiling() called?", file=sys.stderr)
        return 1
    report = render(by_op, model_run_us, len(nodes), a.by_node)
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"wrote {a.out}")
    else:
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
