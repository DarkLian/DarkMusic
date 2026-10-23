# DarkMusic v2.6.0

## Up Next

- Choose **Play Next** to put a song first, or **Add to Queue** to append it after your other queued songs. Find Song Actions at the top-right of the song details panel, with artwork, title, and artist.
- Open **Up Next** beside Downloads to view Now Playing, Queued by You, and Coming from Collection.
- Drag queued songs to reorder them. Drag a collection song into the queue to add a copy at the drop position; its artwork, title, and artist follow the pointer. The original collection and playlists stay unchanged.
- Duplicate songs have separate queue entries. Remove individual occurrences or clear pending songs without stopping playback.
- Queued songs temporarily interrupt the original collection, then normal playback resumes. Shuffle affects the collection; Repeat This holds queued songs until you skip or change repeat.
- The queue supports up to 1,000 pending songs, works with gapless playback and crossfade, and stays available throughout the current Minecraft launch. Starting a new playback collection clears it; Stop and Play/Pause preserve it. It resets when Minecraft exits.
- Metadata edits and successful deletions update queued references. Incomplete scans retain entries from unavailable folders.

## Download diagnostics

- Failed URL analysis and download jobs offer **Error Details**, identifying the operation, tool, recognized problem, and relevant troubleshooting steps.
- Common website, extractor, HTTP, network, tool, conversion, validation, and storage failures receive specific guidance. Uncertain causes remain described as possibilities.
- **Copy Diagnostic** copies a bounded report with allowlisted technical information, without full URLs, personal paths, credentials, or raw tool output. No diagnostic telemetry is sent.
- Tool-related problems link to Download Tools. Checks, installations, and updates remain user-directed. Existing retry limits and download parallelism are preserved.

## Interface improvements

- Added visible Back buttons across secondary menus, including Manage Playlists, while retaining Escape navigation.
- Added concise, distinct queue-action and drag instructions. Section headings provide hover help without extra question-mark markers.
- Improved icon texture filtering for more stable rendering at GUI scale 2.
- Updated English and Traditional Chinese text; Up Next is labeled **即將播放** in Traditional Chinese.

## Verification

- Clean Windows build, packaged-JAR validation, localization checks, and the full local regression suite passed: **371 tests passed; one optional managed-tool installation test skipped**. Audio-dependent checks used the private real music collection, and temporary excerpts were cleaned afterward.
- Native Minecraft checks cover English and Traditional Chinese, fullscreen 1920×1080 at configured/effective GUI scale 2 with SmoothFont, compact/wide layouts, queue dragging, Back navigation, diagnostic copying, and real-device playback transitions.
- Controlled audio tests cover prepared gapless/crossfade promotion and obsolete transition rejection. These do not establish physical device-disconnection behavior.
- Hosted builds validate Windows, Linux, and macOS. Native Linux/macOS behavior, default-font runtime, physical device disconnection, live website failures, and world/server-transition playtests were not verified locally.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**. Existing settings and music files are preserved; no new third-party dependencies or persistent queue format are introduced.
