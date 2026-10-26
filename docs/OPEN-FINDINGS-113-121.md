# Review findings 113–121

Reviewed against the v3.0.1 development branch on 2026-10-26. The supplied workbook is a static review; the dispositions below distinguish confirmed behavior from defensive hardening and optional changes.

| Finding | Disposition | Change |
| --- | --- | --- |
| 113: DSP reset at handoff | Confirmed, fixed | Same-format gapless/crossfade promotion retains the uninterrupted output stream's filter, dynamics, reverb, environment and protection histories. Cold starts and format changes still reset. |
| 114: Width startup ramp | Confirmed, fixed | The first processing block initializes stereo width to its configured value rather than starting at 1. |
| 115: Decoder routing | Confirmed, fixed | WAVE/AIFF content uses the native reader regardless of misleading extensions. Unknown containers can use already-configured FFmpeg/FFprobe; no tool installation is triggered. |
| 116: Watcher noise | Confirmed, fixed | Ignore internal work/recovery/lyric bookkeeping. Deleted paths trigger rescanning when they were indexed audio or known directories, including extensionless tracks during scan publication. |
| 117: Watch registration churn | Confirmed, fixed | Retain successful registrations and the existing service; retry failed directories at most once per 60 seconds when a scan revisits registration. Remove obsolete keys individually. |
| 118: Poll-worker termination | Defensive hardening | An unexpected polling failure is logged once and requests a rescan without suppressing future scheduled polls. Tested using an injected error; no real OS-triggered failure was reproduced. |
| 119: Bot-check wording | Confirmed, fixed | Recognize bot verification separately from private/login-required content. Explain it cautiously, offer tool guidance and manual retry, and do not automatically retry or bypass the check. |
| 120: Environment parameter resets | Confirmed, fixed | Continuous edits update the current/incoming engine in place, retaining histories and smoothing gains, filters and damping. Structural changes retain the existing bounded two-engine transition. |
| 121: 16-bit dither | Optional, deferred | Current conversion rounds rather than truncates. TPDF dither would change output/noise-floor policy and bit-exact expectations; it is not included as an incidental correctness fix. |

## Environment update boundaries

Continuous controls include filter parameters, output trim, noise level, drive, pan, echo gain, wet mix, damping, reflection gains and modulation rate. They preserve the active engine's delay/filter/convolution history, including while a preset transition is already running.

Preset, texture/ambience, mono/acoustic mode, reverb engine/impulse and delay-layout changes (room size, predelay, echo time, modulation depth and reflection times) use the existing 25 ms structural transition. A later structural request waits for the current transition. Fully bypassing the inner acoustic effect clears its residual room/convolution tail once so that re-enabling does not replay frozen audio.

## Verification

- `scripts/verify.ps1 -DesktopIntegration`: **584 tests, 579 passed, five skipped**, zero failures/errors. Uses private real audio and existing managed tools; derived fixtures are cleaned without modifying originals.
- Real-audio regressions cover same-format DSP continuity, changed-format resets, initial width, continuous environment edits, misleading decoder extensions and Ogg FLAC fallback through existing tools.
- Disposable filesystem tests cover retained healthy watch keys, registration retry backoff, removed registrations, ignored bookkeeping, extensionless deletion and recovery after an injected polling error. Download tests distinguish bot checks from genuinely private media.
- Final watcher/download-focused checks passed after the last cache-publication refinement. IntelliJ formatting, workflow lint, whitespace and English/Traditional Chinese parity (1,617 keys) passed.
- Five skips: optional live managed-tool installation, live LRCLIB, two unavailable Windows symlink fixtures and explicit live-release PiP installation.

No new Minecraft GUI smoke, subjective listening test, gameplay performance benchmark, real website bot-check reproduction or native macOS/Linux audio run was performed. DSP continuity assertions are not evidence of device-level audibility or sustained gameplay performance. Settings formats, dependencies and release version are unchanged.
