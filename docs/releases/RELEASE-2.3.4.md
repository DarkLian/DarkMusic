# DarkMusic v2.3.4

## Cleaner player controls

- Use larger, borderless playback icons with existing hitboxes, shortcuts, hover feedback, focus indicators, and selected states.
- Restyle Volume as a slim filled line with a percentage readout. Speed uses a matching unfilled line and multiplier readout. Preserve slider lengths, centered alignment, reset, and mute actions.
- Apply the same borderless styling to the main menu's icon-only utilities, including Open Folder, Import History, Refresh Library, Favorite, Edit, and source-link actions.
- Fix collection tooltips adding an empty subtitle line, and vertically align single-line collection labels with their icons.
- Center Manage Playlists above the right details section.
- Place Lyrics between Edit and Open Source, visible only when lyrics are enabled. Keep copy confirmation after the last action; use a brief tooltip when the panel is too narrow for the full text.

Existing playback, library, downloading, audio processing, and saved settings remain unchanged. No configuration migration or new dependencies.

## Files and requirements

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only.

- `darkmusic-2.3.4.jar`: install this file.
- `darkmusic-2.3.4-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.4-third-party-sources.zip`: bundled-library sources and notices.

Documentation screenshots will be updated separately with user-provided captures.

## Verification

- Full local release build passed: 242 tests passed, two optional integrations skipped, no failures. Audio tests used material derived from the private real collection; temporary conversions were removed and original audio was preserved.
- Eight focused layout/tooltip tests passed, including empty collection subtitles, responsive layout, native widgets, and packaging.
- Windows Minecraft development-client checks used the real collection and verified centered controls, equal gaps, borderless styling, Volume/Speed drag mapping, readout clicks, Lyrics placement, and copy-feedback spacing. Private captures were reviewed without publishing them.
- README, User Guide, and public screenshots await the user's updated images.
