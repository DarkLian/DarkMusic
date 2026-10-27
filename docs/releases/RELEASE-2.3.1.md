# DarkMusic v2.3.1

## Audio correctness and usability

- Preserve Pitch now works with Neutral Audio, including incoming crossfade tracks. Selecting an audio profile preserves this playback preference.
- Loudness status distinguishes actual track/album metadata, file analysis, pending/failed measurements, and peak-headroom adjustments. Album fallback appears only when usable track data is being applied.
- Saved audio profiles and environments require confirmation before replacement. Invalid names, capacity limits, and save failures receive visible feedback.
- Choose all 20 Sound Environments directly from categorized lists. Descriptions and inactive-control guidance make their audible behavior clearer.
- Frequency sliders use logarithmic spacing. Right-click a numeric slider, or focus it and press Enter, to enter an exact value.
- Simplify navigation: Sound Environment is in General Settings; Audio Analyzer is in Audio Options and offers its own comparison-mode selection.
- Localize player-facing download, tool, audio-device, and configuration messages in English and Traditional Chinese.
- Clarify sample versus estimated true peaks and unavailable measurements. Hidden analyzer graphs no longer request their expensive measurements.
- Refresh documentation screenshots, including Advanced Audio, environment selection, and the analyzer.

Existing settings and saved profiles remain compatible. This update adds no third-party dependencies or new DSP algorithms and preserves default sound settings and HUD rendering.

## Verification

- Full local Gradle build: 225 tests passed, two optional integrations skipped, no failures.
- Regression coverage includes Neutral/Preserve Pitch combinations, live changes, transitions, loudness provenance, safe presets, numeric editing, localization, and existing DSP behavior.
- Windows development-client checks used the private real collection: all environments, preset dialogs, exact input, English/Traditional Chinese, compact layouts, GUI scales, and persistence after restart.
- Final menu changes compiled successfully. Documentation captures use fullscreen GUI scale 3 with lyrics disabled.
- Same-source 48 kHz stereo worker benchmark: convolution plus true-peak averaged 1.022 ms per 1,024-frame block versus 1.046 ms on v2.3.0; p99 was 1.295 ms versus 1.500 ms. Output checksums matched. These are local CPU timings, not gameplay FPS.

Extended gameplay, physical device hot-plug, Linux/macOS runtime, and public-site end-to-end downloading were not verified for this update. Local HTTP downloading and real conversion tools were tested. Audio output continues to use the Java Sound default device.

## Requirements and files

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.3.1.jar`: install this mod.
- `darkmusic-2.3.1-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.1-third-party-sources.zip`: corresponding bundled-library sources and notices.

[User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.3.1/docs/USER_GUIDE.md)
