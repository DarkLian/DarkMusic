# DarkMusic v2.9.0

## Clearer menus and lyrics results

- Status messages use distinct, theme-aware colors, separating progress, success, warnings, and errors from filenames and other static information. Downloads, lyrics, Smart Cleanup, and file-management screens are easier to read.
- Lyrics distinguish local-only checks from completed online lookups, paused requests, failed requests, and versions awaiting your review. A local miss stays unresolved instead of implying that no online lyrics exist. Instrumental results have their own label.
- Find Missing Lyrics is now the first action in Advanced Lyrics. Settings no longer includes the redundant icon-marked entry for the current song's lyrics.
- Existing Files inspection is disabled when there are no known matches to inspect.
- Refined English and Traditional Chinese wording throughout the player.

## Commands

- Reorganized `/da help` into readable groups with clickable command suggestions.
- Added `/da lyrics` and `/darkmusic lyrics` to open lyrics for the current song, including while paused. Disabled lyrics, absent playback, and incompatible operations receive an explanation without changing playback or Up Next.

## Safer recovery and file associations

- Backup recovery checks recorded transaction hashes before restoring a destination. Conflicting newer files are preserved and reported instead of being overwritten.
- Smart Cleanup distinguishes verified source associations from unverified embedded source tags. Metadata-only source hints cannot independently create a strong source match.
- Reuse safely validated source hashes during cleanup instead of unnecessarily rereading the same file.
- Downloads retain a repair receipt when source provenance cannot be saved and retry the association independently of the successful audio download.
- Desktop Trash validates the fallback content hash on filesystems without file keys.
- Saved lyric preferences retry recoverable read failures with bounded backoff.

## Compatibility

- Existing music, playlists, settings, listening history, queued songs, and lyric selections are preserved.
- No new audio effects or runtime dependencies. Gradle Wrapper and the test-only ASM dependency received patch updates.
- Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**.

## Verification

- Clean Windows release checks passed through `scripts/verify.ps1 -Clean -DesktopIntegration`, using private-collection excerpts and existing managed tools: **496 passed, two optional tests skipped** (live LRCLIB and managed-tool installation). Original source audio remained byte-identical, and temporary excerpts were removed.
- English and Traditional Chinese Minecraft smoke checks passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont. Checks cover actual device playback, pause/seek/resume/speed, lyrics timing and offsets, Up Next, commands, consent, and compact/wide screen bounds.
- Verified the packaged version, all 1,482 localization keys in both languages, current license notices, source-JAR license, third-party source archive integrity, and exclusion of private audio and smoke classes from the release JAR.
- Hosted release builds run on Windows, Linux, and macOS. Native Linux/macOS desktop behavior, live lyrics-provider coverage, external-tool redirect isolation, and untested hardware failures remain unverified.
