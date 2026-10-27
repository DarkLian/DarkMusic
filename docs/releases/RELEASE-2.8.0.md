# DarkMusic v2.8.0

## Lyrics for your collection

- Find synchronized or plain lyrics for existing songs through LRCLIB, without downloading audio again. Reliable matches apply automatically; preview and choose alternatives when a match needs review.
- Background library lookup works through songs one at a time, keeps its place across rescans, and skips unavailable roots. Cached results survive restarts; confirmed empty responses are cached for 14 days.
- Temporary provider, network, and rate-limit failures retry without selecting the song. Delays increase up to 30 minutes and respect longer provider Retry-After values. Edited manual searches retain their query and still require Apply.
- Retry countdowns preserve horizontal scroll position while updating, so the next attempt remains readable.
- Lyrics language is independent of Minecraft and the operating-system locale. Choose ordered caption languages or a per-song preference; generated captions remain optional and labeled.
- Local sidecars, embedded lyrics, and explicitly chosen versions retain priority. Import text, save lyrics beside audio, confirm replacement with backup, and adjust a per-song timing offset without rewriting timestamps.
- Optional post-download lookup starts after audio is saved. Source-caption failure does not fail the audio download.

## A separate lyrics HUD

- Move and resize lyrics independently in Classic, Circular, Artwork, and Spectrum Only layouts.
- Customize width, text colors, background opacity, and the next line, with wrapped lyrics and live preview.
- Keep the live HUD empty before the first timestamp or when synchronized lyrics are unavailable, without a missing-lyrics placeholder.

## Interface fixes

- Consecutive clicks must target the same song or row to activate it. Clicking another control, changing rows, or leaving focus breaks the sequence.
- Lyrics indicators distinguish available, unchecked, missing, and failed lookups with clear symbols and status messages.
- Up Next, Downloads, and Settings have matching button sizes and align with Back buttons; Downloads header controls follow the same height.
- Settings separates Lyrics and Appearance, with dedicated lyrics configuration and preview.
- `/da update` reports when DarkMusic is already current or when a check fails.

## Compatibility and verification

- Existing settings, music files, playlists, and local lyrics are preserved. New preferences and local lyric caches are additive. No new third-party dependency or executable is required.
- Online lookup sends song metadata to LRCLIB, not audio files. Turn it off in Advanced Lyrics to use local/saved lyrics only. The master Enable Lyrics setting retains existing preferences and starts off on new installations.
- Clean Windows build and full local checks passed: **438 tests passed; two optional integration checks skipped**. Tests used private real-audio excerpts and existing managed tools, with originals preserved.
- A separate live LRCLIB lookup passed through the production HTTP client using public song metadata. This does not establish coverage for a private library.
- Native Windows checks passed in English and Traditional Chinese at fullscreen 1920×1080, GUI scale 2, with SmoothFont. Coverage includes real-font retry scrolling, click targets, compact/wide layouts, independent HUD editing, real playback, pause/seek/resume/speed, offsets, and Up Next.
- Provider coverage, prolonged external outages, native Linux/macOS behavior, and gameplay frame-time impact are not established by these tests. Hosted release builds validate Windows, Linux, and macOS. Captions depend on the source and installed tools; ambiguous caption languages require an explicit preference.
- Audio transcription, translation, and AI alignment are not included. Plain lyrics remain unsynchronized.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**.
