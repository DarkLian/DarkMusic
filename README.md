# DarkMusic

A client-side music player for Minecraft. Browse your own library, build playlists, download audio in-game, and display an audio-reactive HUD.

![DarkMusic music library and playback controls](docs/images/player.png)

## Install

DarkMusic **v2.8.2** targets **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5 or newer**, and **Fabric API for Minecraft 26.2**.

1. Download `darkmusic-2.8.2.jar` from [GitHub Releases](https://github.com/DarkLian/DarkMusic/releases/latest).
2. Put it and Fabric API in your instance's `mods` folder.
3. Start Minecraft and press **B** to open DarkMusic.
4. Open **Settings → General → Music Folders**, or add songs to `config/darkmusic/music` inside the instance.

Install DarkMusic on the client. Servers do not need it. Windows, Linux, and macOS are supported; external audio formats and downloading need compatible tools for the operating system.

## Updates

Update notifications are enabled by default and appear once per launch after joining a world or server. Click **Update** to approve a download. **Settings → General → Check for Updates** also checks manually, including when notifications are off. Close Minecraft after the download finishes to install it; your settings, library, playlists, and assets are preserved. If installation fails, the next launch reports it and offers the installation log.

## Features

- **Library:** scan multiple music folders, choose a default save folder, and browse All Tracks, nested folders, playlists, artists, and albums. Search titles, artists, albums, and paths.
- **File management:** Open Folder follows the selected library folder. Use Manage Audio Files to review and delete selected files, individually or in bulk, with Move to Trash where supported and separate confirmation for permanent deletion. Cancel Remaining Files keeps pending items on disk.
- **Smart Cleanup:** discover possible duplicates across your library, expand related-file groups, compare artwork and file details, and listen with Play/Pause A and B. Keep both, ignore a match, or explicitly review one file for deletion.
- **Import:** drag audio files into the player or playlist manager to copy them into your library. Import into the selected folder or playlist, and review results through Import History beside Open Folder.
- **Listening history:** open the clock icon beside Manage Playlists for Recently Played, Most Played, play counts, and listening time. History stays in order while you play from it.
- **Backup and restore:** export settings, playlists, key bindings, and listening history to a portable ZIP. Preview and choose what to restore; audio files are never included or changed.
- **Audio output:** select an available playback device in Audio Options, or use System Default.
- **Favorites:** mark songs with the heart button and find them in the built-in Favorite Music playlist.
- **Metadata:** edit the selected song's title, artist, album, and filename using the pencil button. Open or copy its source link when available. Tags are saved to the file, and the filename extension stays protected.
- **Playback:** seek in five-second steps, shuffle, repeat, mute, and change speed from 0.5× to 3.0×. The player follows track changes automatically while you are not searching. Jump to Playing returns to the collection where playback started. Search does not narrow the playback queue.
- **Playlists:** bulk-select songs and reorder connected selections by dragging. Completed folder scans clean up confirmed missing entries, including favorites. Unavailable folders retain their entries while other folders finish scanning independently.
- **Up Next:** open Song Actions for the selected song to play it next or append it to a temporary queue. Reorder or remove individual occurrences in Up Next, then continue the original collection. Playlists stay unchanged.
- **Downloads:** analyze a video or collection URL, select entries or a range, preview audio, jump between available preview pages, and manage parallel downloads and retries.
- **Existing music awareness:** download previews identify known source videos across registered folders. Inspect existing copies, skip matching output formats, or choose Download Anyway.
- **Download diagnostics:** open Error Details after failed analysis or for a failed queue item. See the operation, tool, likely explanation, and relevant next steps; copy a bounded report without full URLs, local paths, or raw tool output.
- **Navigation:** use visible Back buttons in secondary menus, or press Escape.
- **HUD:** Classic, Circular, Artwork, and Spectrum Only layouts with optional progress and a spectrum driven by decoded audio. Artwork mode preserves image proportions and supports outward horizontal spectra beside the image.
- **Visuals:** bars, waves, spikes, gradient presets, beveled finishes, glow, trails, peak markers, and frequency smoothing. Customize menu colors and HUD backgrounds using a visual color spectrum, RGB sliders, or hex values.
- **Audio Options:** profiles, loudness normalization, graphic or parametric EQ, dynamic EQ, de-essing, tone controls, crossfeed, compression, algorithmic or convolution reverb, and optional true-peak protection. Pitch-preserving speed, gapless playback, crossfade, and game-audio balance remain available.
- **Sound Environment:** 23 choices, including Off, Car Cabin, Stairwell, and Orbital Echo, with independent intensity, room/tone controls, optional textures and ambience, and saved custom presets. Combine environments with Audio Options.
- **Audio Analyzer:** device-clock stage measurements, spectra, waveforms, EQ response, stereo and dynamics meters, plus temporary A/B comparison and level matching.
- **Lyrics:** find synchronized or plain lyrics for existing songs through LRCLIB, keep local sidecars and embedded text, choose alternate matches, and adjust timing per song. Background lookup caches results and retries temporary failures. Move and resize the lyrics HUD separately in every layout.

## Build your playlists

Create playlists, bulk-select songs, and drag connected selections to reorder them. Keep favorites one heart-click away.

![Playlist manager with bulk selection and drag reordering](docs/images/playlists.png)

## Review possible duplicates

Open **Manage Audio Files → Smart Cleanup** to find related recordings and expand each group to review individual matches. Compare both files, pause and resume previews, and decide what to keep. Deletion always uses a separate file review.

![Smart Cleanup groups related files for manual review](docs/images/smart-cleanup.png)

## Your HUD in-game

A customized Classic HUD, placed on the middle-left. Move and resize it in Edit HUD Layout.

![Classic Now-Playing HUD during gameplay](docs/images/classic-hud.png)

## Customize your visuals

Preview HUD layouts, choose a spectrum style, and pick a gradient or your own colors.

![HUD and spectrum settings with live artwork preview](docs/images/settings-2.3.5.png)

## Find lyrics for your collection

Enable lyrics in **Settings → Lyrics**, then approve online lookup to find lyrics for the song you play. **Find Missing Lyrics** checks your collection only when you start it. The first reliable version applies automatically, and alternatives stay available. Lyrics language is independent of Minecraft’s interface language.

Move the lyrics display separately in Edit HUD Layout, customize its width and colors, and adjust a song’s timing without rewriting its lyric file. Online lookup requires consent; whole-library lookup is an explicit action in Advanced Lyrics. Song-specific failures retry separately, so later songs can continue. Provider-requested waits remain respected.

## Shape your sound

Open Sound Environment from Settings → Audio. Pick an environment directly and adjust its intensity independently of Audio Options. The picker stays open so you can compare different sounds.

![Sound Environment selection](docs/images/environment-picker.png)

Open the Audio Analyzer from Audio Options to inspect live playback and compare processing modes.

![Live Audio Analyzer](docs/images/analyzer.png)

See the [User Guide](docs/USER_GUIDE.md) for controls, setup, and limitations.

## Download music

Paste a video or collection URL, analyze it, and choose entries or a range to download. Preview songs before adding them to the queue.

![Downloads with collection preview and page navigation](docs/images/downloads.png)

In **Downloads → Tools**, choose **Install / Update Tools** for managed setup. DarkMusic uses yt-dlp, FFmpeg/FFprobe, and Deno. Existing installations can be selected under **Advanced Paths**.

Tools are installed separately from the mod. The managed installer supports Windows x64 and Linux/macOS x64 or ARM64. Windows ARM64 needs compatible externally installed tools. Website support and caption availability depend on yt-dlp and the source website. Save content you have permission to download.

## Commands and shortcuts

| Action | Default |
|---|---|
| Open music player | B |
| Play / pause | Backslash |
| Previous / next song | [ / ] |
| Decrease / increase volume | Down / Up arrow |
| Decrease / increase speed | − / + (minus / equals key) |
| Back / forward five seconds | Left / Right arrow |
| Stop | P |
| Jump to Playing | O |
| Reset speed to 1.0× | Numpad 0 |
| Mute / unmute | M |

Space toggles Play/Pause inside the player. Focused controls retain their normal keyboard navigation and activation; text fields remain available for typing.

Change shortcuts in **Options → Controls → Key Binds → DarkMusic**.

`/da` or `/darkmusic` opens the player. Add `gui` to edit HUD layout, or `help` for links and tips. These commands run locally.

## Build and contribute

Use JDK 25 and the included Gradle wrapper:

```sh
./gradlew build
```

Windows: `gradlew.bat build`. Output files are in `build/libs`. See [Development and verification](docs/TESTING.md) for private-audio and runtime workflows.

Interface wording is in [en_us.json](src/client/resources/assets/darkmusic/lang/en_us.json). Preserve translation keys and formatting placeholders when editing text. English and Traditional Chinese (Taiwan) are bundled. Select your language in Minecraft's Language menu; the Traditional Chinese catalog is [zh_tw.json](src/client/resources/assets/darkmusic/lang/zh_tw.json).

Report problems through [GitHub Issues](https://github.com/DarkLian/DarkMusic/issues). Include your Minecraft/mod versions, operating system, reproduction steps, and relevant logs. Remove account details and private paths before sharing logs.

## License

DarkMusic is free of charge and source-available. The [DarkMusic License & Permissions](LICENSE) reserves rights in new DarkLian contributions first published from v2.8.2, while allowing private modifications and unmodified official releases in modpacks under its conditions. Standalone redistribution and distributed modified versions containing that protected material require written permission.

Previously released CC0 portions remain under their original dedication, including when reused in newer versions; the new terms do not revoke those rights. Existing icon notices and third-party licenses remain effective. See [Third-party notices](src/main/resources/THIRD-PARTY-NOTICES.md); corresponding third-party source archives accompany each release. External tools, music, and artwork retain their owners' rights.
