# DarkMusic v2.8.2

## License and permissions

- New original DarkLian contributions first published from this release use the DarkMusic License & Permissions: free-of-charge use, private modifications, and unmodified official release binaries in modpacks with attribution, preserved notices, and third-party compliance.
- Standalone redistribution and distributed modifications containing that new protected material require written permission, except where other existing rights apply.
- Previously released CC0 portions remain under their original dedication, including when reused here. This change does not revoke those rights. Historical releases, the prior dedication, existing per-file notices, and third-party licenses are preserved.
- Update the current license, README, Fabric metadata, bundled notices, and source-JAR license. LGPL library modification/replacement and debugging rights remain intact; the corresponding third-party source bundle is still supplied.

## File integrity and shared lyrics

- Metadata editing claims the original file into retained staging and validates that exact object before publishing. Rename, in-place edits, and rollback no longer overwrite a later external replacement by pathname. Conflicts preserve recovery files and report their directory.
- Renaming one audio format copies lyric companions shared with another format of the same stem, preserving both files' lyrics and attribution. Deletion keeps shared companions and rechecks for sibling formats introduced after review.
- Saved lyric selections, offsets, languages, and encodings survive unreadable subtrees even when the library index is absent. Pruning receives unreadable scopes and keeps unknown records conservatively.

## Lyrics, cleanup, and playback reliability

- Canceling Find Missing Lyrics reaches owned caption fallback and retries through one stable run owner, while leaving unrelated manual searches active. A run with outstanding retries remains cancelable.
- Preserve a missing Retry-After header through the real HTTP adapter. An unqualified 503 does not become a fabricated 60-second provider cooldown.
- Smart Cleanup requires content-bound provenance/archive associations for source evidence. Filename-only download history and stale cached source strings cannot create a strong source match.
- Manual source-caption lookup canonicalizes supported YouTube/Bilibili providers. This narrows the entry point; it is not a complete network sandbox for redirects or DNS resolution inside external tools.
- Repeated empty decoder reads now have a five-second no-progress deadline, independent of output-device timing. Real input progress and legitimate priming remain supported; pausing resets the empty-read window.

## Compatibility and verification

- Existing music, settings, playlists, queues, and local lyrics are preserved. No new dependency, executable, or DSP algorithm is introduced.
- Clean Windows release validation passed through `scripts/verify.ps1 -Clean -DesktopIntegration`: **479 tests passed; two optional network integration checks skipped** (live LRCLIB and managed-tool installation). Checks used temporary private-audio excerpts and existing managed tools; original music files were preserved.
- English and Traditional Chinese Minecraft smoke runs passed at fullscreen 1920?1080, GUI scale 2, with SmoothFont. Coverage includes real audio playback, pause/seek/resume/speed, lyrics timing, Up Next, consent, and compact/wide UI bounds.
- Verified current licensing text in both binary and source JARs, exact preservation of the legacy dedication, third-party notices, and custom Fabric metadata. The real loopback HTTP adapter and controlled cancellation/race tests passed.
- Live provider coverage, external-tool redirect exploitability, native Linux/macOS desktop behavior, and untested hardware failures remain unverified. Hosted release builds cover compilation and non-private tests on Windows, Linux, and macOS; they do not establish native desktop behavior.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**.
