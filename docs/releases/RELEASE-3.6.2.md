# DarkMusic v3.6.2

## Fixes

- Saving or restoring lyric files during a metadata edit or rename is now blocked, keeping lyrics with their song.
- Music folders can be chosen, added or removed before settings have been activated for the first time.
- Shutting down with a queued audio-file deletion now cancels the request and releases the media lock even if its worker task is discarded. The cancelled task cannot delete files later.
- The artwork cache stays within its entry limit even when texture registrations repeatedly fail.

## Other

- Internal restructuring preserves playback and rendering behavior, with exact PCM and visual regression checks.

## Compatibility

Install **`darkmusic-3.6.2.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Existing libraries, playlists, histories and settings remain supported. PiP and Media Controls remain optional Windows x64 extensions installed through their in-game menus; use the matching v3.6.2 packages. **Do not put extension ZIPs in `mods`**. The music player supports Windows, Linux and macOS.
