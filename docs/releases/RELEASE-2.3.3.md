# DarkMusic v2.3.3

## Player layout

- Move Reset Speed and Mute inward, beside the playback buttons, with Speed and Volume sliders at the outer edges.
- Use equal horizontal spacing between controls and keep the full control row centered.
- Restore the original slider lengths; the control row and middle song list no longer need to share a width.
- Center the middle song list independently in the three-column layout while preserving the right details panel's width and position.
- Preserve compact layouts, control callbacks, shortcuts, seeking, and playback behavior.

## Requirements and downloads

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only. No configuration changes or new dependencies.

- `darkmusic-2.3.3.jar`: install this file.
- `darkmusic-2.3.3-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.3-third-party-sources.zip`: bundled-library sources and notices.

[User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.3.3/docs/USER_GUIDE.md)

## Verification

- Full local `clean build`: 241 tests passed, two optional integrations skipped, no failures. Audio-dependent tests used a temporary conversion from the private real collection; the conversion was removed and original files were preserved.
- Seven focused menu/layout tests passed, covering independent centering, original slider widths, responsive breakpoints, details-panel bounds, native widgets, and icon/gradient packaging.
- Source changes were formatted with IntelliJ IDEA. No new runtime playtest was performed after the final slider-length revision.
- Existing screenshots are retained. Updated screenshots will be supplied separately.
