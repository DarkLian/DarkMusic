# DarkMusic

A client-side music player for Minecraft. Browse your own library, build playlists, download audio in-game, and display an audio-reactive HUD.

![DarkMusic music library and playback controls](docs/images/player.png)

## Install

DarkMusic **v2.0.0** targets **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5 or newer**, and **Fabric API for Minecraft 26.2**.

1. Download `darkmusic-2.0.0.jar` from [GitHub Releases](https://github.com/DarkLian/DarkMusic/releases/latest).
2. Put it and Fabric API in your instance's `mods` folder.
3. Start Minecraft and press **B** to open DarkMusic.
4. Open **Settings → Choose Music Folder**, or add songs to `config/darkmusic/music` inside the instance.

Install DarkMusic on the client. Servers do not need it. Windows, Linux, and macOS are supported; external audio formats and downloading need compatible tools for the operating system.

## Features

- **Library:** browse All Tracks, nested folders, playlists, artists, and albums. Search titles, artists, albums, and paths.
- **Playback:** seek, shuffle, repeat, mute, and change speed from 0.5× to 3.0×. Jump to Playing returns to the collection where playback started. Search does not narrow the playback queue.
- **Playlists:** bulk-select songs, reorder connected selections by dragging, and manage playlist entries without deleting audio.
- **Downloads:** analyze a video or collection URL, select entries or a range, preview audio, jump between available preview pages, and manage parallel downloads and retries.
- **HUD:** classic and circular layouts, album artwork or a central icon, progress, scrolling titles, optional lyrics, and a spectrum driven by decoded audio.
- **Visuals:** bars, waves, spikes, gradients, beveled finishes, glow, trails, peak markers, and quality presets.
- **Audio:** profiles, loudness normalization, a 10-band equalizer, bass, stereo width, compression, reverb, limiting, gapless playback, crossfade, and game-audio balance.
- **Lyrics:** synchronized local `.lrc` files and supported embedded tags, plus available source captions during downloads.

See the [User Guide](docs/USER_GUIDE.md) for controls, setup, and limitations.

## Download tools

In **Downloads → Tools**, choose **Install / Update Tools** for managed setup. DarkMusic uses yt-dlp, FFmpeg/FFprobe, and Deno. Existing installations can be selected under **Advanced Paths**.

Tools are installed separately from the mod. The managed installer supports Windows x64 and Linux/macOS x64 or ARM64. Windows ARM64 needs compatible externally installed tools. Website support and caption availability depend on yt-dlp and the source website. Save content you have permission to download.

## Commands and shortcuts

| Action | Default |
|---|---|
| Open music player | B |
| Play / pause | Backslash |
| Previous / next song | [ / ] |
| Decrease / increase volume | Down / Up arrow |
| Decrease / increase speed | Left / Right arrow |
| Reset speed to 1.0× | Numpad 0 |
| Mute / unmute | M |

Change shortcuts in **Options → Controls → Key Binds → DarkMusic**.

`/da` or `/darkmusic` opens the player. Add `gui` to edit HUD layout, or `help` for links and tips. These commands run locally.

## Build and contribute

Use JDK 25 and the included Gradle wrapper:

```sh
./gradlew build
```

Windows: `gradlew.bat build`. Output files are in `build/libs`. See [Development and verification](docs/TESTING.md) for private-audio and runtime workflows.

Interface wording is in [en_us.json](src/client/resources/assets/darkmusic/lang/en_us.json). Preserve translation keys and formatting placeholders when editing text. English is the bundled interface language.

Report problems through [GitHub Issues](https://github.com/DarkLian/DarkMusic/issues). Include your Minecraft/mod versions, operating system, reproduction steps, and relevant logs. Remove account details and private paths before sharing logs.

## License

DarkMusic is released under [CC0 1.0](LICENSE). Bundled audio and metadata libraries have their own licenses; see [Third-party notices](src/main/resources/THIRD-PARTY-NOTICES.md). Corresponding third-party source archives are provided with each release. External download tools retain their publishers' licenses. Music files are not included.
