# DarkMusic v1.0.2

Simpler settings/HUD editing, managed download tools and audio previews, clearer artwork/text, and a media-preserving reset.

- Merge General/HUD settings with a live preview. Artwork, progress, and spectrum form one movable/scalable HUD. Add transparency, spectrum placement/direction, and wheel scaling/readouts.
- Use a desktop folder chooser and show the selected path.
- Explicitly install/update a verified local yt-dlp, FFmpeg, FFprobe, and Deno bundle; show tools individually. Configured/system tools remain supported.
- Remember the last analyzed URL while clearing preview entries on exit. Add preview search, range/checked selection, and temporary audio audition.
- Improve detailed embedded artwork rendering, enlarge player artwork/spectrum space, and pan long names/status text horizontally.
- Add mute and fading volume/speed feedback. Remove redundant visualizer controls and the HUD editor heading.
- Share a confirmation dialog for playlist deletion and Reset to Defaults. Reset preferences, keys, folder selection, scales, tools/history/caches while preserving all media, downloaded music, playlists, and external tools.
- Fixed defaults: HUD on, volume 100%, scale 100%, transparency 0%, 36 bars, below/upward spectrum. Saved preferences remain until reset.

## Default keys

| Action | Key |
|---|---|
| Open player | B |
| Play / pause | Backslash (`\`) |
| Previous / next song | `[` / `]` |
| Volume up / down | Up Arrow / Down Arrow |
| Speed down / up | Left Arrow / Right Arrow |
| Mute / unmute | M |
| Reset speed | Numpad 0 |

## Install

Requires Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API for **26.2**, and **Java 25+**. Install **darkmusic-1.0.2.jar** alongside Fabric API. Sources JAR and third-party sources ZIP are not mods. Personal media and development executables are not bundled.

Open Music on the title screen or press B in gameplay. Existing settings/bindings remain compatible; reset applies fixed defaults without deleting audio/video files. Download only authorized content; support depends on yt-dlp and source availability.

[User guide](https://github.com/DarkLian/DarkMusic/blob/v1.0.2/docs/USER_GUIDE.md) · [Testing](https://github.com/DarkLian/DarkMusic/blob/v1.0.2/docs/TESTING.md)

## Verification

Local verification runs after the implementation commit and before publication, using the real collection and integration tools. Hosted checks explicitly skip unavailable private-audio integrations. Title-screen checks do not claim exhaustive multiplayer, full-modpack, or cross-platform runtime coverage.
