# DarkMusic User Guide

This guide describes DarkMusic v2.0.2 for Minecraft 26.2 with Fabric and Java 25.

## Interface language

DarkMusic includes English and Traditional Chinese (Taiwan). Choose **繁體中文（台灣）** in Minecraft's **Options → Language** menu to use Traditional Chinese. Song names and other music metadata remain as supplied by your files.

## Add your music

Press **B**, open **Settings**, and choose **Choose Music Folder**. A native folder picker opens. The selected path appears below the setting. DarkMusic scans that folder and its subfolders.

The default folder is `config/darkmusic/music` inside your Minecraft instance. Copying music there also works. Library changes are discovered automatically; use Refresh Library if a change has not appeared.

MP3, FLAC, WAV, Ogg Vorbis, and AIFF use the bundled audio readers. Opus, AAC, MP4/M4A, and WebM playback uses FFmpeg. These formats require a compatible FFmpeg installation, available through **Downloads → Tools**. Actual playback depends on the codec in the file.

Titles, artists, albums, duration, and artwork are read from available metadata. Missing metadata may appear as a filename or an unknown artist/album. Edit source tags with an audio tag editor if you want different library information.

## Browse and play

![Library](images/player.png)

Choose **All**, **Playlists**, **Artists**, or **Albums**. The left panel shows collections and track counts. In All, folder disclosure symbols expand nested folders; folders start collapsed.

Search filters the visible list. Select a song and press Play, or double-click it. The right panel shows the selected song's details and artwork. It can show a different song from the one currently playing.

The centered controls are:

**Speed · Shuffle · Jump to Playing · Previous · Play/Pause · Next · Stop · Repeat · Volume**

Drag the speed or volume marker, or focus the slider and use the arrow keys. Speed ranges from **0.5× to 3.0×** in 0.1× steps; speed changes also change pitch. Drag or click the filled progress track above the controls to seek.

Repeat cycles between:

| Mode | Behavior |
|---|---|
| Off | Stop at the end of the collection |
| This | Repeat the current song |
| All | Repeat the collection |

Shuffle randomizes that same collection. Starting a song from a search still uses the full folder, playlist, artist, album, or All Tracks collection. **Jump to Playing** returns to that source collection, clears search, and reveals the song.

Long labels can be scrolled horizontally with a horizontal wheel gesture or Shift + wheel. Song rows do not display hover popups. **Show Hover Info** controls help on settings and other controls. Escape returns to the previous screen.

## Manage playlists

![Playlist manager](images/playlists.png)

Open the Playlists view, then **Manage Playlists**. Create or rename a playlist, and use **Add Songs** to choose tracks. Songs already in the playlist are omitted from the picker.

Use selection controls or Ctrl/Cmd-click to select individual songs. Shift-click extends a selection. Ctrl/Cmd+A selects the list. Connected selections can be dragged together to change their order; Move Up and Move Down provide button controls.

Remove Selected removes playlist entries. Deleting a playlist opens a confirmation dialog. Neither action deletes your audio files. Missing entries can be removed and the current files added again.

Play playlists from the main player's Playlists view.

## Download music

Open **Downloads** from the player.

### Set up tools

Open **Tools → Install / Update Tools**. Managed setup downloads compatible yt-dlp, FFmpeg/FFprobe, and Deno executables. Updates are requested from this menu. Allow up to 1 GB of temporary space.

Under **Advanced Paths**, select existing executables if preferred. Blank paths use managed tools or system lookup. Check Tools reports whether the selected tools can run. External tool publishers and source links are listed in the tools menu.

Managed installation supports Windows x64, Linux x64/ARM64, and macOS x64/ARM64. Windows ARM64 requires compatible externally installed tools. Internet access and website changes can affect downloads.

### Analyze and select

1. Paste a video or collection URL.
2. Choose **Analyze**.
3. Choose This Video Only, Selected Entries, Entry Range, or All Entries, as available.
4. Review the selection, then download.

YouTube videos/playlists/Mixes, Bilibili videos and supported collections, and other yt-dlp-supported sources can be analyzed. A source may expose only some collection types or entries.

Preview pages contain up to 100 entries. Use Previous/Next, or enter a page number and press **Go** or Enter. Direct page selection is restricted to available pages. For collections with an unknown end, first reach later pages with Next; direct jumps can return to pages already reached.

Search filters the current entry page by title or entry number. Select Page checks visible results. Selections persist across pages. Entry Range downloads its inclusive range, such as 10–30; checked entries are used only in Selected Entries mode. Batches are limited to 1,000 entries. Unknown-ended collections require a finite range.

Select an available entry and use the preview Play button to hear up to 30 seconds. Preview needs a network request and may take a moment. It stops when leaving the menu. The URL and analyzed preview remain available when returning.

### Queue and files

Downloads run in the background, including when the menu is closed. Up to three jobs run in parallel; conversion and source restrictions can limit throughput. A HUD indicator reports background progress.

Queue search covers titles, status, errors, and destination folders. **Retry Selected** retries one failed job; **Retry Failed** retries all failed jobs. Automatic retries run after pending work, up to three retries per job. Clear Finished keeps failed jobs; Clear Failed removes their queue records. Cancellation stops active/queued work; completed files remain.

Completed files go into the currently configured music folder and appear in the library. **Audio Options → Download Audio Format** chooses Original / Best Available or Convert to MP3. Available title, uploader/artist, album, track number, and artwork are embedded where supported. Duplicate tracking reduces repeat downloads.

Restricted, deleted, private, or unsupported content may fail. DarkMusic does not bypass authentication or DRM. Download only material you are authorized to save.

## Customize the HUD

![Settings and live preview](images/settings.png)

Settings contains General, HUD, Spectrum, Appearance, Effects, and Reset sections. Most changes save immediately. The preview shows current playback data, or sample visualization when no song is playing.

**Now-Playing HUD** enables the in-game component. Classic mode offers artwork, progress, and spectrum. Circular mode uses the DarkMusic icon, a circular progress ring, outward spectrum, and optional title/artist text.

![Circular HUD preview](images/circular-hud.png)

Choose a spectrum style, position/direction for Classic, bar count, sensitivity, and smoothing. Appearance offers colors, HUD background transparency, and flat or beveled finishes. Effects provides quality presets, frequency smoothing, peak hold, trails, glow, and beat-reactive motion.

![Spectrum controls](images/spectrum.png)

The spectrum analyzes decoded music samples. Pause freezes it; seeking, stopping, and track changes reset its analysis state.

Scroll over the settings preview to resize that preview. Its scale is independent of the saved in-game HUD scale.

Use **Edit HUD Layout** beneath the preview, or `/da gui`, to position the actual HUD. Drag it to move; scroll over it to change its scale in 10% steps from 50% to 300%. A scale indication appears while resizing. Escape saves and returns. The HUD and its spectrum move as one component.

## Audio options

![Audio options](images/audio.png)

**Original / Neutral** bypasses optional audio processing. Turn it off to adjust normalization, equalizer, bass, stereo width, compression, reverb, or limiting. Volume, speed, and listening mode have their own controls.

Profiles provide presets, plus named saved configurations. The 10-band equalizer supports presets and individual adjustments. Loudness normalization uses available tags; Analyze Untagged Loudness can measure missing loudness information with FFmpeg. Album mode uses album loudness tags when available.

Gapless playback preloads consecutive songs. Crossfade sets overlap duration. Listening Mode offers Normal, Focus (quieter game sounds), and Ambient (quieter music). Duck on Priority Sounds briefly reduces music for important game sounds, with level and timing controls.

## Lyrics

**Enable Lyrics** controls lyric display and downloading source captions. **Lyrics in HUD** and **Show Next Lyric Line** control HUD lines. The Lyrics screen displays synchronized text for the current song.

For a local sidecar, place `Song.lrc` beside `Song.mp3` (or another supported audio file) with the same basename. Lyrics need timed lines, for example:

```text
[00:12.30]First line
[00:16.80]Second line
```

Supported embedded lyric tags can also provide synchronized text. Plain untimed text is not enough for synchronized playback. Oversized sidecars and unsupported embedded formats may not be read.

Availability indicators distinguish validated timed lyrics, no supported lyrics, and unknown/unread data. A source with manual captions is a candidate; it does not guarantee song lyrics. Many videos have no captions, and captions may contain speech. The downloader does not generate lyrics or transcribe audio, so not every song gets a lyrics file.

## Shortcuts and commands

| Action | Default key |
|---|---|
| Open player | B |
| Play / pause | Backslash |
| Previous / next track | [ / ] |
| Volume down / up | Down / Up arrow |
| Speed down / up | Left / Right arrow |
| Reset speed | Numpad 0 |
| Mute / unmute | M |

Volume changes in 5% steps and speed in 0.1× steps. Volume, mute, and speed shortcuts briefly show a HUD notification. Rebind controls in Minecraft's Key Binds menu under DarkMusic.

| Command | Action |
|---|---|
| `/da` or `/darkmusic` | Open player |
| `/da gui` or `/darkmusic gui` | Edit HUD layout |
| `/da help` or `/darkmusic help` | Show local tips and links |

These commands are handled client-side; help feedback is shown locally.

## Saved data and reset

Settings are stored in the instance's `config/darkmusic` directory. Your configured music folder can be elsewhere. Keep that configuration directory with your library when backing up playlists and preferences.

**Reset to Defaults** opens a confirmation dialog. It restores settings, key binds, HUD layout/scale, volume, speed, and the default music folder. It stops work and clears DarkMusic-installed tools, recognized download history, and caches. Your audio/video files and playlists are kept. Clearing download history allows the same sources to be downloaded again.

## Troubleshooting

| Problem | What to check |
|---|---|
| Empty library | Confirm the folder in Settings and the audio file format; use Refresh Library |
| Opus/M4A/WebM will not play | Install or select working FFmpeg/FFprobe in Download Tools |
| Artwork missing | Check whether the file contains supported embedded artwork |
| Lyrics missing | Check Enable Lyrics, timed data, matching `.lrc` basename, and source caption availability |
| HTTP 403 or 429 | Check public access, update tools, or wait before retrying |
| HUD missing | Enable Now-Playing HUD and start music; check HUD layout |
| Effects unavailable | Turn Original / Neutral off |
| Shortcut conflict | Rebind it in Minecraft's Key Binds menu |

For a reproducible issue, share the mod/Minecraft versions, operating system, steps, and relevant logs in [GitHub Issues](https://github.com/DarkLian/DarkMusic/issues). Remove private details before posting.
