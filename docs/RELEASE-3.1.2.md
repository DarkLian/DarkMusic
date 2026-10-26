# DarkMusic v3.1.2

## Clearer advice for PiP screen tearing

Whole-screen tearing while PiP captures was traced to how Minecraft is shown in fullscreen, not to DarkMusic's rendering or capture rate.

- Tearing appeared with Minecraft's **Exclusive Fullscreen**, and with the fullscreen modes of the **Cubes Without Borders** mod. Minecraft's own non-exclusive fullscreen and windowed mode did not tear, with or without Sodium. VSync, FreeSync, frame-rate limits and the capture interval made no difference.
- DarkMusic now lists **Cubes Without Borders** (`cwb`) as a conflicting mod. Fabric logs a warning at startup when both are installed; the game still launches.
- PiP settings now warn whenever **Exclusive Fullscreen** is on, even in windowed mode. Cubes Without Borders keeps that setting on while it manages fullscreen itself, so the warning also catches that mod.
- To avoid tearing, turn off Exclusive Fullscreen in Video Settings and restart Minecraft (the setting only applies at startup), and disable Cubes Without Borders while using PiP.

The underlying cause is not established, and this was confirmed on one system (Radeon RX 9060 XT, 144 Hz, Windows 11). Other graphics hardware has not been tested.

## Compatibility

No capture, playback or settings changes. Existing libraries, playlists, lyrics, Up Next and settings remain compatible. Install **`darkmusic-3.1.2.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.1.2 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
