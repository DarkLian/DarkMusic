# DarkMusic v3.5.0

## New

- **Pick up where you left off.** DarkMusic remembers the last song, the collection it played from, and where it was. The player opens on it, and Play, the Play/Pause shortcut, `/da play` and Autoplay on Launch continue from there (**Settings → General → Resume Where You Left Off**, on by default).
- **Discord status (opt-in).** Show "Listening to DarkMusic" on your Discord profile with the song, artist and a progress bar. It talks to the Discord desktop app on your computer; nothing signs in. Songs downloaded from YouTube show the video's thumbnail. You can hide the song title (**Settings → General → Discord Status**).
- **Media keys and Windows media controls (optional, Windows x64).** After **Settings → General → Install Media Controls** (about 6 MB), keyboard media keys, headset buttons and the Windows media flyout control DarkMusic, and the flyout shows the song, artwork and timeline. Like PiP, it is installed only on request, checked against a fingerprint in the mod, kept outside `mods`, and runs as a separate program only while switched on. **Remove Media Controls** uninstalls it.
- **Sleep timer.** `/da sleep 30` pauses after 30 minutes with a gentle fade; `/da stopafter` stops when the current song ends.
- **New shortcuts** (unbound until you assign them): show/hide HUD, show/hide lyrics HUD, favorite current song, shuffle, repeat.
- **Search in Settings.** Type in the box above the settings to find any option across all sections.
- **Auto-updating lists.** History adds **Recently Added** and **Never Played**.

## HUD

- **Hide When Idle:** hides the HUD after music has been paused or stopped for a time you choose (1 second to 2 minutes), by sliding it away or fading it out slowly. The lyrics HUD fades with it.
- **Song Change Card:** briefly shows the new song's title, artist and artwork when the song changes, with a slide or fade animation. Move and resize it in Edit HUD Layout.
- **Song Change Animation:** the new title, artist and artwork fade in.
- **Classic HUD:** optional "Next:" line, and the song's time under the progress bar or on the artist line.
- HUD settings are grouped into Appearance, Visibility and Motion, Song Change Card and Progress Bar.

## Improvements

- **Beveled finish** is now clearly visible on wave, spike and Rounded Bars spectrum styles.
- **Player progress bar** matches the HUD's: same rail, colors and finish.
- **PiP** keeps only one dependency package; older packages and interrupted downloads are removed whenever PiP setup runs.
- Setting rows align their checkboxes, arrows and values in one column.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible. Install **`darkmusic-3.5.0.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP and Media Controls stay optional and install through their menus; each version pins its own packages, so existing PiP users are asked to install the matching v3.5.0 package. **Do not put the dependency ZIPs in `mods`**. PiP and Media Controls require Windows x64; the music player supports Windows, Linux and macOS.
