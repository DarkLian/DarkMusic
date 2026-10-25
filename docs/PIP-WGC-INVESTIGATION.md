# WGC frame-rate investigation

October 26, 2026, measurements made in the 3.0 development workspace before release preparation. Measurements precede any capture architecture change. **144 FPS capture is demonstrated in isolation and with windowed Minecraft, but is not achieved behind exclusive-fullscreen Minecraft on this machine.**

## Findings

1. WGC reports a default `MinUpdateInterval` of **16 ms**. An otherwise empty capture callback acquires about **48 FPS**, not 144. Explicitly requesting **4 ms** raises isolated acquisition to **144 FPS**. This is a session-configuration limit, independent of RGBA conversion, IPC, Java or OpenGL.
2. Exclusive fullscreen introduces a separate limit. At 4 ms, a discard-only callback acquires **66 FPS** with fullscreen Minecraft covering the source, versus **144 FPS** with windowed Minecraft. Switching the same full-pipeline capture session from windowed back to fullscreen reproduces the fall to **66 FPS**. Source presentation remains about 144/s. This implicates upstream compositor/display-mode pacing; it does not establish the exact DWM/driver/independent-flip mechanism.
3. Native transfer and Java reception track callback delivery. The measured copy and upload costs do not explain either the original 48 FPS ceiling or the fullscreen 66 FPS ceiling. Replacing the transport with GPU sharing is not justified as the first correction.
4. OBS Modern Window Capture also delivers approximately **48 distinct images/s** from this source in the default-session comparison, despite recording at 144 FPS. A 144 FPS output setting alone does not establish 144 distinct captured frames.

## Equipment and procedure

- Windows 11 Home, build **26300** (`10.0.26300.0`), Ryzen 7 7800X3D, Radeon RX 9060 XT, approximately 32 GB RAM, 144 Hz display.
- Selected source: one disposable 1920x1080 Direct3D application presenting at approximately 144/s. Each frame shifts real private-collection artwork by one pixel; consecutive source images differ. No synthetic music or cover artwork was generated, and no media is included in this report or public assets.
- Source telemetry counts successful application draw/present calls, **not physical scanout**. It continued near 144/s during these tests.
- Minecraft: GUI scale 2, SmoothFont, VSync off, 144 FPS cap; stationary in a private disposable world. Fullscreen was 1920x1080, exclusive fullscreen enabled. The diagnostic windowed comparison used its existing 1280x720 client size. This is a presentation-mode comparison, not an equal-resolution rendering benchmark.
- PiP captures the full 1920x1080 source; the displayed fullscreen PiP width is 576 physical pixels. PiP audio spectrum and DarkMusic music playback were stopped for isolation. No audio/DSP architecture was changed.
- Unbound input resets Minecraft's inactivity timer before samples. World/HUD, fullscreen state, phase and render counters were recorded. Menus, warm-up and AFK-throttled samples were excluded.
- Probe runs lasted approximately 20 seconds each; rates below trim the first 3 and final 1 seconds. Full-pipeline samples use explicit approximately 30-second windows, with a final 20-second fullscreen crossover. Small count differences at window boundaries are expected.

## Runtime compatibility

[Microsoft documents `GraphicsCaptureSession.MinUpdateInterval`](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.graphicscapturesession.minupdateinterval?view=winrt-26100) as a read/write `TimeSpan`. The current page carries a prerelease notice and does not provide a reliable minimum-build guarantee in its retrieved compatibility table.

On the tested build, `ApiInformation.IsPropertyPresent` returns true. The getter returns `160000` 100-nanosecond ticks (16 ms). Setting `40000` ticks (4 ms) succeeds with HRESULT 0, and the getter confirms 4 ms. This is direct runtime verification on **26300 only**, not a claim about every Windows 10/11 build.

The helper keeps its existing Windows 19041 build target. A small runtime-gated adapter uses Microsoft's [`IGraphicsCaptureSession5` ABI](https://github.com/microsoft/win32metadata/blob/main/generation/WinSDK/RecompiledIdlHeaders/winrt/windows.graphics.capture.h); absent properties and failed interface/get/set calls preserve the default session. Unsupported/older Windows builds were not runtime-tested. The 4 ms request is an explicit developer override; the normal default remains unchanged for this investigation.

## Isolated capture results

Discard mode only acquires and disposes frames. Readback mode additionally creates/disposes a `SoftwareBitmap`; it does not shuffle pixels, send a pipe, receive Java frames or upload textures.

| Foreground/presentation condition | Probe work | Interval | Acquired FPS | Arrival gap p95 |
|---|---|---:|---:|---:|
| No Minecraft load | Acquire/dispose | Default 16 ms | 48.02 | 21.12 ms |
| No Minecraft load | Acquire/dispose | 4 ms | 143.99 | 7.26 ms |
| No Minecraft load | Software readback | Default 16 ms | 48.00 | 21.12 ms |
| No Minecraft load | Software readback | 4 ms | 143.99 | 7.22 ms |
| Fullscreen Minecraft, PiP transport off | Acquire/dispose | 4 ms | 66.32 | 21.30 ms |
| Fullscreen Minecraft, PiP transport off | Software readback | 4 ms | 63.87 | 21.86 ms |
| Windowed Minecraft, PiP transport off | Acquire/dispose | 4 ms | 143.93 | 7.22 ms |

All probe runs reported zero errors and zero sample-buffer overflow. Discard/default, readback/default, readback/4, fullscreen-discard/4 and windowed-discard/4 reported zero busy rejections. Isolated discard/4 and fullscreen-readback/4 each reported one busy rejection over their entire approximately 20-second runs, insufficient to explain the sustained rate differences. Acquisition itself averages about 0.005 ms. Isolated 4 ms software readback averages 2.76 ms, p95 3.51 ms.

At 144 Hz, three display periods are approximately 20.83 ms. The default session's 20.83 ms cadence is consistent with a 16 ms minimum quantized by composition opportunities. This timing explanation is an **inference**, while the measured default getter, setter/readback and resulting rate change are established observations.

## Complete native-to-Minecraft pipeline

Counts come from per-stage CSV records in the same bounded measurement windows. Native copy includes surface readback and existing BGRA-to-RGBA conversion. Java upload means CPU copy plus return from the texture-upload API, **not a GPU fence or displayed scanout**.

| Condition | Arrival | Acquired | Native copy | Pipe transfer | Java payload received | GL upload submitted | MC render FPS |
|---|---:|---:|---:|---:|---:|---:|---:|
| Fullscreen, default | 46.73 | 46.73 | 46.69 | 46.69 | 46.69 | 46.69 | 139.03 |
| Fullscreen, 4 ms | 66.63 | 66.63 | 66.66 | 66.66 | 66.66 | 66.26 | 139.01 |
| Windowed, 4 ms | 144.01 | 144.01 | 143.97 | 143.97 | 143.97 | 133.23 | 139.00 |
| Same session returned to fullscreen, 4 ms | 66.28 | 66.28 | 66.28 | 66.28 | 66.28 | 66.08 | 139.06 |

Rates are frames/s. Default fullscreen counted 1,405 arrival/acquisition records and 1,404 complete transfers/uploads over 30.069 s. Fullscreen 4 ms counted 2,003 arrivals and 2,004 complete transfers, with 1,992 uploads over 30.063 s. Windowed 4 ms counted 4,329 arrivals, 4,328 transfers and 4,005 uploads over 30.061 s.

Minecraft renders about 139 FPS with its 144 cap in this scene. Latest-frame buffering intentionally replaces frames if rendering consumes them slower than reception. The interior one-second state samples counted zero Java replacements in the default sample, 10 in fullscreen/4 and 315 in windowed/4; their intervals differ slightly from the CSV windows. No unbounded queue was added to recover those frames.

| CPU-side stage duration, mean / p95 | Fullscreen default | Fullscreen 4 ms | Windowed 4 ms |
|---|---:|---:|---:|
| Acquisition to copied RGBA | 3.54 / 4.35 ms | 3.03 / 3.85 ms | 3.09 / 3.88 ms |
| Native pipe write | 0.86 / 1.06 ms | 0.83 / 1.03 ms | 0.86 / 1.24 ms |
| Java payload read | 0.93 / 1.14 ms | 0.91 / 1.07 ms | 0.92 / 1.29 ms |
| Texture CPU copy and upload submission | 0.61 / 0.67 ms | 0.62 / 0.67 ms | 0.69 / 1.01 ms |
| Java completed receipt to upload submission | 4.13 / 7.99 ms | 2.78 / 7.66 ms | 3.84 / 7.11 ms |

Pipe read/write overlap; these columns must not simply be added as independent latency. WGC source timestamps were recorded, but their absolute relationship to callback QPC produced negative offsets in the isolated probe. Consequently no source-to-display latency claim is made from that subtraction. Relative source timestamp cadence agrees with the callback cadence, including the slower fullscreen case.

## OBS comparison

An isolated portable copy of installed **OBS 32.2.2** used Window Capture with explicit WGC method 2, the same 1920x1080 source, 144 FPS output, no audio sources, and lossless UTVideo recording. The test scaled output to 256x144 to minimize encoder overhead while preserving the one-pixel-shift content changes. It is **not** a full-resolution recording benchmark.

The successful 12-second run reported 1,727 output frames; FFmpeg decoded 1,726. Removing two seconds at each end and comparing decoded grayscale frame hashes yielded **48 distinct image transitions/s**. Consecutive identical-image groups were almost entirely length three (382 groups; two boundary groups length two). This measures repeated recorded images, not OBS's internal callback counter. The OBS comparison was made without concurrent Minecraft; an OBS fullscreen-Minecraft crossover remains untested.

One earlier failed setup recorded to OBS's default Videos directory before the portable profile was ready. That accidental recording is excluded from the measurements and awaits permission for removal. The successful no-audio measurement is under the ignored verification directory. The user's installed OBS profile was not used or modified for the measured run.

## Instrumentation and reproduction

- Native CSV records: callback arrival, successful acquisition and WGC timestamp, copied packet sequence, completed native transfer, interval presence/result and QPC clock anchor. Heartbeats include cumulative arrivals/acquisitions/conversions/transfers, busy/pacer/replacement counts and last-stage timestamps.
- Java CSV records: decoded header, completed payload reception, texture upload submission, sequence and monotonic timestamps. A final dropped-diagnostic count was zero in this run. The protocol remains version 1 with its existing 64-byte header.
- Native diagnostic queue is bounded to 4,096 records; Java's queue is bounded to 8,192. Both write on background workers and drop diagnostics rather than block capture/rendering. Diagnostic files contain no pixels, audio, source-window title or URLs. Native final trace flushing is best-effort when the process exits; raw heartbeat/CSV boundaries must not be interpreted as exact process-lifetime totals.
- Enable through the smoke run's `-PpipTrace=<ignored-directory>`. Normal capture does not create these trace files. The smoke phase file accepts `off`, `video` (default), `video4` (explicit 4 ms), and `audio`. Outside the harness, the explicit developer JVM property is `-Ddarkmusic.pip.minIntervalMs=4` and requires a new capture session.
- The smoke-only `select-fixture` marker selects exactly one application named `DarkMusic.Pip.BenchmarkSource`; ordinary source selection is unchanged. It is excluded from the release JAR.
- Raw probes, portable OBS profile/recording, scripts and CSV/JSON summaries are private under `.local-tools/verification/wgc-144`. Existing derived media fixtures remain under the earlier ignored benchmark folder. No private media was promoted to `docs/images`.

## Next correction and open questions

The smallest supported correction is to request a shorter WGC interval on runtime-compatible systems and retain a safe default fallback. This investigation leaves that shipping policy unchanged. It should be tested at other requested frame rates and on older Windows before enabling by default.

The remaining fullscreen ceiling needs a compositor/presentation investigation: compare exclusive fullscreen with same-resolution borderless, inspect actual present/composition events, and repeat on another driver/GPU/Windows build. No Windows Game Mode, driver, ExitLag, OS power or security settings were changed. These results do not identify any such application as the cause.

No GPU-sharing rewrite, larger frame queue, forced blocking upload, audio-pipeline integration or new dependency was introduced. Fullscreen 144 FPS, Chrome/YouTube equivalence, HDR/multiple monitors, GPU-completion/scanout latency, heavy gameplay, long-session stability and native macOS/Linux remain unverified.

## Validation

`scripts/verify.ps1 -DesktopIntegration` completed with exit code 0 and Gradle **BUILD SUCCESSFUL**: **546 tests total, 544 passed, two optional live-provider/tool-install tests skipped**, zero failures/errors. The private real collection and existing managed tools were used. The formatter was invoked through IntelliJ, and the Git whitespace check passed.

The instrumented Minecraft run emitted its matching smoke-pass marker, saved the disposable world and completed its Gradle task successfully. The surrounding redirected PowerShell launch returned 1, so this is reported from the child build markers and captured runtime state rather than as a successful wrapper invocation. The owned source, probes, portable OBS recording processes and Minecraft test process were stopped. No release was published or commit pushed during the investigation; subsequent v3.0.0 release checks are recorded in `TESTING.md`.
