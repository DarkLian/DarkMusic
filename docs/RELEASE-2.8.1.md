# DarkMusic v2.8.1

## Lyrics that keep moving

- Fix a song's retry backoff incorrectly pausing the whole library scan. A second network failure could previously stop later songs for 120 seconds. Failed songs now wait separately while later songs continue.
- Respect actual provider rate limits and Retry-After independently of per-song retries. A 503 without Retry-After no longer invents a minute-long global pause. Normal provider requests remain spaced at least one second apart.
- Find Missing Lyrics initializes progress even during a cooldown and explains whether it is checking locally, requesting lyrics, or waiting. The first reliable version applies automatically; alternatives remain available and weak matches still require review.
- Online lookup requires a one-time consent step, including on upgrade. Whole-library lookup is explicitly started and can be canceled; enabling lyrics alone no longer starts a library sweep. Audio is never uploaded.
- Automatic source-caption lookup uses verified DarkMusic provenance for known source sites, rather than arbitrary URLs embedded in file tags. Reliable file-language tags can resolve original-caption ties without using Minecraft's UI language.

## Safer lyrics and music files

- Recoverable lyric replacement preserves original files and the first backup. Save/Replace uses the version shown when clicked, and text imports require confirmation and reject binary content.
- Preserve per-song timing/language after external retagging, while offering the previous selected lyric for review. Add explicit legacy lyric-encoding choices.
- Store lyric content once per document, retain negative lookup results separately, and prune saved orphan records only when roots have been checked safely.
- Bind source identities to actual file contents, update provenance and companions during renames, and invalidate stale download associations. Legacy filename-only records are retained for manual use rather than silently authorizing automatic skips.
- Audio deletion can explicitly include lyric companions in Trash. Companions are kept by default; failed Trash never becomes permanent deletion. Improve identity checks, partial-result reconciliation, and bounded Trash failure logs.
- Recover interrupted download commits without treating a reused filename as the original source. Optional lyric failures continue to leave successful audio downloads intact.

## Larger libraries and small fixes

- Reduce duplicate-cache rewrite amplification and repeated fingerprint matching. Reuse fingerprint keys, coalesce ignored-match writes, and report capacity failures.
- Avoid full source-index/FFprobe sweeps for each partial library snapshot. Retain old tracks only under unreadable subtrees, and limit concurrent walkers sharing a filesystem.
- Keep prepared next tracks during no-op and non-head Up Next edits. Improve requested-format download diagnostics and reduce short-title duplicate noise.
- Update the main-player screenshot; retain the other documentation images.

## Compatibility and verification

- Existing settings, playlists, music, and local lyrics are preserved. Additive lyric/provenance/cache records are compatible with older stored data; online consent is intentionally required again. No new dependency, executable, playback DSP algorithm, or audio latency is introduced.
- Clean Windows release validation passed using `scripts/verify.ps1 -Clean -DesktopIntegration`: **468 tests passed, two optional network integration checks skipped** (live LRCLIB and managed-tool installation). Tests used temporary private-audio excerpts and existing managed tools; original files were preserved.
- English and Traditional Chinese native Minecraft smoke checks passed in fullscreen at 1920×1080, GUI scale 2, with SmoothFont. Coverage includes compact/wide layout bounds, lyrics consent, actual audio playback, seeking, pause/resume, speed, HUD timing, and Up Next isolation. Online access was disabled for these smoke checks.
- Packaged version/classes, English/Traditional Chinese key parity, and the supplied original main-player screenshot were verified.
- Live provider coverage, native Linux/macOS desktop behavior, prolonged external outages, and hardware failure scenarios remain unverified. Hosted release builds validate compilation and non-private tests on Windows, Linux, and macOS, rather than native desktop behavior.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**.
