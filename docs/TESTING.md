# Development and verification

## v3.0.1 release validation

Clean Windows validation through `scripts/verify.ps1 -Clean -DesktopIntegration` passed **555 tests: 551 passed, four skipped**, zero failures/errors. Skips were the optional live-provider/tool-install/PiP-network checks and the linked-directory fixture because local symbolic-link creation was unavailable. Real private-collection, existing managed-tool and desktop runtime checks ran. Focused runtime checks passed after finalizing the opt-in network-test resource selection.

The v3.0.1 base JAR, source JAR, third-party source ZIP and optional Windows dependency ZIP passed integrity checks. The dependency manifest matches its archive SHA-256/size; only DarkMusic helper code remains embedded. Runtime notices, source archive contents, 1,607 matching localization keys, private-media/smoke exclusions, workflow lint and whitespace checks passed. The local base JAR is approximately 4 MB; optional dependencies approximately 76 MB. CI runtime patches may alter exact artifact sizes and checksums.

After publication, the explicit live check is `gradlew.bat test --tests '*PipRuntimeTest.publishedDependenciesInstallWithTheProductionTransport' -PdesktopIntegration -PpipRuntimeIntegration -PpipRuntimeReleaseJar=<downloaded-release.jar>`. It reads the manifest and code from the actual released JAR, uses the production download transport, installs into a disposable fixture and probes the real graphics device. It does not grant window/audio access or start capture. It is opt-in and does not run during ordinary tests. The published JAR is required because CI may use a different .NET patch from a local rebuild.

The earlier fullscreen setup UI smoke remains applicable: no production UI/capture implementation changed for release preparation. Post-FreeSync Chrome/fullscreen throughput, other Windows builds/GPUs and native non-Windows PiP remain unverified; this release makes no new performance claim.

## Optional PiP runtime development

The next-update packaging and consent flow is described in [optional PiP installation](PIP-OPTIONAL-INSTALLATION.md). `PipRuntimeTest` uses disposable runtime archives and injected transport for no-work construction, no-download checks, explicit install/cache reuse, checksum rejection, cancellation and unsupported systems. The desktop-only case runs the real Windows/WGC preflight and native graphics-device probe with a local copy of this build's dependency ZIP. `PipBundleTest` verifies that only DarkMusic helper code remains in the embedded archive and verifies the split extraction and notices. Upgraded PiP preferences retain appearance while new access fields default off.

An isolated fullscreen Minecraft smoke check at 1920x1080, GUI scale 2 with SmoothFont passed the actual setup screens, canceled install/access/audio prompts, offline cached-runtime verification, explicit capture consent and revocation. Installation or access consent did not start capture. The smoke run used the existing private collection for library loading, stopped music, performed no network fetch or window enumeration, emitted its smoke-pass marker and completed Gradle successfully. The matching release dependency asset is not live yet; a future publication must supply it before real in-game downloading can be tested end-to-end. The already published v3.0.0 assets were not replaced.

The full Windows private-collection verification (`scripts/verify.ps1 -DesktopIntegration`) passed with two optional live-provider/tool-install checks skipped. Final focused runtime/bundle/settings checks and assembly passed after the setup progress-label adjustment. Split-archive SHA-256, runtime license placement, 1,607 matching language keys, private-media/smoke exclusions, workflow lint and whitespace checks passed. The base JAR measured 4,014,198 bytes and optional dependencies 76,106,620 bytes. Historical PiP capture/spectrum benchmark harnesses now require the normal setup/access flow first; they must not silently grant access.

## v3.0.0 release validation

Clean Windows release checks through `scripts/verify.ps1 -Clean -DesktopIntegration` completed successfully: **546 tests, 544 passed, two optional live-provider/tool-install checks skipped**, zero failures/errors. The real private collection and existing managed tools were used. The v3.0.0 binary/source JARs and third-party source ZIP passed integrity checks; packaged metadata, the native-helper SHA-256 and licenses, 1,584 matching localization keys, and exclusion of private audio and smoke classes were verified. Workflow lint and Git whitespace checks passed.

Runtime PiP, audio and layout checks are recorded below and in [PiP verification](PIP-VERIFICATION.md). The latest default/4 ms acquisition probes, OBS comparison and fullscreen/windowed crossover are in the [WGC investigation](PIP-WGC-INVESTIGATION.md). No extra public screenshots were taken. The user authorized release with the measured default fullscreen rate of approximately 47 FPS and unresolved focus/display-transition capture reports. No sustained fullscreen 144 FPS, browser-equivalence or native Linux/macOS PiP claim is made. Hosted build outcomes are available on the v3.0.0 release workflow.

## PiP capture timing investigation

See [WGC frame-rate investigation](PIP-WGC-INVESTIGATION.md) for measured default/4 ms capture, isolated acquire/dispose and readback probes, OBS modern-window comparison, and exclusive-fullscreen/windowed crossover results. Developer-only `-PpipTrace=<ignored-directory>` on the PiP smoke run records bounded native/Java stage timings on background writers. A `video4` phase explicitly requests a runtime-gated 4 ms WGC interval; normal capture defaults, protocol and music processing remain unchanged. GPU upload submission is measured on the CPU and must not be reported as GPU completion or scanout. Native Windows build 26300 was exercised; older-build runtime fallback, other GPUs, browser equivalence and sustained fullscreen 144 FPS are unverified.

## PiP application-audio checkpoint

HUD visibility follow-up: Minecraft UI assertions verify that disabling the
Now-Playing HUD immediately removes HUD-only controls without losing preferences,
while shared main-player spectrum/progress controls remain in every saved HUD
layout at compact/wide sizes. Fullscreen SmoothFont preview was reviewed with the
HUD off. Focused layout/localization tests and preview assembly passed.

Independent PiP spectrum follow-up: **544 tests total, 542 passed, two optional
checks skipped** through the Windows private-collection harness. Configuration
tests cover separate music/PiP preferences, persistence, missing/null defaults,
invalid values and four-sided placement at screen edges. The renderer reuses a
session-owned appearance adapter instead of swapping the user's music settings.
Minecraft UI checks exercised style/position controls and keyboard focus at
320×240, 426×240 and 960×540. Private fullscreen 1920×1080, GUI-scale-2 SmoothFont
captures were reviewed for settings, layout and independent color previews.
English and Traditional Chinese smoke runs both completed successfully; final
localized-label/configuration checks and preview assembly also passed.
These follow-up UI tests use synthetic spectrum previews, not new live capture
or device tests; the earlier throughput limit remains unchanged.

Windows `scripts/verify.ps1 -DesktopIntegration` passed **540 tests: 538 passed,
two optional live-provider/tool-install tests skipped**. Temporary excerpts from
the private collection were cleaned and source hashes verified. New audio tests
cover invalid/truncated/oversized packets, duplicate sequences, closed readers,
and spectrum analysis of real audio. Native process-loopback capture, spectrum
rendering and capture stop/restart were exercised in Minecraft.

See [measured PiP performance](PIP-PERFORMANCE.md): fullscreen 1920×1080, GUI scale
2 with SmoothFont. The 144 FPS PiP target remains unmet. These short checks do not
establish browser equivalence, tab isolation, device-change resilience or prolonged
gameplay stability. Native macOS/Linux PiP remains unsupported. Manual approval is
still required before committing or releasing this preview.

## PiP development checks

See [PIP-VERIFICATION.md](PIP-VERIFICATION.md) for implemented paths and the actual
runtime verification record. Run `gradlew test --tests '*Pip*Test' -PbundlePipHelper`
for transport, preferences and bundle integrity checks. Building the native bundle
needs the .NET 8 SDK. Windows full verification includes it automatically.

`runSmokeClient -Psmoke -PpipSmoke -PbundlePipHelper` opens the production PiP
controls in an isolated fullscreen client with SmoothFont and the private audio
collection. Choose the application through the picker; no source is selected
automatically. Bounded metadata/timing samples, without captured pixels, go under
ignored `.local-tools/verification/pip-integrated`. Stop through that folder's
`stop-minecraft` file. Use a fresh `-PpipFolder=...` per subsequent run. The shutdown
marker indicates orderly shutdown only, not acceptable video smoothness.

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

## v2.8.1 safety and retry regressions

`LyricsLibraryRunTest` covers explicit library-run completion, first reliable-version application, persisted results, cooldown progress initialization, per-song network backoff without blocking later songs, exact short server waits, and 503 responses without Retry-After. `LyricsSafetyTest` covers online consent migration, automatic-caption URL restrictions, compact document compatibility, text validation, encoding, and lyric transactions. Network tests use controlled responses; they do not establish live provider availability.

`DeletionCompanionTest`, `DownloadCommitTest`, and source-index/metadata tests cover explicit companion handling, identity changes, rollback, and recoverable download commits. `ReviewPerformanceTest` and `PartialRootScopeTest` cover incremental matching, bounded cache writes, and retention limited to unreadable subtrees. Existing Up Next tests verify that no-op or non-head edits retain the preloaded next track.

Release verification uses `scripts/verify.ps1 -Clean -DesktopIntegration` with the private collection and existing managed tools, followed by both localized Minecraft lyrics smoke runs. Never infer native macOS/Linux, prolonged outage, or real inaccessible-filesystem behavior from mocks or hosted compilation.

## v2.8.2 integrity and licensing checks

`MetadataCommitSafetyTest` replaces the source immediately after final validation for rename and in-place commits, checks recovery retention, and verifies rollback preserves a later external replacement. Shared-stem fixtures verify copying lyric companions and attribution during rename. `DeletionCompanionTest` verifies shared lyrics are not offered for deletion and newly introduced sibling formats are rechecked. These filesystem policy tests use disposable non-audio documents.

`PartialRootScopeTest` saves lyric selections, offsets, language, and encoding with no library index, simulates an unreadable subtree, and verifies only positively missing healthy-scope preferences are pruned. `LyricsOwnershipTest` controls blocked caption and retry operations with latches, cancels their library owner, and verifies unrelated manual work survives. Its loopback HTTP-server test exercises the real adapter with a missing Retry-After header.

Duplicate tests require content-bound source/archive evidence and reject stale filename-only or cached provenance. Controlled decoder tests use real private-collection PCM to verify bounded empty reads and legitimate priming. Caption requests accept canonical supported providers; these checks do not establish a complete network sandbox around external-tool redirects or DNS resolution.

Packaging checks verify the custom current license, preserved CC0 legacy text, LGPL notices, and current Fabric metadata. Release checks inspect the license in both binary and source JARs and keep the third-party source bundle available. Existing historical license grants must not be relabeled or erased.


## Recovery and command reliability

Backup recovery tests cover external changes after interruption, newly created restore targets, conservative handling of legacy journals, and restart after claiming a recognized replacement. Unknown contents remain at their destination with a conflict copy and a retained journal. Duplicate tests distinguish verified source evidence from unverified embedded tags, including cached hints across repeated scans. Fresh provenance verification reuses its digest for exact-file and archive evidence within a scan; unchanged file size and timestamp alone do not authorize trusting an old hash across scans.

Download receipt tests cover failed source persistence, independent repair, and rejection of replaced content. Desktop Trash checks include passing the reviewed fallback hash to the isolated helper, native transfer of disposable documents, and same-size/timestamp companion replacement protection. Saved-lyrics tests use a controlled clock to verify five-second read retries without a restart.

The full Windows `scripts/verify.ps1 -DesktopIntegration` run passed 491 tests (489 passed, two optional network/install tests skipped) before the command additions. Nine focused command/localization tests and a build passed afterward. Native lyrics smoke checks passed in English and Traditional Chinese with the private collection, fullscreen 1920x1080, GUI scale 2, and SmoothFont. The smoke flow checks deferred lyrics commands for active and paused Up Next tracks, offline local lyrics, disabled lyrics, and a stop occurring before the menu opens. No real server disconnect/death race or subprocess redirect exploit was exercised.

`/da help` and `/darkmusic help` list the existing commands in groups with click-to-suggest actions. Both lyrics aliases are checked through the Fabric command dispatcher, including localized failure feedback without triggering unrelated actions. Menu opening is deferred until chat closes and revalidates the current playback state.

External caption networking remains a documented trust boundary. Canonical supported URLs and initial public-address checks are retained. yt-dlp performs redirects and DNS resolution itself ([upstream networking implementation](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/networking/_urllib.py)); these checks are not a complete process network sandbox. Cross-platform egress enforcement requires a separately reviewed solution. No new dependencies or system firewall changes were introduced.


## Semantic menu status colors

`UiStatusTest` checks distinct, readable information/success/warning/error colors against dark, light, custom, hovered, and selected surfaces. It verifies typed download/lyrics outcomes, separation of status from metadata in accessible row text, retained saved filenames, and partial-success warnings. The cached palette is recalculated when the menu theme changes, rather than doing contrast searches per rendered row. `HorizontalText` paints the status prefix separately while keeping the existing bounded, Unicode-aware scrolling behavior.

The full Windows real-collection check passed 497 tests (495 passed, two optional network/tool-install checks skipped), with Gradle 9.8.2 and test-only ASM 9.11.1. Focused status/localization/update checks and builds passed after the final presentation adjustments. English and Traditional Chinese native smoke runs passed at fullscreen 1920x1080, GUI scale 2 with SmoothFont; private captures were visually inspected for colored status prefixes, neutral metadata, selected rows, and CJK text. No new public screenshots were added. These checks do not establish Linux/macOS native GUI or audio behavior.

The translation update's failed build came from a regression test matching an English phrase in the restricted-content explanation. The test now asserts the intended localized classification instead of a particular wording; classification requirements remain intact. Both dependency PRs were inspected and tested together before merging, and their combined GitHub build passed.
## v2.9.0 release validation

Clean Windows verification through `scripts/verify.ps1 -Clean -DesktopIntegration` passed 498 tests: 496 passed and two optional live-network/tool-install checks skipped. Existing managed tools and temporary excerpts derived from the private collection were used; original audio hashes remained unchanged and temporary excerpts were removed. English and Traditional Chinese Minecraft smoke runs passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont, including real output, lyrics/commands, and compact/wide screen bounds.

The local-only missing result is unresolved until online lookup completes. Tests check this for both sidecars and embedded lyrics. A controlled caption fallback test verifies that an empty provider result stays searching until the fallback finishes, rather than publishing a premature final miss. Updated status wording scopes completed misses to the checked sources; failures, pauses, and candidate choices remain distinct. Advanced Lyrics puts Find Missing Lyrics first while preserving its online-lookup requirement, and Settings omits the redundant icon-marked song-lyrics shortcut.

Binary and source packaging checks verify version 2.9.0, license notices, language-key parity (1,482 keys), third-party source archive integrity, and absence of private audio and smoke classes. Live provider coverage, native Linux/macOS desktop behavior, external-tool redirect isolation, and untested hardware scenarios are not established by these checks.

## Waveform progress bars

`WaveformProgressTest` uses only private-collection PCM and disposable copies for real envelope analysis, chunk-size independence, stereo-channel invariance, cancellation, cache reuse across service recreation, changed identity, and corrupted-cache rebuilding. Configuration/geometry checks preserve the default normal bar and reserve no waveform space when HUD progress is disabled. `CircularHudTest` checks finite waveform vertices, measured bounds, and played/unplayed colors. The playback fallback-rate assertion reads one immutable state snapshot to avoid comparing duration with a later position.

The lyrics Minecraft smoke workflow also exercises waveform display in the main player and all three HUD layouts with progress. It checks the decoded track duration, seeking, pause preservation, and disabling, with private full-resolution captures for visual inspection. The waveform service uses a bounded single background decoder, a generation-tagged publication, 1,024 envelope bins, 16 memory entries, and 256 atomic disk entries. It performs no network requests.

Verification completed with the private real-audio harness: **502 tests total, 500 passed, two optional network/tool-install tests skipped**. English and Traditional Chinese Minecraft smoke runs passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont. Private captures of the player and Classic/Artwork/Circular waveform progress displays were visually inspected. No new public documentation screenshots were added. Actual native Linux/macOS behavior, long-recording limits, and gameplay FPS under heavy concurrent workloads remain unverified.

### Waveform appearance revision

Waveform analysis now blends per-window RMS energy with a small peak contribution; cache version 2 invalidates the earlier peak-heavy envelope. Rendering uses immutable deferred mesh snapshots with antialiased filled contours and rounded bars. Tests verify finite measured bounds, opacity on faces and fringes, played/unplayed boundaries, immutable appearance snapshots, persistence, defaults, and invalid-value clamping. Circular geometry reserves configured ring thickness independently of the spectrum. Visual-only sliders avoid reconfiguring the audio visualizer.

The Progress Bar subgroup shares the category heading renderer and separator used by other Settings headings. HUD background transparency, gradient, and colors are above it in HUD appearance. Native smoke checks cover both styles, expanded appearance controls at 320/426/960 logical widths, maximum height/detail, spacing, contrast, smoothing, opacity, main-player seeking, pause, and Circular/Artwork/Classic HUDs. Captures remain private.

Appearance revision validation passed with the private real collection: **505 tests, 503 passed and two optional network/tool-install tests skipped**. Final focused mesh/localization checks passed nine tests, including the additional narrow-spacing regression. English and Traditional Chinese native smoke checks passed again after the category-format correction; they explicitly assert the shared Progress Bar heading and that all three HUD background controls precede it. Fullscreen 1920×1080 GUI scale 2 SmoothFont captures were visually inspected for both waveform styles, main player, HUDs, and expanded settings. No public screenshots were changed.

### HUD image choice, circular gap, rounded spectra, and player preview

`HudImageTest` verifies artwork defaults in all three image-bearing layouts, independent persisted choices, null/range recovery, center-crop UV proportions, rounded-spectrum persistence, and circular baseline/stack geometry for every progress/spectrum/waveform combination at zero and custom gaps. `CircularHudTest` checks shifted progress rail thickness and rounded spectrum connectivity. `SpectrumMeshTest` checks softened caps, alpha, bounds, and worst-width/bar-count batch budgets without adding a render pipeline.

Native smoke checks render artwork and icon choices in all three layouts, simulate an unavailable artwork cache to exercise fallback, and check circular gap combinations and compact bounds. Real private audio drives Rounded Bars in the main player and all four HUD layouts. Settings checks include the shared main-menu progress preview in compact/wide layouts and with the HUD disabled. No audio assets are generated; existing private excerpts are reused.

Final Windows validation passed **511 tests: 509 passed and two optional live-provider/tool-install checks skipped**. Both English and Traditional Chinese native Minecraft smoke runs passed at fullscreen 1920x1080, GUI scale 2, with SmoothFont. Private captures were reviewed for the settings player preview, image choices and fallback, circular spacing, and rounded spectra driven by real audio. Original collection hashes remained unchanged. Linux/macOS native behavior and gameplay FPS under heavy concurrent workloads remain unverified. These changes await user manual confirmation; no release was published.

### Spectrum opacity, curve detail, and rounded peak markers

New regressions verify opacity composition for faces, trails, gradient tails and glow; persisted opacity defaults/range recovery; rounded peak separation; and fixed radial peak thickness independent of bar width. Circular separation is evaluated after the same mirrored frequency smoothing used by the live bar. Native rounded-spectrum captures use 55% opacity with real private audio in the main player and all four HUDs. Curve Detail is shown only for styles using the segment budget; frequency-band count remains independent.

The full Windows real-collection build and regression suite passed **517 tests: 515 passed, two optional live-provider/tool-install checks skipped**. Original audio hashes were verified and temporary excerpts cleaned. Native Linux/macOS behavior and sustained gameplay FPS remain unverified.

English and Traditional Chinese Minecraft runs each emitted their smoke-pass marker and Gradle BUILD SUCCESSFUL at fullscreen 1920x1080, GUI scale 2, with SmoothFont. Private Classic/Circular captures were visually reviewed. The surrounding PowerShell invocation returned status 1 despite those successful child tasks (native stderr is recorded as NativeCommandError); it is not reported as an end-to-end wrapper pass. No public screenshots or release were published.

## v2.10.0 release validation

Clean Windows release checks used scripts/verify.ps1 -Clean -DesktopIntegration through the private-collection harness: **517 tests, 515 passed, two optional live-provider/tool-install checks skipped**. Both language smoke runs completed with their pass markers and successful Gradle builds at fullscreen 1920x1080, GUI scale 2, SmoothFont. Source hashes remained unchanged and this run's excerpts were cleaned. The task-created Gradle daemon was stopped after completion because it held the PowerShell output pipe open.

Packaged version 2.10.0, 1,518 matching localization keys, source-JAR license, third-party source archive (including six nested archives), and exclusion of private audio and smoke classes were verified. Waveform progress remains off by default; spectrum opacity defaults to 100%. New settings are additive. No new dependencies or playback DSP changes. Native Linux/macOS, heavy-workload gameplay FPS, and analysis-limit recordings remain unverified.


## v3.0.1 release validation

Clean Windows checks through `scripts/verify.ps1 -Clean -DesktopIntegration` passed **555 tests: 551 passed, four skipped, zero failures/errors**. The skipped checks were the optional managed-tool installation, live LRCLIB, unavailable Windows symlink fixture, and opt-in live PiP installation. Existing managed tools and the private real collection were used. Prior optional-setup UI checks passed at fullscreen 1920x1080, GUI scale 2 with SmoothFont; this packaging release adds no new capture-performance measurement.

Windows, macOS and Linux [release CI](https://github.com/DarkLian/DarkMusic/actions/runs/40251089951) passed. All four published assets were downloaded and their SHA-256 digests matched GitHub metadata. Package CRCs, runtime/application separation, manifest binding, notices, source archives, private/smoke exclusions and 1,607 matching localization keys passed. The published base JAR is 4,009,649 bytes and optional Windows x64 dependencies are 76,242,875 bytes.

After publication, `PipRuntimeTest.publishedDependenciesInstallWithTheProductionTransport` passed separately (one test, zero skipped). It read the actual published JAR with `-PpipRuntimeReleaseJar`, downloaded the matching release archive through production transport using `-PpipRuntimeIntegration -PdesktopIntegration`, verified and installed it in a disposable JUnit directory, and passed Windows/WGC and native graphics-device checks. It did not enumerate windows, grant capture/audio access or start capture. Local evidence is under ignored `.local-tools/verification/release-3.0.1/`.

Native macOS/Linux PiP remains unsupported; CI builds do not establish native GUI/audio behavior on those systems. Post-FreeSync Chrome delivery at 144 FPS remains unverified and is recorded as the next handoff task.
