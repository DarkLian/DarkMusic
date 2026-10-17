# DarkMusic v1.0.0

> Historical notes for 1.0.0. Menus and defaults below describe that release. See the [current guide](USER_GUIDE.md) and [v1.0.2 notes](RELEASE-1.0.2.md) for current behavior.

Play your music inside Minecraft, with playlists, in-game downloads, and a real-time audio-reactive visualizer.

- Browse MP3, OGG Vorbis, PCM WAV, and FLAC files, with search, metadata, and embedded artwork.
- Create playlists with bulk selection and animated drag reordering. Control repeat, shuffle, volume, seeking, and playback speed from 0.5x to 3.0x.
- Keep listening across menus and worlds. Position and scale the optional now-playing HUD, including cover artwork.
- Preview YouTube, Bilibili, and other yt-dlp-supported URLs. Choose individual entries, the first N, a range, or a collection before downloading. Completed MP3s retain available metadata/artwork and appear in the selected library without restarting.
- View actual PCM-driven FFT spectrum bars in the player and HUD, with independent toggles, configurable bands, sensitivity, smoothing, size, and HUD placement. Analysis runs on the audio worker and follows pause, seek, speed changes, and stop.
- Customize playback, speed, and volume key binds through Minecraft Controls. Default arrow keys select tracks and adjust volume.

## Install

Requires Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API for **26.2**, and **Java 25+**.

Place **darkmusic-1.0.0.jar** in the instance's `mods` folder alongside Fabric API. Open **Music** on the title screen or press **M** during gameplay.

Downloads require separately configured [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases), [FFmpeg and FFprobe](https://ffmpeg.org/download.html). YouTube also needs **Deno 2.3+** or **Node 22+**; see [yt-dlp setup](https://github.com/yt-dlp/yt-dlp/wiki/EJS). Tools are not bundled or automatically installed. Download only content you are authorized to save.

See the [illustrated user guide](https://github.com/DarkLian/DarkMusic/blob/v1.0.0/docs/USER_GUIDE.md).

The sources JAR and third-party sources ZIP are source archives, not installable mods. Include the third-party source bundle and notices when redistributing builds. Personal music, test media, and development executables are not included.

## Validation

The automated suite covers decoding, metadata/artwork, playback, FFT timing and independent Fourier comparison, library storage, playlists, subprocess cancellation, downloading/conversion, and package isolation. Local audio checks require the private real music collection; hosted CI explicitly skips those checks when the collection is unavailable. See [testing instructions](https://github.com/DarkLian/DarkMusic/blob/v1.0.0/docs/TESTING.md).
