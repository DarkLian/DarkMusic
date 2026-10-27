# DarkMusic v3.3.2

## Fixes

- **PiP Install repair.** Install now checks a previously downloaded package before downloading again, and reuses it when it is intact. A damaged package is replaced with a fresh download instead of making the install fail. If the unpacked PiP files are damaged, Install extracts them again; when another Minecraft instance is using them, it says so instead.
- **Smoother quiet passages.** 16-bit playback now adds a very low level of dither, so fade-outs and quiet endings fade smoothly instead of stepping. Playback at full volume, normal speed and no effects stays bit-perfect, and silence stays silent. 24-bit output is unchanged.

## PiP

- The **PiP Audio Spectrum** help now notes that apps playing sound through a separate process, such as some Microsoft Store apps, are not captured.

## Under the hood

- Internal code reorganization with no change to settings, files or behavior.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible. Install **`darkmusic-3.3.2.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.3.2 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
