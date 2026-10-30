# DarkMusic v3.1.0

## A smoother library experience

- Faster repeated searches in large collections, with accent and full-width character matching. Searching `beyonce` can now find `Beyoncé` while Japanese voiced characters remain distinct.
- Opus metadata and embedded lyrics can be read directly without launching FFprobe for ordinary supported files. AAC/WebM retain their existing external-tool fallback.
- Better handling of missing embedded lyrics, inline LRC timestamps and saved lyric preferences. Removing a music folder no longer immediately discards its saved lyric choices and timing adjustments.
- Source-link verification runs separately from browsing work, and lyric-cache maintenance avoids holding up shared lyric state or deleting a freshly updated result.

## Reliability and maintenance

- Tool and PiP dependency downloads can continue beyond ten minutes while making progress; stalled transfers remain bounded.
- Resetting settings clears in-memory import history too. Sub-millisecond FFmpeg seeks use a compatible decimal format.
- Download workers recover from per-job linkage failures, and pitch-preserving playback yields control when a decoder temporarily returns no samples.
- Smart Cleanup avoids a redundant final matching pass and clears old, recognizable fingerprint scratch files conservatively.
- Old completed update jobs are pruned conservatively while pending and failed recovery material is retained.
- New library files respect normal filesystem permissions; imports preserve source POSIX permissions where supported.
- Additional filename and media-container validation prevents control characters and common image/video containers from being mistaken for music.

## More PiP window shapes

PiP now accepts ultrawide and tall windows up to **8,294,400 pixels**, with either dimension at most **7680**. The frame memory budget remains equivalent to 3840×2160. Capture continues to follow your selected frame limit, including the improvements from v3.0.4.

The existing exclusive-fullscreen tearing warning remains. Actual capture rate depends on the application, Windows, display and GPU configuration; this release makes no new FPS guarantee.

## Compatibility and verification

Existing settings, libraries, playlists, lyrics and Up Next remain compatible. No new third-party dependencies or mandatory tools. PiP remains optional and requires Windows x64; the music player supports Windows, Linux and macOS.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Install `darkmusic-3.1.0.jar` in `mods`. PiP dependencies are installed through the in-game setup after confirmation; **do not put the dependency ZIP in `mods`**.

Clean Windows validation passed **607 tests: 601 passed, six optional/environment skips**, with zero failures/errors. All four release archives passed package integrity checks. The changes have deterministic regression coverage and private-real-audio integration coverage. Windows English/Traditional Chinese player and lyrics smoke checks passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont. Native resize/capture testing accepted ultrawide and portrait frames; this does not establish Minecraft/OpenGL display at every supported dimension.

Physical slow links, NAS pressure, real device-disconnection recovery and other GPUs/Windows builds remain unverified. See the [testing documentation](../TESTING.md).
