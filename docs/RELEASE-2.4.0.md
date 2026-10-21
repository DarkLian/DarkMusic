# DarkMusic v2.4.0

## New

- **Listening history:** use the clock icon immediately left of Manage Playlists to open Recently Played and Most Played. View each song's play count, listening time, and last-played date. History lists keep their order while playback starts from History; statistics continue recording.
- **Portable backups:** export settings, audio/environment profiles, playlists, favorites, DarkMusic shortcuts, and listening history to a ZIP. Preview categories before restoring, merge or replace collections, and optionally match songs to the current music folder by relative path. Audio files and tools are never bundled or changed. Installation-specific music/tool paths and the selected output device stay local.
- **Audio output selection:** choose an available Java Sound output in Audio Options, refresh the device list, or use System Default. Compatible switches preserve playback position and pause state; a rejected switch keeps the previous route.
- **Keyboard accessibility:** Tab can reveal offscreen settings rows, list narration includes position/selection, borderless controls have clearer keyboard focus, and custom interface colors maintain readable contrast.

## Fixes and polish

- Replace the top History tab with a matching utility icon beside Manage Playlists and preserve usable spacing at compact window sizes.
- Mouse-selected player controls no longer capture playback arrows and Space. Tab enables native control navigation; any mouse click leaves that navigation state. Search protects typing and releases focus when empty space is clicked.
- Preserve the same listening session across seek, output changes, and metadata-edit playback continuation. Count listening from the audio device clock, excluding paused and muted time.
- Retain unresolved imported playlist references for later matching and keep recovery copies when applying backups.

## Compatibility

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only.

Existing settings, music, playlists, and profiles are preserved. Audio-output preference is additive; listening history and retained imported references use separate versioned storage. No new third-party dependencies.

## Files

- darkmusic-2.4.0.jar — install this file.
- darkmusic-2.4.0-sources.jar — DarkMusic sources.
- darkmusic-2.4.0-third-party-sources.zip — bundled-library sources and notices.

## Verification

- Clean local Gradle release build passed: 270 tests, 268 passed, two optional integrations skipped, zero failures.
- Audio-dependent tests used a temporary MP3 conversion from the private real collection. The conversion was removed after completion; originals were preserved.
- Windows Minecraft development-client checks passed for History navigation, compact player layouts, mouse-selected shortcuts, Tab-controlled sliders, Search focus protection, keyboard settings navigation, real output switching while paused and playing, unavailable-device rejection, backup/restore, and listening-history persistence.
- Packaged v2.4.0 metadata, English/Traditional Chinese key parity, and exclusion of private media and development test code were checked.
- Actual desktop file-picker interaction, fresh managed-tool installation, physical Bluetooth/USB hot-plug, spoken narration, and Linux/macOS runtime behavior were not manually verified.
