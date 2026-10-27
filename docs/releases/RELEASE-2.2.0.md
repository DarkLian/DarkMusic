# DarkMusic v2.2.0

## HUD and color customization

- Add the Artwork HUD: large, proportional artwork with an optional progress bar directly beneath it and a spectrum above, below, left, or right. Side spectra grow horizontally outward. Move and scale the whole component together.
- Adjust spectrum height in all three HUD layouts.
- Customize Classic and Artwork backgrounds with solid colors, gradients, and transparency. Artwork keeps its own opacity and aspect ratio; verified uniform padding is omitted.
- Add a visual hue/saturation/brightness picker alongside RGB mode, retaining palettes and hex input. Add concrete Default actions, 24 additional gradient presets, and global menu background/accent colors.
- Use a plain background in the live HUD preview.
- New-installation and reset defaults: lyrics off, fully transparent HUD background, Enhanced visualizer quality, Beveled spectrum/progress finishes, and Ocean Drive colors. Existing saved preferences are preserved.

## Library and player

- Open or copy a song's known source link from its details panel. Successful copying displays **Copied!**. Source associations follow downloads, imports, and renames.
- Automatically remove confirmed missing playlist and Favorite Music entries after a complete scan. Incomplete scans and unavailable library folders do not trigger cleanup.
- Add a dedicated Use Default Music Folder setting.
- Delete saved audio profiles with confirmation while keeping current sound settings and built-in profiles.
- Refine the heart and copy-link icons and enlarge UI icons within their existing control boxes.
- Move Refresh Library beside Import History and extend the search field. Fix toolbar overlap beside Manage Playlists in narrow layouts.

## Downloads, lyrics, and reset

- Add pause/resume and a separate Stop control for download audio previews.
- Support ordered preferred lyrics languages and regional variants. Auto follows Minecraft's language; caption selection prefers matching manual captions without arbitrary language fallback.
- Remove lyrics availability fields and per-entry caption checks from download analysis results. Validated local-library lyric indicators remain available.
- Remove speculative external-drag hover detection; real file drops and import progress remain supported.
- Reset to Defaults preserves installed download tools and configured executable paths. It restores the music folder path and clears recognized histories/caches without deleting music, videos, or playlists.

## Verification

- Local regression suite: 198 passed, 2 optional integrations skipped, no failures. Audio checks used temporary conversions from the private real collection.
- Minecraft development-client checks covered color editing, artwork fitting, side spectra, background transparency, preview playback, tool-preserving reset, source-link copying, saved-profile deletion/persistence, and narrow playlist navigation.
- Final changes were manually approved before publication. Private music and test captures are excluded from the release.

## Requirements and files

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.2.0.jar`: install this mod.
- `darkmusic-2.2.0-sources.jar`: DarkMusic source archive.
- `darkmusic-2.2.0-third-party-sources.zip`: corresponding sources and notices for bundled libraries.

See the [User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.2.0/docs/USER_GUIDE.md) for setup and controls.
