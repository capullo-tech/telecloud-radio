#!/usr/bin/env python3
"""Parse atrace output and report PerfTrace section durations.

atrace app sections appear as pairs of tracing_mark_write lines:
  B|<pid>|<sectionName>   (begin)
  E|<pid>|                (end)
We match B/E pairs per tid and report count / total / mean / max per section,
plus a main-thread-only breakdown (sections whose names start with VM. or
Sheet. run on the main thread when captured on it — reported per thread).

Usage: parse_atrace.py <trace.txt>
"""

import re
import sys
from collections import defaultdict

LINE_RE = re.compile(
    r"^\s*\S+-(\d+)\s+\(\s*\d+\)\s+\[\d+\]\s+\S*\s*([\d.]+):\s+tracing_mark_write:\s+(.*)$"
)


def main():
    path = sys.argv[1]
    stacks = defaultdict(list)          # tid -> [(name, ts)]
    sections = defaultdict(list)        # (tid, name) -> [durations_ms]
    for line in open(path, errors="replace"):
        m = LINE_RE.match(line)
        if not m:
            continue
        tid, ts, mark = int(m.group(1)), float(m.group(2)), m.group(3).strip()
        if mark.startswith("B|"):
            name = mark.split("|", 2)[2]
            stacks[tid].append((name, ts))
        elif mark.startswith("E|") and stacks[tid]:
            name, start = stacks[tid].pop()
            sections[(tid, name)].append((ts - start) * 1000.0)

    agg = defaultdict(list)
    for (tid, name), durs in sections.items():
        agg[name].extend(durs)

    if not agg:
        print("no trace sections found")
        return
    print(f"{'section':<32} {'count':>5} {'total_ms':>9} {'mean_ms':>8} {'max_ms':>8}")
    for name in sorted(agg, key=lambda n: -sum(agg[n])):
        durs = agg[name]
        print(
            f"{name:<32} {len(durs):>5} {sum(durs):>9.1f} "
            f"{sum(durs) / len(durs):>8.2f} {max(durs):>8.1f}"
        )


if __name__ == "__main__":
    main()
