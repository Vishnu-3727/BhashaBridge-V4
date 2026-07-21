"""Parse a captured logcat into per-sentence benchmark stats for Phase 6D.

Reads a logcat dump on stdin (or a file arg). Groups the BB.Bench `translate` JSON
lines by the BB_BENCH `BENCH_SENTENCE i=..` markers that bracket them, drops warmup
(everything before WARMUP_DONE), and prints median / p95 / stddev for total, encoder,
decode, plus tokens/sec, per sentence. Runtime-agnostic: uncached emits an "encode"
stage, cached emits "encoder" + init/step counters; both are handled.

    python bench_parse.py cached.log
"""

import json
import re
import statistics
import sys


def load_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def parse(path, label):
    lines = load_lines(path)
    started = False
    cur = None  # current sentence index
    sent_text = {}
    sent_out = {}
    runs = {}  # i -> list of metric dicts

    for ln in lines:
        if "WARMUP_DONE" in ln:
            started = True
            continue
        if not started:
            continue
        m = re.search(r"BENCH_SENTENCE i=(\d+) n=(\d+) text=\"(.*?)\"", ln)
        if m:
            cur = int(m.group(1))
            sent_text[cur] = m.group(3)
            runs.setdefault(cur, [])
            continue
        m = re.search(r"BENCH_OUTPUT i=(\d+) out=\"(.*?)\"", ln)
        if m:
            sent_out[int(m.group(1))] = m.group(2)
            continue
        if cur is not None and '"run":"translate"' in ln:
            j = ln[ln.index("{"): ln.rindex("}") + 1]
            try:
                runs[cur].append(json.loads(j))
            except json.JSONDecodeError:
                pass

    mem = None
    for ln in lines:
        m = re.search(r"BENCH_MEM totalPss=(\d+) nativePss=(\d+) dalvikPss=(\d+)", ln)
        if m:
            mem = tuple(int(x) for x in m.groups())

    def stat(vals):
        vals = sorted(vals)
        n = len(vals)
        p95 = vals[min(n - 1, int(round(0.95 * (n - 1))))] if n else 0
        return {
            "n": n,
            "median": statistics.median(vals) if vals else 0,
            "p95": p95,
            "stdev": statistics.pstdev(vals) if n > 1 else 0.0,
        }

    print(f"\n===== {label} =====")
    if mem:
        print(f"memory: totalPss={mem[0]/1024:.1f} MB  nativePss={mem[1]/1024:.1f} MB  dalvikPss={mem[2]/1024:.1f} MB")
    for i in sorted(runs):
        r = runs[i]
        if not r:
            continue
        total = [x["total_ms"] for x in r]
        enc = [x["stages"].get("encoder", x["stages"].get("encode", 0)) for x in r]
        dec = [x["stages"].get("decode", 0) for x in r]
        toks = [x["counters"].get("tokens", 0) for x in r]
        steps = [x["counters"].get("steps", 0) for x in r if "steps" in x["counters"]]
        tps = [(t / (d / 1000.0)) if d else 0 for t, d in zip(toks, dec)]
        st = stat(total)
        avg_tok = statistics.mean(toks) if toks else 0
        print(f"\n[{i}] \"{sent_text.get(i,'')[:50]}\"  out=\"{sent_out.get(i,'')}\"")
        print(f"    runs={st['n']}  avg_tokens={avg_tok:.1f}" + (f"  avg_steps={statistics.mean(steps):.1f}" if steps else ""))
        print(f"    total_ms   median={st['median']:.1f}  p95={st['p95']:.1f}  stdev={st['stdev']:.1f}")
        print(f"    encoder_ms median={statistics.median(enc):.1f}")
        print(f"    decode_ms  median={statistics.median(dec):.1f}  p95={stat(dec)['p95']:.1f}  stdev={stat(dec)['stdev']:.1f}")
        print(f"    tokens/sec median={statistics.median(tps):.1f}")


if __name__ == "__main__":
    parse(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else sys.argv[1])
