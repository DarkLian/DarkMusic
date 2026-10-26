# Picture-in-Picture verification

This records development and release checks for DarkMusic v3.0.0, based on v2.10.0.
The user authorized publication with the documented performance limitations. The longer-term goal is
perceptually indistinguishable video playback, assessed through actual video
comparison as well as frame pacing. A successful build does not establish that goal.

The final clean v3.0.0 Windows release check passed 544 tests with two optional checks skipped (546 total). Package integrity, helper checksum/licenses and language-key parity passed. The sections below preserve earlier development checkpoints; see [release validation](TESTING.md#v300-release-validation) for the final scope and the [WGC investigation](PIP-WGC-INVESTIGATION.md) for the latest pacing measurements.

## Implemented path

- Explicit window selection and Start/Stop; no saved HWND or automatic capture.
- Windows Graphics Capture in an isolated, self-contained Windows x64 helper.
- Current-user-only, randomly named local pipe, validated RGBA frames and three
  reusable Java buffers. A slow renderer takes the newest frame, without a backlog.
- Texture upload at render cadence, independently of Minecraft's 20 Hz tick rate.
- Movable, proportional resize, crop, opacity, visibility and menu display.
- Configurable Ctrl+Alt+function-key focus/return shortcuts. Both are registered
  before capture; a conflict aborts capture. Source identity includes process
  creation time to reject handles reassigned to another process. Parent exit stops the helper.
- Optional Windows process-tree loopback analysis drives a spectrum beside PiP.
  It defaults off, requires build 20348+, and never replays audio or captures a
  microphone. Audio failure leaves video usable. Browser process sharing can
  include other tabs; tab-isolated audio is not guaranteed.
- PiP Spectrum Settings independently persist artwork-style spectrum appearance,
  analysis controls, height and four-side placement. Its color editor previews
  PiP rather than the music HUD. Edit PiP Layout previews the combined placement.
- No capture recording, network transport, extra playback route, or music DSP changes.

## Checks performed

- Protocol validation, fragmented/truncated/zero-progress input, stale sequences,
  bounded buffer reuse, 10,000 concurrent frame publications and slow-consumer
  ownership checks pass in focused tests.
- Old settings defaults, invalid values, compact/wide bounds, crop aspect ratio,
  window-title sanitization and bundle extraction/integrity tests pass.
- Actual bundled helper extraction includes redistributable runtime notices.
- The production picker, Start Capture, live texture rendering, dragging, resizing,
  crop selection and saved preferences were exercised in an isolated Minecraft
  client at fullscreen 1920×1080, configured/effective GUI scale 2, SmoothFont.
  The first production capture used an IntelliJ project window. Private-derived
  audio remained PLAYING. This is a functional check, not a video-smoothness test.
- The earlier low-rate feasibility probe captured Chrome behind fullscreen Minecraft
  and verified 20/20 focus events across ten round trips. It deliberately sent
  approximately three frames per second and is not production smoothness evidence.

The full Windows check (`scripts/verify.ps1 -DesktopIntegration`) passed:
537 tests, 535 passed, 2 skipped, no failures. The skipped checks were the optional
managed-tool installation integration and live LRCLIB network test. Existing
private-audio, desktop and configured FFmpeg/tool checks ran. The compiler reports
the pre-existing `ExtendedOpenOption` internal-API warning in AudioDeletionTest.

The production helper completed two focus/return round trips (4/4 events). Each
destination was checked by the helper 350 ms after the hotkey, before any automation
activated that destination. Exclusive fullscreen was restored, capture remained
live, and audio stayed PLAYING. Stop/Start used a new helper process and resumed
capture. Normal test-client shutdown left no helper running. Display preferences
survived restarting the client; capture did not automatically restart.

Minecraft's `FramerateLimitTracker` caps out-of-world menus at 60 FPS. The menu
smoke requested a 140 FPS cap and disabled VSync, but observed approximately 60
draws/second. That is not a 140 FPS gameplay benchmark. The captured project window
changed behind fullscreen Minecraft, but is not a substitute for source-video
pacing or listening to an A/V comparison. The sample helper working set was around
123 MiB; this static/project-window sample does not predict 4K/video load.

Hosted CI results for the release are tracked by the v3.0.0 release workflow;
compilation does not establish native Linux/macOS capture support.

The updated preview package also passed all 19 focused PiP checks, including execution
of the bundled helper's deterministic pacing test on Windows. That test preserves
a 60 FPS input with alternating 3.5 ms callback jitter, limits a faster 144 FPS
input, and does not invent frames for a slower input. It verifies the rate budget,
not browser video delivery or perceptual smoothness. The expanded native pacing
test also accepts native-rate inputs at 15, 24, 144, 165, 240, 360 and 480 FPS.
Settings tests verify monitor matching, unavailable-monitor fallback, bounded
manual limits, persistence and deduplicated choices including unusual refresh rates.
The monitor detector uses Minecraft's selected monitor and GLFW's current video
mode. An isolated follow-up client reported 144 Hz on this setup. Multi-monitor
changes and video capture above 144 FPS have not been runtime-tested.

The follow-up also exercised the main-player PiP icon, removed explanatory menu
text, center snapping with highlighted guides drawn over the capture, and Chrome
capture across windowed/fullscreen transitions. Non-suspending IntelliJ logpoints
confirmed 1920×1032 frame uploads while Minecraft was windowed and after returning
to fullscreen. Chrome video frames were visible in the editor. This brief functional
check did not reproduce the reported intermittent white video rectangle; its cause
remains unresolved, and perceptual smoothness during gameplay is not established.
The source was left on its original video, paused near its original position.
Both command aliases, default menu visibility, utility-row spacing and icon atlas
integrity pass regression tests. The disposable client exited normally and left no
capture helper running; the agent logpoint was removed.

The earlier local handoff artifact was `build/libs/darkmusic-3.0.0-preview.1.jar`, built with
a version override before the v3.0.0 release preparation. Its
metadata, helper checksum, redistributable notices and source archive were checked.
The reminder-only follow-up adds a localized Windows-only notice and explains
Ctrl+Alt in shortcut tooltips. Its build and 29 PiP/menu-layout tests passed;
English and Traditional Chinese key sets match. This notice was not runtime-tested.

It contains no smoke harness or test audio. The updated preview JAR is 79,407,295 bytes;
its SHA-256 is `46fa6db2c56633fb8c7bc3b5f8d494422e61badb00c1526b40d44657d7d3e58a`.

## Remaining verification

- Extended focus/return and rapid-restart stress, including abrupt parent termination.
- Sustained 60 FPS source video and subjective comparison with watching directly;
  video/audio synchronization and gameplay stutter, including a 140/144 Hz setup.
- Browser autoplay/background-throttling cases, resize/minimize/restore/close.
- Default font and Traditional Chinese runtime, compact screen interaction.
- Hardware/device-loss, HDR, multiple monitors/DPI, elevated/protected applications.
- Linux/macOS compilation of the cross-published helper and normal non-PiP runtime.

## Build and resources

`gradlew build` (and `runClient`) require the .NET 8 SDK on the build machine.
The resulting JAR bundles the Windows runtime; users do not install .NET.
`scripts/verify.ps1 -DesktopIntegration` includes this bundle by default;
`-JavaOnly` (`-PjavaOnly` for Gradle) is available for Java-only development checks. A Java-only build
shows an explicit missing-helper message if PiP is requested.

The first measured archive is about 76 MB compressed, with approximately 177 MB
of root runtime files before localized resources. Runtime extraction happens in
the instance's DarkMusic configuration directory on a background worker. Files
are verified against the bundled archive; changed installations are preserved
and rejected, rather than silently overwritten. Subsequent sessions reuse it.
The runtime archive has stable entry order/timestamps, so Java-only changes do not
create a new extracted runtime solely because ZIP timestamps changed. Old or
conflicting runtime folders are preserved; automatic pruning is not implemented.
The final helper expands to 193,209,858 bytes including localized resources and
notices. No global .NET installation or system settings change is performed.

No new audio dependencies, configuration migration, or music-file changes.
The additive `pip` settings contain display preferences and the opt-in audio-spectrum toggle. The helper uses
three bounded frame owners on the Java side and bounded pooled frames on its
native side; CPU, memory bandwidth and upload cost increase with source size/rate.
No sustained performance claim is made from an idle or static-window capture.

Test logs and unpublished captures belong under ignored
`.local-tools/verification` and `docs/images_private`. No new documentation
screenshots have been promoted.

Native API references: [Windows screen capture](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/screen-capture),
[frame-pool threading](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframepool.createfreethreaded),
[foreground activation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setforegroundwindow).

## Application-audio and performance follow-up

Windows full verification passed 540 tests (538 passed, two optional checks
skipped), followed by a successful preview assembly. Real process-loopback audio
and an independently driven spectrum were checked, including stop/restart. A
status-row null dereference found when stopping was fixed and retested.

The fullscreen 144 FPS target is **not met by the shipping default**. A later Chrome retest after the AMD FreeSync correction measured about 48 FPS at the default interval and about 143 FPS received at 4 ms, in both borderless and exclusive fullscreen ([details](TESTING.md#pip-chrome-retest-after-the-freesync-correction)). The earlier measurement below used a different source: measured source presentation was
approximately 144 FPS, Minecraft approximately 139 FPS, and PiP approximately
47 FPS. See [performance measurements](PIP-PERFORMANCE.md) for CPU, RAM, GPU,
methodology and exclusions. Sustained/browser equivalence remains unverified.
