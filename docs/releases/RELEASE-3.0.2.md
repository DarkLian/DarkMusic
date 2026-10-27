# DarkMusic v3.0.2

## More reliable PiP

- Resize a captured window without ending the session when an old-size frame arrives.
- Optional audio-spectrum failures retry twice independently; video keeps running if audio cannot recover.
- Audio setup failures no longer stop video. Unexpected helper UI errors shut down cleanly instead of opening a hidden Windows error dialog.
- Reclaim old unused dependency ZIPs even after upgrading from early PiP versions. Locked runtimes are kept; stale session leases and abandoned installation work are cleaned up conservatively.
- A separate message explains when another Minecraft client is already setting up PiP. Disabling PiP Access also revokes its audio access.

## Smoother listening and library behavior

- Preserve DSP histories through same-format gapless and crossfade transitions. Stereo width starts at its configured value.
- Continuous Sound Environment adjustments retain effect histories instead of repeatedly restarting them. Preset and delay-layout changes still transition between engines.
- Crossfades use equal-power curves, and high-ratio downsampling has stronger rejection of frequencies above the output bandwidth.
- Recognize WAVE/AIFF files with misleading extensions and use already-configured tools for additional unknown containers.
- Keep healthy folder watches, back off failed registrations and ignore internal bookkeeping changes. Slow watcher work no longer holds the library configuration lock or blocks the caller closing the library.

## Clearer recovery and safer handling

- Show metadata recovery-folder counts in library status and Manage Audio Files. Retained recovery audio is never automatically deleted.
- Improve metadata-edit free-space checks, local lyric bounds/symlink checks and shared-companion recovery handling.
- Preserve this installation's online-lookup and capture permissions when exporting/restoring portable backups.
- Back off failed waveform analysis and avoid decoding an idle library track unless its settings preview is visible.
- Distinguish downloader bot verification from private content, with appropriate tool guidance and manual retry.
- Pin the native helper SDK and lock restore inputs; release notes include the actual helper/dependency checksums.

## Compatibility and verification

Settings, libraries, playlists, lyrics and Up Next remain compatible. No new dependencies, effects or mandatory tools. The base JAR remains small; PiP runtime dependencies are installed separately through its in-game setup, after confirmation. Do not put the dependency ZIP in `mods`.

Clean Windows validation passed 584 tests, with five optional/environment-dependent checks skipped (589 total). English and Traditional Chinese Minecraft playback/HUD smoke runs passed at fullscreen 1920x1080, GUI scale 2 with SmoothFont. Verification also includes native helper failure checks and live repeated WGC resizing, including an intentionally unavailable audio pipe. See [testing results](https://github.com/DarkLian/DarkMusic/blob/v3.0.2/docs/TESTING.md) for exact checks and limitations.

This release makes no new fullscreen 144 FPS or Chrome-equivalence claim. Post-FreeSync throughput/resource measurements, real-device audio recovery and broader Windows/GPU coverage remain pending. Native PiP is Windows x64 only; the music player remains available on Windows, Linux and macOS.

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.2.
