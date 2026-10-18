# DarkMusic v2.0.0

DarkMusic v2.0.0 brings a unified music-library interface, expanded audio controls, in-game downloading, and customizable audio-reactive HUDs.

## Player and library

- Navy and lavender interface with shared controls, icons, keyboard navigation, and draggable scrollbars.
- Browse nested folders, playlists, artists, and albums with track counts and search.
- Centered Speed, Shuffle, Jump to Playing, Previous, Play/Pause, Next, Stop, Repeat, and Volume controls; seek track above them.
- Repeat Off / This / All with distinct mode icons. Search filters the display while playback, shuffle, and repeat use the source collection.
- Larger landscape artwork, corrected embedded side-padding display, and centered playback/lyrics indicators.
- Bulk playlist editing and connected-selection drag reordering.

## Downloads and lyrics

- Video and collection analysis, selected entries/ranges, audio preview, direct available-page selection, and searchable queues.
- Three parallel jobs, automatic retries, manual selected/all-failed retries, cancellation, and background download notifications.
- Managed yt-dlp, FFmpeg/FFprobe, and Deno setup, with custom executable paths available.
- Original-format or MP3 downloads with supported metadata/artwork; immediate library discovery.
- Timed local/embedded lyrics and optional available source captions, with explicit availability states.

## Sound and HUD

- Audio profiles, loudness normalization, 10-band EQ, bass, stereo width, compression, reverb, and limiting.
- Gapless playback, crossfade, Focus/Ambient balance, and priority-sound ducking.
- Classic and circular HUDs with real PCM/FFT visualization, color presets, waves/spikes/bars, beveled finishes, glow, trails, peak hold, and beat-reactive animation.
- Unified settings, live preview, draggable HUD layout, scroll-to-scale editing, and concise per-control help.
- Batched spectrum geometry and bounded artwork/lyrics workers preserve responsive rendering.
- Client-side commands and configurable playback, speed, mute, and volume shortcuts.

The bundled interface is English. Song rows do not display hover popups. Documentation and screenshots describe the current interface.

## Requirements and files

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.0.0.jar`: install this mod.
- `darkmusic-2.0.0-sources.jar`: DarkMusic source archive.
- `darkmusic-2.0.0-third-party-sources.zip`: corresponding sources and notices for bundled libraries.

External formats and downloads need compatible tools. Source-site support and caption availability vary. Audio files and development resources are not included.

See the [User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.0.0/docs/USER_GUIDE.md) for setup and usage.
