# DarkMusic v2.3.5

## Playback and player controls

- Add backward/forward five-second controls beside Play/Pause, with Left/Right arrow shortcuts. Speed shortcuts now default to minus/plus; Stop defaults to P and Jump to Playing to O.
- Use Space for Play/Pause inside the player without restarting the song. Text fields retain normal typing behavior.
- Make manual Previous/Next wrap through the playback collection independently of repeat mode, including after playback history runs out.
- Automatically reveal the current song when tracks or playback collections change. Play starts the viewed song when its song or collection differs from the playing selection; the right-side spectrum appears only for the playing selection.
- Refine the centered Play/Pause control with a smooth square outline, thicker pause strokes, balanced spacing, and a working small pause glyph in download previews.
- Move the speed readout to the left, keep slider rails visible when focused, and add a visible progress rail above the controls.
- Place Manage Playlists in the utility row above the collection list.

## Playlists, downloads, and updates

- Group playlist actions above the collection list, Add/Remove above songs, and selection controls beneath songs. Remove Move Up/Down; drag reordering remains available.
- Give Downloads separate navigation and results panels on wide screens while preserving compact controls, previews, queue actions, and pagination.
- Allow manual release checks in development launches and show the result. Automatic installation remains available only for installed mod JARs.

## HUD and settings

- Add optional Audio-Reactive Artwork to the Artwork + Spectrum HUD without stretching the image.
- Remove the circular progress rail when progress is disabled and connect the spectrum to the icon edge.
- Consolidate Appearance and Effects into HUD and Spectrum. Place spectrum choices first, effect switches next, then colors and sliders.
- Remove the visualizer quality selector. New/reset settings enable all four individual spectrum effects without increasing the existing rendering detail. Existing saved choices remain intact.

Existing libraries, playlists, audio processing, and saved preferences are preserved. The new artwork-reactivity preference is additive. Uncustomized legacy speed-arrow bindings migrate to the new defaults; custom bindings are retained. No new dependencies.

## Files and requirements

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only.

- `darkmusic-2.3.5.jar`: install this file.
- `darkmusic-2.3.5-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.5-third-party-sources.zip`: bundled-library sources and notices.

Documentation screenshots will be replaced separately with user-provided captures.

## Verification

- Clean Gradle release build passed: 245 tests passed, two optional integrations skipped, no failures. Tests used audio derived from the private real collection. Original audio is preserved and temporary conversions are removed afterward.
- Windows Minecraft development-client checks cover pause/resume without resetting the playhead, five-second seeking, collection-specific playback, shortcuts, selection actions, HUD previews, download layout, and manual update-check feedback.
- Player and playlist widget bounds are checked at compact and wide GUI sizes; playlist controls are checked for overlap. Play-control spacing is measured from native GUI-scale-3 captures.
- External file chooser and fresh managed-tool installation are optional integrations and are not rerun for this release. Physical keyboard/focus transitions, other operating systems, and live multiplayer usage require broader manual testing.
