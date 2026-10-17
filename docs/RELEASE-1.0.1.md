# DarkMusic v1.0.1

> Historical notes for 1.0.1. Menus and defaults below describe that release. See the [current guide](USER_GUIDE.md) and [v1.0.2 notes](RELEASE-1.0.2.md) for current behavior.

This maintenance release improves playback, library recovery, downloads, and settings while preserving the existing player, HUD, and playlists.

- Add English and Traditional Chinese interface text, tooltips, and formatted labels through Minecraft's language setting.
- Reduce the packaged icon from 1,153,624 to 801,515 bytes with lossless compression; its pixels and design are unchanged.
- Make repeat-mode settings type-safe while preserving existing settings and safely handling invalid values.
- Seek once when the progress slider is released, keeping its preview responsive during dragging.
- Improve audio-device sample-rate fallback, 24-bit FLAC decoding, stalled-output handling, duration reconciliation, and idle/paused worker behavior.
- Preserve library caches after incomplete scans, reduce redundant refresh work, recover valid playlist records, and keep Minecraft's saved key bindings authoritative.
- Improve downloader tool rechecks, quoted PATH lookup, error messages, cancellation, bounded diagnostics, and cleanup of interrupted downloads. Active workspaces are protected against cleanup by another client.
- Harden smoke-test success/capture verification and pin GitHub Actions dependencies with automated update checks.

## Install

Requires Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API for **26.2**, and **Java 25+**.

Install **darkmusic-1.0.1.jar** alongside Fabric API in the instance's `mods` folder. Open **Music** on the title screen or press **M** during gameplay. Existing music, playlists, and settings remain compatible.

Downloads still require separately installed yt-dlp, FFmpeg/FFprobe, and any runtime required by the selected site. These tools and personal media are not bundled. The sources JAR and third-party sources ZIP are source archives, not installable mods.

See the [illustrated user guide](https://github.com/DarkLian/DarkMusic/blob/v1.0.1/docs/USER_GUIDE.md) and [testing instructions](https://github.com/DarkLian/DarkMusic/blob/v1.0.1/docs/TESTING.md).

## Validation

All **65 automated tests passed locally without skips**, using the private real music collection and actual download/conversion tools. English and Traditional Chinese in-game smoke tests passed at GUI scales 2, 3, and 6. The real-collection capture session checks playback starts across 33 songs, visualizer synchronization, playlist editing, downloads preview, and HUD controls; public captures use fullscreen GUI scale 3.

Hosted CI deliberately skips the 31 tests requiring unavailable private audio; 34 other checks pass. The title-screen sessions do not exhaustively cover full modpacks, world transitions, every external download site, or physical audio-device disconnection.
