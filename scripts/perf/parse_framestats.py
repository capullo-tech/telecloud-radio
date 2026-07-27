#!/usr/bin/env python3
"""Parse `adb shell dumpsys gfxinfo <pkg> framestats` output.

Reads framestats CSV from stdin (or a file argument) and prints frame-count,
janky-frame counts at several deadlines, and total-frame-time percentiles.

Framestats columns (API 28, 120+ ns-timestamp columns); we use:
  col 1  FLAGS
  col 2  IntendedVsync
  col 3  Vsync
  col 14 FrameCompleted (index may vary; we auto-detect by header row count)
Actually gfxinfo framestats has no header; the reliable pair is
  Vsync (col 2) and FrameCompleted (col 13) -> total duration.
Column layout (0-based) per https://developer.android.com (16 fields on API 28):
  0 FLAGS, 1 IntendedVsync, 2 Vsync, 3 OldestInputEvent, 4 NewestInputEvent,
  5 HandleInputStart, 6 AnimationStart, 7 PerformTraversalsStart, 8 DrawStart,
  9 SyncQueued, 10 SyncStart, 11 IssueDrawCommandsStart, 12 SwapBuffers,
  13 FrameCompleted, 14 DequeueBufferDuration, 15 QueueBufferDuration
"""

import sys

JANK_MS = (16.67, 30.0, 50.0, 100.0)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p / 100.0
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    frac = k - lo
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * frac


def main():
    text = sys.stdin.read() if len(sys.argv) < 2 else open(sys.argv[1]).read()
    durations = []
    in_block = False
    for line in text.splitlines():
        line = line.strip()
        if "---PROFILEDATA---" in line:
            in_block = not in_block
            continue
        if in_block and line and line[0].isdigit():
            cols = line.split(",")
            if len(cols) >= 14:
                try:
                    vsync = int(cols[2])
                    done = int(cols[13])
                except ValueError:
                    continue
                if done > vsync > 0:
                    durations.append((done - vsync) / 1e6)  # ms

    if not durations:
        print("no frames parsed")
        return
    durations.sort()
    n = len(durations)
    print(f"frames: {n}")
    for deadline in JANK_MS:
        janky = sum(1 for d in durations if d > deadline)
        print(f"janky >{deadline:.2f}ms: {janky} ({100.0 * janky / n:.1f}%)")
    for p in (50, 90, 95, 99):
        print(f"P{p}: {percentile(durations, p):.1f} ms")
    print(f"max: {durations[-1]:.1f} ms")


if __name__ == "__main__":
    main()
