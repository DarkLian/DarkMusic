# DarkMusic v3.3.0

## Circular HUD rotation

- **Image & Spectrum Rotation** (Settings → HUD, Circular layout) turns the artwork and spectrum: **Off**, **Clockwise** or **Counterclockwise**.
- **Progress Rotation** (Settings → HUD → Progress Bar) turns the progress ring or circular waveform independently, in either direction.
- Both share one angle, so they stay in step even when they turn opposite ways. **Rotation Time** sets the seconds per turn (4–120, default 20).
- Rotation pauses with the music and keeps moving in Settings previews. Bevel lighting stays fixed on screen, and the title and artist never rotate.

## Settings

- **Lyrics HUD** controls (next line, width, background opacity, line colors) moved from their own menu into **Settings → Lyrics**, directly below **Lyrics in HUD**.
- **General** now starts with **Music Folders** and ends with **Clear Listening History**.
- **Text Backdrop** (lyrics) and **Song Info Backdrop** (Classic and Circular title and artist) turn the soft dark readability plates off for bare text. Both are on by default.
- **Show HUD in Menus** now applies only to Minecraft's menus. DarkMusic's own screens, which already show what is playing, never draw the HUD over them.

## Fixes

- With waveform progress on and nothing playing, the Settings HUD preview showed a plain progress bar in every section except Progress. It now shows the waveform everywhere.
- Downloads: entry icons no longer overlap the labelled **Play Audio Preview** button.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible; new options start off (rotation) or keep the previous look (backdrops). Install **`darkmusic-3.3.0.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.3.0 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
