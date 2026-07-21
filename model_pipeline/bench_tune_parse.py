"""Parse the Phase 7 ORT-tuning sweep logcat into a per-config comparison.

Segments the capture by `CONFIG name=X` markers, and within each config groups the
BB.Bench `translate` JSON by the `BENCH_SENTENCE` markers. Prints, per config and per
sentence, median/p95/stdev total, median decode, tokens/sec, memory, and the % change
vs the `baseline` config. Also checks every config produced the same output (parity).

    python bench_tune_parse.py bench_tune.log
"""

import json
import re
import statistics
import sys


def load(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.readlines()


def parse(path):
    cfg = None
    started = False
    cur = None
    data = {}  # cfg -> {sent_i -> [metrics]}
    outs = {}  # cfg -> {sent_i -> output}
    mem = {}   # cfg -> totalPss KB

    for ln in load(path):
        m = re.search(r"CONFIG name=(\S+)", ln)
        if m:
            cfg = m.group(1)
            data[cfg] = {}
            outs[cfg] = {}
            started = False
            cur = None
            continue
        if "WARMUP_DONE" in ln:
            started = True
            continue
        if cfg is None or not started:
            continue
        m = re.search(r"BENCH_SENTENCE i=(\d+)", ln)
        if m:
            cur = int(m.group(1))
            data[cfg].setdefault(cur, [])
            continue
        m = re.search(r"BENCH_OUTPUT i=(\d+) out=\"(.*?)\"", ln)
        if m:
            outs[cfg][int(m.group(1))] = m.group(2)
            continue
        m = re.search(r"BENCH_MEM totalPss=(\d+)", ln)
        if m:
            mem[cfg] = int(m.group(1))
            continue
        if cur is not None and '"run":"translate"' in ln:
            try:
                data[cfg][cur].append(json.loads(ln[ln.index("{"): ln.rindex("}") + 1]))
            except (json.JSONDecodeError, ValueError):
                pass
    return data, outs, mem


def med_total(runs):
    return statistics.median([x["total_ms"] for x in runs]) if runs else 0


def med_decode(runs):
    return statistics.median([x["stages"].get("decode", 0) for x in runs]) if runs else 0


def main(path):
    data, outs, mem = parse(path)
    configs = list(data.keys())
    base = "baseline"
    sents = sorted({i for c in data.values() for i in c})

    # parity: every config's output per sentence must equal baseline's
    print("=== PARITY ===")
    ok = True
    for i in sents:
        ref = outs.get(base, {}).get(i)
        for c in configs:
            o = outs.get(c, {}).get(i)
            if o != ref:
                ok = False
                print(f"  MISMATCH cfg={c} sent={i}: '{o}' != baseline '{ref}'")
    print("  all outputs identical to baseline" if ok else "  PARITY BROKEN")

    print(f"\n=== TOTAL_MS median (Δ% vs {base}), per sentence ===")
    header = "config".ljust(16) + "".join(f"s{i}".rjust(18) for i in sents) + "   mem_MB"
    print(header)
    base_med = {i: med_total(data[base].get(i, [])) for i in sents}
    for c in configs:
        row = c.ljust(16)
        for i in sents:
            m = med_total(data[c].get(i, []))
            b = base_med[i] or 1
            row += f"{m:7.1f} ({(m-b)/b*100:+5.1f}%)".rjust(18)
        row += f"   {mem.get(c,0)/1024:6.0f}"
        print(row)

    print(f"\n=== DECODE_MS median (Δ% vs {base}) ===")
    base_dec = {i: med_decode(data[base].get(i, [])) for i in sents}
    for c in configs:
        row = c.ljust(16)
        for i in sents:
            m = med_decode(data[c].get(i, []))
            b = base_dec[i] or 1
            row += f"{m:7.1f} ({(m-b)/b*100:+5.1f}%)".rjust(18)
        print(row)

    print("\n=== spread (longest sentence s%d) ===" % sents[-1])
    for c in configs:
        r = data[c].get(sents[-1], [])
        if not r:
            continue
        tot = sorted(x["total_ms"] for x in r)
        p95 = tot[min(len(tot) - 1, int(round(0.95 * (len(tot) - 1))))]
        sd = statistics.pstdev(tot) if len(tot) > 1 else 0
        print(f"  {c.ljust(16)} median={statistics.median(tot):7.1f}  p95={p95:7.1f}  stdev={sd:5.1f}  n={len(tot)}")


if __name__ == "__main__":
    main(sys.argv[1])
