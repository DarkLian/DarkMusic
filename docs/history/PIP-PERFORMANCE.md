# PiP performance checkpoint

**Superseded in part (October 26, 2026).** A Chrome retest after the system-wide AMD FreeSync/Adaptive Sync correction measured the v3.0.3 default at about **48 FPS** and an experimental 4 ms interval at about **143 FPS received** in both borderless and exclusive fullscreen. It did **not** reproduce the exclusive-fullscreen drop recorded below. The figures here came from a different source application, with exclusive fullscreen forced by the test harness and before the FreeSync correction; which of those caused the difference is not established. See [PiP Chrome retest](RELEASE-CHECKS.md#pip-chrome-retest-after-the-freesync-correction). **Since v3.0.4** the helper requests half the frame limit's period instead of the 16 ms default; at a 144 FPS limit Chrome was received at about 143 FPS in exclusive fullscreen ([measurement](RELEASE-CHECKS.md#pip-capture-interval-following-the-frame-limit-v304)).

Development preview, October 26, 2026. **The v3.0.3 default did not reach 144 FPS.**
The later [WGC investigation](PIP-WGC-INVESTIGATION.md) isolates the default
16 ms capture interval and a separate exclusive-fullscreen pacing limit. It
demonstrates 144 FPS reception in windowed mode; the resource figures below
remain measurements of the earlier approximately 47 FPS run.
This is a short controlled measurement, not a minimum-spec recommendation or a
claim of perceptually indistinguishable browser playback.

## Setup

- Windows, Ryzen 7 7800X3D (16 logical processors), Radeon RX 9060 XT, 32 GB RAM.
- Minecraft fullscreen 1920×1080, GUI scale 2, SmoothFont, 144 Hz monitor,
  144 FPS cap, VSync off; stationary in a normal world, 12-chunk render distance.
- Selected application: a disposable Direct3D source presenting at approximately
  144 FPS, animating artwork extracted from the private real music collection
  and playing a temporary real-audio excerpt. This tests transport throughput;
  it does not establish Chrome/YouTube decoding or background-tab behavior.
- Captured window 1920×1080, displayed PiP width 576 screen pixels, full crop.
  Music playback in DarkMusic was stopped.
- Baseline sampled for 30 seconds; video and video-plus-spectrum for 45 seconds
  each, excluding their first 15 seconds. An unbound key reset Minecraft's
  inactivity timer before each sample. Earlier AFK-throttled samples were excluded.

## Observed averages

| Measurement | PiP off | Video | Video + audio spectrum |
|---|---:|---:|---:|
| Minecraft rendered FPS | 139.11 | 139.10 | 139.10 |
| PiP frames uploaded/s | — | 46.87 | 46.65 |
| Minecraft CPU (% of entire 16-thread CPU) | 2.80% | 3.47% | 2.86% |
| Helper CPU (same denominator) | — | 0.98% | 1.22% |
| Minecraft resident RAM | 1,805 MiB | 1,829 MiB | 1,829 MiB |
| Helper resident RAM | — | 116 MiB | 118 MiB |
| Minecraft dedicated GPU memory | 759 MiB | 770 MiB | 770 MiB |
| Helper dedicated GPU memory | — | 25 MiB | 25 MiB |
| Helper busiest GPU engine | — | 0.80% | 0.80% |
| PiP inter-frame gap, p95 | — | 27.41 ms | 28.00 ms |
| Captured-frame age at upload, mean / p95 | — | 9.67 / 11.66 ms | 9.90 / 12.38 ms |

Relative to this baseline, total Minecraft-plus-helper resident RAM increased
about **140–142 MiB**, dedicated GPU memory about **36 MiB**, and aggregate CPU
about **1.3–1.7 percentage points**. Private committed memory increased about
98–100 MiB; this is a different memory measure, not additional resident RAM.
CPU run-to-run variation exceeds the measured difference between spectrum off/on.
The source application was present in every phase and is excluded from those
increments (approximately 119–121 MiB resident RAM and 35 MiB dedicated GPU memory).

Minecraft's busiest GPU engine averaged 14.0%, 14.4%, and 5.8% respectively.
Utilization depends on GPU clocks and scheduling; these sequential samples do
not establish a reliable GPU-utilization delta or show that spectrum saves GPU
work. They do show the helper's measured load and memory footprint. GPU counters
were collected per process/engine, without adding overlapping engine percentages.

These resource costs apply to the **observed 47 FPS capture**, not hypothetical
144 FPS delivery. Frame age excludes monitor scanout, source decoding and audio
output latency; it is not an end-to-end audiovisual synchronization measurement.

## Conclusions and remaining work

Minecraft's render rate stayed effectively unchanged in this scene. Video receipt
and upload rates both remained around 47 FPS while the source presented at 144;
the shortfall is upstream of Minecraft's presentation rate. The exact split
between Windows capture scheduling, surface readback and pipe transport has not
been isolated. The implementation copies GPU surfaces through CPU memory (it still does);
a faster path needs a separate measured investigation before making a 144 FPS
claim. Audio packets arrived at about 100/s and did not materially reduce delivery.

Native process-loopback activation and a spectrum driven by real source audio
were verified, including capture stop/restart. A status-row null dereference found
during stop testing was corrected. Earlier experimental Java-source runs crashed
in GLFW before capture; they are excluded, and their cause is not established.

Not established: sustained 144 FPS capture, browser equivalence, moving/combat
gameplay, long-session memory stability, multiple browser processes/tabs,
device changes, HDR/multiple monitors, other GPUs, or native macOS/Linux support.
Private logs and disposable benchmark harnesses remain under ignored verification
directories. No private media or benchmark harness is included in the release JAR.
The benchmark applications were stopped. Automatic approval review blocked
cleanup of its generated `media/source.wav` and `media/cover.png`; these two
derived fixtures remain in the ignored local benchmark directory, outside builds.
