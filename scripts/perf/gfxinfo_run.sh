#!/usr/bin/env bash
# Capture gfxinfo framestats for the telecloud-radio perf scenario.
#
# Usage:
#   scripts/perf/gfxinfo_run.sh <label>
#
# Resets gfxinfo, waits for you (or scenario_run.sh) to perform the scenario,
# then dumps framestats to docs/perf/<label>/ and prints parsed stats.
#
# If WAIT_CMD is set it is executed instead of the interactive wait (used by
# scenario_run.sh to drive the scenario with adb input events).
set -euo pipefail

PKG=tech.capullo.telecloudradio
LABEL="${1:?usage: gfxinfo_run.sh <label>}"
OUT="docs/perf/${LABEL}"
mkdir -p "$OUT"

adb shell "dumpsys gfxinfo $PKG reset" >/dev/null

if [[ -n "${WAIT_CMD:-}" ]]; then
    eval "$WAIT_CMD"
else
    echo "gfxinfo reset. Perform the scenario (scripts/perf/scenario.md), then press Enter."
    read -r
fi

adb shell "dumpsys gfxinfo $PKG framestats" >"$OUT/framestats_${LABEL}.txt"
python3 "$(dirname "$0")/parse_framestats.py" "$OUT/framestats_${LABEL}.txt" | tee "$OUT/gfxinfo_${LABEL}.txt"
