# DarkMusic v3.0.1

## A smaller mod, optional PiP setup

The main DarkMusic JAR is now approximately **4 MB**, down from approximately 79 MB. All PiP feature code and the existing icon remain in DarkMusic; there is **no companion mod**.

If you want PiP:

1. Open its icon beside Manage Audio Files, or use `/da pip`.
2. Choose **Check PiP Requirements**.
3. Confirm **Install PiP Dependencies** to download the optional runtime.
4. Confirm **Enable PiP Access**, choose a window, and start capture.

The runtime stays inside your Minecraft instance, with no system-wide installation or restart. Installing it does not enable capture. PiP Audio Spectrum asks for separate audio access. Disable PiP Access to stop capture and its shortcuts immediately.

## Safe defaults and compatibility

- Capture and audio access default off, including when upgrading from v3.0.0. Existing display settings remain saved.
- Downloads are explicit, cancellable and checksum-verified. Existing valid dependencies are reused after relaunch; ordinary startup and requirements checks do not download anything.
- The matching optional Windows x64 dependency ZIP is included as a separate release asset. **Do not put that ZIP in `mods`**; use the in-game installer.
- PiP requires Windows x64, build 19041 or later and compatible graphics capture. Its audio spectrum requires build 20348 or later. The music player remains available on Windows, Linux and macOS.
- Fixed macOS CI fixtures using symlinked temporary paths without weakening installation-path protections.
- Existing music, playlists, lyrics, Up Next, audio profiles and other settings are preserved. No changes to music DSP, capture buffering or frame-rate defaults.

## Verification and limitations

Local Windows release checks use the private real collection and existing managed tools. Setup screens were exercised at fullscreen 1920x1080, GUI scale 2 with SmoothFont, including canceled prompts, offline dependency verification, explicit access and revocation. Automated tests cover installation, reuse, corruption, cancellation, linked paths, archive collisions, defaults and migration.

Clean local validation passed **551 tests**, with four skipped (555 total, zero failures/errors). The four packaged assets, their manifest/checksum links, runtime licenses and 1,607 matching localization keys were checked. Hosted release builds cover Windows, Linux and macOS; they do not establish native PiP operation outside Windows.

This release changes packaging and consent, **not video throughput**. Earlier capture measurements predate a user-reported AMD FreeSync correction; a post-correction Chrome/fullscreen comparison is pending. Sustained fullscreen 144 FPS, every browser/GPU, other Windows builds and native Linux/macOS PiP are not claimed. Existing intermittent capture issues remain documented.

Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.2. See the [setup guide](https://github.com/DarkLian/DarkMusic/blob/v3.0.1/docs/PIP-OPTIONAL-INSTALLATION.md) and [verification notes](https://github.com/DarkLian/DarkMusic/blob/v3.0.1/docs/TESTING.md).
