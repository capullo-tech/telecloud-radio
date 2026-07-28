# Perf scenario — queue/library sheet

Same path every run, on the `benchmark` build, device SM-A730F (1080x2220).
Drive it with `scenario_run.sh` (adb input events) or manually following the same steps.

Preconditions:
- App logged in, on the "Select a station" list, station list loaded.
- The target station (first in list) has a populated playlist (hundreds of tracks).

Steps (timings used by scenario_run.sh):
1. Tap the first station -> player screen loads, playback starts. (~8 s settle)
2. Tap the queue button -> QueueSheet opens on the Queue tab. (~2 s)
3. Fling the queue list up x3 (fast swipes). (~1 s pauses)
4. Drag the drag-handle of the 2nd visible row down ~2 rows and release (reorder). (~2 s)
5. Tap the "Library" tab. (~3 s — tab-switch hitch shows here)
6. Type 3 characters into the library search field. (~1 s per char)
7. Close the sheet (X button). (~1 s)

Total ~30 s of measured interaction.
