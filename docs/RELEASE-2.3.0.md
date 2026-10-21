# DarkMusic v2.3.0

## Unified audio experience

- Expand Audio Options with five-node parametric EQ, tone shelves, two dynamic EQ bands, de-essing, headphone crossfeed, and detailed compressor controls.
- Add pitch-preserving tempo, an 8× intersample true-peak limiter with 64-frame lookahead, and partitioned convolution reverb using three original modeled impulse responses.
- Add independent Sound Environment processing: Off plus 19 distinct treatments, adjustable intensity, contextual controls, optional texture/ambience, and custom saved presets.
- Keep Audio Options and Sound Environment usable together. Acoustic environments share the reverb slot and restore Audio Options reverb when disabled.
- Add a device-clock audio analyzer with actual submitted-PCM stage measurements, spectra, waveforms, filter curves, stereo/dynamics meters, and temporary A/B comparison with optional level matching.
- Improve loudness-normalization status reporting, bounded background analysis, and asynchronous cache shutdown.
- Include English and Traditional Chinese controls and help. Preserve existing playback, downloading, playlists, and HUD rendering.

Convolution kernels prepare in the background. The analyzer reports preparation or algorithmic fallback, including unsupported device rates outside 8–192 kHz. The included impulse responses are generated models, not recorded physical rooms.

## Verification

- Full local Gradle build: 216 tests passed, two optional integration tests skipped, no failures.
- Numerical DSP checks include intersample peak stress, convolution correctness, pitch/duration, filter response, dynamics, environment distinctions, and independent effect stacking.
- IntelliJ debugger verification confirmed that live configuration changes alter PCM accepted by the output backend.
- Windows Minecraft development-client checks used the private real music collection: all environment choices, convolution/limiter readiness, tempo, pause/seek/resume/stop, English/Traditional Chinese screens, compact layouts, and settings persistence after restart.
- Same-source worker benchmark at 48 kHz, 1,024 stereo frames per block: neutral processing averaged 0.0044 ms (baseline 0.0040 ms); convolution plus true-peak averaged 1.03 ms, or 1.26 ms with all analyzer panels enabled. Each block represents 21.33 ms of audio. These are local CPU timings, not gameplay FPS measurements.

Extended gameplay, render-thread CPU profiling, and Linux/macOS runtime verification were not performed for this release.

## Requirements and downloads

Minecraft 26.2 · Java 25 · Fabric Loader 0.19.5+ · Fabric API for 26.2. Client installation only.

- `darkmusic-2.3.0.jar`: install this mod.
- `darkmusic-2.3.0-sources.jar`: DarkMusic source archive.
- `darkmusic-2.3.0-third-party-sources.zip`: corresponding sources and notices for bundled libraries.

[User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.3.0/docs/USER_GUIDE.md)
