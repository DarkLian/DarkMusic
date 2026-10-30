# DarkMusic User Guide

DarkMusic v3.5.1 for Minecraft 26.2, Fabric and Java 25. The music player works on Windows, Linux and macOS;
Picture-in-Picture and Media Controls are optional Windows x64 extensions.

## Getting started

### Add your music

Press **B** to open the player. Music in `config/darkmusic/music` inside your Minecraft instance is found
automatically. To use other folders, open **Settings → General → Music Folders → Add Music Folder**. Every
registered folder is scanned with its subfolders. The checked **Default Save Folder** receives downloads and imports.
Removing a folder from the list leaves its files on disk.

New, changed and deleted files are picked up automatically; **Refresh Library** in the player forces a rescan. A slow
or unavailable folder does not hold up the others, and songs are only removed from the library after a folder was
scanned completely.

| Formats | Needs |
|---|---|
| MP3, FLAC, WAV, Ogg Vorbis, AIFF | Nothing extra |
| Opus, AAC, MP4/M4A, WebM | FFmpeg, installed from **Downloads → Tools** |

Titles, artists, albums, duration and artwork come from the files' tags. Missing tags show the filename or an
unknown artist/album; use the pencil button to edit them.

### Drag and import

Drag audio files onto the player or the playlist manager to copy them into your music folder. The originals stay
where they are, and an existing file is never overwritten (the copy gets a numbered name).

| Selected view | Copied to |
|---|---|
| All Tracks, Artists, Albums | The music folder |
| A folder in All | That folder |
| A playlist (including Favorite Music) | The music folder, and added to that playlist |

**Import History**, beside Open Folder, lists past imports with their results.

### Language

DarkMusic follows Minecraft's language: English or Traditional Chinese (**Options → Language → 繁體中文（台灣）**).
Song details are always shown as they appear in your files.

## Playing music

![Library](images/player.png)

Choose **All**, **Playlists**, **Artists** or **Albums**; the left panel lists the collections. In All, folders
expand to show subfolders. Search filters the list and ignores accents and character width (`beyonce` finds
`Beyoncé`).

Select a song and press Play, or double-click it. The right panel shows the selected song, which can differ from the
one playing. When the song changes, the list follows it unless you are searching.

The controls, from left to right: **Speed · Reset Speed · Shuffle · Jump to Playing · Previous · Back 5 s ·
Play/Pause · Forward 5 s · Next · Stop · Repeat · Mute · Volume**. Drag the progress bar above them to seek.

- Speed ranges from 0.5× to 3.0×. It changes pitch unless **Preserve Pitch** is on (Advanced Audio).
- **Repeat** cycles Off (stop at the end), This (repeat the song) and All (repeat the collection). Shuffle mixes the
  collection. Previous and Next always wrap around.
- Playing a song found by search still plays its whole collection. **Jump to Playing** clears the search and shows
  the playing song in its collection.
- **Resume Where You Left Off** (Settings → General, on by default) reopens the last song and collection at the
  position you left. If the song left its playlist or folder, it resumes from the whole library.
- **Autoplay on Launch** starts playback when Minecraft starts.

Space plays and pauses while the player is open. Tab moves between controls; arrow keys and Space then operate the
focused one. While the search box has focus, keys type instead of controlling playback; click elsewhere to release it.

## Up Next

![Up Next playback queue](images/up-next.png)

Open **Song Actions** (the More icon on the song details panel) and choose **Play Next** or **Add to Queue**. Open
**Up Next** from the player header to see Now Playing, Queued by You, and Coming from Collection.

- Drag queued songs to reorder them; check several and **Remove**, or **Clear Queued Songs**. Playback keeps going.
- The right-hand list shows the current **Collection** or **All Songs**. Drag a song into Queued by You, or
  double-click it to add it at the end.
- Queued songs play before the rest of the collection, which then continues where it left off. Repeat This holds the
  queue until you press Next.

The queue holds up to 1,000 songs. It survives closing menus and changing worlds, and picking another song from a list
keeps it. It is cleared when Minecraft closes or by Reset to Defaults. It never changes your playlists, and it is not
part of backups. Deleted songs leave the queue; edited songs keep their place.

## Playlists, favorites and history

![Playlist manager](images/playlists.png)

**Manage Playlists** (music-note button above the collections) creates, renames and deletes playlists. **Add Songs**
picks tracks; **Remove Selected** removes entries. Ctrl/Cmd-click and Shift-click select several songs, Ctrl/Cmd+A
selects all, and connected selections can be dragged to reorder. Removing entries or deleting a playlist never
deletes audio files.

Click the heart on a song to add it to **Favorite Music**, a built-in playlist that cannot be renamed or deleted.

When a folder has been scanned completely, playlist entries for files that are really gone are removed. Entries in
unavailable folders are kept.

**Listening history** (clock icon left of Manage Playlists) shows **Recently Played**, **Most Played**, **Recently
Added** (the 100 newest songs) and **Never Played**. A play counts after 30 seconds, or half of a shorter song;
pausing, seeking and muting add no listening time. Clear it in **Settings → General → Clear Listening History**.

## Managing files

### Edit song details

Select a song and click the pencil to edit its title, artist, album or filename (the extension is kept). Changes are
written to the file and to your playlists. A playing song pauses briefly and continues where it was. Opus and WebM tags
need FFmpeg.

The details panel also offers Lyrics, **Open Source** and **Copy Source Link** when the original video is known.

### Delete audio files

Open **Manage Audio Files** (wrench above the collections). Select files and choose **Review Selected Files**, which
lists their full paths. Choose **Move to Trash**, or **Delete Permanently…** with a second confirmation. A failed
Trash operation never turns into permanent deletion.

Lyric files and other companions are only deleted if you include them, and always go to the Trash. Deleted songs
leave playlists, Favorite Music and Up Next. **Cancel Remaining Files** stops the rest; finished deletions stay done.

### Smart Cleanup

![Expandable Smart Cleanup results](images/smart-cleanup.png)

**Manage Audio Files → Smart Cleanup** finds possible duplicates. Choose **Strict**, **Balanced** or **Loose** and
**Start Scan**. Results come in groups; open a pair to compare artwork, details, paths, formats and evidence, and play
either file (your music pauses meanwhile).

- **Keep Both** dismisses the pair for this scan; **Ignore Match** hides it until a file changes (see **Ignored
  Matches** to undo).
- **Delete A… / Delete B…** opens the normal deletion review. Nothing is ever deleted automatically.
- **Compare Audio** (off by default) also compares how the first three minutes sound, using FFmpeg on your computer.

Matches are suggestions: Loose can pair different songs with similar production, and no setting finds every
duplicate.

## Downloads

![Downloads with collection preview and page navigation](images/downloads.png)

Download only material you are allowed to save. DarkMusic does not bypass logins or DRM.

### Set up tools

Open **Downloads → Tools → Install / Update Tools** to install yt-dlp, FFmpeg/FFprobe and Deno (allow about 1 GB of
temporary space). They are only downloaded or updated when you choose this. **Advanced Paths** selects tools you
installed yourself; **Check Tools** tests them. Managed tools are available for Windows x64, Linux x64/ARM64 and
macOS x64/ARM64.

### Download

1. Paste a video or collection URL (YouTube videos, playlists and Mixes, Bilibili, and other yt-dlp sources).
2. Choose **Analyze**.
3. Choose **This Video Only**, **Selected Entries**, **Entry Range** or **All Entries**.
4. Start the download.

Previews show 100 entries per page; selections carry across pages. Batches are limited to 1,000 entries. Select an
entry and press Play to hear up to 30 seconds. **Format** chooses Original (best available) or MP3.

Downloads continue in the background, up to three at a time, with progress on the HUD. Finished songs are saved to the
Default Save Folder with their title, artist and artwork where available. Temporary failures retry up to three
times. **Retry Selected**, **Retry Failed**, **Clear Finished** and **Clear Failed** manage the queue.

### Songs you already have

Entries you already own are marked **Already Downloaded**; likely matches are marked **Possible Duplicate**. Before
downloading, a review lists existing copies: **Skip Already Downloaded** or **Download Anyway**. An unmarked entry
is not proof that you don't have it.

### When something fails

![Download error details](images/download-diagnostics.png)

Use **Error Details** (warning icon) for the stage and tool that failed, what the error may mean, and what to try.
**Copy Diagnostic** copies a report without URLs, file paths or account details. Website changes can break downloads
until yt-dlp is updated; **Install / Update Tools** may help.

## HUD

![HUD and spectrum settings with live artwork preview](images/settings.png)

Settings is divided into General, Audio, Lyrics, Appearance, HUD, Spectrum and Reset. **Search all settings** finds
an option by name or description across every section. The preview shows the current song, or a sample when nothing
plays.

### Layouts

Turn on **Now-Playing HUD** and choose a style:

- **Classic**: song details with optional artwork, progress bar and spectrum. **Show Next Song** and **Show Time**
  add lines.
- **Circular**: the artwork or DarkMusic icon with a progress ring and outward spectrum. **Audio-Reactive Icon**
  pulses with the level or the beat. Image, spectrum and progress can rotate (**Rotation Time** 4–120 s).
- **Spectrum Only**: just the spectrum.
- **Artwork**: large artwork with a spectrum on any side, optional progress bar and song information.

Use **Edit HUD Layout** (or `/da gui`) to drag the HUD, and scroll to scale it from 50% to 300%. Centre guides
snap when you drag near them; hold Shift to place freely. Lyrics and the song card move separately.

### Visibility and motion

- **Hide When Idle** hides the HUD after music has been paused or stopped (1 s to 2 min), by sliding or fading.
- **Song Change Animation** fades in the new song's details.
- **Song Change Card** briefly shows the new song, **When HUD Is Hidden** or **Always**; position it with **Position
  Song Card**.
- **Show HUD in Menus** keeps the HUD, lyrics and notices visible over Minecraft menus.
- **Song Info Backdrop** draws a soft plate behind the title and artist for readability.

### Progress, spectrum and colours

**Waveform Progress Bar** draws the song's shape as **Filled Wave** or **Rounded Bars** in the player and the HUD,
with its own height, detail, contrast and colours. **Reset Waveform Appearance** restores those options.

Spectrum settings cover style (including Rounded Bars), bands, height, sensitivity, smoothing, opacity, finish,
direction, and peak, trail and glow effects. Colour editors offer a Color Spectrum picker, RGB sliders, palette
colours, `#RRGGBB` input and gradient presets. **Appearance** holds the menu background and accent colours.

![Customized Classic HUD during gameplay](images/classic-hud.png)

## Audio

![Audio options](images/audio.png)

### Audio Options

Open **Settings → Audio → Audio Options**. **Audio Profile** chooses:

| Profile | Sound |
|---|---|
| Original | Your music without optional enhancements; the controls below are off |
| Music (Normalize) | More even volume between songs |
| Gaming (Ambient) | Steady, slightly quieter music under game sounds |
| Vocal (EQ) | Vocals brought forward |
| Night (Compress) | Smaller volume differences for quiet listening |
| Custom | A blank starting point for your own settings |

Changing a value of a built-in profile switches to Custom. Save your settings as a named profile; a loaded saved
profile stays selected while you edit it, and **Save** updates it. Deleting a saved profile keeps the current sound.

The options include loudness normalization (using tags, or analysing untagged songs with FFmpeg), a 10-band
equalizer, bass, stereo width, compression, reverb, limiting, gapless playback, crossfade, **Listening Mode**
(Normal, Focus, Ambient) and **Duck on Priority Sounds**. **Analyze Library Loudness** measures your whole library in
the background.

**Advanced Audio** adds parametric EQ, shelves, dynamic EQ, a de-esser, headphone crossfeed, compressor details, a
true-peak limiter, convolution reverb and Preserve Pitch. Right-click a numeric slider, or focus it and press Enter, to
type an exact value.

![Advanced Audio controls](images/advanced-audio.png)

### Sound Environment

![Sound Environment controls](images/sound-environment.png)

Open **Settings → Audio → Sound Environment** and **Choose Sound Environment**. The picker lists Off, 22
environments (from Old Radio and Vinyl to Concert Hall, Deep Cave, Underwater and Orbital Echo), Custom, and your
saved environments. It stays open so you can compare; press Escape to return.

![Direct environment selection](images/environment-picker.png)

**Intensity** sets the strength; 0% leaves the music unchanged. **Advanced Parameters** shows the detailed controls
for tone, filters, reflections, distance and room. Changing a built-in environment switches to Custom; a saved
environment stays selected while you edit it. Save up to 32 environments. Sound Environment works independently of
Audio Options.

### Audio Analyzer

![Audio Analyzer status and comparison controls](images/analyzer.png)

**Audio Options → Audio Analyzer** measures the sound sent to your speakers: levels, stereo, dynamics, spectra,
waveforms and filter curves, comparing Audio Options, Environment or the full chain. Curves show filter tone only, not
reverb or distortion. A/B bypass is temporary and never changes your settings.

![Audio Analyzer graphs](images/analyzer-graphs.png)

### Output device

**Audio Options → Output Device** picks a playback device; System Default follows your operating system. Switching
keeps the position. If a saved device is missing, System Default is used.

## Lyrics

Turn on **Enable Lyrics** in **Settings → Lyrics**, then open a song's Lyrics icon to read, search, pick a version or
adjust timing.

**Local lyrics:** put `Song.lrc` beside `Song.mp3`, use **Import Lyrics**, or rely on embedded lyrics. Lines look like
`[00:12.30]First line`.

**Online lyrics:** **Online Lyrics Lookup** asks for consent once. It sends the title, artist, album and duration to
LRCLIB, never audio. Options under **Advanced Lyrics** look up lyrics on playback, after downloads, or for your whole
library (**Find Missing Lyrics**). When LRCLIB has nothing, captions from the original video are tried;
**Allow Autogenerated Captions** is off by default. Weak matches are never applied automatically, and your own files
and choices always win.

**In the Lyrics screen:**
- **Search Lyrics** looks with an edited title or artist, without changing your tags;
- set preferred languages, such as `ja` or `zh-Hant`, when several versions exist;
- shift timing per song by ±30 s (positive shows lyrics later);
- **Save Lyrics** writes the chosen version beside the song, and **Replace Existing Lyrics** keeps a backup you can
  restore.

Plain lyrics show unsynchronized; timestamps are never invented.

Status marks: ✓ lyrics available, ? not yet checked, ✗ nothing found or the lookup failed. "Lookup paused" waits to
retry; "choose a matching version" needs your review.

**Lyrics in HUD** shows synchronized lines as a separate HUD element with its own width, colours, next line and text
backdrop. It stays empty when no timed lyrics exist.

## Picture-in-Picture (Windows x64)

PiP shows another application's window inside Minecraft. It is off until you set it up:

1. Open the Picture-in-Picture icon in the player (or `/da pip`) and **Check PiP Requirements**.
2. **Install PiP Dependencies** downloads the helper; see [PiP installation](PIP-OPTIONAL-INSTALLATION.md).
3. Confirm **Enable PiP Access**.
4. Choose an open window and **Start Capture**. Capture never starts by itself, also not after a restart.

**Edit PiP Layout** moves and resizes the view (drag, scroll, arrow keys, +/−); **Crop Captured Window** shows part
of the window. While capturing, **Ctrl+Alt+F8** switches to the captured app and **Ctrl+Alt+F9** back to Minecraft.
**Show PiP** hides the view without stopping capture; **Stop Capture** ends it.

- **Sound:** sound keeps coming from the captured app. **PiP Audio Spectrum** (separate consent, Windows build 20348
  or later) can show its audio beside the video.
- **Frame limit:** **Match Monitor**, or a value from 15 to 480 FPS.
- **Keep the source visible:** keep it open and not minimized.
- **Privacy:** everything in the window may be visible, so crop or stop before showing private content.

**Tearing:** if the whole screen tears while PiP captures, turn off Exclusive Fullscreen in Video Settings and restart
Minecraft, and disable the Cubes Without Borders mod if you use it.

## Integrations

**Media keys and Windows media controls** (Windows x64): **Settings → General → Install Media Controls** downloads a
small optional helper. Your keyboard media keys, headset buttons and the Windows media flyout then control DarkMusic,
also when Minecraft is in the background. Windows shows it as "Unknown app". **Remove Media Controls** deletes it.

**Discord status** (off by default): shows "Listening to DarkMusic" with the song on your profile through the Discord
desktop app; no sign-in is involved. **Show Song on Discord** and **Show Artwork on Discord** control the details;
artwork comes only from public YouTube thumbnails, never your files. If Discord starts later, DarkMusic connects
within a few seconds. If nothing shows, check **Discord → User Settings → Activity Privacy**.

**Updates:** **Update Notifications** checks GitHub when you join a world and tells you once per launch. **Check for
Updates** checks on demand. Downloading needs a separate click; the update installs when you close Minecraft and keeps
your settings, music and playlists.

## Shortcuts and commands

| Action | Default key |
|---|---|
| Open player | B |
| Play / pause | Backslash |
| Previous / next song | [ / ] |
| Volume down / up | Down / Up arrow |
| Speed down / up | − / = |
| Back / forward 5 seconds | Left / Right arrow |
| Stop | P |
| Jump to Playing | O |
| Reset speed | Numpad 0 |
| Mute | M |
| Show/hide HUD, show/hide lyrics HUD, favorite current song, shuffle, repeat mode | Not bound |

Change keys in Minecraft's **Key Binds** under DarkMusic.

Commands work as `/da` or `/darkmusic`:

| Command | Action |
|---|---|
| `/da` | Open the player |
| `/da play [song]`, `/da pause`, `/da stop` | Play (resume, or the closest title match), pause, stop |
| `/da previous`, `/da next` | Previous or next song |
| `/da now` | Current song with control buttons |
| `/da queue <song>` | Add a song to Up Next |
| `/da volume [0–100]`, `/da mute`, `/da speed [0.5–3]` | Show or set volume and speed |
| `/da seek <time>` | Jump to `1:30` or `90`, or move `+10` / `-10` seconds |
| `/da shuffle [on\|off]`, `/da repeat [off\|one\|all]` | Shuffle and repeat |
| `/da sleep [minutes\|off]` | Pause after 1–720 minutes, fading out at the end |
| `/da stopafter` | Stop when the current song ends (run again to cancel) |
| `/da gui`, `/da lyrics`, `/da pip`, `/da keys` | HUD layout, lyrics, PiP, key list |
| `/da update`, `/da help` | Check for updates, list all commands |

Press Tab while typing a song name to see matches.

## Your data

### Backup and restore

**Settings → General → Backup / Restore**:
- **Export Backup** saves settings, profiles, environments, playlists, key binds and listening history to a ZIP. Music
  files and tools are not included.
- **Open Backup** previews a backup. Choose what to restore, and whether to merge or replace playlists and history.

Restore asks for confirmation and keeps recovery copies. Music-folder paths, tool paths, the output device and
PiP/lyrics permissions stay as they are on this computer. If a restore is interrupted, the previous setup is recovered
on the next start. If DarkMusic says the backup was restored but needs a restart, restart Minecraft to finish.

### Reset to Defaults

**Settings → Reset → Reset to Defaults** restores settings, key binds, HUD layout, volume, speed and the default
music folder. It clears download history, import history and caches. It keeps your music, videos, playlists,
installed tools and tool paths.

### Where data is stored

Settings, playlists, histories and caches are in `config/darkmusic` inside your instance. Your music folders can be
anywhere. Copy both when moving to another computer.

## Troubleshooting

| Problem | What to try |
|---|---|
| Empty library | Check Music Folders and the file formats; press Refresh Library |
| Opus, M4A or WebM won't play | Install FFmpeg in Downloads → Tools |
| No artwork | The file may have no embedded artwork |
| No lyrics | Turn on Enable Lyrics; check the `.lrc` name matches the song |
| Download fails with 403 or 429 | The video may be private or limited; update tools, or wait and retry |
| HUD missing | Turn on Now-Playing HUD and start music; check Edit HUD Layout |
| Audio Options controls are off | Choose a profile other than Original |
| A shortcut does nothing | Check for conflicts in Key Binds |
| Screen tears with PiP | Turn off Exclusive Fullscreen and restart; disable Cubes Without Borders |

Menu status colours: blue for activity, green for success, amber for warnings or anything needing review, red for
failures.

Report problems in [GitHub Issues](https://github.com/DarkLian/DarkMusic/issues) with your DarkMusic and Minecraft
versions, operating system, steps and logs (remove private details).

## License and modpacks

DarkMusic is free. New contributions since v2.8.2 use the [DarkMusic License & Permissions](../LICENSE): private
changes are allowed, and unmodified official releases may be included in modpacks with attribution and the notices
kept. Other redistribution needs written permission. Earlier CC0 parts and third-party components keep their own
licenses.
