# DarkMusic v3.6.0

## Improvements

- Import History lets you select records in bulk (Ctrl/Cmd- and Shift-click, Select All, Invert Selection) and remove them after confirming. Only the history changes; imported files stay where they are.
- Sound Environment with **Off** shows only the environment picker, since nothing is applied: Intensity, parameters and saved environments return when you choose an environment.
- Songs without an artist or album tag are listed as **Unknown Artist** or **Unknown Album**, including the title above the song list.
- Lyrics and playing icons on song rows are evenly spaced and centred.
- The player's progress bar no longer shows a focus outline; Tab and the arrow keys still seek.
- **Resume Where You Left Off** is now off by default for new installs and after Reset to Defaults. A setting you already chose is kept.

## Fixes

- Quitting Minecraft during or soon after a restore no longer overwrites the restored listening history or settings, and an interrupted restore is still rolled back on the next start.
- Listening done while the restore confirmation is open is kept in a merge restore.
- Reset to Defaults closes every old service even if one fails to close, and waits for final cache and history writes before clearing them.
- If startup fails part way, DarkMusic stops cleanly and asks for a restart instead of running half-started.
- Switching an environment high- or low-pass filter back on no longer replays old audio, and the analyzer ignores bypassed filters.
- The current song stays in Never Played until playback moves on, so Jump to Playing still finds it there.

## Compatibility

Install **`darkmusic-3.6.0.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Existing libraries, playlists, histories and settings remain supported. PiP and Media Controls remain optional Windows x64 extensions installed through their in-game menus; use the matching v3.6.0 packages. **Do not put extension ZIPs in `mods`**. The music player supports Windows, Linux and macOS.
