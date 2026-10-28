# Release checks

What was checked for each release, newest first, moved from the testing guide. Commands and options still in use are in [the testing guide](../TESTING.md).

## v3.5.0: Settings search, lyrics fade and Media Controls

**Settings search.** A search box above the form filters rows from every section and lists them under their section headings. A row matches when every word of the search appears in its label, help text, or section and group names, ignoring case. Choosing a section clears the search, and the search survives rebuilds such as toggling an option. `SettingsSearchTest` covers matching.

**Lyrics fade.** The lyrics HUD follows the player HUD's Hide When Idle state through `HudFade`, fading with it for both Slide and Fade, since sliding a centered line looks wrong.

**Media Controls.** A new optional Windows extension, built and shipped like PiP.
- **Helper.** `src/mediaHelper` is a .NET 8 console program, published self-contained as one trimmed and compressed file (`DarkMusic.Media.exe`, 10.8 MB; 5.7 MB zipped). A `MediaPlayer` with its command manager disabled owns the Windows media session; DarkMusic plays the audio itself. It reads one JSON state per line on standard input (UTF-8, so Chinese and accented titles survive) and writes `play`, `pause`, `next`, `previous`, `stop` and `seek <seconds>`. It exits when its input closes, so it never outlives Minecraft.
- **Build and release.** Gradle publishes it with the pinned SDK and locked restore, zips it as `darkmusic-media-controls-<version>-windows-x64.zip`, and writes `media-controls.json` (size, archive SHA-256, executable SHA-256) into the JAR. The sources JAR includes the helper source. The release workflow requires and publishes the package as a fifth file and lists its SHA-256 in the notes.
- **Installer.** `MediaControlsPackage` downloads only on Install, under a setup lock, verifies the archive and the extracted helper against the manifest, unpacks plain names one level deep, and keeps exactly one version: other versions, interrupted downloads and staging folders are removed. Development builds install the locally built package.
- **Session.** `MediaControls` starts the helper while the option is on, sends state on changes, seeks and every 5 seconds while playing (the flyout shows the last timeline it received), writes embedded artwork to a file for the flyout, backs off when the helper keeps exiting, and dispatches buttons to the client thread. Play with nothing loaded resumes or starts the library.
- **App name.** Windows shows the helper as "Unknown app". Setting an app ID and registering a per-user display name under `HKCU\Software\Classes\AppUserModelId` changed the session's app ID, but Windows' name lookup (`AppInfo.GetFromAppUserModelId`, used by the flyout) still failed, and the helper's file description is not used either. Windows resolves names for unpackaged programs only through a Start menu shortcut carrying the app ID, or through a signed app package. Both were declined (a Start menu entry that launches nothing; a paid certificate), so the name stays unknown and nothing is written to the registry. **Remove Media Controls** deletes the extension's files.
- **Checked against Windows.** A probe program found the helper's session in Windows' media session list with the title, artist, album, artwork, Playing status and timeline, and Pause, Next and a seek sent through Windows came back as button messages. `MediaControlsTest` covers manifests, verified installation, fingerprint mismatches, unsafe archive entries, unsupported systems, message parsing and update timing; its desktop test installs the built package, connects to Windows and checks that turning the option off ends the helper.

`scripts/verify.ps1 -Clean -DesktopIntegration` passed 680 tests (6 skipped), including the desktop Media Controls check. Not yet checked in game.

## v3.5.0: Everyday features

**Discord status (opt-in).** `presence.DiscordIpc` speaks Discord's local RPC: little-endian opcode and length frames with JSON, over `\\.\pipe\discord-ipc-N` on Windows or the `discord-ipc-N` Unix socket (plain, Flatpak and Snap locations). The handshake carries only the public application ID. `DiscordPresence` records the wanted status on the client thread; a worker thread connects, sends at most every 4 seconds (Discord allows 5 per 20), retries every 30 seconds while Discord is closed, and re-sends every 60 seconds so a restarted Discord is noticed. A song that keeps playing as expected is not re-sent; a seek of 2 seconds or more is. Pausing clears the status; loading and seeking keep it. The status uses type Listening, the icon from the repository's raw URL, and a releases button. `PackagedJarTest` allows exactly one class, `DiscordIpc$UnixSocket`, to mention `java/net/Socket`, and only alongside `UnixDomainSocketAddress` and without `InetSocketAddress`. `DiscordPresenceTest` drives the protocol and the worker through a fake Discord: framing, handshake refusal and silence, nonce matching with pings, rate limiting, seek detection, pause clearing, reconnection, hidden titles, progress at the playback speed, and Discord's 2-to-128-character limits without splitting characters.

**Resume Where You Left Off.** Settings save the song id, the player collection (mode and group) and the position on a song change, pause or stop, every 30 seconds while playing, and on exit. The collection lookup moved from `MusicScreen` to `DarkMusicClient.collection` so resume rebuilds the same queue. `PlaybackController.play` gained a start position and paused flag. `ResumePlaybackTest` covers starting part-way and paused, unreadable positions, persistence and damaged saved points.

**Shortcuts.** Show/hide HUD, show/hide lyrics HUD, favorite current song, shuffle and repeat are new key bindings, unbound by default. Shuffle and repeat share `DarkMusicClient.shuffle`/`repeat` with the player and the chat commands. `/da keys` lists them.

**Stop after this song and sleep timer.** `PlaybackController.stopAfterCurrent` stops at the end of the playing song and plans no next song, so gapless playback and crossfade cannot start it early. Stopping clears it. The sleep timer pauses after 1 to 720 minutes and fades the volume over the last 30 seconds without changing the saved volume. `/da sleep` and `/da stopafter` fit in the 20-line help by sharing lines.

**HUD.** `HudMotion` slides the live HUD out through the nearer side edge after the idle delay and back when playback resumes or the song changes; it also fades in the title and artist and lifts a veil off the artwork for 0.4 seconds after a song change. Previews and the layout editor always show the HUD settled. `SongCard` shows the new song for 4.5 seconds in the HUD's corner. The Classic HUD's optional "Next:" line uses `PlaybackController.upcoming`: Up Next first, then the collection, the same song under Repeat One, and nothing when stopping after this song. `HudMotionTest` covers the delay, slide-out and return, song changes while paused, fades, exit direction, card timing and settings, and the Classic height.

**Auto-updating lists.** History gained Recently Added (100 newest by file date) and Never Played (no counted play). Imports and downloads write new files, so their dates are when they were added. Only Recently Played and Most Played freeze their order while playing from History.

`scripts/verify.ps1 -Clean -DesktopIntegration` passed 666 tests (6 skipped).

**After the first in-game test.**
- **Discord.** A probe running the mod's `DiscordIpc` against the Discord desktop app connected, and Discord accepted the full status (type Listening, the external icon, timestamps and the button), so the protocol works on Windows. Connection state is now logged at INFO once per change: connected, Discord unreachable (with the reason) and connection lost. The worker also logs and survives unexpected errors instead of ending silently. Settings shows the state when hovering over Discord Status.
- **Resume.** Opening the player with nothing playing now selects the remembered song in its collection, and Play continues it from the saved position (`DarkMusicClient.resumes`). Before, the player opened on its first song and Play started from the beginning.
- **Hide When Idle** is a slider from Off to 2 minutes. **Idle Animation** chooses Slide or a slow Fade (2.5 seconds out, 0.35 seconds back).
- **Fading the whole HUD.** Minecraft's GUI has no global transparency. `GuiRenderStateMixin` modifies the three methods every GUI element passes through (`addGuiElement`, `addBlitToCurrentLayer`, `addText`): only inside `HudFade.run` they replace each element with a copy whose colors have scaled alpha. Fills and meshes are wrapped with a vertex consumer that scales alpha, images are rebuilt with a scaled tint, and text is rebuilt through `GuiTextRenderStateAccessor`. Text never gets a zero alpha, which would draw opaque. Outside a fade the methods return their argument unchanged. Unit tests cannot apply mixins, so the mixin itself needs an in-game check.
- **Song Change Card** has its own Card Animation (Slide, or Fade: 0.4 seconds in, 2.5 seconds held, 2.5 seconds out) and its own position and scale in Edit HUD Layout, which now handles three elements. A card that was never moved still follows the HUD.
- **HUD settings** are grouped: Appearance, Visibility and Motion, Song Change Card, Progress Bar.
- **Aligned row controls.** Checkboxes, choice arrows and page arrows in setting rows share one 12-pixel column (`SettingRow.ICON_INSET`); choice values end 4 pixels before it, and slider values end at its right edge. Before, choice arrows sat 3.5 GUI pixels right of the checkbox centers.
- **Classic time readout.** Show Time (Progress Bar group) offers two placements. Below Progress Bar (the default, matching the player) adds a 10-pixel line with the position under the bar's start and the length under its end. Artist Line right-aligns "position / length" on the artist line, which shortens to make room, so the card keeps its height. Without a progress bar the time uses the artist line. `HudMotionTest` checks the Classic height for each case. `text.Clock` formats it and now also backs `/da` time output.
- **Discord artwork.** For a song whose recorded source is a YouTube video, `DiscordPresence.artwork` builds the public thumbnail address (`i.ytimg.com/vi/<id>/hqdefault.jpg`) from the video ID; Discord fetches it through its own media proxy. Nothing is uploaded and no service is queried. The DarkMusic icon becomes the small badge. Other sources and local files keep the icon, and hiding the song hides the artwork. Source links resolve in the background, so the status updates once when the link becomes known. `DiscordPresenceTest` covers the YouTube link forms, rejected links and IDs, the hidden-song case, and the single update when artwork arrives.

`HudFadeTest` covers alpha scaling of fills and meshes, nesting, the minimum text alpha and skipping invisible fades. `HudMotionTest` adds the slow fade, the fast return, the card's fade timing and the new setting limits. `gradlew test -PjavaOnly` passed 671 tests in a separate worktree, because a dev client was running from the main checkout. Not yet checked in game.

## v3.5.0: One PiP package

PiP setup used to keep older dependency ZIPs for at least 24 hours, and longer while their runtime was in use or recently used. Downloads interrupted by a crash were never removed. Each package is about 76 MB, and three accumulated in the development instance in one day.

`PipStorage.removeOtherPackages` now runs at the start of every check and install, while the setup lock is held. It removes every `dependencies-*.zip` except the current version's, plus leftover `runtime-*.download` files, regardless of age. ZIPs are only read under that lock, so no other client can be using one, and a running runtime does not need its ZIP. `prune` now handles only extracted runtimes, which keep the 24-hour grace and their leases.

`PipReliabilityTest.onlyTheCurrentPackageIsKeptEvenWhileAnOlderRuntimeIsInUse` covers an old ZIP whose runtime is leased, a young ZIP, a partial download and an unrelated file. `PipRuntimeTest` checks that a check removes the other version's ZIP and that exactly one ZIP remains after install. `gradlew test -PjavaOnly` passed 650 tests (112 skipped without the private collection).

## v3.4.0: Chat commands and update consent

**Commands.** New commands control playback from chat: `play [song]`, `pause`, `stop`, `previous`, `next`, `now`, `queue <song>`, `volume [0–100]`, `mute`, `speed [0.5–3]`, `seek <time>`, `shuffle [on|off]`, `repeat [off|one|all]` and `keys`. `LocalCommands` holds the command tree. `ClientActions` runs each command with the same guards as the player's controls and refuses while DarkMusic is busy.

**Help.** `/da help` fits in open chat: 20 lines, grouped as Playback, Screens and More. Each command inserts itself on click and explains itself on hover. `ChatPalette` uses the menu accent, lightened until it reaches 4.5:1 contrast on chat's dark backdrop. `/da keys` shows the current bindings, says "Not bound" for unbound keys, and has a button that opens Key Binds.

**Update consent.** `/da update` used to download as soon as it found a release. It now only checks, then offers **Download and Install** (`/da update install`) and **Release Notes**. The update notice's button also runs `/da update install`.

**Clicked commands.** Since v1.0.3 the commands required typed ("attended") input. Fabric runs clicked chat buttons unattended, so every click opened Minecraft's confirmation dialog. Clicks now run directly. Only installing an update still checks: it is accepted when typed, or when clicked from DarkMusic's own button, which carries a random code made for this session. A link sent by a server cannot start a download.

**Screens from chat.** Screens requested from chat (player, HUD layout, lyrics, PiP, Key Binds) waited for no screen to be open. A button clicked in chat leaves chat open, so **[Change Keys]** and **[Lyrics]** never appeared. The requested screen now replaces chat, and closing it returns to the game.

**Seek limits.** An exact time past the end of the song is refused with the song's length, instead of jumping to the end. Moves with `+` or `-` stop at the start or end. Times are parsed as decimals, so a number like `99999999999:00` no longer overflows.

**Smoke runs.** `prepareSmokeClient` writes `pauseOnLostFocus:false`, so switching away from a borderless smoke run no longer opens the pause menu.

`LocalCommandsTest` covers:
- argument routing for both aliases;
- out-of-range values rejected before they reach playback;
- `/da update` checking without installing;
- song suggestions;
- the key list and its button;
- song matching order;
- seek parsing;
- palette contrast.

`NavigationAndCommandsTest` checks the 20-line help and its click targets. `LocalCommandsTest` also checks that clicked commands parse without confirmation and that install refuses clicks without the right code. `gradlew test -PjavaOnly` passed 649 tests (112 skipped without the private collection), and the smoke source set compiles. It was released at the user's request; the commands were tried in game during development, but no full play session was recorded for this build.

## v3.3.4: Beveled waves, spikes and Rounded Bars

Beveled used to look almost the same as Flat for every linear spectrum style except Bars.

**Waves and spikes.** These are drawn as one-pixel columns, so they have no side edges to shade. `SpectrumMesh.contour` lights each column by the slope of the unrounded curve instead: rising edges face the upper-left light that Bars use, and falling edges are shaded.
- Smooth Wave and Spike no longer get a dark bottom row, which cancelled the highlight on lines two or three pixels thick.
- Filled Wave and Filled Spike fade from lit just below the edge to shaded at the base.

**Rounded Bars.** The straight part of each bar now matches Bars: a lit left side, a shaded right side and a slightly darker face, with side columns only where Bars would have them.

The circular HUD is unchanged. `SpectrumMeshTest` adds three tests:
- rising, level and falling line segments differ clearly in brightness;
- a level two-pixel line stays brighter than Flat;
- filled bodies fade toward the base, and beveled Rounded Bars get their side columns.

`scripts/verify.ps1 -Clean -DesktopIntegration` passed 640 tests (6 skipped). It was released at the user's request without an in-game check of the new shading.

## v3.3.3: Repository review fixes

These fixes come from a review of the whole repository. One of the review's findings was withdrawn because the fields it flagged were already `volatile`.

- **Reset to Defaults.** `DarkMusicClient.createDownloads()` builds the download service for both startup and reset. `ResetSmokeClient` requires the saved-download lyric hook to be connected after a reset.
- **Settings load.** `AuditRegressionTest.undecodableOptionsFileWarnsButStillLoadsSettings` covers an `options.txt` that is not valid UTF-8.
- **Download reviews.** `DownloadAwarenessTest.laterBatchReplacesAnUnansweredReviewInsteadOfBeingDropped` covers a later batch replacing an earlier review that is still open.
- **Update rollback.** `UpdateTest` adds two tests:
  - a failed swap always leaves exactly one working mod JAR;
  - a same-name swap restores the old JAR even when the backup cannot be moved back.
- **Low-rate tempo.** `UnifiedAudioDspTest.wsolaKeepsLowSampleRateMonoContinuous` runs pitch-preserving tempo at 8, 11.025, 16 and 22.05 kHz, in mono and stereo, and requires zero discontinuities. Before the fix, 16 kHz mono had 26.
- **Spectrum budget.** `SpectrumMeshTest` checks that geometry beyond the initial budget grows the buffers once, then drops the extra instead of throwing.
- **Shared hashing.** `HashesTest` pins the SHA-256 of "abc" and checks cancellation. `RestoreJournal` keeps its own digest because `config` may not depend on `tools`.
- **Test runs.** `scripts/verify.ps1 -Clean -DesktopIntegration` passed 637 tests (6 skipped). The smoke source set compiles; the smoke clients were not rerun for this release. The changes passed a manual play session.

## v3.3.2: PiP Install repair, 16-bit dither and code cleanup

**PiP Install repair.** `PipRuntimeTest` covers three cases:
- a damaged cached package is replaced by a fresh download;
- a valid cached package is reused without downloading;
- a damaged unpacked runtime is extracted again, unless another client is using it.

**16-bit dither (review 121).** Playback adds TPDF dither to 16-bit output only. Unit tests cover:
- a constant a quarter step high averages back to a quarter step instead of rounding to silence;
- digital silence, full-scale clipping and 24-bit output are unchanged;
- the real-collection clip stays bit-exact through dithered quantization.

`NeutralTempoPlaybackTest` resamples, so it now allows one step of difference per sample.

**PiP audio note (review 100).** The PiP Audio Spectrum help text (English and Traditional Chinese) and the user guide now say that apps playing sound through a separate process are not captured.

### Code cleanup

This is internal restructuring with no user-facing change.

- **Package layering.** The core packages are layered in this order:
  `text` < `config` < `tools` < `media` < `library` < `audio`/`lyrics`/`playlist` < `cleanup` < `download` < `backup`.
  - Core packages never reference the interface packages or `DarkMusicClient`.
  - Drawing helpers live in `render`, so `hud` and `pip` don't depend on `gui`.
  - `ArchitectureTest` enforces these rules, including wildcard imports and inline qualified names.
- **Equivalence checks.**
  - Each move was checked by comparing `javap` output class by class, with old class names mapped to their new ones.
  - The import rewrite compiled to identical bytecode for all 777 classes.
  - Golden files pin the on-disk JSON of saved lyrics.
  - `Settings.validate` gives byte-identical results before and after it was split into per-feature validators.
- **Test runs.** `scripts/verify.ps1 -Clean -DesktopIntegration` passed 626 tests (6 skipped). All eight smoke modes passed after the package changes: default, preview, advanced, queue, cleanup, lyrics, reset, and PiP with Show HUD in Menus.
- **Large classes.**
  - `DarkMusicClient` names its busy checks `alive()`, `idle()` and `accepting()` instead of combining flags at each call site. Seeking, shortcuts, media actions and output changes now also refuse after shutdown.
  - The lyric provider result cache moved out of `LyricsLookup` into `LyricsResultCache`.
  - `JavaSoundBackend` and `MusicScreen` were left whole: neither has a separate concern worth splitting out.
  - `scripts/verify.ps1 -Clean -DesktopIntegration` passed 628 tests (6 skipped) and all eight smoke modes passed. A manual play session covered import, restore, reset, switching the output device during playback, and a lyrics lookup before and after Clear Cache.

Release notes now live in `docs/releases`. Earlier review records are in `docs/reviews`.

### Review items 121 and 100

- **[121](reviews/FINDINGS-113-121.md), 16-bit dither.** Fixed as described above.
- **[100](reviews/FINDINGS-86-108.md), PiP process audio.** Still a platform limitation, now documented in the help text and user guide.

## v3.3.1: Up Next library search, bulk remove and Beveled bars

The queue in-game check now ticks two of three queued songs and confirms Remove takes both; it switches Up Next to All Songs, searches, confirms the results match and double-clicks one into the queue. The HUD check captures every bar layout at 64 bars with the Beveled finish after the spectrum settles. Zoomed captures showed Classic plain bars and the sideways Artwork spectrum alternating between striped and flat, because side edges were drawn only on bars rounded to 3 pixels. After the fix all bars in a row share one decision. Circular and Rounded Bars were already uniform. Both checks passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont.

Release: clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed on the release commit (**623 tests: 617 passed, six skips**). The first hosted release run failed only on Windows, in `ProcessRunnerTest.passesUnicodeAndShellMetacharactersAsOneLiteralArgument` with an IOException from `ProcessRunner.run`, most likely its 10-second timeout around a cold `powershell.exe` start; the push build of the same commit had passed. A rerun passed and published. The test now allows 60 seconds. Publication: all four v3.3.1 assets are downloadable; the downloaded JAR reports version 3.3.1, SHA-256 `93987a27c336e386c164b01b1a4efb742951482a13d092173bbf920e80f564d6`.

## v3.3.0: Circular HUD rotation, settings reorganization and check repairs

The default and reset in-game checks had drifted from the menus and were repaired. The default check configures ffmpeg/ffprobe from `-PdownloadToolsDirectory` (the private collection is Opus-only), reads playback through Up Next and waits for the player's progress bar to become seekable before dragging it. That wait fixes the old intermittent "Seek release must issue exactly one command" failure, which happened when the first track was still starting. The reset check copies tools from the managed bundle and requires installed tools and music folders to survive Reset to Defaults.

The Lyrics HUD controls (next line, width, background opacity, line colors) moved from their own menu into Settings → Lyrics, below Lyrics in HUD. The default check confirms they hide when lyrics are off.

**Show HUD in Menus** now has an in-game check. Put an empty `check-hud-menus` file in a fresh PiP folder and run `runSmokeClient -Psmoke -PpipSmoke -PpipFolder=<folder> "-PpipWorldSource=run/saves/<world>" -PdownloadToolsDirectory=<tools>`. The client opens a disposable copy of the world and plays music. It confirms the HUD is hidden over the pause menu with the option off, shown over the pause menu with it on, hidden over the DarkMusic player and Settings and hidden while F1 hides the HUD. Each screen stays open for several frames so the drawing runs too. The copy skips `session.lock`, so it works while another client has the source world open. Removing the Settings preview exclusion makes the check fail, as intended. It checks the decision through `HudLayer` and does not compare pixels.

The HUD check (`-PpreviewSmoke`) now also covers Circular HUD rotation: the Settings rows, the shared speed row, the shared angle advancing while music plays, cycling the direction, and two private captures taken 0.3 s apart with Beveled finishes and the ring half filled. Measured on those captures, the artwork turned clockwise and the progress ring 30° counterclockwise. Bevel lighting is computed in screen space, so the shine stays put while the rings turn. Unit tests cover defaults, saved values and repair of invalid values.

The waveform preview fix is covered as well. With waveform progress on, the Settings preview must request the waveform on General, not only in the Progress section; the old behavior fails the check. This checks the request rather than a freshly launched client, because stopping playback keeps the last track loaded.

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **623 tests: 617 passed, six optional/environment skips**, zero failures/errors. Running Gradle from Git Bash on Windows may pick an older Java; set `JAVA_HOME` to a JDK 25 first. All runtime checks below were run at fullscreen 1920×1080, GUI scale 2, with SmoothFont:
- default: 7 of 8 runs passed. The one failure was "Unreachable control: missing". With the improved message, a later failure showed the cause: the first-use online-lyrics consent prompt (a ConfirmationScreen) opened over the player whenever the run folder had no saved answer. The check now records the answer up front with online lookup off, and passes when started from an unanswered consent.
- reset, cleanup, preview + HUD, queue, advanced and lyrics: all passed.
- PiP setup (`check-setup`): passed.
- Show HUD in Menus: passed.

Release: clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed again on the release commit (**623 tests: 617 passed, six skips**). Publication: all four v3.3.0 assets are downloadable from the release; the downloaded JAR reports version 3.3.0, contains `CircularRotation` and no longer contains the separate Lyrics HUD menu, SHA-256 `aeab9f13b06929fc5a994006beadd0a9255515b26bfa24dbc4c39eec6982e15e`. Documentation screenshots are to be supplied after release.

## v3.2.0: menu and HUD refresh

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **621 tests: 615 passed, six optional/environment skips**, zero failures/errors, using the private collection and existing managed tools. All four release archives were built; the local JAR reports version 3.2.0.

Smoke checks at fullscreen 1920×1080, GUI scale 2, with SmoothFont: cleanup (English and Traditional Chinese, enforcing that no button label is shortened) and lyrics passed on the release commit; advanced (graphic EQ) and queue passed on the preceding menu commit. The lyrics run captured the Circular and Artwork HUDs and the lyrics HUD with the new text plates and Artwork caption. The default and preview smoke runs stop on collection items that fail on v3.1.2 as well (an unplayable track and an Ogg file served as `.mp3`), so the HUD smoke client, which captures the volume, speed and download notices, did not run. **Show HUD in Menus** has no automated in-game check; the user approved the release after manual review. Documentation screenshots in `docs/images` predate this release and are to be replaced.

Publication: all four v3.2.0 assets are downloadable from the release; the downloaded JAR reports version 3.2.0 and contains the new HUD classes, SHA-256 `d64543760c46ad71faa54c45c20145c036aef171a76b36cde0aa254cdc9d80bd`.

## v3.1.2: Cubes Without Borders conflict and exclusive-fullscreen warning

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **609 tests: 603 passed, six optional/environment skips**, zero failures/errors, using the private collection and existing managed tools. Localization, menu layout and packaged-JAR checks passed; the JAR declares `"conflicts": {"cwb": "*"}`. The PiP package checksum differs from v3.1.1, so existing PiP users install the matching package through the PiP menu.

The tearing diagnosis comes from the user's manual tests on one system (Radeon RX 9060 XT, 144 Hz, Windows 11): tearing with Exclusive Fullscreen or Cubes Without Borders' fullscreen modes while capturing, none with Minecraft's own non-exclusive fullscreen (with or without Sodium) or windowed mode. The warning was reviewed in game. Native playback/HUD smoke runs were not repeated; the change affects only the PiP settings warning and mod metadata.

Publication: all four v3.1.2 assets are downloadable from the release; the downloaded JAR reports version 3.1.2 with the `cwb` conflict, SHA-256 `1d5af066980bc91d6ccd2ab62bcb3f2d5d167dbceb89381c00f70a2e90385753`.

## v3.1.1: PiP helper outside the base JAR

The optional PiP ZIP now contains both the Windows helper and its runtime. The base JAR carries only a schema-2 installation manifest. Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **609 tests: 603 passed, six optional/environment skips**, zero failures/errors. Regression tests cover native-payload exclusion, combined extraction, changed-file preservation, traversal/normalized-path collisions, missing helper rejection, explicit legacy-layout installation, cancellation and restart reuse. Real helper pacer/reliability self-tests and the Windows graphics/setup integration check passed.

An isolated Minecraft PiP setup smoke passed at fullscreen 1920x1080, GUI scale 2, SmoothFont. It checked installation/access cancellation, verified local-package reuse, enable/disable and separate audio consent without enumerating windows or starting capture. Recursive inspection of the main JAR and nested libraries found no EXE/DLL/SO/dylib payloads. All four local archives passed CRC checks; the schema-2 manifest matched the combined package size and SHA-256. The local v3.1.1 main JAR is 3,954,986 bytes; the optional package is 76,206,540 bytes. IntelliJ formatting, workflow lint, whitespace and English/Traditional Chinese key parity passed.

The previous v3.1.0 publication is unchanged. Hosted release CI and live published-package verification are recorded below. Platform moderation acceptance has not been tested. Capture pacing, protocols, DSP, device requirements and consent settings are unchanged. Evidence is under ignored `.local-tools/verification/pip-external-helper/`. Release preparation repeated the clean full check (609 tests, 603 passed/six skipped), package audit and isolated in-game setup smoke successfully for v3.1.1; release evidence is under `.local-tools/verification/release-3.1.1/`.

Publication verification: [v3.1.1 release workflow 40303229337](https://github.com/DarkLian/DarkMusic/actions/runs/40303229337) passed builds/tests on Windows, Linux and macOS. All four actual published assets matched GitHub SHA-256 digests and passed archive/package checks. Recursive inspection of the published main JAR and nested libraries found no native binaries; its schema-2 manifest matched the combined helper/runtime ZIP. The production-transport installation test passed separately (one test, zero skips/failures), retrieving and verifying the published package and probing the installed helper on Windows without granting capture access. Published sizes: main JAR 3,954,987 bytes; optional ZIP 76,206,462 bytes. Published package SHA-256: `b06fdc789d8341d0d831dd481b07e159efc71937c26f102072bb254e1310feed`. Build-platform output differs from the local archive; the actual published manifest/package were tested together.

## v3.1.0 release validation

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **607 tests: 601 passed, six skipped**, zero failures/errors, using private-real-audio-derived fixtures and existing managed tools. The skipped cases are optional live tool installation, live LRCLIB, live published PiP installation, two unavailable Windows symlink fixtures and POSIX permissions on Windows. The published PiP installation check is run separately after publication.

All four local archives passed CRC, version, embedded helper/dependency hashes, dependency separation, source/license notices, private-media/smoke exclusions and 1,624-key localization parity checks. The mod JAR is 4,055,409 bytes; the optional dependency ZIP is unchanged from v3.0.4 (SHA-256 `55eccdc65bc1242f7d2fae0547ab7a35ab3a435a50a831d239653f932a2f5273`). Detailed behavioral tests, measurements and limitations are recorded in [the review report](reviews/FINDINGS-128-148.md). Evidence: ignored `.local-tools/verification/release-3.1.0/`.

English and Traditional Chinese native player/lyrics/HUD smoke checks passed at fullscreen 1920x1080, configured/effective GUI scale 2, with SmoothFont and actual device playback. Online lyric access was disabled for deterministic checks. No new public screenshots were taken.

Publication verification: [release workflow 40296945963](https://github.com/DarkLian/DarkMusic/actions/runs/40296945963) passed Windows, Linux and macOS builds/tests. All four actual GitHub release assets were downloaded, matched against their published SHA-256 digests and checked for package integrity. The published mod JAR is 4,055,313 bytes (platform build packaging differs from the local JAR). The production-transport PiP installation test passed separately: one test, zero skips/failures, using the published JAR and its matching dependency ZIP in a disposable installation. This verifies dependency retrieval, validation, installation and graphics probing; it does not grant capture consent or test additional live applications.

## PiP Chrome retest after the FreeSync correction

October 26, 2026, Windows 11 build 26300, Ryzen 7 7800X3D, Radeon RX 9060 XT, 144 Hz display. The user reported fixing the system-wide AMD FreeSync/Adaptive Sync problem that had capped frame rates across applications; the earlier PiP measurements were taken before that correction.

The source was a disposable Chrome window (throwaway profile) drawing real artwork from the private collection, shifted by one pixel every frame, with its own paint counter. Chrome painted 143-144 frames per second throughout, including while Minecraft covered it, so Chrome did not throttle the covered window. Minecraft ran fullscreen at 1920x1080, GUI scale 2, SmoothFont, VSync off and a 144 FPS cap, in a disposable copy of an existing development world, with PiP audio and music off. The live window state was logged during each run. The test harness previously forced exclusive fullscreen for every run; `-PsmokeBorderless` now selects borderless.

| Mode | Capture interval | Received FPS | Uploaded FPS | Minecraft FPS |
|---|---|---:|---:|---:|
| Borderless | default | 48.0 | 48.0 | 139.1 |
| Borderless | 4 ms | 144.0 | 134.9 | 139.0 |
| Exclusive | default | 47.9 | 47.9 | 139.0 |
| Exclusive | 4 ms | 142.6 | 132.9 | 139.0 |

Rates trim the first 3 and last 1 seconds of 33-47 second phases; there was no frame-rate throttling in any measured window. The default 16 ms Windows capture interval limits delivery to about 48 FPS. The experimental 4 ms interval reached about 143-144 FPS received; uploads are bounded by Minecraft's roughly 139 FPS render rate, with 320-346 frames replaced by newer ones. The earlier drop to about 66 FPS behind exclusive fullscreen was **not reproduced**; the source application, the harness forcing exclusive fullscreen and the FreeSync problem all differ from the earlier runs, and which one explained the difference is not established.

Not measured: visual tearing, CPU/RAM/GPU cost, other sources, other GPUs or long sessions. These runs used the v3.0.3 default interval and the experimental 4 ms override. Two earlier exclusive attempts were discarded when the window lost focus and minimized; the harness now restores it and clears the pause screen. Logs are under ignored `.local-tools/verification/pip-chrome-retest/`; derived media and the Chrome profile were deleted.

## v3.0.4 release validation

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **589 tests: 584 passed, five skipped**, zero failures/errors, using the private collection and existing managed tools. The four local release archives built; the dependency ZIP is byte-identical to v3.0.3. The helper self-test covers the new interval values, and the live measurements are in the next section. English and Traditional Chinese native playback/HUD smoke runs were not repeated for this release; the user-facing change is in the PiP capture helper only.

## PiP capture interval following the frame limit (v3.0.4)

The helper now requests a minimum update interval of half the frame limit's period, clamped between 1 ms and Windows' 16 ms default; the 4 ms diagnostic override remains. Same machine, Chrome source and fullscreen 1920x1080, GUI scale 2, SmoothFont, VSync off, 144 FPS Minecraft cap, with the live state confirming **exclusive** fullscreen and no throttling:

| Frame limit | Interval requested | Received FPS | Uploaded FPS | Minecraft FPS |
|---|---|---:|---:|---:|
| 144 | 3.47 ms | 142.7 | 132.9 | 139.0 |
| 60 | 8.33 ms | 60.0 | 60.0 | 139.0 |

Chrome painted about 143 frames per second in both runs, so 142.7 received is source-limited. The 60 FPS limit stayed at 60 with no replaced frames, so the frame pacer still enforces the limit. Not measured: borderless with the new interval, CPU/RAM/GPU cost, visual tearing, other Windows builds, GPUs or sources, and long sessions. The helper self-test checks the interval values for 15, 60, 144 and 480 FPS.

## v3.0.3 release validation

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **589 tests: 584 passed, five skipped**, zero failures/errors, using the private collection and existing managed tools. The four local release archives built. The change adds only a PiP settings warning for exclusive fullscreen, with English and Traditional Chinese text; localization parity and menu layout tests passed.

The warning was confirmed manually in the PiP settings screen at GUI scale 2. The underlying tearing was reproduced on one system (RX 9060 XT, 144 Hz, VSync off, 140 FPS cap, Fabric API and DarkMusic only): exclusive fullscreen tore in the inventory and PiP layout editor while capturing; borderless and windowed mode did not, and VSync did not help. The cause is not established, and other hardware, the capture helper's role and the OBS comparison were not tested. Full native playback/HUD smoke runs were not repeated for this release.

## v3.0.2 release validation

Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration` passed **589 tests: 584 passed, five skipped**, zero failures/errors, using the private collection and existing managed tools. The skips are optional live tool installation, live LRCLIB, two unavailable Windows symlink fixtures and explicit live-release PiP installation. Originals remained unchanged; verification excerpts were cleaned.

English and Traditional Chinese native lyrics/player smoke runs passed at fullscreen 1920x1080, configured/effective GUI scale 2 with SmoothFont. They exercised actual playback, pause/seek/speed, timed/plain lyrics, Up Next isolation, waveform progress, artwork fallback, rounded spectra, compact/wide layouts and independent lyric HUD placement. The generic legacy player smoke initially failed because its isolated configuration lacked tools for this Opus-only collection; the current localized harness explicitly configures the existing managed tools and passed. No blanket pass is claimed for the generic harness.

The native PiP setup smoke also passed: requirements checks, canceled installation/access/audio prompts, cached dependency verification, explicit access and revocation, without window enumeration or starting capture. Its display used the same fullscreen 1920x1080, GUI scale 2 and SmoothFont.

The four local release archives passed CRC/integrity, packaged version, helper/dependency manifest and checksum binding, runtime/application separation, notices/source archives, 1,617-key localization parity and private/smoke exclusions. Workflow lint and whitespace checks passed. Recent native WGC resilience probes are recorded in [findings 122-127](reviews/FINDINGS-122-127.md).

Hosted [Windows, macOS and Linux release CI](https://github.com/DarkLian/DarkMusic/actions/runs/40262685668) and [main CI](https://github.com/DarkLian/DarkMusic/actions/runs/40262682462) passed. All four published assets were downloaded and matched GitHub SHA-256 metadata; packaged integrity, manifests, notices, sources, localization and exclusions passed again. The released base JAR is 4,038,865 bytes; optional dependencies are 76,106,620 bytes.

The post-publication production-transport installation check passed separately (one test, zero skipped), using the actual released JAR and its matching GitHub dependency archive in a disposable fixture. It verified checksums, installation, Windows/WGC and graphics-device setup without granting capture/audio access, enumerating windows or starting capture. Published assets and the v3.0.2 tag were not modified after verification.

No post-FreeSync fullscreen 144 FPS/resource benchmark, real-device COM/write-stall audio recovery, live website bot-check or native non-Windows PiP validation was performed. PiP remains Windows-only. Optional dither remains deferred.

## v3.0.1 release validation

Clean Windows validation through `scripts/verify.ps1 -Clean -DesktopIntegration` passed **555 tests: 551 passed, four skipped**, zero failures/errors. Skips were the optional live-provider/tool-install/PiP-network checks and the linked-directory fixture because local symbolic-link creation was unavailable. Real private-collection, existing managed-tool and desktop runtime checks ran. Focused runtime checks passed after finalizing the opt-in network-test resource selection.

The v3.0.1 base JAR, source JAR, third-party source ZIP and optional Windows dependency ZIP passed integrity checks. The dependency manifest matches its archive SHA-256/size; only DarkMusic helper code remains embedded. Runtime notices, source archive contents, 1,607 matching localization keys, private-media/smoke exclusions, workflow lint and whitespace checks passed. The local base JAR is approximately 4 MB; optional dependencies approximately 76 MB. CI runtime patches may alter exact artifact sizes and checksums.

After publication, the explicit live check is `gradlew.bat test --tests '*PipRuntimeTest.publishedDependenciesInstallWithTheProductionTransport' -PdesktopIntegration -PpipRuntimeIntegration -PpipRuntimeReleaseJar=<downloaded-release.jar>`. It reads the manifest and code from the actual released JAR, uses the production download transport, installs into a disposable fixture and probes the real graphics device. It does not grant window/audio access or start capture. It is opt-in and does not run during ordinary tests. The published JAR is required because CI may use a different .NET patch from a local rebuild.

The earlier fullscreen setup UI smoke remains applicable: no production UI/capture implementation changed for release preparation. Post-FreeSync Chrome/fullscreen throughput, other Windows builds/GPUs and native non-Windows PiP remain unverified; this release makes no new performance claim.

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

## v2.8.1 safety and retry regressions

`LyricsLibraryRunTest` covers explicit library-run completion, first reliable-version application, persisted results, cooldown progress initialization, per-song network backoff without blocking later songs, exact short server waits, and 503 responses without Retry-After. `LyricsSafetyTest` covers online consent migration, automatic-caption URL restrictions, compact document compatibility, text validation, encoding, and lyric transactions. Network tests use controlled responses; they do not establish live provider availability.

`DeletionCompanionTest`, `DownloadCommitTest`, and source-index/metadata tests cover explicit companion handling, identity changes, rollback, and recoverable download commits. `ReviewPerformanceTest` and `PartialRootScopeTest` cover incremental matching, bounded cache writes, and retention limited to unreadable subtrees. Existing Up Next tests verify that no-op or non-head edits retain the preloaded next track.

Release verification uses `scripts/verify.ps1 -Clean -DesktopIntegration` with the private collection and existing managed tools, followed by both localized Minecraft lyrics smoke runs. Never infer native macOS/Linux, prolonged outage, or real inaccessible-filesystem behavior from mocks or hosted compilation.

## v2.8.2 integrity and licensing checks

`MetadataCommitSafetyTest` replaces the source immediately after final validation for rename and in-place commits, checks recovery retention, and verifies rollback preserves a later external replacement. Shared-stem fixtures verify copying lyric companions and attribution during rename. `DeletionCompanionTest` verifies shared lyrics are not offered for deletion and newly introduced sibling formats are rechecked. These filesystem policy tests use disposable non-audio documents.

`PartialRootScopeTest` saves lyric selections, offsets, language, and encoding with no library index, simulates an unreadable subtree, and verifies only positively missing healthy-scope preferences are pruned. `LyricsOwnershipTest` controls blocked caption and retry operations with latches, cancels their library owner, and verifies unrelated manual work survives. Its loopback HTTP-server test exercises the real adapter with a missing Retry-After header.

Duplicate tests require content-bound source/archive evidence and reject stale filename-only or cached provenance. Controlled decoder tests use real private-collection PCM to verify bounded empty reads and legitimate priming. Caption requests accept canonical supported providers; these checks do not establish a complete network sandbox around external-tool redirects or DNS resolution.

Packaging checks verify the custom current license, preserved CC0 legacy text, LGPL notices, and current Fabric metadata. Release checks inspect the license in both binary and source JARs and keep the third-party source bundle available. Existing historical license grants must not be relabeled or erased.

## v2.9.0 release validation

Clean Windows verification through `scripts/verify.ps1 -Clean -DesktopIntegration` passed 498 tests: 496 passed and two optional live-network/tool-install checks skipped. Existing managed tools and temporary excerpts derived from the private collection were used; original audio hashes remained unchanged and temporary excerpts were removed. English and Traditional Chinese Minecraft smoke runs passed at fullscreen 1920×1080, GUI scale 2, with SmoothFont, including real output, lyrics/commands, and compact/wide screen bounds.

The local-only missing result is unresolved until online lookup completes. Tests check this for both sidecars and embedded lyrics. A controlled caption fallback test verifies that an empty provider result stays searching until the fallback finishes, rather than publishing a premature final miss. Updated status wording scopes completed misses to the checked sources; failures, pauses, and candidate choices remain distinct. Advanced Lyrics puts Find Missing Lyrics first while preserving its online-lookup requirement, and Settings omits the redundant icon-marked song-lyrics shortcut.

Binary and source packaging checks verify version 2.9.0, license notices, language-key parity (1,482 keys), third-party source archive integrity, and absence of private audio and smoke classes. Live provider coverage, native Linux/macOS desktop behavior, external-tool redirect isolation, and untested hardware scenarios are not established by these checks.

## v2.10.0 release validation

Clean Windows release checks used scripts/verify.ps1 -Clean -DesktopIntegration through the private-collection harness: **517 tests, 515 passed, two optional live-provider/tool-install checks skipped**. Both language smoke runs completed with their pass markers and successful Gradle builds at fullscreen 1920x1080, GUI scale 2, SmoothFont. Source hashes remained unchanged and this run's excerpts were cleaned. The task-created Gradle daemon was stopped after completion because it held the PowerShell output pipe open.

Packaged version 2.10.0, 1,518 matching localization keys, source-JAR license, third-party source archive (including six nested archives), and exclusion of private audio and smoke classes were verified. Waveform progress remains off by default; spectrum opacity defaults to 100%. New settings are additive. No new dependencies or playback DSP changes. Native Linux/macOS, heavy-workload gameplay FPS, and analysis-limit recordings remain unverified.

## v3.0.1 release validation

Clean Windows checks through `scripts/verify.ps1 -Clean -DesktopIntegration` passed **555 tests: 551 passed, four skipped, zero failures/errors**. The skipped checks were the optional managed-tool installation, live LRCLIB, unavailable Windows symlink fixture, and opt-in live PiP installation. Existing managed tools and the private real collection were used. Prior optional-setup UI checks passed at fullscreen 1920x1080, GUI scale 2 with SmoothFont; this packaging release adds no new capture-performance measurement.

Windows, macOS and Linux [release CI](https://github.com/DarkLian/DarkMusic/actions/runs/40251089951) passed. All four published assets were downloaded and their SHA-256 digests matched GitHub metadata. Package CRCs, runtime/application separation, manifest binding, notices, source archives, private/smoke exclusions and 1,607 matching localization keys passed. The published base JAR is 4,009,649 bytes and optional Windows x64 dependencies are 76,242,875 bytes.

After publication, `PipRuntimeTest.publishedDependenciesInstallWithTheProductionTransport` passed separately (one test, zero skipped). It read the actual published JAR with `-PpipRuntimeReleaseJar`, downloaded the matching release archive through production transport using `-PpipRuntimeIntegration -PdesktopIntegration`, verified and installed it in a disposable JUnit directory, and passed Windows/WGC and native graphics-device checks. It did not enumerate windows, grant capture/audio access or start capture. Local evidence is under ignored `.local-tools/verification/release-3.0.1/`.

Native macOS/Linux PiP remains unsupported; CI builds do not establish native GUI/audio behavior on those systems. Post-FreeSync Chrome delivery at 144 FPS remains unverified and is recorded as the next handoff task.

## Open findings 86-108 follow-up

Windows verification through `scripts/verify.ps1 -DesktopIntegration` passed **572 tests: 567 passed, five skipped** with the private collection and existing managed tools. The first full attempt found a new cleanup-fixture clock mismatch after adding a 24-hour grace period; correcting the test clock resolved it. The five skips are optional live managed-tool installation, live LRCLIB, the two Windows symlink fixtures unavailable to this account, and explicit live-release PiP installation. The final bounded-receipt I/O handling and terminal receiver shutdown refinements then passed **19 focused cases: 17 passed, two skipped**, including real Windows/WGC and native graphics-device setup checks. Logs are under ignored `.local-tools/verification/open-findings/`.

New regressions cover portable-backup consent preservation, waveform retry backoff, metadata-edit space bounds, local control characters and symbolic links, compact lyric JSON, shared-companion journal discovery/conflict archival, codec/receiver Errors, equal-power crossfade, ratio-scaled resampler coefficients, old commit receipts, staging cleanup, publication races and active runtime leases. The 192 kHz to 44.1 kHz kernel's tested stopband response improved from about -29.7 dB to -77.1 dB; this is a coefficient measurement rather than a listening or hardware benchmark. Normal-rate paths retain 32 taps; high-ratio paths scale up to a bounded 512 taps.

IntelliJ's formatter, workflow actionlint, whitespace checks, locked .NET restore/build and English/Traditional Chinese parity (1,611 keys) passed. No Minecraft GUI smoke, native non-Windows runtime, UWP audio routing, real heap-exhaustion test or post-FreeSync 144 FPS comparison was performed in this turn. See [the per-finding disposition](reviews/FINDINGS-86-108.md). Existing full content-hash deletion safeguards are deliberately retained.


Hosted [Windows, macOS and Linux CI](https://github.com/DarkLian/DarkMusic/actions/runs/40254982912) passed for `1e46232`. A final local assembly and four-artifact package check passed archive CRCs, embedded helper/dependency checksums, notices/source archives, localization parity and private/smoke exclusions. Published v3.0.1 assets were not changed.

## PiP cleanup and recovery-status follow-up

`scripts/verify.ps1 -DesktopIntegration` passed **576 tests: 571 passed, five skipped** with the private collection and existing managed tools. Final source checks passed 19 focused cases (17 passed, two skipped), including Windows/WGC/native graphics setup. New checks cover old ZIP reclamation despite a locked legacy runtime, current/young/leased runtime retention, stale zero-byte lease removal alongside an active lease, explicit setup-contention status and cancellation, Windows native sharing violations, and visible recovery-folder counts that clear on rescan after review. Original recovery-fixture contents remain unchanged. Native sharing is exercised with a disposable Windows file handle, not an actively running legacy helper executable.

A Windows probe revealed that holding our own preflight handles prevents directory renaming. Cleanup now closes those handles before claiming the candidate. Locked/inaccessible legacy runtimes are deferred; reclaiming legacy files remains best effort. Unreferenced ZIPs must exceed the 24-hour grace independently of legacy directories. Both current and referenced active managed archives remain protected.

IntelliJ formatting, actionlint, whitespace and English/Traditional Chinese parity (1,613 keys) passed. GUI status-row checks in an actual Minecraft window, hosted/UWP audio and post-FreeSync 144 FPS capture were not performed in this follow-up. Private evidence is under `.local-tools/verification/pip-cleanup-followup/`.

## Review findings 113-121

Windows `scripts/verify.ps1 -DesktopIntegration` passed **584 tests: 579 passed, five skipped**, with zero failures/errors. The private collection and existing managed tools exercised same-format DSP handoff continuity, first-block stereo width, environment-history preservation, misleading audio extensions and unknown-container fallback. Filesystem and download regressions cover persistent watch registrations, retry backoff, bookkeeping filters, extensionless deletion, unexpected poll errors and bot-check classification. Final watcher/download-focused tests passed after the cache-publication refinement. IntelliJ formatting, actionlint, whitespace and 1,617-key English/Traditional Chinese parity passed.

The five skips remain optional live managed-tool installation, live LRCLIB, two Windows symlink fixtures unavailable to this account and explicit live-release PiP installation. No new Minecraft GUI smoke, subjective listening, gameplay performance, real bot-check website or native non-Windows audio test was performed. Dither remains an optional deferred output-policy change. See [the per-finding disposition](reviews/FINDINGS-113-121.md). Private evidence: `.local-tools/verification/findings-113-121/`.

## Review findings 122-127

Windows `scripts/verify.ps1 -DesktopIntegration` passed **587 tests: 582 passed, five skipped**, zero failures/errors. Final focused checks passed **12 tests**, including two watcher-isolation cases added after the full run. Native helper checks cover bounded audio recovery, actual pipe-name collision and the WinForms exception route. Two live WGC runs each resized a disposable native window 98 times; neither produced capture/pipe errors. The second run deliberately occupied the audio pipe and verified that video continued through final audio failure. These are resilience checks, not frame-rate measurements or Minecraft playtests. See [the detailed findings and limits](reviews/FINDINGS-122-127.md). Private evidence: `.local-tools/verification/findings-122-127/`.

## Review findings 128-148

Windows `scripts/verify.ps1 -DesktopIntegration` passed **606 tests: 600 passed, six skipped**, zero failures/errors. EN/ZH native playback/lyrics/player/HUD smoke checks passed at fullscreen 1920x1080, GUI scale 2 with SmoothFont. Real private-derived Opus fixtures verify native metadata/lyrics and tiny FFmpeg seeks. Native WGC resized through ultrawide/portrait shapes without capture/pipe errors; this does not verify Minecraft upload at those sizes. A 100k-track metadata benchmark measured four-query medians of 34.7 ms before versus 6.7 ms cached (256.1 ms construction), not gameplay FPS. IntelliJ formatting, workflow lint, whitespace and 1,624-key localization parity passed. See [per-finding dispositions and verification limits](reviews/FINDINGS-128-148.md).

An initial full attempt found an invalid new Gson/Path fixture and an existing gapless/output-route timeout. The corrected fixture and isolated routing case passed, followed by passing full runs; timing assertions were not relaxed. POSIX permissions are skipped on Windows and tested where supported. Actual slow links, NAS/large-FLAC pressure, hardware disconnection, extreme cache sizes and native non-Windows PiP remain unverified. Existing v3.0.4 post-FreeSync capture measurements remain current.


The final review-147 refinement protects refreshed cache files against stale trim candidates while keeping directory enumeration outside the result-map/publication locks. Its deterministic replacement regression and lyrics-focused suite passed; see the per-finding report for counts. The main review commit passed Windows, macOS and Linux CI: https://github.com/DarkLian/DarkMusic/actions/runs/40294665587 .
