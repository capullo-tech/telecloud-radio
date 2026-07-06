# Telecloud Radio — Project Reference

Telegram-sourced audio player for Android. Treats a Telegram group/channel as a
music library, syncs audio message metadata to a local SQLite database, downloads
tracks on demand, and plays them via ExoPlayer.

Python prototype: `infifopullo.py` (same directory).

---

## Architecture

```
AuthScreen ──► GroupSelectorScreen ──► PlayerScreen ──► SettingsScreen / SnapcastScreen
     │                │                     │
AuthViewModel  GroupSelectorViewModel  PlayerViewModel    SettingsViewModel / SnapcastViewModel
     │                │                     │
TelegramRepository ──────────────────────────┤
     │                                       │
TdLibTelegramClient          PlaylistRepository
(:tdlib module)                    │
                              MediaMessageDao
                              (Room / SQLite, v2)
                                   │
                    ┌──────────────┼──────────────┐
               DownloadManager  ConnectivityMonitor  SettingsRepository
                    │
               PlaybackService ── SnapcastManager
               (ExoPlayer / Media3)   (snapserver/snapclient/web player)
```

**Package:** `tech.capullo.telecloudradio`
**Stack:** Kotlin · Jetpack Compose · Hilt · Room · Media3/ExoPlayer · TDLib · Snapcast

### Snapcast multiroom (ported from ../quantumcast)

Audio path while playing (always on):
```
ExoPlayer (volume 0) → [mix→2ch] → [Sonic→44100] → [Balance] → [Tee → FIFO]
                                                                     │
   cacheDir/telecloud_fifo ──► libsnapserver.so (stream 1804/http 1880) ┤
        ├── local libsnapclient.so   (the audible output on this device)
        ├── remote snapclients       (other phones running the app, "listen-in")
        └── web players              (browser at http://<phone-ip>:1880)
```

- Ports are **non-standard** (`snapcast/SnapserverDiscoveryManager.kt` → `SnapcastPorts`):
  stream 1804, tcp 1805, http/ws 1880 (standard snapcast 1704/1705/1780) — set via
  `--stream.port/--tcp.port/--http.port` on the snapserver, so our devices only find
  each other and don't clash with a regular snapserver on the LAN.
- Native binaries from `com.github.capullo-tech.lib-snapcast-android` (JitPack), run
  as child processes (`snapcast/SnapserverProcess.kt`, `SnapclientProcess.kt`)
- `snapcast/SnapcastManager.kt` (singleton) orchestrates broadcast + listen-in +
  client control (volume/latency/channel/stream-lock); `PlaybackService` drives the
  broadcast side (tee sink, start-on-playing)
- `snapcast/SnapcontrolPlugin.kt` — Snapcast stream-plugin JSON-RPC over an abstract
  local socket; pushes track title/artist/station/albumArt (base64 artData) to all
  clients, receives play/pause/next/prev from web players; honors the stream-lock
- FIFO gotchas (hard-won in quantumcast, do not regress): sink opens O_RDWR before
  snapserver starts; writes stay disabled until first "playing" (else preroll fills
  the 64KB pipe with no reader → playback thread deadlock); FIFO reused not recreated
  while a sink holds the write end
- Broadcast player must NOT hold audio focus (handleAudioFocus=false): it's a muted
  clock, and the local snapclient's Oboe stream owns focus — otherwise ExoPlayer
  pauses when the snapclient starts, stalling the tee
- **Player controls**: Multiroom button (surround-sound icon + connected-client count,
  `ic_surround_sound_nodot`) under the order button opens `ui/snapcast/SnapcastControlSheet`
  (ported from quantumcast: knob cards for volume/latency, group volume, QR, [L/R/S]
  channel badges); Playlist button under the repeat button opens the queue sheet
- **Listen-in**: discovery lives in `GroupSelectorScreen` (contrasting section, radar-sweep
  scan animation, mDNS `_snapcast._tcp`, tap-to-expand manual host:port). Joining a server
  navigates to `PlayerRoute(LISTEN_IN_CHAT_ID, name)`; `PlayerScreen` then renders the
  remote stream's now-playing (art/metadata + `Stream.Control` transport) from
  `SnapcastManager.state`, fed by `SnapcastControlClient` (ktor WS JSON-RPC on port 1880)
- Web player: `app/src/main/assets/webui/index.html` (Material-purple dark, marquees,
  client cards with volume/latency knobs, QR share, navigator.mediaSession). Copied to
  `filesDir/webui/` and served by snapserver's HTTP server. `webcfg.json` (written by
  `SnapcastManager.updateWebConfig` from Settings toggles) controls debug panel and
  autostart, both default off. Bump `UI_VERSION` on every webui edit (cache-bust).

### Sleep timer
- Moon button in the player top bar; countdown m:ss shown in place of the icon
- On expiry the current track finishes, then playback pauses ("zZ" while finishing)
- Duration configurable in Settings (`sleepTimerMinutes`, default 30)

### Key mappings from `infifopullo.py`

| Python | Android |
|--------|---------|
| `pyrogram.Client` | `TdLibTelegramClient` (TDLib via `:tdlib` module) |
| `sqlite3` + `media_messages` table | Room `MediaMessageEntity` |
| `update_media_messages()` | `TelegramRepository.syncAudioMessages()` |
| `load_playlist()` + shuffle | `PlaylistRepository.loadPlaylist()` + shuffle toggle |
| `PlaylistPlayerFFmpeg` + lookahead | `DownloadManager` + `PlayerViewModel` |
| `MutagenFile` album art | `MediaMetadataRetriever.embeddedPicture` |
| `AlbumArtWindow` (Tkinter) | `PlayerScreen` (Compose) |
| FIFO → Snapserver | ExoPlayer plays directly on device |
| Fernet + hostname key | EncryptedSharedPreferences (AES-256-GCM) |

---

## Navigation flow

```
AuthRoute → GroupSelectorRoute → PlayerRoute → SettingsRoute / SnapcastRoute
```

- `AppNavHost` owns a `mutableStateListOf<Any>` backstack and renders with `NavDisplay` (navigation3)
- `MiniPlayer` bar is shown at the bottom of `AppNavHost` whenever a track is active and the user is not on `PlayerRoute`
- `AppViewModel` bridges mini-player controls (prev/play-pause/next) to `ActiveTrackRepository`

---

## Screen-by-screen behaviour

### AuthScreen
- States: `WaitParameters` → `WaitPhone` → `WaitCode` → `Authenticated`
- API id/hash saved to `EncryptedSharedPreferences`; TDLib session persists in `filesDir/tdlib/`
- On `Authenticated`, navigates to `GroupSelectorRoute`

### GroupSelectorScreen
- Loads up to 20 Telegram chats (groups + channels) via `TelegramRepository.getAudioGroups()`
- **Tap a group → sync runs every time** (incremental: only fetches messages newer than the highest stored `messageId`)
- Sync completion auto-navigates to `PlayerRoute`; backstack returns to group list

### PlayerScreen
- `PlayerViewModel.loadAndPlay(chatId)` is idempotent (no-op if already loaded for that chat)
- Displays album art, track title/artist, position timer, shuffle/repeat/prev/play/next/queue controls
- Landscape layout: album art left, controls right
- Top bar: queue icon + settings gear
- Offline banner shown when `ConnectivityMonitor.isOnline == false`
- Queue bottom sheet: searchable track list, auto-scrolls to current track, scroll-to-top FAB appears after scrolling past 3 items

### SettingsScreen
- Single setting: **Download buffer** — maximum GB of tracks to keep on disk (free-form decimal input, default 1.0 GB)
- Saved to plain `SharedPreferences` (`settings_prefs`)

---

## Key components

### PlaylistRepository
- `loadPlaylist(chatId)` — all tracks, newest-first
- `loadLocalPlaylist(chatId)` — only tracks with a persisted `localPath` (offline use)
- `getTotalSize(chatId)` — sum of `fileSize` in bytes

### DownloadManager
- `LinkedHashMap<Long, String>` maintains insertion order for FIFO eviction
- `ensureDownloaded(chatId, messageId)`: checks in-memory map → DB `localPath` (survives restart) → network download
- After each download: persists `localPath` to DB, then calls `enforceBuffer()` which deletes oldest files until total on-disk size ≤ configured GB limit
- `evict(messageId)`: removes from map, deletes file, clears `localPath` in DB
- All file deletions are real (`File.delete()`) — no dangling disk accumulation

### ConnectivityMonitor (singleton)
- Wraps `ConnectivityManager.NetworkCallback` as a `StateFlow<Boolean>`
- `PlayerViewModel` observes it (skipping the initial emission with `.drop(1)`) and calls `reloadPlaylist(online)` on changes

### PlayerViewModel
- Manages `orderedPlaylist` (DB order) and `playlist` (shuffled or ordered, active)
- On connectivity change: swaps between full playlist and local-only playlist while preserving current track position
- `controllerPreparedForCurrentChat` flag prevents stale `PlaybackService` state from leaking when switching stations
- Lookahead prefetch: downloads 2 tracks ahead in a cancellable coroutine; evicts the track 1 position behind current
- `lastPlayed` per-chat stored in `player_prefs` SharedPreferences
- `PlayerUiState.isOffline` drives the offline banner in `PlayerScreen`

### SettingsRepository (singleton)
- `bufferSizeGb: Float` backed by plain `SharedPreferences` (`settings_prefs`), default 1.0

---

## Database

**Room DB:** `telecloud_radio.db`, current version **2**

### `media_messages` table (`MediaMessageEntity`)

| Column | Type | Notes |
|--------|------|-------|
| `messageId` | Long PK | Telegram message ID |
| `chatId` | Long | Telegram chat/channel ID |
| `date` | String | Message date |
| `senderId` | Long? | |
| `senderUsername` | String? | |
| `caption` | String? | Used to extract `@uploader` tag |
| `fileName` | String? | |
| `fileUniqueId` | String? | |
| `fileId` | Int | TDLib file ID for download |
| `duration` | Int? | Seconds |
| `performer` | String? | ID3 artist |
| `title` | String? | ID3 title |
| `fileSize` | Long? | Bytes |
| `mimeType` | String? | |
| `station` | String? | Chat title; NULL rows excluded from playlists |
| `groupType` | String? | `CHANNEL`, `GROUP`, etc. |
| `reactions` | String? | Emoji reaction string |
| `localPath` | String? | **v2** — absolute path to downloaded file; NULL if not on disk |

### Migration
- v1 → v2: `ALTER TABLE media_messages ADD COLUMN localPath TEXT`
- Applied via `MediaMessageDatabase.MIGRATION_1_2` registered in `DatabaseModule`

---

## Sync logic

`TelegramRepository.syncAudioMessages(chat)`:
1. Reads `MAX(messageId)` for the chat from DB (`latestStored`)
2. Fetches history in pages of 100, starting from the newest message (`fromId = 0`)
3. Inserts audio messages where `messageId > latestStored`
4. Stops when the oldest message in a page is ≤ `latestStored`

**Runs every time a group is tapped** in `GroupSelectorScreen`. Incremental after first sync (typically 1–2 API calls).

---

## Playback & download flow

1. `PlayerViewModel.loadAndPlay(chatId)` loads playlist from DB (full or local-only based on connectivity)
2. `togglePlayPause()` or auto-play calls `playTrack(index)`:
   - `DownloadManager.ensureDownloaded()` returns local path (from cache, DB, or network)
   - `MediaMetadataRetriever` extracts album art
   - `MediaController` loads URI and calls `play()`
3. Prefetch: `ensureDownloaded` called for next 2 tracks in background
4. Eviction: previous track (index - 1) evicted after each play; buffer enforced by GB limit

---

## App icon

Generated by `scripts/gen_icon.py` (requires Pillow):
- Gradient squircle: Telegram blue (#0088CC) → warm orange (#FF6B35)
- White vintage radio silhouette + paper-plane dart (upper right)
- Outputs `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher[_round].png` into `app/src/main/res/`
- Also writes `icon_preview.png` (1024×1024) and `ic_launcher_web.png` (512×512) to project root

```bash
python3 scripts/gen_icon.py
```

Run after clone; files are git-ignored.

---

## Module layout

```
telecloud_radio/
├── app/                          Android application module
│   └── src/main/java/.../
│       ├── auth/                 Credentials + phone/OTP screens
│       ├── data/
│       │   ├── ConnectivityMonitor.kt   Network state as StateFlow
│       │   ├── SettingsRepository.kt    Buffer size (GB) in SharedPreferences
│       │   ├── credentials/      CredentialsRepository (api_id, api_hash)
│       │   ├── db/               Room: MediaMessageEntity, Dao, Database (v2)
│       │   ├── playlist/         PlaylistRepository, ActiveTrackRepository
│       │   └── telegram/         TelegramClient interface, TdLibTelegramClient, Repository
│       ├── di/                   Hilt modules (App, Database, Telegram)
│       ├── player/               PlaybackService (Media3), DownloadManager
│       └── ui/
│           ├── groupselector/    Group list + sync progress screen
│           ├── player/           Now-playing screen + queue sheet
│           ├── settings/         SettingsScreen + SettingsViewModel
│           └── theme/
├── tdlib/                        :tdlib Gradle module (git-ignored sources)
│   └── src/main/                 Populated by scripts/setup_tdlib.sh
├── scripts/
│   ├── setup_tdlib.sh            Downloads TDLib prebuilt from TGX-Android/tdlib
│   └── gen_icon.py               Generates mipmap PNGs for all densities
├── infifopullo.py                Python prototype (reference)
└── audio_fifo_loop.sh            PulseAudio FIFO setup for Snapserver (desktop ref)
```

---

## Key design decisions

**TDLib via local module** — No Maven artifact exists for TDLib Android. The `:tdlib`
module compiles `org.drinkless.tdlib.Client` + `TdApi.java` from the TGX-Android
prebuilt (same binary used in Telegram X) plus the prebuilt `.so` files.

**`TelegramClient` interface** — `TdLibTelegramClient` is hidden behind an interface.
The rest of the app never imports TDLib classes directly, making the Telegram layer
swappable and testable.

**Incremental sync** — `syncAudioMessages()` fetches from the newest message back
until it hits the highest already-stored `message_id`. Runs on every group tap;
fast after first sync.

**Lookahead prefetch + GB buffer** — `PlayerViewModel` pre-downloads 2 tracks ahead.
`DownloadManager` enforces a configurable GB ceiling (FIFO eviction by insertion order).
Files are actually deleted on evict; `localPath` in DB is the persistent source of truth
for what's on disk across restarts.

**Offline mode** — `ConnectivityMonitor` drives playlist switching. When offline,
`PlaylistRepository.loadLocalPlaylist()` returns only tracks with a valid `localPath`.
The player continues from the current track; queue shows only locally available tracks.

**Station-switch guard** — `controllerPreparedForCurrentChat` prevents ExoPlayer from
playing audio from the previous station while a new one is loading.

**Session persistence** — TDLib writes its session to `filesDir/tdlib/`. api_id/api_hash
are in `EncryptedSharedPreferences`. Neither is cleared by `adb install -r`.

---

## Common issues

| Symptom | Fix |
|---------|-----|
| `:tdlib` compile error — "package org.drinkless.tdlib does not exist" | Run `./scripts/setup_tdlib.sh` |
| `UnsatisfiedLinkError: libtdjni.so` | Setup script didn't copy `.so` files; re-run it |
| Stuck on WaitParameters after entering credentials | Check logcat for TDLib error; verify api_id is numeric and api_hash is correct |
| Album art not showing | Track has no embedded ID3/FLAC picture tag — placeholder shown |
| Download hangs | TDLib file download is network-bound; check logcat for `TdApi.Error` |
| Icon not showing after install | Run `gen_icon.py`, rebuild — manifest needs `android:icon="@mipmap/ic_launcher"` |
| Room `IllegalStateException` on upgrade | DB version mismatch — ensure `MIGRATION_1_2` is registered in `DatabaseModule` |
| APK packaging fails "not writeable" | Previous APK locked by Android Studio/adb — delete `app/build/outputs/apk/debug/app-debug.apk` and retry |

---

## Build environment — how to trigger builds from Claude Code

Claude Code runs on **20gg** (this machine, `192.168.16.225`).
Android Studio and the Android SDK live on a **separate Windows PC** (`192.168.16.239`, user `FAE006`).
The Windows PC reaches the project via a network-mapped drive, but that mapping only exists in
the interactive desktop session — not in SSH sessions.

### SSH alias

`~/.ssh/config` on 20gg has:
```
Host windows-as
    HostName 192.168.16.239
    User FAE006
    LogLevel ERROR      ← suppresses the post-quantum warning
```

Use `ssh windows-as "..."` for all remote commands.

### Samba share

20gg runs Samba. A guest-accessible usershare called **`telecloud`** maps to `/home/guko/projects/telecloud_radio`:

```bash
net usershare add telecloud /home/guko/projects/telecloud_radio "Telecloud Home" "Everyone:F" guest_ok=yes
```

Check it exists before building:
```bash
net usershare list   # should show "telecloud"
```

If it's missing, re-run the command above (no sudo needed).

### Running a Gradle build via SSH

Because each SSH call is a new PowerShell session, the `Z:` drive must be mapped
**in the same command** as the Gradle invocation:

```bash
ssh windows-as "
  net use Z: \\\\192.168.16.225\\telecloud /persistent:no 2>&1;
  \$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr';
  \$env:PATH=\"\$env:JAVA_HOME\bin;\$env:PATH\";
  cmd /c 'cd /d Z:\ && gradlew.bat assembleDebug' 2>&1
"
```

Key points:
- Use `net use` (not `New-PSDrive`) — `.bat` files need a real Windows drive letter; `New-PSDrive` is PowerShell-only and `cmd.exe` rejects it.
- Use `cmd /c 'cd /d Z:\... && gradlew.bat ...'` — the `cd /d` sets the working directory so Gradle finds `settings.gradle.kts`.
- Set `JAVA_HOME` explicitly — it is not set in SSH sessions.
- Exit code from PowerShell may be 1 due to llvm-strip stderr warnings; check for `BUILD SUCCESSFUL` in the output, not the exit code.

Use `run_in_background=true` on the Bash tool — builds take 5–15 minutes depending on cache warmth.

**Important:** `gradle-wrapper.jar` is git-ignored but must be present. If missing, fetch it:
```bash
curl -sL "https://raw.githubusercontent.com/gradle/gradle/v8.14.0/gradle/wrapper/gradle-wrapper.jar" \
  -o gradle/wrapper/gradle-wrapper.jar
```

**Important:** `JAVA_HOME` is not set in SSH sessions. Always set it to Android Studio's bundled JBR:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

**Important:** `.bat` files require a real Windows drive letter — `New-PSDrive` creates a
PowerShell-only drive that `cmd.exe` can't use. Always use `net use` to map Z:, not `New-PSDrive`.

### Gradle cache / WSL file-locking issues

The Gradle daemon on Windows writes its project cache (`.gradle/`) to the network share.
Windows file-locking over SMB is unreliable for Gradle's BTree cache files and causes
`java.io.IOException: An unexpected network error occurred`.

Mitigations already in `gradle.properties`:
- `org.gradle.vfs.watch=false` — disables VFS file watching
- `org.gradle.buildOutputCleanup.enabled=false` — disables the cleanup cache that corrupts most often

If a build fails with a cache corruption error (`outputFiles.bin`, `executionHistory`),
delete the project-level cache from 20gg and retry:
```bash
rm -rf /home/guko/projects/telecloud_radio/.gradle
```

### Installing on device

The test device is a **OnePlus PFFM10** (`model:PFFM10 device:OP520DL1`) connected via USB to the
Windows PC. It shows up in `adb devices` as transport over `adb-tls-connect`.

Always map Z: in the same command before calling adb, since Z: is not persistent across SSH sessions:

```bash
ssh windows-as "
  net use Z: \\\\192.168.16.225\\telecloud /persistent:no 2>&1;
  adb install -r Z:\app\build\outputs\apk\debug\app-debug.apk 2>&1
"
```

If `adb devices` shows no device, the phone is unplugged — ask the user to reconnect via USB.
Use `-r` to replace the existing install and preserve the TDLib session / auth data.

---

### llvm-strip warnings (harmless)

```
error: 'libtdjni.so': The file was not recognized as a valid object file
```

`llvm-strip.exe` on Windows can't parse the Linux ELF `.so` files served over SMB.
Gradle falls back to packaging them unstripped — the APK still works correctly.
