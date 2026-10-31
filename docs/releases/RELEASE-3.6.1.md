# DarkMusic v3.6.1

## Fixes

- Turning True Peak on or off now fades across the change instead of briefly mixing the sound with a delayed copy of itself.
- Loudness normalization readings are no longer lost when two saves overlap, when another Minecraft instance saves the same cache, or after a song file changes on disk. When the records of failed loudness checks or retry waits fill up, only the oldest or soonest-ending entries are dropped instead of all of them.
- A sleep timer set before Reset to Defaults is cancelled by the reset and never pauses or fades the new player.
- After restoring a backup, Resume Where You Left Off records the playing song straight away instead of up to 30 seconds later.
- A music folder choice or folder reset that finishes after Reset to Defaults or a restore no longer changes the music folder.
- Starting, stopping, pausing or resuming **Play Audio Preview** in quick succession no longer fails.

## Security

- The PiP and Media Controls packages now include .NET runtime 8.0.33 with the latest .NET 8 security fixes.

## Compatibility

Install **`darkmusic-3.6.1.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Existing libraries, playlists, histories and settings remain supported. PiP and Media Controls remain optional Windows x64 extensions installed through their in-game menus; use the matching v3.6.1 packages. **Do not put extension ZIPs in `mods`**. The music player supports Windows, Linux and macOS.
