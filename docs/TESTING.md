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

Review library navigation, search-independent playback queues, playlists and selection/reordering, download paging/cancellation/retries, settings persistence, lyrics, and HUD input/layout at multiple GUI scales. Performance-sensitive spectrum changes should preserve detail/effects and compare measurements on the same local fixture.

For imports, use real files from the private collection and an isolated destination. Check root/subfolder/playlist routing, filename collisions, history persistence, and byte-for-byte preservation of imported copies. On Windows, check an external file drag before and after the target gains focus, leaving the window, cancellation, and a completed drop. Normal mouse clicks in the mod must not display the import overlay. Check these interactions in both the player and playlist manager.

For metadata edits, work on temporary copies. Cover active and paused playback, position restoration, filename changes, playlist/favorite references, matching lyric sidecars, embedded artwork preservation, and supported tag containers. Delete temporary audio copies after the run.

## Screenshots

Capture documentation fullscreen at configured and effective Minecraft GUI scale 3, with Enable Lyrics turned off. Disable forced Unicode-font scaling if it rounds the effective GUI scale up. Use the private real collection through temporary copies, and keep original captures without cropping or resizing. Store unpublished captures under ignored `docs/images_private`, and promote only reviewed public screenshots to `docs/images`.

## Style and publication

Use IntelliJ IDEA's built-in formatter and the project `.editorconfig`. English interface prose lives in `src/client/resources/assets/darkmusic/lang/en_us.json`, with Traditional Chinese in `zh_tw.json` beside it. Keep both catalogs aligned and preserve formatting placeholders. Localization checks compare keys, placeholders, and line breaks, exercise translated components, and verify the packaged catalog.

Publish the main JAR, source JAR, and third-party source ZIP together. Keep license notices and source-archive references current. The tag must match `version` in `gradle.properties`; the release workflow reads `docs/RELEASE-<version>.md`.
