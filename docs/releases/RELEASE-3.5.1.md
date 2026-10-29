# DarkMusic v3.5.1

## Improvements

- Audio profiles now offer Original, a blank Custom starting point, and saved profiles. Edited values belong to the selected profile, with Save updating the saved entry.
- Sound environments put Off in the picker, offer a blank Custom starting point, and keep saved environments selected while editing them.
- Selecting a song from a list preserves your Up Next queue.
- Settings ownership and the connections between screens, playback, HUD, PiP and background workers have been simplified.

## Fixes

- Retry Discord status connections every five seconds when Discord is opened after Minecraft.
- Prevent lost updates when library source evidence changes concurrently.
- Preserve the latest lyrics retry deadline when multiple workers update it.

## Compatibility

Install **`darkmusic-3.5.1.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Existing libraries, playlists and settings remain supported. PiP and Media Controls remain optional Windows x64 extensions installed through their in-game menus; use the matching v3.5.1 packages. **Do not put extension ZIPs in `mods`**. The music player supports Windows, Linux and macOS.
