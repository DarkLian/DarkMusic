# Testing and documentation captures

All audio checks use the same private real music collection as gameplay tests
and documentation screenshots. The default is `run/config/darkmusic/music`.
Override it with `-PmusicDirectory=/path/to/music` or `DARKMUSIC_TEST_MUSIC`.
Original files are opened read-only. No music or artwork is bundled, committed,
or uploaded. Temporary excerpts, format conversions, and artwork copies derive
from that collection and stay in test-managed temporary directories.

## Local release validation

Use Java 25:

```powershell
./gradlew.bat build -PrequireMusic -PdownloadIntegration
./gradlew.bat runSmokeClient -Psmoke
./gradlew.bat runSmokeClient -Psmoke -PcaptureMusic
```

The equivalent Unix command is `./gradlew`. `-PrequireMusic` fails when the real
collection is unavailable, preventing a skipped audio suite from being mistaken
for full release validation. The downloader integration checks additionally use
the optional development executables in ignored `Music/`.

The 49 automated checks cover real-song decoding and tags, embedded artwork,
read-only files, PCM gain/rate conversion, FFT comparison with an independent
Fourier calculation on actual music, attack/decay, pause/seek/reset, device-clock
synchronization, library discovery, playlists, subprocess trees/timeouts, queue
failures/cancellation, duplicate history, and release-package isolation. Real
music excerpts are converted to PCM16 WAV/FLAC and OGG Vorbis for format coverage.
Downloader tests serve a real music excerpt only over loopback, then exercise
actual yt-dlp/FFmpeg/FFprobe and immediate library discovery. They do not upload
audio to an external site. Public YouTube/Bilibili checks are metadata-only.

The ordinary in-game session uses the real collection in `build/smoke-run` and
checks GUI scales 2, 3, and 6, shortcuts, playlist selection/editing, downloader
navigation, independent visualizer toggles, HUD dragging, and Escape behavior.
The capture session uses separate settings/playlists in `build/capture-run`,
samples playback starts for all 33 current songs, and tests seeking, pause/resume,
speed, connected dragging/cancellation/autoscrolling, filtered bulk editing,
artwork, and visualizer pause/seek/resume/stop behavior. Sampling a song's start
is not an end-to-end audition. Full-modpack and world-transition behavior is not
exhaustively covered by these title-screen sessions.

## Hosted CI

`./gradlew build` compiles and runs the tests on GitHub. Hosted runners do not
have the private collection, so audio-dependent checks are explicitly reported
as skipped. The current no-music run has 22 passing checks and 27 skipped audio
checks; local validation runs all 49 without skips. No dummy media replaces the
private collection, and an all-green hosted build does not claim audio coverage.

## Screenshots and code style

Captures require fullscreen, a monitor-attached window, and both configured and
effective Minecraft GUI scale 3. Current captures are original 2560×1440 images,
without cropping/resizing, under ignored `docs/images_private/screenshots/` with
prefix `100-test-3x-`. All twelve capture callbacks must finish before success.
Inspect captures before copying only the images used by public documentation
into `docs/images`. Use new versioned filenames to avoid stale image caches.
Only images used in public documentation belong in docs/images.

Use IntelliJ IDEA's built-in formatter and the current project's code style for
source changes. Java uses four spaces and JSON two, recorded in `.editorconfig`.