# DarkMusic v3.1.1

## PiP installs its native helper separately

The Windows PiP helper now installs together with its runtime dependencies in the optional PiP package. The main mod JAR keeps the Java integration and installation manifest, with no bundled native executable, DLL or helper ZIP.

- PiP stays optional. Open the PiP menu, check requirements, then confirm **Install PiP Dependencies**.
- Upgrading from the earlier packaging requires installation of the new matching package. Appearance settings and existing capture/audio permissions are preserved; capture never starts automatically.
- The installer still verifies the exact package size and pinned SHA-256 before safe extraction. Cancellation, separate audio consent and restart reuse remain supported.
- No companion mod, system-wide installation or restart is required. **Do not put the dependency ZIP in `mods`.**

This changes distribution and setup only. Capture pacing, protocols, audio processing, device requirements and the existing exclusive-fullscreen warning remain unchanged. It does not establish approval by any mod-hosting platform.

## Compatibility and verification

Install **`darkmusic-3.1.1.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. Existing libraries, playlists, lyrics, Up Next and settings remain compatible. The music player supports Windows, Linux and macOS; native PiP requires Windows x64 and compatible Windows Graphics Capture support.

The implementation passed a clean full Windows test run (**603 passed, six optional/environment skips, zero failures/errors**) and an isolated Minecraft PiP setup smoke at fullscreen 1920×1080, GUI scale 2, with SmoothFont. Regression tests cover combined-package verification, migration, cancellation, restart reuse, traversal/collision rejection and exclusion of native payloads from the main JAR. The helper's native self-tests and Windows graphics probe passed. See [testing documentation](../TESTING.md) for release validation and remaining limits.
