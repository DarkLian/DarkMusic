# DarkMusic v2.4.2

## Smoother backups and loudness preparation

- **Responsive backup exports:** prepare snapshots, copy listening history, and serialize/write archives on the storage executor. Export captures the selected settings, playlists, and shortcuts without copying the full history on the Minecraft UI thread. Existing archive format and restore safeguards are preserved.
- **Faster loudness-cache loading:** stream cached readings instead of constructing a full JSON tree, and start loading earlier when normalization is enabled. Cached measurements still apply from the first audible sample; invalid cache files are preserved rather than partially trusted.
- **Upcoming-track analysis:** prioritize the next track in a bounded background queue, including when gapless playback is disabled. Playback never waits for analysis, and a completed measurement never changes the active session's gain. Available measurements are used when the next session begins.

## Verification

- Clean local build: **289 tests, 288 passed, one optional fresh managed-tool installation integration skipped, zero failures**.
- Real-collection tests cover upcoming-track analysis, stable active-session gain, cached normalization on the next session, queue priority/deduplication, malformed-cache protection, and background export consistency/failure handling.
- Windows Minecraft verification passed using the private real collection and actual Realtek output: normalization across seek/output changes, rejected-route retention, native-line fallback, restore disk/memory safeguards, and export with 20,000 history records. Export dispatch on the render thread took 1.38 ms in that run.
- Local benchmark with 100,000 history records: export UI preparation fell from roughly 6–7 ms median to 0.002 ms; serialization and ZIP creation remain background work.
- Fresh-object loading of a 65,536-reading cache fell from 181–193 ms to 96–118 ms. These measurements used warm filesystem caches, not a cold disk. Initial cache loading can still delay the first session if it has not finished; this preserves first-sample normalization accuracy.
- IntelliJ formatting applied. Packaged artifacts exclude private audio, benchmark harnesses, and development tests.

Cold-disk measurements and macOS/Linux runtime behavior remain unverified. Background prioritization does not interrupt an analysis already running. No new dependencies, configuration formats, or default sound changes.

## Compatibility and files

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only. Existing settings, profiles, playlists, assets, and audio files are preserved.

- `darkmusic-2.4.2.jar` — install this file.
- `darkmusic-2.4.2-sources.jar` — DarkMusic sources.
- `darkmusic-2.4.2-third-party-sources.zip` — bundled-library sources and notices.
