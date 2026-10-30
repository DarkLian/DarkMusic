# DarkMusic

Your own music library, inside Minecraft. Browse and play your collection, build playlists, download songs without
leaving the game, shape the sound, and show it all on an audio-reactive HUD. Optionally, watch another window in a
Picture-in-Picture view while you play.

![DarkMusic music library and playback controls](docs/images/player.png)

## Install

DarkMusic **v3.5.1** needs **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5 or newer**, and **Fabric API for
Minecraft 26.2**.

1. Download `darkmusic-3.5.1.jar` from [GitHub Releases](https://github.com/DarkLian/DarkMusic/releases/latest).
2. Put it and Fabric API in your instance's `mods` folder.
3. Start Minecraft and press **B** to open DarkMusic.
4. Add songs under **Settings → General → Music Folders**, drag audio files into the player, or copy them to
   `config/darkmusic/music` in your instance.

DarkMusic runs on the client only; servers do not need it. The player works on Windows, Linux and macOS. Downloading
and some audio formats (Opus, AAC, M4A, WebM) need extra tools, which DarkMusic can install for you.

New to DarkMusic? The [User Guide](docs/USER_GUIDE.md) covers every screen and setting.

## Highlights

- **Your library, your way.** Several music folders, browsed by folder, playlist, artist or album, with search.
- **Playlists and favorites.** Bulk selection, drag to reorder, favorites one heart-click away.
- **Up Next.** Play a song next or queue it without touching your playlists.
- **Now-Playing HUD.** Classic, Circular, Artwork or Spectrum Only, with lyrics, a progress bar or waveform, and your
  own colours and position.
- **Sound.** Audio profiles, EQ, loudness normalization, crossfade, gapless playback, 22 sound environments and a live
  Audio Analyzer.
- **In-game downloads.** Paste a video or playlist link, preview it, and pick what to save.
- **Lyrics.** Synchronized or plain lyrics for songs in your library.
- **Smart Cleanup.** Find possible duplicates and decide yourself what to keep.
- **Optional extras for Windows x64.** Picture-in-Picture and media-key support, installed only when you ask.

## Play your music

Search, shuffle, repeat, skip five seconds, and change speed from 0.5× to 3.0× (turn on Preserve Pitch to keep the
pitch). **Jump to Playing** returns to the collection a song came from. DarkMusic remembers where you left off and
continues from there.

- **Edit song details** (title, artist, album, filename) with the pencil button.
- **Import** by dragging audio files into the player or playlist manager.
- **Listening history:** Recently Played, Most Played, Recently Added and Never Played.
- **Sleep timer:** `/da sleep 30` pauses after 30 minutes with a fade; `/da stopafter` stops after this song.
- **Discord status (opt-in):** show what you're listening to on your profile; no sign-in.
- **Backup and restore:** export settings, playlists, key binds and history to a ZIP, then choose what to restore.
- **Manage Audio Files:** review files and move them to the Trash; permanent deletion needs its own confirmation.

## Build your playlists

Create playlists, bulk-select songs and drag them to reorder.

![Playlist manager with bulk selection and drag reordering](docs/images/playlists.png)

## Decide what plays next

Choose **Play Next** or **Add to Queue** from a song's actions, then open **Up Next** to rearrange or remove entries.
When the queue ends, the original collection continues.

![Up Next queue](docs/images/up-next.png)

## Show your music in the game

Choose a **Classic**, **Circular**, **Artwork** or **Spectrum Only** HUD. Pick bars, waves or spikes with gradients,
glow, trails and peak markers, or a **waveform progress bar** showing the shape of the song. Move and resize
everything in **Edit HUD Layout**. The HUD can hide while music is paused, show a card when the song changes, and stay
visible over menus.

![Classic Now-Playing HUD during gameplay](docs/images/classic-hud.png)

Preview layouts live and pick gradients or your own colours.

![HUD and spectrum settings with live artwork preview](docs/images/settings.png)

## Shape your sound

**Audio Options** offers Original, Music, Gaming, Vocal, Night and Custom profiles plus your saved ones, with loudness
normalization, graphic or parametric EQ, compression, reverb, gapless playback and crossfade. The **Audio Analyzer**
shows your sound and compares settings with A/B listening.

**Sound Environment** places the music in one of 22 settings, from Old Radio and Vinyl to Concert Hall, Car Cabin
and Orbital Echo. Adjust its intensity and save your own.

![Sound Environment selection](docs/images/environment-picker.png)

![Live Audio Analyzer](docs/images/analyzer.png)

## Follow along with lyrics

Turn on lyrics in **Settings → Lyrics**. With your approval, DarkMusic searches [LRCLIB](https://lrclib.net) for the
song you play; your own lyric files and embedded lyrics always work. Adjust timing per song, and place the lyrics HUD
anywhere.

## Download music

Paste a video or playlist link under **Downloads**, analyze it, and choose entries or a range. Preview songs, download
several at once, and see clear explanations when something fails. Songs you already have are marked, so you can skip
them.

![Downloads with collection preview and page navigation](docs/images/downloads.png)

**Downloads → Tools → Install / Update Tools** sets up yt-dlp, FFmpeg and Deno (Windows x64, Linux and macOS x64 or
ARM64), or select your own under **Advanced Paths**. Which sites work depends on yt-dlp. Save only content you are
allowed to download.

## Find duplicates

**Manage Audio Files → Smart Cleanup** finds related recordings even when names, tags or formats differ. Compare
details, play both versions, then keep both, ignore the match, or review a file for deletion. Nothing is deleted
automatically.

![Smart Cleanup groups related files for manual review](docs/images/smart-cleanup.png)

## Optional Windows extras

**Picture-in-Picture** (Windows x64) shows another application's window inside Minecraft, even in fullscreen, with an
optional audio spectrum for its sound. Open the PiP icon in the player (or `/da pip`), check the requirements,
install the dependencies, enable access, and start capture. **Ctrl+Alt+F8** switches to the captured app and
**Ctrl+Alt+F9** back. Capture never starts by itself. It needs Windows build 19041 or later; avoid exclusive
fullscreen while capturing. See the [PiP setup guide](docs/PIP-OPTIONAL-INSTALLATION.md).

![Picture-in-Picture window inside Minecraft](docs/images/pip.png)

**Media keys and Windows media controls**: **Settings → General → Install Media Controls** lets keyboard media keys,
headset buttons and the Windows media flyout control DarkMusic.

Both extensions are downloaded only when you install them, verified against the mod, kept outside `mods`, and run as
separate programs. Don't put their ZIP files in `mods`.

## Updates

DarkMusic checks for updates when you join a world and tells you once per launch; **Settings → General → Check for
Updates** checks on demand. Nothing downloads until you click, and the update installs when you close Minecraft.
Your settings, library and playlists are kept.

## What's new

**v3.5.1** gives audio profiles and sound environments Original/Off, a blank Custom and saved entries, keeps Up Next
when you pick a song from a list, and reconnects Discord faster ([release notes](docs/releases/RELEASE-3.5.1.md)).

**v3.5.0** added Resume Where You Left Off, Discord status, media controls, a sleep timer and HUD motion options
([release notes](docs/releases/RELEASE-3.5.0.md)).

Earlier versions are in [docs/releases](docs/releases).

## Controls

| Action | Default |
|---|---|
| Open music player | B |
| Play / pause | Backslash |
| Previous / next song | [ / ] |
| Volume down / up | Down / Up arrow |
| Speed down / up | − / = |
| Back / forward five seconds | Left / Right arrow |
| Stop | P |
| Jump to Playing | O |
| Reset speed | Numpad 0 |
| Mute | M |
| Show/hide HUD, show/hide lyrics HUD, favorite current song, shuffle, repeat mode | Not bound |

Change keys in **Options → Controls → Key Binds → DarkMusic**, or with `/da keys edit`. `/da` (or `/darkmusic`) opens
the player, and commands such as `/da play <song>`, `/da queue <song>`, `/da volume 40`, `/da seek 1:30`,
`/da sleep 30` and `/da now` control playback from chat. `/da help` lists them all.

## Language

English and Traditional Chinese (Taiwan), following Minecraft's language setting.

## Build and contribute

Use JDK 25, the included Gradle wrapper, and the .NET 8 SDK for the optional Windows extensions.

```sh
./gradlew build
```

On Windows use `gradlew.bat build`; output is in `build/libs`. See [Development and testing](docs/TESTING.md) and
[Architecture](docs/ARCHITECTURE.md). Interface text is in
[en_us.json](src/client/resources/assets/darkmusic/lang/en_us.json) and
[zh_tw.json](src/client/resources/assets/darkmusic/lang/zh_tw.json); keep keys and placeholders intact.

Report problems in [GitHub Issues](https://github.com/DarkLian/DarkMusic/issues) with your Minecraft and mod
versions, operating system, steps and logs (remove private details).

## License

DarkMusic is free and source-available. The [DarkMusic License & Permissions](LICENSE) covers new contributions since
v2.8.2: private modifications and unmodified official releases in modpacks are allowed under its conditions; other
redistribution needs written permission. Versions through v2.8.1 remain CC0. See the
[third-party notices](src/main/resources/THIRD-PARTY-NOTICES.md); external tools, music and artwork belong to their
owners.
