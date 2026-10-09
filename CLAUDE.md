# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android video player for TV (primary), phones and tablets. Plays local videos ("Open with" from file managers) and streams video files straight from torrents. UI language is Russian (`res/values/strings.xml` is the default locale; future translations go to `values-xx/`). Package / applicationId: `com.zetronik.torrentplayer`.

Three screens: recent torrents (max 20) → torrent file list (video files only) → player.

Planned features and their status are tracked in `roadmap.md` (in Russian). Update the statuses there when working on a roadmap item.

## Commands

Run from the repo root (`gradlew.bat` on Windows, or `./gradlew` from Git Bash).

- Build: `./gradlew assembleDebug` — ABI splits are on, so it produces one APK per ABI plus `app-universal-*.apk` in `app/build/outputs/apk/debug/`
- Install on a device/emulator: `adb install -r app/build/outputs/apk/debug/app-<abi>-debug.apk`
- Release (R8 on, signed with the debug key for now): `./gradlew assembleRelease`
- Lint: `./gradlew lintDebug` (fails on errors)
- Unit tests: `./gradlew testDebugUnitTest`
- Single test: `./gradlew :app:testDebugUnitTest --tests "com.zetronik.torrentplayer.torrent.TorrentFilesTest.subtitlesAreMatchedByVideoName"`

In Git Bash, prefix `adb shell` commands that contain device paths with `MSYS_NO_PATHCONV=1`, otherwise `/sdcard/...` gets rewritten to a Windows path.

## Build setup

- AGP 9 with built-in Kotlin: no `org.jetbrains.kotlin.android` plugin is applied in the module. It is declared `apply false` in the root build only to pin the Kotlin version, which also fixes the Compose compiler and serialization plugin versions.
- Versions live in `gradle/libs.versions.toml`. **Media3 is deliberately pinned to 1.9.x** to match the prebuilt Jellyfin FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`, built against 1.9.0). Upgrade both together.
- Room uses KSP; schemas are exported to `app/schemas/`.
- R8 keep rules live in `app/src/main/keepRules/` (libtorrent4j is a SWIG/JNI binding and must be kept).
- Configuration cache is enabled.

## Architecture

Single activity (`MainActivity`) with Compose for TV (`androidx.tv:tv-material`) and Navigation Compose with type-safe routes (`ui/navigation/Routes.kt`). Manual DI: `AppContainer`, created in `TorrentPlayerApp` and reached via `context.appContainer`. ViewModels are built with `viewModel { ... }` initializers.

### Torrent streaming (`torrent/`)

- `TorrentEngine` owns the libtorrent4j `SessionManager`, started at app launch. **Only one torrent is active at a time**; adding another removes the previous one with its data. Structural operations (add, reset, close, prepare) are serialized by a `Mutex`.
- Data lives in `noBackupFilesDir/torrent-data/<infohash>/<generation>/` and is wiped on app start. It is deliberately not in `cacheDir`: on low storage Android wipes app caches mid-stream. Saved `.torrent` metadata lives in `filesDir/torrents/<infohash>.torrent`, so reopening from the recent list skips the DHT/metadata step.
- Lifecycle tied to screens:
  - `TorrentViewModel.onCleared` calls `engine.close()`, which deletes the data.
  - `PlayerViewModel.onCleared` releases the player first, then calls `engine.resetData()`, which removes and re-adds the torrent from metadata with nothing selected. That is how "cache is deleted when leaving the player" is implemented. Both run in `appScope`.
- Playback path: `TorrentUris` (`torrent://<infohash>/<fileIndex>`) → `player/TorrentDataSource` (Media3 `BaseDataSource`) → `TorrentReader`.
  - `TorrentReader` blocks the loader thread in `ActiveTorrent.awaitPiece()` until the piece arrives (via `PieceFinishedAlert`, with a polling fallback). ExoPlayer cancels loads by interrupting the thread, so the wait must stay interruptible.
- **The cache is a bounded ring, not a full download.** TV boxes have a few hundred MB free, while files can be 60+ GB.
  - `StreamBudget.forStream` sizes it from the file, not the disk: about 5 min of video at a bitrate estimated as file size / 90 min, clamped to 128 MB–1.5 GB. It is further capped at 60% of free space minus a 256 MB reserve. Below `minimumFreeBytes`, `prepareStream` throws `INSUFFICIENT_SPACE`.
  - `StreamWindow` is pure piece arithmetic and is unit-tested.
  - `StreamPrioritizer` (one per played file, held by `ActiveTorrent.stream`):
    - Only the read-ahead window has non-zero piece priority. The window is sized by the budget, and by the bitrate (at most 10 min ahead) once the player reports it via `engine.setBitrate`.
    - Download order comes from piece deadlines: the next ~6 s are re-tightened on every move, and about 2 min ahead (max 160 pieces) get increasing deadlines. `SEQUENTIAL_DOWNLOAD` alone does not keep order once only a window is wanted: measured on the TV, the contiguous buffer stuck at the deadline pieces while bandwidth went to random pieces.
    - Every second the monitor gives the first missing piece after the read position a deadline of 0 (`boostFrontier`), so a slow peer sitting on it cannot cap the buffer: libtorrent then treats it as late and fetches its blocks from other peers too.
    - The engine monitor merges libtorrent's piece bitmap into `ActiveTorrent` every second, so a dropped piece-finished alert cannot stall the buffer metric or the freeing of space.
    - The file head (first 2 MB: container header), the tail (MKV Cues / MP4 `moov`) and subtitle files are pinned at top priority. Reads of head/tail while the window is elsewhere are lookups and do not move the window: when resuming mid-film the demuxer reads header, then index, then jumps to the resume point.
  - Pieces behind the "behind" budget are freed with `fallocate(PUNCH_HOLE)` through the JNI shim `NativeFs` (`app/src/main/cpp`). They are marked discarded in `ActiveTorrent` *before* punching.
    - Freeing works from a `touched` set (every piece ever wanted), not a forward cursor, so data from before a seek back and partial pieces left by a jump are freed too. Leftovers ahead of the window are freed only when the cache is over budget.
    - Safety net: every 5 s the engine monitor checks the file's real allocated blocks (`st_blocks`). Above 1.25× budget + 64 MB it restarts the torrent at the read position.
  - Reading a discarded piece (a seek back) calls `engine.restartFrom()`: the torrent is re-added as a new generation from metadata, and `TorrentReader` transparently switches to it. This is also the fallback when the filesystem cannot punch holes.
  - The streamed file keeps file priority LOW, never 0. libtorrent writes pieces of zero-priority files into its part file, not the file the player reads.
- **Dynamic prebuffer.** `PrebufferPolicy` sets the required buffer: 0 (player default) while download ≥ 1.5× bitrate, up to 100 s as the margin shrinks, capped by the disk budget.
  - The gate lives in `TorrentReader`, not in a `LoadControl`. A `LoadControl` that refuses to start while not loading trips Media3's "Playback stuck buffering" check, and letting it keep loading would pull the whole prebuffer into RAM.
  - The gate only acts after `PlayerViewModel` reports a stall via `engine.setPlayerStalled`, so the first start stays fast.
  - The engine's per-stream monitor refreshes the smoothed download rate and the disk-ahead bytes once a second.
  - The player's buffer readout is memory (`bufferedPosition − position`) plus disk ahead of the loader. Disk alone reads ~0, because ExoPlayer consumes pieces as soon as they arrive.
- Re-adding a removed torrent must use an independent `TorrentInfo` (saved `.torrent` or a fresh `encodeTorrent` copy). The removed handle's info loses its file list ("no files in torrent").
- Session setting: `close_redundant_connections = false`, so seeds stay connected while the window is full and the torrent is momentarily "finished".
- Session settings:
  - Uploading is disabled with `unchoke_slots_limit = 0`. **Do not set `upload_rate_limit`**: it also throttles ACK/protocol overhead and stalled downloads at about 1 KB/s.
  - POSIX disk I/O (not mmap), because of 32-bit TV boxes.
- `SubtitleMatcher` attaches external `.srt/.ass/.vtt` files whose names start with the video's base name, or all of them when the torrent has a single video. They are sideloaded as `SubtitleConfiguration`s and downloaded at top priority.

### Player (`player/`, `ui/player/`)

- `PlayerFactory`:
  - FFmpeg extension renderers are enabled after the platform decoders (AC3/DTS/TrueHD).
  - `LoadControl` has a byte cap for low-RAM boxes.
  - The preferred audio language is the device locale.
  - Subtitles are off unless the file marks a track default or forced.
- `PlayerScreen` embeds `PlayerView` (SurfaceView, `useController = false`, focus blocked) and draws its own Compose controls.
- Remote handling:
  - Controls hidden: a root `onPreviewKeyEvent` handles OK/←/→/↑/↓ and media keys. Key-ups whose key-downs were consumed there are swallowed, so they don't click the newly focused button.
  - Controls shown: normal focus traversal.
- Repeated seeks are accumulated in `PlayerViewModel.seekBy` and committed once (each real seek restarts torrent prioritization).
- Controls layout: title and audio/subtitles/playlist icon buttons at the top. At the bottom: the seek bar, and under it the time, prev/play/next (the spinner replaces the play icon while buffering) and the scale button (`VideoScale`, saved in SharedPreferences). While the controls are shown, subtitles are lifted above the seek bar (`setBottomPaddingFraction`).
- Playlist: a torrent's `videoFiles`, or local videos opened together (VIEW clip data / `SEND_MULTIPLE`, passed in `PlayerRoute.playlistUris`). `PlayerViewModel.playItem` switches in place with the same player: stop, `engine.resetData`, then prepare the new file. Playback advances to the next item at the end.
- Subtitles use a fixed `CaptionStyleCompat`: white with a black outline, no background box.
- Recent list management: toolbar refresh/clear, per-item actions via long press or the remote Menu key (`OptionsDialog`). Seed/peer refresh uses `TrackerScraper`: its own UDP (BEP 15) and HTTP scrape client, independent of the single-torrent libtorrent session, one batched request per tracker. Trackers come from each magnet `tr=` plus `PublicTrackers`.
- Playback positions are saved in Room (`PlaybackPosition`, key `<infohash>:<index>` or the content URI).

### Play on TV (`remote/`, `ui/remote/`)

A phone sends the torrent it plays to the app on a TV in the same network; the TV downloads and plays it itself, and the phone becomes a remote. No Google Cast.

- Protocol (`RemoteProtocol`): the TV advertises `_torrentplayer._tcp` over NSD with its stable id in the `id` TXT attribute. One TCP connection = one JSON line request + one JSON line response. Plain sockets, not HTTP: cleartext HTTP is blocked at this targetSdk.
- TV side (`RemoteReceiver`): runs only on TV and only while `MainActivity` is started (`onStart`/`onStop`), because a background app cannot bring the player to the front. Pairing: the phone asks, the TV shows a 4-digit code (`PairingCodeDialog`, at most 3 tries, 2 min), the phone gets a token kept in the TV's prefs.
- `play` imports the `.torrent` the phone sends (`TorrentEngine.importMetadata`), so the TV skips the metadata download, then opens it through `NavRequest.PlayTorrent`.
  - If the TV's player already has that torrent, it switches file in place (`RemotePlayback.playFile`).
  - If its file list is open, the player is pushed on top. Popping that screen would close the torrent the player needs.
  - `PlayerRoute.startPositionMs` carries the position across devices.
- The open `PlayerViewModel` registers itself in `RemoteSession` as `RemotePlayback`: status (with track labels) and commands, always on the main thread. "Stop" sets `UiState.exitRequested`.
- Phone side: the cast button shows in the player for torrents on non-TV devices. `CastDialog` handles discovery (`DeviceDiscovery`), pairing and sending. `RemoteScreen` polls the status every second. "Watch on phone" stops the TV and reopens the player at the TV's position.
- Not done yet: sending local (non-torrent) videos. That would need the phone to serve the file.

### Theme and templates (`ui/theme/`)

- The look is a swappable `AppTemplate` (`AppTemplates.all`: `ios` by default, `classic`). It holds semantic `AppColors` (dark, plus an optional light set), corner radii (`AppShapes`, separate for phone and TV), typography for phone and TV, and the touch list style (`InsetGrouped` or `Cards`).
- `UiPreferences` (in `AppContainer`) stores the template id and `ThemeMode` (System/Light/Dark) as flows. `TorrentPlayerTheme` follows them live. There is no picker UI yet: a settings screen only needs to call `setTemplate` / `setThemeMode`.
- TV is always dark and uses `Cards`, with tvOS-style focus (a white card with black text). Phones and tablets follow the system light/dark setting when the template has a light set. The theme also sets the status/navigation bar icon colours (the activity is edge-to-edge).
- Read tokens through `AppTheme.colors / shapes / listStyle / layout`. tv-material components get the same values through the derived `MaterialTheme`. Never hardcode colours outside the player, which is always white-on-black over the video. A new template is just a new entry in `AppTemplates.kt` plus its name string.
- iOS text styles are mapped onto Material slots: headlineMedium = Large Title, headlineSmall = Title 2, titleLarge = Title 3, titleMedium = Headline, bodyLarge = Body, bodyMedium = Subheadline, bodySmall = Footnote.

### Adaptive layout

- `AppLayout` (`AppTheme.layout`) is computed from the window, not the screen, so split screen counts:
  - `isCompact`: non-TV and under 600 dp wide;
  - `isShort`: phone landscape, under 480 dp tall;
  - `isExpanded`: 840 dp and wider.
- Rule against squeezed buttons: anything with a label gets `weight(1f, fill = false)` in its row, so it ellipsizes instead of crushing its neighbours. In compact windows button rows become icon-only (`ToolbarAction`), stacked (`RemoteScreen` session buttons) or wrapped (`FlowRow`). A row of fixed-size controls is scaled as a whole (`BoxWithConstraints` in `RemoteScreen`).
- `ui/common/Layout.kt` has the shared pieces:
  - `ScreenHeader`: iOS nav bar. Compact: actions bar plus a Large Title. Wide: one line with the smaller inline title (TV keeps the Large Title). The back chevron shows on touch only.
  - `ToolbarAction`: labelled on TV and expanded windows, icon-only on phones; `prominent` always keeps its label.
  - Also `IconBadge`, `DisclosureIndicator` (touch only), `readableWidth()` (caps content at 1040 dp on large tablets) and `AppDialog`.
- Player controls in compact mode: the close/track buttons get their own bar above the title, the times sit under the seek bar ends, and the scale button is icon-only (the new mode flashes as `ScaleHint`). Touch screens get a close button; TV uses the Back key.

### UI conventions

- **tv-material clickable components (`Button`, `ListItem`, `Surface`) only react to D-pad/Enter, not touch.** Always use the wrappers in `ui/common/TouchSupport.kt`. They add tap and long-press, feed touches into the component's interaction source (pressed state after 60 ms, so scrolling does not flash rows) and apply the template:
  - `AppButton`: filled;
  - `AppSecondaryButton`: tinted;
  - `AppIconButton`: round;
  - `AppListItem`: pass `ItemPosition.of(index, count)` for grouped corners and separators, and space rows with `listItemSpacing()`.
- Initial focus is only requested when `isKeyboardNavigation()` is true (TV or keyboard input mode). On touch a focused control would show a stray highlight.
- Focus restoration when returning to a list: `rememberSaveable` holds the last-opened key, `rememberItemFocusRequester` registers per-item requesters, and a `LaunchedEffect` scrolls to the item and requests focus. It only runs on TV or in keyboard input mode. Use `requestFocusSafely()` (`requestFocus` throws when the node is not attached).
- Low-end TV performance: screen transitions are a 150 ms fade, and none for the player. Position polling (`PlayerViewModel.progress`) runs only while the controls are composed. `LoadingIndicator` animates in the graphics layer only.
- Screens apply `LocalScreenPadding` (overscan-safe on TV) and `WindowInsets.safeDrawing`.

## Known limitations

- Media3's `AviExtractor` does not support OpenDML AVI (`RIFF AVIX` continuation chunks); such files fail with `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`. This is not a torrent-layer bug: the downloaded bytes were verified identical to the source.

## Icons

- Adaptive launcher icon (API 26+): `drawable/ic_launcher_background.xml` + `ic_launcher_foreground.xml` (also used as the monochrome layer).
- PNGs are generated from the same geometry by `java tools/IconGen.java app/src/main/res app/src/main/ic_launcher-playstore.png`. These are the legacy `mipmap-*/ic_launcher*.png` for API 24–25, the 512 px Play Store icon and the TV banner `drawable-*/banner.png`, which is what Android TV launchers show instead of the icon. After changing the foreground vector, update `glyph()` in the generator and re-run it.
