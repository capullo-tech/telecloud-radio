#!/usr/bin/env bash
# Capture an atrace (API 28-compatible) while scenario steps run, then extract PerfTrace
# section durations. Start state: player screen open, playback running, sheet closed.
#
# Usage: scripts/perf/atrace_run.sh <label>
# Output: docs/perf/<label>/atrace_<label>.txt (raw) + sections_<label>.txt (durations)
set -euo pipefail

PKG=tech.capullo.telecloudradio
LABEL="${1:?usage: atrace_run.sh <label>}"
OUT="docs/perf/${LABEL}"
mkdir -p "$OUT"
RAW="$OUT/atrace_${LABEL}.txt"

adb shell atrace -t 25 -b 16384 view res am wm sched freq -a "$PKG" -o /data/local/tmp/tc_trace.out >/dev/null 2>&1 &
TRACE_PID=$!
sleep 2   # let atrace actually start

"$(dirname "$0")/scenario_steps_only.sh"

wait $TRACE_PID || true
adb pull /data/local/tmp/tc_trace.out "$RAW" >/dev/null
python3 "$(dirname "$0")/parse_atrace.py" "$RAW" | tee "$OUT/sections_${LABEL}.txt"
