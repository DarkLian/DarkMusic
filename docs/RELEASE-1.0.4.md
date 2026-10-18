# DarkMusic v1.0.4

New HUD designs, spectrum shapes, and optional shaded depth, using the existing audio analysis and playback controls.

- Add an optional **Circular** HUD: the original DarkMusic icon, a clockwise progress ring, and an outward spectrum attached directly to the ring. Move and scale it as one component.
- Offer **Filled Bars, Filled Waves, and Filled Spike** for Circular, with preferences separate from Classic. Toggle centered song information and a gentle audio-reactive icon pulse independently.
- Add **Smooth Wave, Filled Wave, Spike, and Filled Spike** alongside Filled Bars for the Classic HUD and music player. Animation uses actual decoded audio samples.
- Add Rectangle, Rounded Card, and Chamfered Card backgrounds, with an optional Raised HUD Finish for Classic.
- Add independent **Flat / Beveled** spectrum and progress finishes in both HUD styles and the player. Beveled lighting stays within the original layout bounds; Flat remains the default.
- Update the live settings preview, layout editor bounds, English/Traditional Chinese labels, and guide screenshots. Remove the small redundant artwork icon beside the player's Playing status.

## Install

Requires Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API for **26.2**, and **Java 25+**. Install **darkmusic-1.0.4.jar** with Fabric API. The source archives are not installable mods. Personal media and external download executables are not bundled.

Existing installations retain their settings, positions, colors, and key binds. Classic with flat finishes remains the default. Circular song information and icon pulse default on when that style is selected. Switching styles preserves the other style's preferences. No gameplay packets or multiplayer networking are changed.

[User guide](https://github.com/DarkLian/DarkMusic/blob/v1.0.4/docs/USER_GUIDE.md) · [Testing](https://github.com/DarkLian/DarkMusic/blob/v1.0.4/docs/TESTING.md)

## Verification

All **122 automated tests passed locally without skips**, including real audio, download-tool integrations, settings migration/persistence, circular geometry, and bounded bevel shading. Minecraft development-client verification covered 96 HUD combinations, 180 visual style/shape/depth/direction/GUI-scale combinations, Circular controls and toggle combinations, pause/seek/stop synchronization, icon pulse, and both finishes. IntelliJ Reformat Code and Code Cleanup were applied.

Release screenshots use the private real music collection, fullscreen, and Minecraft GUI scale 3. Hosted CI explicitly skips private-audio/desktop integrations it cannot run. These checks do not claim exhaustive modpack, multiplayer, site, or Linux/macOS runtime coverage.
