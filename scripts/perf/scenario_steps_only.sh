#!/usr/bin/env bash
# The shared scenario steps (scripts/perf/scenario.md), no measurement around them.
# Start state: player screen open, playback running, sheet closed.
set -euo pipefail

# Coordinates for SM-A730F 1080x2220 (verified against screenshots 2026-07-22)
QUEUE_BTN="945 2010"
LIBRARY_TAB="728 255"
CLOSE_X="1013 255"
SEARCH_FIELD="540 570"
DRAG_HANDLE_ROW2="1000 936"

# 1. Open the queue sheet
adb shell input tap $QUEUE_BTN
sleep 2

# 2. Fling the queue list up x3
for _ in 1 2 3; do
    adb shell input swipe 540 1800 540 600 150
    sleep 0.8
done

# 3. Scroll back to the top (3 slow drags down)
for _ in 1 2 3; do
    adb shell input swipe 540 700 540 1900 400
    sleep 0.5
done
sleep 1

# 4. Drag-reorder the 2nd visible row down ~1.5 rows via its drag handle
adb shell input swipe $DRAG_HANDLE_ROW2 1000 1300 900
sleep 2

# 5. Switch to the Library tab
adb shell input tap $LIBRARY_TAB
sleep 3

# 6. Type 3 characters into the library search field
adb shell input tap $SEARCH_FIELD
sleep 0.5
adb shell input text "roc"
sleep 1.5

# 7. Close the sheet (keyboard may be up: dismiss first, then X)
adb shell input keyevent 4   # back: dismiss keyboard (sheet stays, consumed by field)
sleep 0.5
adb shell input tap $CLOSE_X
sleep 1
