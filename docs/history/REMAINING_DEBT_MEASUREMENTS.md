# Remaining hot-loop measurements

Recorded before and after each candidate on the same Ryzen 7 7800X3D and Microsoft JDK under
`C:/Users/User/.jdks/ms-25.0.4.1`. Native smoke, debugger sessions and other repository builds finished before timing.
The IDE and ordinary system background processes remained running; this is not a dedicated benchmark host.

Each method has 10,000 warm-up calls and 15 measured rounds of 1,000 calls. Results below are microseconds per
operation. Spread means p90 minus p10 across the rounds, with no statistical significance claim. Raw rounds,
full minimum/maximum, test XML and two independent original baselines remain in ignored verification evidence.
The second original baseline was selected as the primary comparison before trying any split; the first remains
available to show allocation/GC noise, especially in the waveform analysis.

Keep rule: output must match every pre-split golden bit, and the absolute median change must be below the larger
before/after spread. A retained split is not a claim of zero cost or a speedup on other systems. Timing never fails CI.

| Method/workload | Before median | Candidate median | Change | Larger spread | Decision |
|---|---:|---:|---:|---:|---|
| TrackWaveform.analyze, 16,384 frames | 70.934 | 70.875 | −0.083% | 5.451 | Keep final envelope phase |
| SpectrumAnalyzer.analyze, 2,048-point FFT / 64 bands | 28.406 | 26.332 | −7.302% | 6.219 | Keep transform phase; no speedup claim |
| CircularHudMesh.Rings.buildVertices | 146.775 | 155.521 | +5.958% | 15.824 | Keep progress/contour phases under the noise rule |
| SpectrumRenderer.bars | 14.858 | 15.189 | +2.228% | 0.747 | Keep contour/bar-row phases |
| SoundEnvironmentProcessor.Engine.process, 256 stereo frames | 19.353 | 19.847 | +2.556% | 5.114 | Keep input/space/noise phases |
| AudioProcessor.process, 256 stereo frames | 167.129 | 169.419 | +1.370% | 9.656 | Keep private DSP phases |

All six attempted candidates matched their fixed goldens and passed focused functional/guard suites before
retention. H1-H4 helpers are final-analysis, per-block or per-frame calls. H5's private per-sample helpers are guarded at
325 encoded bytes: smoothInput 151, smoothSpace 236 and generatedNoise 216. No per-sample allocations were added.
H5 uses fresh quiet H56 baseline 2; the user confirmed continuation after publication. H6 uses the immediate
H5-retained run as its pre-split control, chosen before attempting H6 (167.129 median, p10 165.130, p90 174.786).
H6's after p10/p90 are 168.087/172.441. Its 2.289 median delta is below 9.656; comparison to H56 baseline 2
also fits (168.728 to 169.419, +0.410%, delta 0.691 below 4.970). No speedup or universal zero-cost claim is made.
H6 helpers filterTone/stereoOptions/reverbCompression/blendOptions/applyEnvironment/feedProtection/switchProtection
are private and respectively 129/126/114/145/143/58/198 bytes. Worker-owned stereo scratch adds no per-sample
allocation; neutralBlock runs once per buffer. Both per-sample hot entries are removed; only recipe data remains.

## Workloads and scope

- Waveform: a 16,384-frame excerpt of the real private clip, including stream creation and bounded envelope arrays.
- FFT: the fixed real-input ring; the wrapper calls the actual private analyze method without charging PCM decoding.
- Circular mesh: 192-detail beveled filled wave, 36 bands, glow, rotation and a partly filled progress ring. A concrete
  primitive checksum sink consumes vertices; reflection/proxy dispatch is absent.
- Rectangular spectrum: 220-pixel beveled wave, 64 fixed real-audio bands, trails and peaks; mesh construction and
  analyzer snapshot copying are included. A null extractor excludes GPU submission; glow is checked by native smoke.
- Environment: the DREAMSCAPE engine, warmed histories, real PCM in 256-frame stereo blocks.
- DSP: EQ, compressor, true-peak limiter, reverb and crossfeed, 256-frame real PCM blocks copied before processing.

Exactness fixtures separately cover all 24 environment enum values through both direct Engine and AudioProcessor,
neutral/EQ/limiter/full-chain modes, waveform/band arrays, rectangular styles/shapes/directions/finishes/trails/peaks,
and circular styles/modes/finishes/glow/rotation. Three repetitions must match, then fixed raw-bit SHA-256 resources
are checked. Audio-dependent tests explicitly skip on hosted CI when the private clip is absent; a different clip
still runs repetition checks but cannot claim equality to these private-clip goldens.

Original G2 method bytecode sizes were 804, 1099, 1570, 1428, 1474 and 1731 bytes respectively. None exceeded 8000.
No timing-based justification for prioritizing a huge interpreted method was needed.
