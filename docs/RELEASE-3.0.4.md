# DarkMusic v3.0.4

## Capture follows your frame limit

- PiP now asks Windows to deliver new window frames at about twice your chosen frame limit, never slower than the Windows default of 16 ms and never faster than 1 ms. Earlier versions kept Windows' 16 ms default, which held capture near **48 FPS** on a 144 Hz display even with a higher limit chosen.
- Your frame limit still decides the actual capture rate. A lower limit does not capture more frames than you chose, and Match Monitor and manual limits from 15 to 480 FPS behave as before.
- On one tested system (Windows 11 build 26300, Radeon RX 9060 XT, 144 Hz, Minecraft in exclusive fullscreen at 1920x1080, Chrome as a 144 FPS source), a 144 FPS limit delivered about **143 FPS** and a 60 FPS limit delivered **60 FPS**. Upload to Minecraft is bounded by its render rate, about 139 FPS in that test.
- Higher limits use more CPU and memory bandwidth, as the guide already says. That cost was not measured for this change.
- Windows builds that do not offer this setting keep the default behavior. Only build 26300 was tested.

## Exclusive fullscreen warning (from v3.0.3)

PiP settings still warn when Minecraft is in exclusive fullscreen, where tearing appeared on one tested system. Borderless or windowed mode avoids it. Earlier reports of a drop to about 66 FPS behind exclusive fullscreen did not reproduce after a system-wide AMD FreeSync/Adaptive Sync correction and a Chrome source; the earlier tests used a different source and forced exclusive fullscreen.

## Compatibility

Settings, libraries, playlists, lyrics and Up Next remain compatible. No new dependencies or mandatory tools. PiP runtime dependencies are installed through the in-game setup after confirmation; **do not put the dependency ZIP in `mods`**. The capture helper is bundled in the mod JAR, and the optional dependency ZIP is byte-identical to v3.0.3 (SHA-256 `55eccdc65bc1242f7d2fae0547ab7a35ab3a435a50a831d239653f932a2f5273`), so existing installed dependencies keep working.

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.2. Native PiP is Windows x64 only; the music player remains available on Windows, Linux and macOS.
