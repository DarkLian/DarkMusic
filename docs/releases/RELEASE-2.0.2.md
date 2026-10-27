# DarkMusic v2.0.2

## Performance and visual polish

- Cache unchanged EQ filter coefficients and frequency-response calculations while preserving exact audio output, smoothing, and headroom.
- Coalesce loudness-cache writes, flush pending results on shutdown, and retry failed writes. Load larger caches and preserve unreadable cache files and offline-library entries.
- Avoid rebuilding audio settings and the prepared playback queue when settings have not changed.
- Give classic and circular spectrum trails a fading opacity gradient. Preserve spectrum detail, peak timing, glow, and beveled effects.
- Skip halo contour generation when the glow is fully clipped.

## Reliability fixes

- Validate detached audio settings before publishing them to the playback worker.
- Use consistent settings and speed snapshots during crossfades.
- Distinguish slow decoding from stalled audio output.
- Wait for playback shutdown before reset clears cache files.
- Save successfully read metadata during partial library scans without pruning inaccessible entries.
- Report download-tool redirects with a missing Location header as a controlled error.

## Verification

The approved implementation passed real-audio tests, FFmpeg/yt-dlp integration, cache-recovery checks, and native Minecraft HUD comparisons. Local EQ comparisons produced exact PCM matches against the previous implementation. No visual-detail reductions or audio-processing-order changes were made.

## Requirements and files

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.0.2.jar`: install this mod.
- `darkmusic-2.0.2-sources.jar`: DarkMusic source archive.
- `darkmusic-2.0.2-third-party-sources.zip`: corresponding sources and notices for bundled libraries.

See the [User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.0.2/docs/USER_GUIDE.md) for setup and usage.
