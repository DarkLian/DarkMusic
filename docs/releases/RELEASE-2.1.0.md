# DarkMusic v2.1.0

## Import, organize, and edit your music

- Drag audio files into the player or playlist manager to copy them into your library. The selected folder or playlist determines the destination; Artists and Albums fall back to All Tracks.
- Review saved import results through Import History beside Open Folder. Existing destination files are preserved with numbered filenames for new copies.
- Use the new heart button and built-in **Favorite Music** playlist. Favorites have heart icons in the track list and playlist navigation.
- Edit song titles, artists, albums, and filenames through the pencil button. Metadata is written to the file, extensions are protected, and renamed tracks retain their playlist/favorite references and matching lyric sidecars.
- Editing the playing song safely releases its file handle, then resumes its previous position and playing/paused state.

## Interface refinements

- Add Reset Speed before the speed slider and Mute/Unmute after the volume slider. Controls with shortcuts show the current key bindings in hover help.
- Give the right-panel heart the same button frame as the pencil. Hide both when no song is selected, and fix the empty-state icon overlap.
- Use vertical handles without horizontal fill on Settings and Audio Options sliders.
- Consolidate circular icon animation into **Audio-Reactive Icon: Off / Audio Level / Beat** in HUD settings, preserving existing preferences.
- Keep the Windows external-drag overlay stable when the target gains focus and while the drag source owns mouse capture.
- Preserve embedded artwork when reading and editing supported Ogg/Opus metadata.

## Publication

The approved changes are packaged with refreshed documentation and fullscreen GUI-scale-3 screenshots taken with lyrics disabled. No additional test suite was run for this publication, as requested.

## Requirements and files

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.1.0.jar`: install this mod.
- `darkmusic-2.1.0-sources.jar`: DarkMusic source archive.
- `darkmusic-2.1.0-third-party-sources.zip`: corresponding sources and notices for bundled libraries.

See the [User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.1.0/docs/USER_GUIDE.md) for setup and usage.
