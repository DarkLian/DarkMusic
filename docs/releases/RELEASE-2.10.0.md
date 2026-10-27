# DarkMusic v2.10.0

## Waveform progress

- Optional full-track waveform progress in the main player and Classic, Circular, and Artwork HUDs. Seek through the song using its actual musical envelope.
- Choose Filled Wave or Rounded Bars and adjust height, detail, spacing, smoothing, contrast, opacity, and the unplayed color. Existing progress colors and finishes remain shared.
- Settings shows a live Main Menu Progress Preview alongside the HUD preview, including when the HUD is disabled.
- Analysis runs on one bounded background worker and caches results locally across launches. A normal bar remains available while analysis loads or is unavailable. Waveform progress is off by default.

## HUD images and spectra

- Classic, Circular, and Artwork + Spectrum each remember a Song Artwork / DarkMusic Icon choice. Artwork is the default, with the icon as fallback. Circular artwork is center-cropped without stretching.
- Circular Image-to-Ring Gap controls spacing from the image to the nearest progress or spectrum layer, defaulting to zero.
- Rounded Bars spectrum is available in the player and every HUD layout. Rounded peak markers stay thin and appear only when separated from the live bar, preserving peak-hold/decay timing.
- Spectrum Opacity fades bars, curves, peaks, trails, and glow together. Curve Detail is exposed for applicable curve styles; Band Count remains the frequency-detail control.
- Progress settings share the existing category styling, with HUD background controls grouped above them. English and Traditional Chinese strings are included.

## Compatibility and verification

- Existing music, playlists, settings, lyrics, audio profiles, and playback behavior are preserved. New appearance settings are additive; no new dependencies or audio effects.
- Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API.
- Windows private-collection regression checks: 515 passed, two optional live-provider/tool-install checks skipped. Native English and Traditional Chinese smoke checks exercise actual audio, waveform seeking/pause, all HUD layouts, image fallback, and compact/wide bounds at fullscreen 1920x1080, GUI scale 2, with SmoothFont.
- Native Linux/macOS behavior, sustained gameplay FPS under heavy concurrent workloads, and recordings beyond the documented analysis limits remain unverified. Hosted build results do not establish those runtime behaviors.
