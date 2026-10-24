# Development and verification

Use JDK 25 and the included Gradle wrapper. The client sources are in `src/client`; unit/integration checks are in `src/test`. `src/smoke` is a separate development-only mod for native Minecraft interaction checks and captures. It is not included in the distributed JAR.

## Build

```sh
./gradlew build
```

On Windows use `gradlew.bat`. `assemble` builds the mod JAR, project sources, and corresponding third-party source archive without executing tests. Artifacts are written to `build/libs`.

## Private audio

Audio-dependent checks use an existing real music collection. They explicitly skip when it is absent on hosted CI. Do not add personal music, fabricated audio fixtures, or private test images to the repository.

For local audio validation:

```sh
./gradlew build -PrequireMusic -PmusicDirectory=/path/to/real/music
```

Choose formats supported by the test readers (MP3, FLAC, WAV, or Ogg Vorbis). Temporary format conversions must derive from the real collection, retain metadata/artwork when a check requires them, and be deleted afterward. Original files must remain untouched.

`-PdownloadIntegration -PdownloadToolsDirectory=/path/to/tools` enables tests with existing yt-dlp, FFmpeg, and FFprobe executables. The platform's executable suffix is selected automatically. The default directory remains the ignored `Music/` folder; tools are never installed or downloaded by this flag. Desktop chooser and managed-tool installation integration checks are opt-in through `-PdesktopIntegration` and `-PmanagedToolsIntegration`.

On Windows, the maintained local verification entry point is:

```powershell
./scripts/verify.ps1 -Clean -DesktopIntegration
```

If Windows blocks unsigned local scripts, run `powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/verify.ps1 -Clean -DesktopIntegration`. This applies only to that process; it does not change the system execution policy.

It uses `run/config/darkmusic/music` and detects an existing managed tool bundle. Override with `-MusicDirectory` and `-ToolsDirectory`. Use JDK 25 in `JAVA_HOME` or on PATH. If the collection has no bundled-reader formats, the script prepares temporary MP3 excerpts from up to three real songs, keeping available metadata/artwork. Excerpts live outside `build/`, are removed on success or failure, and their source hashes are checked afterward. It requires the private collection, never generates dummy audio, and does not publish anything.

## Development client

```sh
./gradlew runClient
./gradlew runSmokeClient -Psmoke -PmusicDirectory=/path/to/real/music
```

Inspect the smoke entrypoint and Gradle flags before choosing an optional capture, preview, HUD, reset, or advanced-audio scenario. Runtime checks require a graphical desktop and an audio device; they are separate from ordinary unit tests.

Functional smoke runs use SmoothFont when it is installed and can fall back to the vanilla font when it is absent. **Visual verification and all captures require SmoothFont.** Use `-PvisualSmoke` to require it explicitly; capture, preview, and advanced-smoke modes require it automatically. `-PsmokeFont=/path/to/SmoothFont_1.21.zip` selects another local location without adding the pack to Git. `./gradlew prepareSmokeClient -Psmoke` checks run-directory preparation without launching Minecraft. A clean checkout still needs a real collection for audio-dependent runtime checks.

Review library navigation, search-independent playback queues, playlists and selection/reordering, download paging/cancellation/retries, settings persistence, lyrics, and HUD input/layout at multiple GUI scales. Performance-sensitive spectrum changes should preserve detail/effects and compare measurements on the same local fixture.

For imports, use real files from the private collection and an isolated destination. Check root/subfolder/playlist routing, filename collisions, history persistence, and byte-for-byte preservation of imported copies. Verify that ordinary clicks, focus changes, fullscreen/windowed transitions, and recording do not trigger an import overlay. A real file drop must still import and show progress in both the player and playlist manager.

Check missing-entry cleanup only for successfully completed roots, including Favorite Music, startup/autoplay, unavailable roots, and existing files temporarily absent from an index. A blocked root must not delay healthy-root results or cause its own entries to be pruned. Check saved audio-profile deletion with both cancellation and confirmation, persistence, and preservation of the active sound settings. Reset must preserve installed tool files and executable paths.

For the Artwork HUD, use actual embedded images with different aspect ratios. Verify proportional fitting, the flush progress bar, all four spectrum positions, outward side growth, shared background transparency/gradients, and combined preview/editor bounds. Exercise Color Spectrum/RGB switching, palette and hex editing, concrete defaults, all preset pages, and narrow-window controls. Check ordered caption-language preferences independently of local timed-lyric indicators; analysis results must not perform caption checks for an availability field.

For metadata edits, work on temporary copies. Cover active and paused playback, position restoration, filename changes, playlist/favorite references, matching lyric sidecars, embedded artwork preservation, and supported tag containers. Delete temporary audio copies after the run.

## Audio correctness and usability

Cover Neutral and Preserve Pitch independently, mono/stereo tempo and duration, crossfade, live switching, and pause/seek/resume. Check normalization provenance for missing, malformed, track, album, analyzed, legacy, pending, failed, and peak-limited data.

Exercise profile/environment create, load, rename, delete, duplicate replacement and cancellation, name limits, the 32-environment limit, and persistence. Saving or deleting stored entries must preserve active audio settings. Verify all 23 environment choices, logarithmic frequency mapping, exact numeric input and rejected nonfinite/out-of-range values, dependent control ranges, and hidden analyzer panel costs. Environment selection must keep the picker open, and advanced controls must appear below their toggle.

Use Settings → Audio for Audio Options and Sound Environment; open the analyzer from Audio Options. Check both languages and small-window keyboard navigation. Compare worker timings on the same real excerpt and block size; gameplay FPS is a separate measurement.

## Screenshots

Capture documentation fullscreen at 1920×1080 and configured and effective Minecraft GUI scale 2, with Enable Lyrics turned off and `run/resourcepacks/SmoothFont_1.21.zip` applied. Disable forced Unicode-font scaling if it rounds the effective GUI scale up. Use the private real collection through temporary copies, and keep original captures without cropping or resizing. Store unpublished captures under ignored `docs/images_private`, and promote only reviewed public screenshots to `docs/images`.

## File management and desktop integration

Use disposable copies of real collection audio for deletion tests. Verify single/bulk selection, review paths, cancellation, changed-file rejection, partial failures, retry review, and playlist/Favorite Music cleanup only after successful deletion. Confirm sidecars and unrelated files remain untouched. Cover active/paused playback, deleting the current track, preserving a surviving track's queue/position, and newer user stop requests.

`AudioDeletionFilesystemTest` runs on clean CI without music. It uses ordinary text documents with an explicitly injected audio validator to verify filesystem safety, orchestration, partial failures, and cancellation. It also checks that the production validator rejects those documents. These checks do not establish real-audio detection or native Trash support; `AudioDeletionTest` and desktop integration retain that separate role. Never generate dummy audio to satisfy these tests.

Cancellation must retain completed results, distinguish untouched canceled entries from failures, and wait for an issued native Trash request to settle. The permanent-deletion confirmation must return immediately to the operation screen so Cancel Remaining Files is reachable. Check narrow-window controls as well as normal fullscreen.

Exercise native Trash separately from permanent deletion, including unsupported Trash, rejected file identity, and Windows sharing violations. A Trash failure must never fall back to permanent deletion. Compare original collection hashes before and after testing.

The smoke client verifies overlapping desktop-window guards and native fullscreen transitions. Check actual Trash preparation and transfer for unwanted minimization, F11 during and after operations, and restoration of GLFW auto-iconify. The development client's windowed fallback is 1280×720; fullscreen capture remains 1920×1080 with GUI scale 2 and SmoothFont. Hardware and desktop behavior must be reported separately from mocked tests.

## Style and publication

Use IntelliJ IDEA's built-in formatter and the project `.editorconfig`. English interface prose lives in `src/client/resources/assets/darkmusic/lang/en_us.json`, with Traditional Chinese in `zh_tw.json` beside it. Keep both catalogs aligned and preserve formatting placeholders. Localization checks compare keys, placeholders, and line breaks, exercise translated components, and verify the packaged catalog.

Publish the main JAR, source JAR, and third-party source ZIP together. Keep license notices and source-archive references current. The tag must match `version` in `gradle.properties`; the release workflow reads `docs/RELEASE-<version>.md`.

## GitHub updater and layout alignment

Test automatic update notices with notifications enabled and disabled, repeated world joins, disconnect/rejoin, and toggle changes. The availability notice must appear at most once per launch. Manual checks must work with notifications off, display failures distinctly from up-to-date results, and never download without a separate click.

Use isolated installed-JAR fixtures for update installation tests. Verify consent cancellation, size/hash/compatibility checks, download-completion chat, pending-download recovery, waiting for game exit, collision protection, rollback, and byte-for-byte preservation of user settings/assets/music and other mods. Never replace the real development instance during tests. Development source sets must report that installation is unavailable.

Check editor center guides at different window sizes, GUI scales, HUD scales, and HUD styles. Both axes snap independently near the center; Shift bypasses snapping. Centered bounds may differ by half a GUI pixel where integer dimensions have opposite parity. Guides belong only to the editor.

## Player focus and update recovery

Check Left/Right on focused sliders and horizontal song text, and Space on focused buttons before trying playback shortcuts with focus cleared. Keep search text, cursor, and focus intact across a natural end-of-track transition; playback must continue to the next song. Explicit Jump to Playing still clears search.

Retry classification covers full HTTP error messages, including Service Unavailable, and nested permanent filesystem/authentication failures. Update recovery checks failed, pending, installed, malformed, and unrelated manifests without network access. Failed jobs must not be restaged. Verify localized failure information and the installation-log action in Check for Updates; automatic failure notices respect the notification toggle and appear once per launch.

## Background backup and loudness preparation

Check export capture against subsequent preference, shortcut, playlist, and history replacement. History copying, JSON preparation, and ZIP writing must run on the storage executor; failed exports must preserve an existing destination and leave no partial files.

Benchmark fresh loudness-cache instances at small and large sizes, distinguishing warm filesystem caches from true cold-disk startup. Preserve cached first-sample gain, reject partially parsed corrupt caches, deduplicate prefetch requests, and keep the background queue bounded. Use real-collection audio to verify that upcoming-track analysis completes before playback when possible, without waiting for it or altering the active session's gain. Confirm next-track preparation also works with gapless disabled.

## Up Next and download diagnostics

`UpNextTest` covers queue priority, duplicates and stable identities, the 1,000-entry limit, 100,000-track source previews, repeat/shuffle, source continuation, Previous, stop/new-source behavior, failed songs, metadata/deletion reconciliation, incomplete roots, stale callbacks, and rapid skips. Controlled transition gates make callback-order checks deterministic. `UpNextPreloadTest` uses real-collection PCM and a controlled output clock through JavaSoundBackend: both gapless and crossfade must promote the prepared manual decoder, reject the obsolete plan, return to the collection, and retain one output device. This is not a hardware-disconnection test.

`DownloadDiagnosticsTest` checks category/stage precedence and allowlisted reports using synthetic sensitive strings, multiline and oversized output, URLs, credentials, paths, cookies, headers, and control characters. `DownloadDiagnosticServiceTest` injects process results to cover analysis lifetime, independent parallel-job failures, retry association, and clearing. `DownloadTest` also covers successful retry and cancellation with real-collection media. No website request or tool update is needed for these checks.

Run the focused native workflow with already installed tools:

```sh
./gradlew runSmokeClient -Psmoke -PqueueSmoke -PmusicDirectory=/path/to/real/music -PdownloadToolsDirectory=/path/to/tools
```

This uses the isolated `build/queue-run` directory, fullscreen GUI scale 2 and SmoothFont. It checks 320×240, 426×240, and 960×540 screen coordinates, keyboard actions, duplicate drag reorder/removal, collection-copy drops, diagnostic copying through the actual clipboard, Back navigation across secondary menus, artwork cards and drag previews, and real-device pause/seek/resume and queue-to-collection promotion. The font pack is required for this visual workflow; the music and tools must already exist. No personal audio is packaged or changed.

Before release, also review both languages, the default font, compact-window rendering, queue entry deletion/rename during playback, live crossfade queue mutation, and device switching. Re-run native checks on supported operating systems where available; passing mock/device-clock tests or CI compilation does not establish hardware or desktop compatibility.

## Smart Cleanup and library-wide duplicate awareness

`DuplicateMatcherTest` covers independent signals, Unicode normalization, version markers, source URL forms, acoustic retrieval with unrelated tags, offset matching, negative examples, cancellation, and bounded sparse retrieval for 100,000 synthetic metadata records. `DuplicateGroupsTest` verifies a five-file/ten-edge group, non-transitive evidence, stable grouping, and a 20,001-file chain without recursive traversal or invented pairs.

`DuplicateStorageTest` uses disposable text documents for hash/cache invalidation, corruption recovery, source archives, known-deletion result updates, offline entries, cancellation, fingerprint-toggle behavior, and persistent ignored pairs. These are not generated audio assets. `LibrarySourceIndexTest` verifies registered roots, formats, renamed/deleted files, unregistered roots, incomplete checks, and the separation between confirmed source identity and fuzzy evidence.

`DownloadAwarenessTest` injects tool results to verify explicit review, skip versus download-anyway decisions, disappearing copies, different output formats, repeated Mix entries, concurrent requests, and fallback when the shared index is unavailable. Existing download integration tests retain destination-specific archive and processing coverage.

`CleanupAudioTest` converts temporary excerpts from real recordings into FLAC, MP3, and Opus and tests trimmed overlap, then removes the excerpts. It skips without private audio or existing FFmpeg on hosted runners. `ComparisonAuditionTest` verifies pause/resume at the retained preview position, switching files, initialization failure recovery, original pause state, and preservation of source/queue state.

```sh
./gradlew runSmokeClient -Psmoke -PcleanupSmoke -PsmokeLanguage=en_us -PdownloadToolsDirectory=/path/to/tools
./gradlew runSmokeClient -Psmoke -PcleanupSmoke -PsmokeLanguage=zh_tw -PdownloadToolsDirectory=/path/to/tools
```

The isolated `build/cleanup-run` client uses the private real collection, existing tools, SmoothFont, fullscreen 1920×1080, and GUI scale 2. It checks group expansion against actual evidence counts, 320×240/426×240/960×540 logical widget bounds and nonoverlap, real-device preview playback/pause/resume, retained position, original-song restoration, and unchanged Up Next. It never deletes or retags private music and makes no download requests to media websites. Captures remain in `docs/images_private` until reviewed for public use.

Before publication, run `scripts/verify.ps1 -Clean -DesktopIntegration`, inspect the packaged JAR, and review both languages. Real NAS failures, Linux/macOS desktop/device behavior, live website availability, and in-world frame-time stability require separate runtime verification; synthetic benchmarks and title-screen checks do not establish those results.

## Multilingual lyrics and independent lyrics HUD

`MultilingualLyricsTest` uses mocked LRCLIB responses and disposable non-audio identity files to cover candidate matching, plain/timed text, local-file preservation, language independence, persistent choices and offsets, rate limits, provider errors, cancellation, automatic application, and cache reuse across restarts. Fake clocks verify retry deadlines without sleeping through real backoff. Background-library checks cover rescans, unhealthy-root recovery, a lazy 100,000-entry snapshot, bounded loaded documents, and retained availability after eviction. Edited manual queries must survive retries and playback ticks.

`HorizontalTextTest` covers live countdown updates without resetting horizontal position. `RowListReliabilityTest` requires consecutive same-row clicks and invalidates sequences after another target, focus loss, or changed rows. Localization and packaging checks include all new keys and resources.

Run `./gradlew runSmokeClient -Psmoke -PlyricsSmoke -PmusicDirectory=<private-excerpts> -PdownloadToolsDirectory=<existing-tools>` for the native lyrics checks. Repeat with `-PsmokeLanguage=en_us` and `-PsmokeLanguage=zh_tw`. The suite uses real collection excerpts, SmoothFont, fullscreen 1920×1080, and GUI scale 2. It checks native cross-widget click sequences, real-font countdown scrolling, compact/wide bounds, independent HUD drag/resize, pause/seek/resume/speed, offsets, plain text, and Up Next transitions. Online lookup is disabled in this smoke workflow; no private song metadata is sent.

Optional `-PlyricsIntegration` enables `LrclibLiveTest`, which uses public test metadata through the production bounded HTTP client. Do not infer library-wide coverage, live source-caption support, or prolonged outage recovery from mocked tests or this one endpoint check. Check vanilla-font rendering separately with `-PvanillaFontSmoke`; it does not replace SmoothFont release checks. No audio alignment/transcription tools or extra provider dependencies are included.
