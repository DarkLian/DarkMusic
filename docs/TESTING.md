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

On the Windows development setup, `-PdownloadIntegration` uses local `Music/yt-dlp.exe`, `Music/ffmpeg.exe`, and `Music/ffprobe.exe`. That ignored directory is a development resource, not a runtime installation path. Desktop chooser and managed-tool installation integration checks are opt-in through `-PdesktopIntegration` and `-PmanagedToolsIntegration`.

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
