# Development and verification

Release-by-release check logs and PiP investigations are kept in [release checks](history/RELEASE-CHECKS.md).

## Optional PiP runtime development

The next-update packaging and consent flow is described in [optional PiP installation](PIP-OPTIONAL-INSTALLATION.md). `PipRuntimeTest` uses disposable runtime archives and injected transport for no-work construction, no-download checks, explicit install/cache reuse, checksum rejection, cancellation and unsupported systems. The desktop-only case runs the real Windows/WGC preflight and native graphics-device probe with a local copy of this build's dependency ZIP. `PipBundleTest` verifies that only DarkMusic helper code remains in the embedded archive and verifies the split extraction and notices. Upgraded PiP preferences retain appearance while new access fields default off.

## PiP development checks

See [PIP-VERIFICATION.md](history/PIP-VERIFICATION.md) for implemented paths and the actual
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

Build configuration: `build.gradle` keeps the Minecraft/Fabric setup, dependencies, tests and packaging. Bundled
libraries are listed once in its `bundledLibraries` map, which also feeds the third-party source archive.
`gradle/native-helpers.gradle` builds the optional Windows helpers and their manifests (skipped with `-PjavaOnly`);
`gradle/smoke.gradle` defines the smoke client (only with `-Psmoke`). Both helpers build with the SDK in their
`global.json`; CI installs the one `src/pipHelper/global.json` names, and a test keeps the two files equal, so upgrade
them together. CI setup is shared by both workflows in `.github/actions/setup-toolchain`.

## Private audio

Audio-dependent checks use an existing real music collection. They explicitly skip when it is absent on hosted CI. Do not add personal music, fabricated audio fixtures, or private test images to the repository.

For local audio validation:

```sh
./gradlew build -PrequireMusic -PmusicDirectory=/path/to/real/music
```

Choose formats supported by the test readers (MP3, FLAC, WAV, or Ogg Vorbis). Temporary format conversions must derive from the real collection, retain metadata/artwork when a check requires them, and be deleted afterward. Original files must remain untouched.

For a focused run with the same excerpts, pass test classes or patterns:
`./scripts/verify.ps1 -Tests 'com.darkmusic.client.PackagedJarTest'`.

`-PdownloadIntegration -PdownloadToolsDirectory=/path/to/tools` enables tests with existing yt-dlp, FFmpeg, and FFprobe executables. The platform's executable suffix is selected automatically. The default directory remains the ignored `Music/` folder; tools are never installed or downloaded by this flag. Desktop chooser and managed-tool installation integration checks are opt-in through `-PdesktopIntegration` and `-PmanagedToolsIntegration`.

On Windows, the maintained local verification entry point is:

```powershell
./scripts/verify.ps1 -Clean -DesktopIntegration
```

If Windows blocks unsigned local scripts, run `powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/verify.ps1 -Clean -DesktopIntegration`. This applies only to that process; it does not change the system execution policy.

It uses `run/config/darkmusic/music` and detects an existing managed tool bundle. Override with `-MusicDirectory` and `-ToolsDirectory`. Use JDK 25 in `JAVA_HOME` or on PATH. If the collection has no bundled-reader formats, the script prepares temporary MP3 excerpts from up to three real songs, keeping available metadata/artwork. Excerpts live outside `build/`, are removed on success or failure, and their source hashes are checked afterward. It requires the private collection, never generates dummy audio, and does not publish anything.

## In-game smoke checks

`scripts/smoke.ps1` runs the smoke checks in sequence, each in its own fullscreen client with SmoothFont and the
private collection. It launches Minecraft and plays audio, so run it only when the computer is free.

```powershell
./scripts/smoke.ps1 -Scenario suite -DesktopVerify
./scripts/smoke.ps1 -Scenario default, default-restart
./scripts/smoke.ps1 -Scenario reset -DryRun
```

Scenarios: `default`, `default-restart`, `reset`, `reset-restart`, `advanced`, `cleanup`, `queue`, `lyrics`, `preview`,
`native-bindings`; `suite` runs the first nine in that order. A `-restart` scenario must directly follow the run it
checks. `-DesktopVerify` then runs `verify.ps1 -Clean -DesktopIntegration`. Requirements: JDK 25 in `JAVA_HOME` (or
`-JavaHome`), the collection (`-MusicDirectory`), a managed tool bundle (`-ToolsDirectory`) and SmoothFont (`-Font`);
a missing one stops the run before anything starts. `-DryRun` checks them and prints the Gradle commands;
`-SelfTest` checks the script's own rules without Gradle.

A scenario passes only when Gradle succeeds and its log carries the pass marker for the run id that invocation
wrote. Gradle runs with `--no-daemon`, so everything a scenario starts descends from it; a run that exceeds
`-TimeoutMinutes` (20) is stopped as a whole, whether Gradle is still preparing or the client is running, and the
remaining scenarios are not started unless every process has ended. Results, logs and
the collection hash lists (which name private files) go to ignored `.local-tools/verification/smoke/<time>`; the
collection must be unchanged afterwards. PiP and captures stay manual: use the Gradle options below.

## Development client

```sh
./gradlew runClient
./gradlew runSmokeClient -Psmoke -PmusicDirectory=/path/to/real/music
```

The private collection is Opus, so pass `-PdownloadToolsDirectory=<existing managed tool bundle>` to every runtime check that plays music; without it the default check stops on the first track. Inspect the smoke entrypoint and Gradle flags before choosing an optional capture, preview, HUD, reset, or advanced-audio scenario. Runtime checks require a graphical desktop and an audio device; they are separate from ordinary unit tests.

Smoke options (each mode runs in its own `build/<mode>-run` folder; Gradle rejects two modes at once, `-PverifyHudRestart` with anything but the default or reset check, and `-PnativeBindingsSmoke` with any mode):

| Option | What it checks |
|---|---|
| (none) | Default player, playback, shortcuts and Settings check in `build/smoke-run` |
| `-PqueueSmoke` | Up Next, playlists and queue editing |
| `-PcleanupSmoke` | Smart Cleanup and duplicate review |
| `-PlyricsSmoke` | Lyrics screens and the lyrics HUD |
| `-PpreviewSmoke` | Download preview: URL and preview kept across menu re-entry, search/range/check modes, real-device audition, and the visualizer settings entry |
| `-PadvancedSmoke` | Advanced audio screens |
| `-PresetSmoke` | Reset to Defaults keeps tools and music folders |
| `-PcaptureMusic` | Takes the private player captures |
| `-PpipSmoke` | PiP, with `-PpipFolder`, `-PpipWorldSource`, `-PpipSourceTitle`, `-PpipFrameRate`, `-PpipBenchmark` and `-PpipTrace` to choose the source, frame limit and measurements |
| `-PverifyHudRestart` | Second run of the default or reset check: the HUD settings saved by the first run survived the restart |
| `-PnativeBindingsSmoke` | With the default check: a key set in Minecraft's Controls wins over an old DarkMusic config value |
| `-PsmokeLanguage=<code>` | Runs a check in another language, such as `zh_tw` |
| `-PsmokeBorderless`, `-PvanillaFontSmoke`, `-PvisualSmoke`, `-PsmokeFont` | Window mode and font for the run |

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

Publish the main JAR, source JAR, and third-party source ZIP together. Keep license notices and source-archive references current. The tag must match `version` in `gradle.properties`; the release workflow reads `docs/releases/RELEASE-<version>.md`.

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

## Recovery and command reliability

Backup recovery tests cover external changes after interruption, newly created restore targets, conservative handling of legacy journals, and restart after claiming a recognized replacement. Unknown contents remain at their destination with a conflict copy and a retained journal. Duplicate tests distinguish verified source evidence from unverified embedded tags, including cached hints across repeated scans. Fresh provenance verification reuses its digest for exact-file and archive evidence within a scan; unchanged file size and timestamp alone do not authorize trusting an old hash across scans.

Download receipt tests cover failed source persistence, independent repair, and rejection of replaced content. Desktop Trash checks include passing the reviewed fallback hash to the isolated helper, native transfer of disposable documents, and same-size/timestamp companion replacement protection. Saved-lyrics tests use a controlled clock to verify five-second read retries without a restart.

`/da help` and `/darkmusic help` list the existing commands in groups with click-to-suggest actions. Both lyrics aliases are checked through the Fabric command dispatcher, including localized failure feedback without triggering unrelated actions. Menu opening is deferred until chat closes and revalidates the current playback state.

External caption networking remains a documented trust boundary. Canonical supported URLs and initial public-address checks are retained. yt-dlp performs redirects and DNS resolution itself ([upstream networking implementation](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/networking/_urllib.py)); these checks are not a complete process network sandbox. Cross-platform egress enforcement requires a separately reviewed solution. No new dependencies or system firewall changes were introduced.

## Semantic menu status colors

`UiStatusTest` checks distinct, readable information/success/warning/error colors against dark, light, custom, hovered, and selected surfaces. It verifies typed download/lyrics outcomes, separation of status from metadata in accessible row text, retained saved filenames, and partial-success warnings. The cached palette is recalculated when the menu theme changes, rather than doing contrast searches per rendered row. `HorizontalText` paints the status prefix separately while keeping the existing bounded, Unicode-aware scrolling behavior.

## Waveform progress bars

`WaveformProgressTest` uses only private-collection PCM and disposable copies for real envelope analysis, chunk-size independence, stereo-channel invariance, cancellation, cache reuse across service recreation, changed identity, and corrupted-cache rebuilding. Configuration/geometry checks preserve the default normal bar and reserve no waveform space when HUD progress is disabled. `CircularHudTest` checks finite waveform vertices, measured bounds, and played/unplayed colors. The playback fallback-rate assertion reads one immutable state snapshot to avoid comparing duration with a later position.

The lyrics Minecraft smoke workflow also exercises waveform display in the main player and all three HUD layouts with progress. It checks the decoded track duration, seeking, pause preservation, and disabling, with private full-resolution captures for visual inspection. The waveform service uses a bounded single background decoder, a generation-tagged publication, 1,024 envelope bins, 16 memory entries, and 256 atomic disk entries. It performs no network requests.

### Waveform appearance revision

Waveform analysis now blends per-window RMS energy with a small peak contribution; cache version 2 invalidates the earlier peak-heavy envelope. Rendering uses immutable deferred mesh snapshots with antialiased filled contours and rounded bars. Tests verify finite measured bounds, opacity on faces and fringes, played/unplayed boundaries, immutable appearance snapshots, persistence, defaults, and invalid-value clamping. Circular geometry reserves configured ring thickness independently of the spectrum. Visual-only sliders avoid reconfiguring the audio visualizer.

The Progress Bar subgroup shares the category heading renderer and separator used by other Settings headings. HUD background transparency, gradient, and colors are above it in HUD appearance. Native smoke checks cover both styles, expanded appearance controls at 320/426/960 logical widths, maximum height/detail, spacing, contrast, smoothing, opacity, main-player seeking, pause, and Circular/Artwork/Classic HUDs. Captures remain private.

### HUD image choice, circular gap, rounded spectra, and player preview

`HudImageTest` verifies artwork defaults in all three image-bearing layouts, independent persisted choices, null/range recovery, center-crop UV proportions, rounded-spectrum persistence, and circular baseline/stack geometry for every progress/spectrum/waveform combination at zero and custom gaps. `CircularHudTest` checks shifted progress rail thickness and rounded spectrum connectivity. `SpectrumMeshTest` checks softened caps, alpha, bounds, and worst-width/bar-count batch budgets without adding a render pipeline.

Native smoke checks render artwork and icon choices in all three layouts, simulate an unavailable artwork cache to exercise fallback, and check circular gap combinations and compact bounds. Real private audio drives Rounded Bars in the main player and all four HUD layouts. Settings checks include the shared main-menu progress preview in compact/wide layouts and with the HUD disabled. No audio assets are generated; existing private excerpts are reused.

### Spectrum opacity, curve detail, and rounded peak markers

New regressions verify opacity composition for faces, trails, gradient tails and glow; persisted opacity defaults/range recovery; rounded peak separation; and fixed radial peak thickness independent of bar width. Circular separation is evaluated after the same mirrored frequency smoothing used by the live bar. Native rounded-spectrum captures use 55% opacity with real private audio in the main player and all four HUDs. Curve Detail is shown only for styles using the segment budget; frequency-band count remains independent.