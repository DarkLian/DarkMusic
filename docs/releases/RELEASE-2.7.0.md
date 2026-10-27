# DarkMusic v2.7.0

## Smart Cleanup

- Find possible duplicate recordings across registered music folders using independent file-content, metadata, filename, source-video, and optional audio-fingerprint evidence.
- Choose Strict, Balanced, or Loose sensitivity. Related files appear as expandable groups, with each actual pair and its evidence preserved. Five matching files occupy one group containing ten matches.
- Compare artwork, song information, paths, formats, file sizes, and available audio/source details. Play/Pause A and B lets you audition without losing your current playback source or Up Next.
- Keep both files, persistently ignore a match, or explicitly choose one file for the existing deletion review. Files are never deleted, merged, renamed, or retagged automatically.
- Background analysis supports cancellation, reusable versioned caches, unavailable files, and bounded candidate retrieval. Acoustic analysis uses existing FFmpeg tools and up to the first three minutes; it supports tested codec changes and trimmed overlap but can miss substantial edits or produce false positives, especially under Loose.

## Library-wide download awareness

- Preview entries show known existing downloads, available possible-duplicate evidence, and incomplete-check status across registered folders.
- Confirmed source matches offer existing-file inspection, Skip Already Downloaded, or Download Anyway. Skip respects the requested output format and verifies the file still exists.
- Existing per-destination archive checks remain active. Fuzzy similarities never silently skip a download, and skipped entries are distinct from newly saved music.
- Existing parallelism, retries, cancellation, diagnostics, playlists, and playback remain in place.

## Compatibility and verification

- English and Traditional Chinese are included. Existing settings and music files are preserved; the new caches and ignore records are additive. No third-party dependency or new managed executable is added.
- Clean Windows build and full local checks passed: **394 tests passed; one optional managed-tool installation test skipped**. Audio checks used the private real collection with existing tools; destructive filesystem checks used disposable documents only.
- Native Windows checks cover both languages, fullscreen 1920×1080 at GUI scale 2 with SmoothFont, compact/wide widget layouts, grouped results, A/B play/pause/resume, and restoration of normal playback and Up Next.
- The indexed 100,000-track sparse metadata benchmark runs without all-pairs enumeration. This does not measure fingerprinting an entire 100,000-song collection.
- Native Linux/macOS behavior, offline network mounts, live website conditions, and gameplay frame-time impact remain unverified locally. Hosted CI checks builds on Windows, Linux, and macOS.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**.
