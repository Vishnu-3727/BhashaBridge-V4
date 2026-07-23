"""Phase 5 benchmark exporter.

Consumes the single JSON report BenchmarkSuiteTest writes (pulled from the device, or
reassembled from a logcat dump's `REPORT_JSON <i> <chunk>` lines) and produces, next to it:

    <report>.csv   flat key metrics, one row
    <report>.md    human-readable summary tables

With --baseline it also writes <report>.regression.md and prints a verdict per metric:
FASTER / SLOWER / MEM+ / THERMAL+ / startup regression, plus an overall PASS/REGRESSED line.

Everything is stdlib. Missing metrics (nulls from the device) are printed as "n/a", never faked.

    python bench_report.py report.json
    python bench_report.py report.json --baseline baseline.json
    python bench_report.py --from-logcat dump.log            # reassemble, then export
"""

import argparse
import csv
import json
import os
import re
import sys

# name -> (group, json path, unit, lower-is-better?)
METRICS = [
    ("startup.cold.engineInitMs", "startup", ("startup", "cold", "engineInitMs"), "ms", True),
    ("startup.warm.engineInitMs", "startup", ("startup", "warm", "engineInitMs"), "ms", True),
    ("startup.hot.engineInitMs", "startup", ("startup", "hot", "engineInitMs"), "ms", True),
    ("startup.cold.modelLoadMs", "startup", ("startup", "cold", "modelLoadMs"), "ms", True),
    ("startup.warm.tokenizerMs", "startup", ("startup", "warm", "tokenizerMs"), "ms", True),
    ("inference.firstTranslationMs", "inference", ("inference", "firstTranslationMs"), "ms", True),
    ("inference.median", "inference", ("inference", "sustained", "median"), "ms", True),
    ("inference.p95", "inference", ("inference", "sustained", "p95"), "ms", True),
    ("inference.p99", "inference", ("inference", "sustained", "p99"), "ms", True),
    ("inference.stdev", "inference", ("inference", "sustained", "stdev"), "ms", True),
    ("inference.tokensPerSec", "inference", ("inference", "tokensPerSec"), "tok/s", False),
    ("memory.totalPssKb", "memory", ("systemAfter", "memory", "totalPssKb"), "KB", True),
    ("memory.nativeHeapKb", "memory", ("systemAfter", "memory", "nativeHeapKb"), "KB", True),
    ("memory.rssKb", "memory", ("systemAfter", "memory", "rssKb"), "KB", True),
    ("cache.ortBytes", "storage", ("cacheBytes", "ortCache"), "B", True),
    ("thermal.batteryTempC", "thermal", ("systemAfter", "thermal", "batteryTempC"), "C", True),
]


def dig(d, path):
    for k in path:
        if not isinstance(d, dict) or k not in d or d[k] is None:
            return None
        d = d[k]
    return d


def fmt(v):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.3f}".rstrip("0").rstrip(".")
    return str(v)


def load_report(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def reassemble_from_logcat(path):
    chunks = {}
    pat = re.compile(r"REPORT_JSON (\d+) (.*)$")
    with open(path, encoding="utf-8", errors="replace") as f:
        for ln in f:
            m = pat.search(ln.rstrip("\n"))
            if m:
                chunks[int(m.group(1))] = m.group(2)
    if not chunks:
        sys.exit("no REPORT_JSON lines found in logcat dump")
    return json.loads("".join(chunks[i] for i in sorted(chunks)))


def write_csv(report, out):
    row = {"model": dig(report, ("device", "model")), "timestampMs": report.get("timestampMs")}
    for name, _g, path, _u, _lb in METRICS:
        row[name] = dig(report, path)
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(row.keys())
        w.writerow(["" if v is None else v for v in row.values()])
    return out


def write_md(report, out):
    dev = report.get("device", {})
    lines = [
        f"# Benchmark report - {dev.get('model', '?')}",
        "",
        f"- **CPU:** {dev.get('cpu', 'n/a')}",
        f"- **Policy:** {dev.get('policy', 'n/a')}",
        f"- **Android SDK:** {dev.get('androidSdk', 'n/a')}   ABI: {dev.get('abi', 'n/a')}",
        f"- **Timestamp:** {report.get('timestampMs', 'n/a')}",
        "",
        "## Metrics",
        "",
        "| Group | Metric | Value | Unit |",
        "|---|---|---|---|",
    ]
    for name, group, path, unit, _lb in METRICS:
        lines.append(f"| {group} | {name.split('.', 1)[1]} | {fmt(dig(report, path))} | {unit} |")
    unavail = dig(report, ("systemAfter", "unavailable")) or []
    lines += ["", "## Unavailable on this device (unrooted)", "", "```",
              ", ".join(unavail) if unavail else "(all requested metrics captured)", "```", ""]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return out


def regression(cur, base, out, slower_pct=5.0, mem_pct=5.0, therm_c=2.0):
    rows, verdicts = [], []
    for name, _group, path, _unit, lower_better in METRICS:
        c, b = dig(cur, path), dig(base, path)
        if not isinstance(c, (int, float)) or not isinstance(b, (int, float)):
            rows.append((name, fmt(b), fmt(c), "n/a", "-"))
            continue
        delta = (c - b) / b * 100 if b else 0.0
        verdict = "="
        if name == "thermal.batteryTempC":
            if c - b >= therm_c:
                verdict = "THERMAL+"
        elif name.startswith("memory") or name == "cache.ortBytes":
            if delta >= mem_pct:
                verdict = "MEM+"
            elif delta <= -mem_pct:
                verdict = "MEM-"
        elif lower_better:
            if delta >= slower_pct:
                verdict = "STARTUP-REGRESSION" if name.startswith("startup") else "SLOWER"
            elif delta <= -slower_pct:
                verdict = "FASTER"
        else:
            if delta <= -slower_pct:
                verdict = "SLOWER"
            elif delta >= slower_pct:
                verdict = "FASTER"
        verdicts.append(verdict)
        rows.append((name, fmt(b), fmt(c), f"{delta:+.1f}%", verdict))

    bad = [v for v in verdicts if v in ("SLOWER", "STARTUP-REGRESSION", "MEM+", "THERMAL+")]
    good = [v for v in verdicts if v in ("FASTER", "MEM-")]
    overall = "REGRESSED" if bad else "PASS"

    md = ["# Regression vs baseline", "",
          f"**Overall: {overall}**  ({len(good)} improved, {len(bad)} regressed)", "",
          "| Metric | Baseline | Current | Delta | Verdict |", "|---|---|---|---|---|"]
    md += [f"| {n} | {b} | {c} | {d} | {v} |" for n, b, c, d, v in rows]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")

    print(f"\nRegression: {overall}  ({len(good)} improved, {len(bad)} regressed)")
    for n, b, c, d, v in rows:
        if v not in ("=", "-"):
            print(f"  {v:20} {n:32} {b} -> {c} ({d})")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report", nargs="?", help="bench_report_*.json (or a logcat dump with --from-logcat)")
    ap.add_argument("--baseline", help="baseline report json for regression mode")
    ap.add_argument("--from-logcat", action="store_true", help="treat input as a logcat dump and reassemble")
    ap.add_argument("--out", help="output directory (default: alongside the report)")
    args = ap.parse_args()
    if not args.report:
        ap.error("report path required")

    report = reassemble_from_logcat(args.report) if args.from_logcat else load_report(args.report)
    base = os.path.splitext(os.path.basename(args.report))[0]
    outdir = args.out or os.path.dirname(os.path.abspath(args.report))
    os.makedirs(outdir, exist_ok=True)

    if args.from_logcat:
        norm = os.path.join(outdir, base + ".report.json")
        with open(norm, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print("normalized JSON ->", norm)

    print("CSV      ->", write_csv(report, os.path.join(outdir, base + ".csv")))
    print("Markdown ->", write_md(report, os.path.join(outdir, base + ".md")))
    if args.baseline:
        baseline = load_report(args.baseline)
        print("Regress. ->", regression(report, baseline, os.path.join(outdir, base + ".regression.md")))


if __name__ == "__main__":
    main()
