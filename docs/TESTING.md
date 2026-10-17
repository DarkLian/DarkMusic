# Testing and documentation captures

Use Java 25. Audio checks, playtests, and screenshots use the private real collection at `run/config/darkmusic/music`. Override with `-PmusicDirectory=/path/to/music` or `DARKMUSIC_TEST_MUSIC`. Originals are read-only, never committed/uploaded. Temporary excerpts, conversions, and copies derive from that collection and are removed after testing. Do not substitute dummy media.

## Local release verification

```powershell
./gradlew.bat build compileSmokeJava -Psmoke -PrequireMusic -PdownloadIntegration -PdesktopIntegration -PmanagedToolsIntegration
./gradlew.bat runSmokeClient -Psmoke
./gradlew.bat runSmokeClient -Psmoke -PpreviewSmoke
./gradlew.bat runSmokeClient -Psmoke -PresetSmoke
./gradlew.bat runSmokeClient -Psmoke -PresetSmoke -PverifyHudRestart
./gradlew.bat runSmokeClient -Psmoke -PcaptureMusic
```

Use `./gradlew` on Linux/macOS. Desktop checks need an interactive desktop. Do not run separate GUI automation sessions simultaneously.

`-PrequireMusic` fails without private audio. Downloader integration uses ignored development executables in `Music/`. Managed-tool integration explicitly downloads verified assets into ignored build output; it never silently installs tools in a user's instance.

Coverage includes real-song decoding/tags/artwork and integrity; PCM gain/rate conversion and 24-bit FLAC; independent FFT comparison, smoothing/synchronization, pause/seek/track changes; device fallback and worker parking; library/cache recovery, playlists/reordering; subprocess trees/timeouts, tools, downloads/conversion, duplicate history/cancellation; native keys, localization, and artifact isolation.

Reset checks cover fixed defaults, safe record/tool cleanup, media hashes, preserved playlists/external tools, locks, and download cancellation releasing workspace ownership. Artifact checks reject gameplay-network dependencies and Minecraft options-save/broadcast calls. Native reset changes only DarkMusic's own `options.txt` entries.

Downloader integration serves private audio only over loopback and exercises actual yt-dlp/FFmpeg/FFprobe. Audio is not uploaded to external services. Public-site checks are metadata-only.

## Development client sessions

- `build/smoke-run`: player/playlists/settings, real-device playback, seeking, shortcuts, combined HUD movement/scaling, compact layouts, and GUI scales 2/3/6.
- `build/preview-run`: URL re-entry/analysis safety, search/selection, temporary audition, tool behavior, long-text panning, artwork resolution, shortcut feedback, and combined HUD orientations/combinations.
- `build/reset-run`: isolated configuration and **copies** of real music. Confirms/cancels reset and playlist deletion, checks tool/history cleanup and default-folder rescan, then verifies defaults/native keys after a separate restart. Run reset before restart verification; remove only its byte-verified copies afterward.
- `build/capture-run`: current public screenshots using the real collection and embedded artwork.

These are title-screen development sessions. They do not exhaustively verify full modpacks, multiplayer/world transitions, every site, physical device disconnection, or Linux/macOS runtime integration. Sampling playback is not a full audition of every song.

## Hosted CI

`./gradlew build` compiles/tests on GitHub. Hosted runners lack private audio, desktop access, and development tools, so dependent checks explicitly skip. A green hosted build does not claim audio coverage. Counts vary with flags; inspect reports rather than relying on old counts.

Release automation checks the tag/version match, main-branch ancestry, and build/tests before publishing the mod, sources, and third-party source bundle.

## Screenshots and code style

Documentation captures require fullscreen and **configured and effective GUI scale 3**, without cropping/resizing. Keep originals in ignored `docs/images_private`, inspect them, then promote only public/Markdown images into `docs/images`. Use versioned filenames; remove obsolete captures after UI changes.

Use IntelliJ IDEA's built-in Reformat Code dialog with **Code cleanup** enabled and the project's style. Java uses four spaces and JSON two, recorded in `.editorconfig`. Review cleanup for behavioral changes, especially defaults, localization, and file/subprocess safety.
