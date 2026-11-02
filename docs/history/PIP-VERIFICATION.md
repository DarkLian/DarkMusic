# Picture-in-Picture verification

How PiP works and how it is checked. Setup and the package are described in
[PiP extension](../PIP-OPTIONAL-INSTALLATION.md); per-release results are in [release checks](RELEASE-CHECKS.md).

## How it works

- You choose the window and press Start; nothing is saved that would capture a window by itself.
- Windows Graphics Capture runs in a separate Windows x64 helper process from the optional PiP package. If Minecraft
  exits, the helper stops.
- Frames travel over a randomly named local pipe that only the current user can open. They are checked before use and
  land in three reusable buffers; if rendering falls behind, it takes the newest frame rather than building a backlog.
- Protocol v2 carries the original source dimensions, crop rectangle and output dimensions independently. Placement
  and crop editing use the original source geometry. The helper crops and area-averages to the view's physical pixel
  size, without enlarging small sources. Crop/size requests are bounded while dragging; old frames retain their own
  mapping until the new geometry arrives.
- The optional helper uses exact integer area weights in a D3D11 compute shader and three nonblocking readback slots.
  Its retained GPU source lets crop/size changes update even when a static source stops producing frames. A CPU SIMD
  implementation with the same arithmetic remains available when GPU setup is unsupported.
- The capture interval accounts for both the frame limit (Match Monitor, or 15 to 480 FPS) and the source monitor's
  refresh rate. It asks Windows for half the faster rate's period, between 1 and 16 ms, and the frame pacer enforces
  the chosen output limit. This avoids capturing alternate monitor updates before pacing a lower output rate.
  The source monitor rate is rechecked when capture runs; unknown modes retain the previous interval calculation.
- Textures upload directly from the receiver's ByteBuffer at Minecraft's render rate, without an intermediate
  NativeImage copy. Exact physical-size draws use nearest sampling; other sizes retain linear sampling.
- The view can be moved, resized in proportion, cropped, faded, hidden and shown in menus.
- Ctrl+Alt+function-key shortcuts switch to the captured window and back. Both are registered before capture starts,
  and a conflict stops it. The source is identified with its process start time, so a reused window handle from
  another process is rejected.
- PiP Audio Spectrum (off by default, Windows build 20348 or later) analyses the selected application's process audio.
  It never replays audio or uses a microphone, and an audio failure leaves the video running.
- PiP Spectrum Settings are separate from the music HUD's.
- Nothing is recorded or sent over the network, and music playback and its processing are not involved.

## How it is checked

- **Tests** (`gradlew test --tests '*Pip*Test'`):
  - frames: fragmented, truncated and stalled input, stale sequences, buffer reuse, 10,000 concurrent publications
    and a slow consumer;
  - settings: old defaults, invalid values, layout bounds, crop aspect ratio and window-title cleaning;
  - the package: no work when the screen opens, no download during checks, explicit install, reuse after restart,
    cancellation, wrong checksums, ZIP paths that escape or repeat, missing helpers, and no native files in the JAR;
  - audio packets and recovery.
- **Helper self-test**, run by the tests on Windows: frame pacing, and the requested interval at 15, 60, 144 and
  480 FPS. Resampling is compared byte for byte against an independent Java reference, including scalar/SIMD,
  hardware GPU and software GPU paths, random crops, 4K and maximum-budget wide/tall inputs. A changing-size
  100-upload burst plus rapid target changes verifies bounded staging and delivery of the latest cached source.
- **Desktop checks**: `-PdesktopIntegration` runs the real Windows checks and graphics probe;
  `-PpipRuntimeIntegration` also installs the published package for the matching release.
- **In game**: `runSmokeClient -Psmoke -PpipSmoke` opens the real PiP screens in a fullscreen client; see
  [Development and verification](../TESTING.md).
- **By hand**: focus and return round trips, Stop and Start with a new helper, no helper left after Minecraft closes,
  layout kept after a restart, and capture not resuming on its own.

Current implementation evidence and outstanding release gates are recorded in
[PiP quality progress](PIP-QUALITY-PROGRESS.md). Protocol v2 requires the matching optional helper package; existing
users install that package from the PiP menu when updating the mod. The native helper remains outside the mod JAR.

## Building

The helper builds with every normal build (the `bundlePipHelper` task) and needs .NET SDK 8.0.421. `-PjavaOnly`
skips it; PiP then reports that its installation information is missing.

Windows API references: [screen capture](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/screen-capture),
[frame-pool threading](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframepool.createfreethreaded),
[foreground activation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setforegroundwindow).
