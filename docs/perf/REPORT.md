# PlayerScreen / queue-sheet performance — measured before/after

Date: 2026-07-26. Device: Samsung SM-A730F (Galaxy A8+ 2018), Android 9 (API 28), arm64.
Build: `benchmark` type (initWith release + debuggable for API 28 atrace; minify off, same as release).
NOTE (2026-09-01): the `benchmark` type has since been removed and perf work moved to `rig`.
These numbers were taken on `benchmark` and are left as recorded. `rig` is debug-compiled
rather than release-compiled, which changed nothing measurable while isMinifyEnabled stays
false, so a fresh run is comparable to these baselines - but say which build produced it.
Scenario: open queue sheet → fling queue ×3 → drag-reorder one row → switch to Library tab →
type "roc" in search → close sheet. Driven by `scripts/perf/scenario_steps_only.sh` via adb,
playback running throughout ("Fellow travelers" station, 28-track queue/library).

## Tools (all in-tree, reusable)

- `scripts/perf/scenario_run.sh <label>` — gfxinfo framestats run → `docs/perf/<label>/`
- `scripts/perf/atrace_run.sh <label>` — atrace + PerfTrace section durations
- `scripts/perf/parse_framestats.py`, `scripts/perf/parse_atrace.py`
- `util/PerfTrace.kt` — atrace sections in VM/filters/sheet hot paths (kept in prod, ~free)
- Compose compiler reports: `app/build/compose-reports/` (baseline copy in `baseline/compose-reports/`)

## Results — gfxinfo framestats (~120 frames/run, 3 runs per phase)

| phase | janky >16.7 ms | janky >30 ms | janky >50 ms | P50 | P90 | P95 |
|---|---|---|---|---|---|---|
| baseline | 78 / 84 / 83% | **43 / 43 / 47%** | 7.5 / 10 / 6.7% | 26.5–29.1 | 46.1–48.8 | 54.3–63.2 |
| P1 hot-state split | 89 / 73 / 69% | **24 / 31 / 33%** | 2.5 / 5.8 / 12.5% | 21.3–25.3 | 37.6–51.3 | 40.5–55.3 |
| P2 sheet interactions¹ | 61 / 68% | **24 / 27%** | 5.0 / 5.8% | 21.5–21.6 | 39.2–41.4 | 50.0–50.8 |
| P3 main-thread offload² | 84 / 81% | **37 / 34%** | 7.5 / 5.8% | 25.1–25.9 | 45.5–47.6 | 51.7–55.0 |

¹ phase2_run1 (87.5% / P50 35.7) discarded: first run after reinstall, cold caches.
² Device warmed up over the session (battery 37.1 → 38.0 °C); P2↔P3 gfxinfo deltas are inside
run-to-run noise — framestats can resolve baseline→P1 but not P1→P2→P3. See atrace below.

**Headline:** frames missing the 30 ms deadline dropped from ~43–47% to ~24–34% (−30 to −45%,
meets the ≥30% target); P50 frame time 26–29 ms → 21–26 ms.

## Results — atrace section timings (25 s scenario)

| section | baseline | phase 3 | delta |
|---|---|---|---|
| `Recomposer:recompose` (total) | 325 calls / 1424 ms | 321 calls / 1141 ms | −20% time |
| `Compose:recompose` (calls) | 493 calls / 1296 ms | 313 calls / 1058 ms | **−36% calls** |
| `Filters.monthKey` | 28 ×, 17.0 ms (0.61 avg) | 28 ×, 1.6 ms (0.06 avg) | **−90%** (cached SimpleDateFormat) |
| `Sheet.dateOptions` (Library tab open) | 18.4 ms | 1.9 ms | **−90%** |
| `Sheet.uploaderOptions` | 6.8 ms | 2.6 ms | −62% |
| `VM.saveQueueState` | 2.6 ms on main thread | off-main (Dispatchers.IO, debounced) | main-thread time: 0 |
| `VM.publishQueue` | 6.8 ms | 5.7 ms | saveQueue/totalSizeGb moved out |
| `Sheet.gbString` | 8 ×, 3.0 ms | 3 ×, 1.1 ms | remembered per list now |
| `VM.positionTick` | 49 × (drove full-screen recompose) | 49 × (updates isolated flow) | blast radius removed |

## What changed, per phase

- **P1** — `currentPosition`, `downloadProgress`, `nextDownloadProgress`,
  `sleepTimerSecondsRemaining` moved out of `PlayerUiState` into separate `StateFlow`s
  collected only by SeekBar/progress rings/sleep chip. Download progress conflated (≥1% or
  null/1f transitions); `refreshNextTrackState()` no longer spawns a coroutine per progress tick.
- **P2** — queue auto-scroll keyed on `currentIndex` only (no more list yank on every edit);
  stable LazyColumn keys (`QueueRow.key = messageId#occurrence` in queue, `messageId` in
  library) + `contentType`, so reorders move rows instead of rebinding everything past the edit.
- **P3** — `saveQueueState()` debounced 500 ms on Dispatchers.IO (delay-then-write; flushed
  synchronously in `onCleared`); `totalSizeGb` cached by list identity; `prefetchAhead` skips
  when the window is unchanged; `SimpleDateFormat` cached in `monthKey`/`formatTelegramDate`;
  `startOfTodayEpoch()` computed once per `matches` call; Library filter options hoisted to
  `QueueSheet` scope (survive tab switches); `gbString` remembered.

## Success criteria vs plan

- PortraitPlayer recompositions while playing + sheet open ≈ 0: met indirectly — uiState no
  longer emits at 2 Hz (position/sleep/progress are separate flows); recompose call count down
  36%. (Layout Inspector visual confirmation still worth doing interactively.)
- ≥30% fewer janky frames: met for the >30 ms band; the >16.7 ms band is dominated by this
  device's baseline inability to hold 60 fps on the full-screen player, unaffected by these fixes.
- `publishQueue`/`saveQueueState` main-thread time: met (save on IO, gb sum cached).
- Library tab-switch hitch: met (dateOptions 18.4 → 1.9 ms).

## Caveats

- atrace on API 28 has no perfetto; app sections require a debuggable build - `benchmark`
  when this was measured, `rig` now. Representative because release already ships unminified.
- gfxinfo runs are sensitive to device state (thermals, TDLib background sync); discard the
  first run after install and compare medians of ≥3 runs.
- The 28-track library is small; filter costs scale linearly with library size, so the P3
  wins grow on bigger stations.
- One atrace artifact: a `VM.saveQueueState` begin on the IO thread without a parsed end —
  parser anomaly (per-tid B/E pairing), not a code issue; the section is balanced via
  try/finally.
- No unit tests exist for the VM queue logic (`testDebugUnitTest` is NO-SOURCE); validation
  was device-measurement only.
