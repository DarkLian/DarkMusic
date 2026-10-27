# DarkMusic v2.4.3

## Multiple music folders and faster loudness preparation

- Scan multiple music folders together and select a clearly marked default save destination. Folder order stays stable when the destination changes. Keep the built-in save-folder action in Music Folders only.
- Prepare the full scanned library with Analyze Library Loudness, progress, and cancellation. Optional parallel analysis runs up to three jobs with a CPU-usage warning; single-job analysis remains the default.
- Reuse cached measurements and preserve stable normalization throughout the active playback session. Analysis cancellation isolates subprocesses and retains completed readings.
- Improve indexed seeking, next-track preparation, and playback-order reconciliation during shuffle changes.
- Harden configuration and playlist writes, managed-tool cleanup, backup recovery retention, download subprocess handling, and trusted download URL checks.
- Improve multi-folder backups/import routing, listening-history preparation, artwork cleanup, metadata filename changes, and lyric text parsing.
- Declare the client command dependency and verify release builds across Windows, Linux, and macOS before publication.

## Verification

- Local build: **312 tests; 311 passed, one optional managed-tool installation test skipped, zero failures**.
- Real three-song analysis benchmark: 10.7 seconds serially versus 4.9 seconds with three workers, with identical normalization gains.
- Minecraft development-client verification: all 54 real-library songs analyzed successfully during playback, stable active-session gain, analysis continuing after leaving Audio Options, cached rechecks, persisted preferences, and compact/normal UI bounds.
- Additional Windows checks cover audio routing, backup recovery, multi-folder registration, import destinations, and non-destructive removal. Linux/macOS runtime and hardware-disconnection scenarios remain unverified locally.
- No private music, test harnesses, or review documents are included in release artifacts. Existing audio files and settings are retained; parallel analysis defaults off.

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API. Client-side only.

Install `darkmusic-2.4.3.jar`. Sources and bundled third-party source notices are provided separately.
