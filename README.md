# DarkMusic

<img src="src/main/resources/assets/darkmusic/icon.png" alt="DarkMusic logo: a purple pixel-style musical note on a dark background" width="160" height="160">

[![Build](https://github.com/DarkLian/DarkMusic/actions/workflows/build.yml/badge.svg)](https://github.com/DarkLian/DarkMusic/actions/workflows/build.yml)
[Download the latest release](https://github.com/DarkLian/DarkMusic/releases/latest)
· [Source code](https://github.com/DarkLian/DarkMusic)

**Your music, inside Minecraft.** Browse your own collection, make playlists,
and keep listening as you explore, build, or take a break in the menus.

![DarkMusic's in-game music player showing a music library and playback controls](docs/images/player-1.0.1-3x.png)

*Captured in fullscreen at Minecraft GUI scale 3 using a real music collection
and its embedded artwork.*

- **Play your collection:** MP3, OGG Vorbis, PCM WAV, and FLAC.
- **Find the next song:** search and browse by playlist, artist, or album.
- **Download in-game:** preview a video or collection URL, choose entries, and
  save MP3s with available metadata and artwork directly into your music folder.
- **Make it yours:** bulk playlist editing with animated drag reordering, shuffle, repeat, seeking, volume,
  and playback speed from 0.5× to 3.0×.
- **Keep listening:** music continues when you close the player or change worlds.
- **Stay in the game:** an optional now-playing HUD with artwork, drag positioning,
  whole-HUD scaling, and configurable playback and speed shortcuts.
- **Choose your language:** English and Traditional Chinese follow Minecraft’s language setting.
- **See the music:** real PCM-driven frequency bars in the player and an
  independently toggleable HUD visualizer, with size and response controls.

## Requirements

| Component | Version |
|---|---|
| Minecraft Java Edition | **26.2** |
| Fabric Loader | **0.19.5 or newer** |
| Fabric API | **0.161.0+26.2** |
| Java runtime | **25 or newer** |

## Install

1. Create or select a **Minecraft 26.2 Fabric** instance in your launcher.
2. Add **Fabric API for 26.2** to that instance's `mods` folder.
3. Add the DarkMusic mod JAR, such as `darkmusic-1.0.1.jar`, to the same folder.
   Download it from [Releases](https://github.com/DarkLian/DarkMusic/releases/latest),
   or [build the mod from source](#build-from-source).
4. Launch Minecraft and click **Music** on the title screen.

The player also opens with **M** during gameplay. You can change that key in
**Options → Controls → Key Binds**. Fresh installations use **Right/Left Arrow**
for next/previous track and **Up/Down Arrow** for volume, in 5% steps.
Existing saved bindings are preserved.

## Your first five steps

1. **Open the player:** click **Music** on the title screen or press **M** in-game.
2. **Open your music folder:** click **Open Folder** at the bottom of the player.
3. **Add Songs:** copy supported audio files into the folder. Subfolders work too.
4. **Refresh:** wait for discovery or click **Refresh Library**.
5. **Listen:** double-click a song, then use the playback buttons and sliders.

Already have a music collection? Choose it through
**Settings → Choose Music Folder**, browse to the directory, and click
**Use This Folder**. The default folder follows your active Minecraft instance,
so you can start with **Open Folder** without searching through launcher folders.

## Explore the player

| You want to… | Start here |
|---|---|
| Find an artist or album | Use the search field and **View** selector, or the tabs on taller layouts |
| Listen to a playlist | Select the **Playlists** view, choose its name on the left, and double-click a song |
| Make or edit a playlist | Open **Manage Playlists → New**, name it, then use **Add Songs → Add Selected** |
| Choose another music directory | Open **Settings → Choose Music Folder** |
| Show the current song while playing | Enable **Settings → HUD Settings → Now-Playing HUD** |
| Assign playback, speed, or volume shortcuts | Open **Options → Controls → Key Binds → DarkMusic** |
| Adjust volume, speed, shuffle, or repeat | Use the main player's bottom controls; hover for explanations |
| Move or resize the HUD | Open **Settings → HUD Settings → Edit HUD Layout** |
| Download authorized audio | Open **Downloads → Tools** for setup, then paste a URL and **Analyze** |
| Configure spectrum bars | Open **Settings → Music Visualizer** |
| Adjust autoplay | Open **Settings** |

Use **Escape** to return from screens. Settings save automatically.

Read the **[illustrated user guide](docs/USER_GUIDE.md)** for detailed steps,
control descriptions, playlist editing, metadata tips, and troubleshooting.

## Download music in-game

Open **Downloads** from the player. Set up [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases),
[FFmpeg and FFprobe](https://ffmpeg.org/download.html) through **Tools**, using
existing executables, your system PATH, or the instance's `config/darkmusic/tools`
directory. YouTube also needs a compatible JavaScript runtime: **Deno 2.3+** or
**Node 22+** ([yt-dlp setup guide](https://github.com/yt-dlp/yt-dlp/wiki/EJS)).
These tools are separate from the mod and are never installed or updated automatically.

Paste a YouTube, Bilibili, or another yt-dlp-supported URL and click **Analyze**.
Choose **This Video Only**, checked preview entries, **First N**, an inclusive
range, or explicitly **All Entries**. Downloads never start from previewing alone.
Pages show up to 100 entries and batches support up to 1,000; use separate ranges
for larger collections. Dynamic Mixes require a bounded selection.

Finished MP3s are saved permanently in your selected music folder and appear
without restarting. Download only content you are authorized to save. See the
[downloader guide](docs/USER_GUIDE.md#download-music) for setup and troubleshooting.

## Build from source

Use a Java 25 JDK for Gradle, open a terminal in the project directory, and run:

**Windows**

```powershell
.\gradlew.bat build
```

**Linux / macOS**

```sh
./gradlew build
```

The mod is written to `build/libs/darkmusic-1.0.1.jar`. Install that JAR in your
Minecraft instance's `mods` folder. The `-sources.jar` and
`-third-party-sources.zip` files are source archives rather than installable mods.
When redistributing a build, include its third-party source bundle and notices.

## License

DarkMusic is **[CC0-1.0](LICENSE)**. Included libraries retain their respective
licenses; see the **[third-party notices](src/main/resources/THIRD-PARTY-NOTICES.md)**.
