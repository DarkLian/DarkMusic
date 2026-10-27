# DarkMusic v2.5.1

## Library scan preservation

- Partial and failed root scans merge discovered songs with previously indexed entries by stable track ID. Updated metadata takes precedence; missing songs disappear only after a successful scan.
- Reconfiguring music folders retains entries and folder navigation for roots that remain registered, including offline roots.
- Changing only the Default Save Folder no longer empties the library or restarts an active scan.

## Cancel pending deletions

- Add Cancel Remaining Files while bulk deletion is running.
- Preserve completed deletions and report untouched pending files as canceled, separately from failures.
- Return from permanent-deletion confirmation to the operation screen immediately so cancellation remains accessible.
- Allow an already-issued native Trash operation to settle before reporting its result. Trash failures never cause automatic permanent deletion.
- Add matching English and Traditional Chinese status text.

## Verification and development workflow

- `clean build` passed locally: **342 tests; 341 passed, one optional managed-tool installation test skipped, zero failures**, with real-collection excerpts and local codec tools. Temporary excerpts were removed afterward.
- All ten new scan-retention/filesystem tests also passed with the music directory unavailable. Missing-font functional setup passed, visual setup rejected the missing pack as intended, and setup with SmoothFont passed.
- Add clean-CI filesystem and deletion-orchestration tests using ordinary documents and an explicit test validator. Production audio recognition still rejects those documents; real-audio and native Trash integration remain separate checks.
- Add regression coverage for partial/failed scan merging, metadata replacement, successful empty scans, root changes, and save-destination changes during an active scan.
- Functional smoke setup can run without the private font pack. Visual verification and screenshot capture continue to require SmoothFont, 1920×1080 fullscreen, and GUI scale 2.
- Windows Minecraft verification exercised cancel-pending controls, permanent-deletion confirmation, canceled result classification, a subsequent successful deletion, compact 426×240 control bounds, fullscreen, and original-file SHA-256 preservation using disposable real-audio copies. No screenshots were taken.

Existing settings and music are retained. No new third-party dependencies, DSP changes, or multiplayer networking changes.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**. Install `darkmusic-2.5.1.jar`.
