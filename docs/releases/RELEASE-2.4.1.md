# DarkMusic v2.4.1

## Correctness and reliability

- **Stable loudness normalization:** cached measurements apply from the first submitted audio samples. When a measurement is unavailable, the active session keeps its gain while metadata/analysis is cached for the next session. Seeks, output changes, metadata continuation, gapless playback, and crossfades retain the selected measurement. The cache is reused rather than recreated when normalization is bypassed.
- **Faster backup imports:** insertion-ordered deduplication preserves song order and uniqueness without repeated list searches. Restore preparation and serialization run off the Minecraft UI thread, and committed playlists are published without another redundant disk write.
- **Recoverable restores:** settings, playlists, retained song references, listening history, and native DarkMusic shortcuts share a durable recovery journal. Interrupted restores recover before loading user data on restart. Recovery copies are kept if storage or permission problems prevent rollback.
- **Preview playback:** fix an initialization race that could report Playing before the audio backend accepted its callbacks.
- **Broader build coverage:** configure Windows, macOS, and Linux CI builds and existing regression tests. These automated checks do not establish physical-device or desktop runtime compatibility.

## Verification

- Local v2.4.1 build passed: **282 tests, 281 passed, one optional fresh managed-tool installation integration skipped, zero failures**.
- Actual Windows desktop folder-picker approval/cancellation integration passed.
- Real-collection PCM regressions verify first-block normalization, deferred results, seek/metadata continuation, rapid route changes, failed output initialization, gapless transitions, and crossfades. Simulated output lines are used for deterministic stress tests.
- A 100,000-entry remapped import completed in about 50 ms locally, preserving ordered uniqueness.
- Backup tests cover malformed/oversized archives, injected disk-full and access-denied failures at every transaction write, simulated process interruption, blocked rollback/retry, restart recovery, and restored native key bindings.
- Windows Minecraft checks with the private real collection and actual Realtek output passed: paused switching, seek/resume normalization, rejected output retention, software-closed native-line recovery, unavailable saved-ID fallback, locked-file restore preservation of disk and memory, and asynchronous restore preview.
- Packaged metadata, English/Traditional Chinese key parity, and exclusion of private media and development test code were verified.

Physical USB/Bluetooth unplugging, actual filesystem exhaustion, and macOS/Linux desktop/audio/tool/update runtime behavior remain unverified. Fresh managed-tool installation was not rerun. No platform-native device identities or new dependencies were introduced.

## Compatibility and files

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only. Existing settings, profiles, playlists, and audio files are preserved.

- darkmusic-2.4.1.jar — install this file.
- darkmusic-2.4.1-sources.jar — DarkMusic sources.
- darkmusic-2.4.1-third-party-sources.zip — bundled-library sources and notices.
