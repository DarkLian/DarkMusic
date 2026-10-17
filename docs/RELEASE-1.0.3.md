# DarkMusic v1.0.3

Custom spectrum/progress colors, clearer downloads, and navigation that follows your playback queue.

- Add independent **Spectrum Colors** and **Progress Bar Colors**: Moonlight (the unchanged default purple), Aurora Borealis, Neon Dream, Solar Flare, Ocean Drive, Sakura Sky, Glacier Glow, Ruby Pulse, and custom solid/mixed hex colors. Changes appear immediately in the player and HUD previews.
- Add client-only `/da` and `/darkmusic`, with `help` and `gui` subcommands. Help produces local feedback with useful links.
- Browse collapsible music folders under All Tracks. Jump to Playing restores the folder, playlist, artist, album, and search that started playback, following track changes without restarting music.
- Loop long gameplay HUD song titles from right to left. Remove generic horizontal-scrolling hover hints; instructions remain in the guide.
- Run up to three downloads concurrently with safe shared history and file publication. Show gameplay progress and fading outcome notices outside the download menu.
- Automatically retry failed downloads after the queue pass finishes, up to three additional attempts. Add Retry Selected, Retry Failed, and Clear Failed. Clear Finished preserves failures; media is never deleted by queue cleanup.
- Retain analyzed previews and URLs across menu exits. Add separate Entries/Queue searches; filtered actions use stable job IDs. Disable Mode/Download on Queue and irrelevant bottom actions in either view. Align Tools with Analyze.

## Install

Requires Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API for **26.2**, and **Java 25+**. Install **darkmusic-1.0.3.jar** with Fabric API. Source archives are not mods. Personal media and external download executables are not bundled.

Existing settings/key binds remain compatible. New color settings default to the original purple. Reset restores those defaults without deleting music, video, downloaded media, or playlists. Download only content you are authorized to save; site support and access depend on yt-dlp.

The three-worker cap is retained to limit resource contention and excessive requests; it does not grow with playlist length. Automatic retry does not bypass authentication or access restrictions.

[User guide](https://github.com/DarkLian/DarkMusic/blob/v1.0.3/docs/USER_GUIDE.md) ? [Testing](https://github.com/DarkLian/DarkMusic/blob/v1.0.3/docs/TESTING.md)

## Verification

All **108 automated tests passed locally without skips**, including real-tool downloads/conversions, concurrent history updates, retries, and color migration/persistence. Development-client checks passed for player/colors/source jumps, filtered queue actions, retained previews/audio audition, 96 HUD combinations, reset/media preservation, and restart persistence. Ten original fullscreen GUI-scale-3 captures were verified; five are used in the public guide. IntelliJ Reformat Code and Code Cleanup were applied. Hosted CI explicitly skips private-audio/desktop integrations it cannot run. These checks do not claim exhaustive multiplayer, full-modpack, site, or Linux/macOS runtime coverage.
