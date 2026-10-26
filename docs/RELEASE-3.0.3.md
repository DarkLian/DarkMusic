# DarkMusic v3.0.3

## Warning for exclusive fullscreen

- On a tested 144 Hz system (VSync off, 140 FPS cap, Fabric API and DarkMusic only), visible tearing across the whole screen appeared in the inventory and the PiP layout editor while PiP was capturing in **Exclusive** fullscreen. Borderless and windowed mode did not show it, and turning VSync on did not fix it.
- PiP settings now show a warning when Minecraft is set to exclusive fullscreen, with English and Traditional Chinese text. Turn off Exclusive Fullscreen (Borderless) or use windowed mode; Minecraft may need a restart.
- The cause is not fully established, and other graphics hardware has not been tested. Sodium is not required to reproduce it.

## Compatibility

No capture, playback or settings changes. Existing settings, libraries, playlists, lyrics and Up Next remain compatible, and no new dependencies are added. PiP runtime dependencies are installed through the in-game setup, as before; do not put the dependency ZIP in `mods`.

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.2. Native PiP is Windows x64 only; the music player remains available on Windows, Linux and macOS.
