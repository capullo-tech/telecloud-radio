#!/usr/bin/env bash
# Drive the perf scenario and capture gfxinfo framestats around it.
# Start state: player screen open, playback running, sheet closed.
#
# Usage: scripts/perf/scenario_run.sh <label>
# Output: docs/perf/<label>/framestats_<label>.txt + gfxinfo_<label>.txt (parsed stats)
set -euo pipefail

PKG=tech.capullo.telecloudradio
LABEL="${1:?usage: scenario_run.sh <label>}"
OUT="docs/perf/${LABEL}"
mkdir -p "$OUT"

adb shell "dumpsys gfxinfo $PKG reset" >/dev/null
sleep 1

"$(dirname "$0")/scenario_steps_only.sh"

adb shell "dumpsys gfxinfo $PKG framestats" >"$OUT/framestats_${LABEL}.txt"
python3 "$(dirname "$0")/parse_framestats.py" "$OUT/framestats_${LABEL}.txt" | tee "$OUT/gfxinfo_${LABEL}.txt"
