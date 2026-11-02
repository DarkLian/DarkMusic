# PiP quality implementation checkpoint

Implementation checkpoint, 2026-11-02. This is a resume record, not a release claim.
Baseline: `78e23ae`; working branch: `main`. The user explicitly requested commit and push after the implementation
handoff. This authorizes committing the self-tested changes without waiting for the pending manual review; it does
not assert that manual testing passed. No tag, release, or public image promotion is included.
The user's original untracked `PIP_QUALITY_PLAN.md` is preserved. The user requested documentation during implementation.

## Latest resume checkpoint (2026-11-02, implementation and self-tests finished)

Implementation and the final GPU fidelity grid are complete. Setup, spectrum and HUD/menu runtime smoke scenarios
passed with a fresh disposable profile and the private collection. The clean desktop check and a full desktop rerun
after the final corrections both passed. The final 144-limit runtime measurement also passed and compiled the corrected
smoke focus action. Evidence is saved; manual visual review and a normal play session remain before release.

- GPU overload now keeps three staging jobs plus one latest deferred request. A full ring no longer overwrites an
  in-flight staging texture or queues additional compute jobs. Deferred work uses the latest cached source after
  Unmap. A 100-upload, changing-size burst test verifies four readbacks, 97 replacements and the final pixels on
  hardware GPU and WARP; the targeted resampler test passed. A subsequent review found that a rapid target A→B→A
  sequence could leave B deferred when A was already the last submitted target. Retarget now supersedes any deferred
  request even when returning to A. The expanded burst adds these two requests and expects 99 replacements plus
  the final A dimensions. This last refinement passed the subsequent full desktop rerun, including hardware/WARP.
- The previous full run reported 984 tests, one failure and 15 skips: the smoke reflection budget caught the new
  focus-action reflection. A controller focus command replaces that reflection without raising the budget. The first
  attempted textual replacement missed the formatted smoke block; it was explicitly patched and the full checks pass.
- Current full-check command: process-local Java 25, `powershell -NoProfile -ExecutionPolicy Bypass -File
  scripts/verify.ps1 -Clean -DesktopIntegration`. This bypass is per process, not a system configuration change.
- Python fidelity/timing self-tests and `git diff --check` passed after the last source corrections.
- The clean desktop check passed: **984 tests, zero failures/errors, 15 skips**, build successful in 3m43s. The full
  desktop rerun after the final A→B→A refinement also passed in 3m32s with the same counts. Summary:
  `.local-tools/verification/pip-quality/final-test-summary.json`. No audio source hashes changed during verification.
- Trace privacy audit passed: twelve CSV files, **602,528 rows**, known stage names and integer metadata only. Report:
  `.local-tools/verification/pip-quality/trace-privacy-audit.json`. Older captures' short dropped-summary rows are
  accepted by that audit; the final writer pads them to the full 15-column schema.
- The sections below retain intermediate measurements and investigations. Final GPU grid, 60-limit cadence and
  near-full transport measurements supersede their earlier pending-check notes.

Remaining: private visual review and normal-play/manual confirmation before release. The user's explicit commit/push
request overrides waiting for that review before committing, as allowed by AGENTS.md. Live 4K
capture remains hardware/window-size limited; 4K resampler arithmetic has been tested. No P4/P5 implementation was
added: cadence quantization explains the adjusted P4 gate; measured 8 MB write cost does not trigger P5, with the
sustained-144-FPS limitation explicitly retained below.

### Final runtime result and acceptance limits

`gpu-final144-v2/` contains the final helper/Java CSVs, render timings, quiet measurement window, ten Get-Counter
samples per process, copied client log and `timing-summary.json`. The client exited with DARKMUSIC_SMOKE_PASS and
Gradle BUILD SUCCESSFUL. An earlier optional rerun named `gpu-final144/` could not select its hidden source window and
is invalid evidence; the visible disposable pattern window fixed the fixture. That failed run and the superseded
`gpu-live-144/` and `gpu-perf-144/` runs are preserved in `.local-tools/archive/pip-quality-superseded-20261102/`.
The final task-owned Chrome profile
and helper processes have stopped. No user browser process or original audio was changed.

| Metric | Original + P0 traces | Final code, 144 limit |
|---|---:|---:|
| Received FPS | 143.995 | 143.991 |
| Displayed FPS | 135.400 | 136.966 |
| Capture to display p50 / p99 | 5.867 / 9.937 ms | 4.800 / 8.572 ms |
| Render-thread update p99 | .468 ms | .074 ms |
| Helper readback / copy / write p95 | 2.945 / .459 / .502 ms | 1.686 / .038 / .187 ms |
| Minecraft frame p99, visible / hidden | 8.410 / 8.249 ms | 8.340 / 8.251 ms |
| Helper CPU, mean | 34.173% | 2.625% |
| Minecraft CPU, mean | 40.177% | 36.227% |
| Helper working set, mean | 116.282 MiB | 117.475 MiB |
| Minecraft working set, mean | 1243.679 MiB | 1308.522 MiB |

CPU values are raw Windows process-counter percentages (100% is one logical processor; this machine has 16).
Both CPU targets improve. **The strict V8 helper-memory target is not met by this sample: +1.193 MiB, about 1%.**
Do not claim every proposed acceptance threshold passed. No working-set trimming or threshold change was used to
hide this result. The lower 60-limit working set below is context, not a substitute for the matched-rate comparison.
There were zero busy/staging drops, zero lost trace records, and no clock-step warning in the final 144 run.

Live source-size coverage is limited to this desktop's maximum normal window size (roughly 1940x1100); 1440p/4K
arithmetic is covered by independent CPU/hardware-GPU/WARP tests, but live 1440p/4K FPS gates remain unverified.
HDR is unavailable on the supplied monitor. The sharpening-on visual comparison and linear-light preference remain
part of manual review; system display settings were not changed by the implementation. The user can restore their
preferred Super Sharpness, Radeon sharpening and Video Upscale settings now that baseline measurements are finished.

## Implemented

- P0: helper readback/conversion trace separation, frame-pool count, Java present/display events and microsecond
  wall clock, all-frame smoke timings, `scripts/pip-timings.py`, `scripts/pip-fidelity.py`, the JSON-specified
  `src/smoke/resources/pip-pattern.html`, and smoke-only physical-pixel bounds and capture/configuration controls.
- P1: 96-byte protocol v2 with independently validated source/crop/output geometry; `PipMapping` avoids texel
  truncation and maps partially available old frames correctly; source dimensions drive placement and crop editing.
  `PipTarget`/`PipTargets` aggregate extraction-frame requests and limit continuous updates to 100 ms intervals.
  Commands use a bounded latest-request writer. The helper clamps crops and prohibits enlargement.
- P1 CPU reference/fallback: separable exact integer area coverage, BGRA to RGBA, forced opaque alpha, one final
  half-up rounding. SIMD and scalar implementations share arithmetic. Work arrays are reused.
- P2: direct ByteBuffer upload to RGBA8_UNORM GPU textures, without NativeImage storage/copy; nearest sampling
  at exact physical size, linear sampling otherwise. Actual 26.2 APIs were inspected locally.
- P3: `GpuResampler.cs` uses raw D3D11 ABI calls, an exact integer area compute shader, three output-sized staging
  slots and nonblocking Map. It caches the full source on the GPU for crop/size changes when a static source stops
  producing capture events. Capture frames are released after submission. The existing CPU path remains a fallback.
  No new NuGet dependency or executable is added to the mod JAR. The optional helper package contains this code.
- `CaptureWait.cs` provides a process-local high-resolution wait. Ordinary Task.Delay(1) experimentally produced
  bursty roughly 70 FPS delivery because Windows woke it in coarse steps. No global timer-resolution setting is changed.
- Project/package version is prepared as **3.6.4**, not published. The v2 helper and Java mod must be installed together.

The GPU extension was justified by an isolated P1 CPU benchmark: 4K-to-576x324 conversion alone measured approximately
6.8 ms p95, already beyond P3's 4.17 ms budget at 144 FPS before any readback. Live 4K capture is not verified yet.

## Verification already completed

- A full `scripts/verify.ps1 -Clean -DesktopIntegration` passed after P1/P2, and again after the GPU-stage additions.
  See the latest checkpoint for the final refinement rerun. Use process-local JAVA_HOME `C:/Users/User/.jdks/ms-25.0.4.1`;
  the shell default is Java 8 and cannot launch Gradle.
- Subsequent targeted resampler tests passed for scalar, SIMD, hardware GPU, and WARP software GPU against an
  independent Java 2D coverage oracle: 128 random crops/ratios plus 1080p, 1440p, 4K and maximum-budget wide/tall
  cases. The later clean full check also covers unloading the system compiler DLL after shader compilation.
- Original-build and CPU-P1 static screenshots: source capture 1266x713, browser canvas 1264x681 at offset (1,31).
  GUI scales 1,2,3,4; exact 1:1 and widths .2,.3,.6,1. The 1:1 cases and all reductions in the new build had **zero
  differing RGB values** in checked static regions. Original reductions showed substantial aliasing. Enlargement
  keeps bilinear sampling and differs by at most 2 from Pillow bilinear in these checks.
  The GPU path was subsequently checked with the same 20-case grid; see Latest completed checks.
- 98 actual source-window resizes completed with the crop editor open on the GPU build. The session remained live,
  and final metadata returned to 1280x720. Stop/Start passed. Focus then Return produced **2 successes, 0 failures**.
  Original fullscreen private capture: `docs/images_private/pip-quality/screenshots/gpu-resize98-editor.png`.
- HDR test is unavailable: user's monitor does not support HDR.

### Controlled 144 FPS comparison

Fullscreen 1920x1080, configured/effective GUI scale 2, borderless, SmoothFont, VSync off, Minecraft limit 144.
Synthetic Chrome pattern source, 1266x713 WGC capture; overlay width .3 (576x324 output). Isolated measurement windows
exclude startup/build activity. CPU/memory samples use Get-Counter. Values below are from stored reports, not estimates.

| Metric | Original + P0 traces | GPU implementation |
|---|---:|---:|
| Received FPS | 143.995 | 143.991 |
| Displayed FPS | 135.400 | 136.940 |
| Capture to display p50 | 5.867 ms | 4.762 ms |
| Capture to display p99 | 9.937 ms | 8.526 ms |
| Render-thread update p99 | .468 ms | .081 ms |
| Minecraft frame p99, visible | 8.410 ms | 8.380 ms |
| Minecraft frame p99, hidden | 8.249 ms | 8.283 ms |
| Helper readback p95 | 2.945 ms | 1.677 ms |
| Helper conversion/copy p95 | .459 ms | .045 ms |
| Pipe write p95 | .502 ms | .190 ms |
| Busy/staging drops | 0 / n/a | 0 / 0 |
| Lost trace records | 0 | 0 |

The GPU row predates unloading d3dcompiler_47.dll after shader compilation. That unload subsequently reduced helper
working set: a 60-limit run measured 108.8 MiB versus the original 144-limit run's 116.3 MiB; rates differ, so this is
context, not a controlled memory comparison. Do not claim all memory/CPU thresholds passed without the final check.

## Latest completed checks

- The production source-monitor-aware interval is verified: effective interval **34,722 ticks** for a 60 limit on
  the 144 Hz source monitor. `gpu60-monitor/` receives/displays 59.99 FPS, with display interval p95 **21.810 ms**,
  latency p50/p99 **4.979 / 8.969 ms**, no busy/staging drops and no trace losses. The old 8.333 ms capture interval
  produced capture gaps of roughly 28 ms; those are fixed upstream rather than hidden by a presentation buffer.
- The **final GPU fidelity grid passes all 20 cases**: GUI scales 1–4, identity and widths .2/.3/.6/1. Identity and
  reductions have zero differing checked RGB values. Enlargement remains bilinear (maximum difference 2 from Pillow's
  bilinear implementation). Results: `gpu-grid-results.json`; original captures: `screenshots/gpu-grid-*.png` in the
  private image directory. No captures were promoted publicly.
- The generated Python cache was moved to `.local-tools/archive/pip-quality-python-cache-20261102`.

### P4 interpretation

No presentation buffer has been added. The original proposed 1.25-source-period threshold is 20.833 ms at 60 FPS.
Minecraft's observed median render interval is 7.143 ms (about 140 FPS); a normal three-render display interval is
already 21.429 ms, before render jitter. The measured 21.810 ms p95 fits that cadence plus ordinary render jitter.
The prior approximately 28.9 ms p95 came from missing capture updates, not pipe jitter. The gate therefore needs
this cadence floor rather than treating unavoidable quantization as a transport defect. Raw results remain recorded;
this is an explicit adjustment to the plan's proposed threshold, not a claim that 21.810 is below 20.833.

## Near-full transport and review evidence

Latest update: the real near-full case is complete: **1876x1064 = 7,984,256 bytes/frame**, source 1940x1100.
Pipe write p95 was **1.139 ms**, below even the strict 144-limit 25% budget of 1.736 ms; update p99 was **.443 ms**.
No busy/staging drops or trace losses occurred. The disposable GDI source updated at roughly 40 FPS, so this checks
per-frame cost at 8 MB, not sustained 144 FPS/8 MB throughput. P5 is not triggered in the measured conditions.

The whole default-size PiP, **including text and window chrome**, was additionally compared with exact area reduction
of the 1:1 capture: **zero differing RGB values**. Private review artifact:
`docs/images_private/pip-quality/visual-review.html`; generated reference is labeled separately from original captures.

Regression driver initially found an enabled PiP preference left by the earlier benchmark profile, so the setup test's
default-off precondition failed. The entire disposable `build/pip-smooth-run` was preserved in
`.local-tools/archive/pip-quality-smoke-run-before-setup-20261102`, and all three scenarios then passed with a fresh
profile. This was fixture contamination, not a change to shipped default settings. No real user configuration moved.

The remaining work is listed in the latest resume checkpoint above. Earlier .441 ms write results were not 8 MB
frames and must not be used for P5. The prepared regression driver is `run-smokes.ps1` in the verification directory.

### Colour-space experiment

Production retains exact sRGB-space area averaging. An offline exact-coverage linear-light reference was generated
from the real 1:1 pattern capture, including its lines and anti-aliased text, at the same 576x324 output size. Mean
RGB changed from approximately 128 to 164; 407,465 channel values differed, maximum 73. This shows the substantial
brightness change, not superiority of either reference. `colour-space-reference.json` records the comparison and the
private review has a separately labeled Linear-light experiment button. No production linear-light implementation
or 1440p/144 timing is claimed: the plan requires visible improvement and an acceptable cost before switching, and
visual approval is still pending. Original captures remain untouched.

## Earlier checkpoint investigation (retained for traceability)

Update after the checkpoint: the existing `video4` diagnostic confirmed the cadence cause. At the same 60 limit,
capture interval p95 fell from 27.860 to 21.016 ms and display interval p95 from 28.904 to 22.393 ms, with 60.001
received FPS and zero drops. A source-monitor-aware interval calculation is now implemented in `CaptureInterval.cs`
and `Program.cs`: half the period of max(limit, source monitor refresh), preserving the old calculation if the monitor
rate cannot be read. Moving to a monitor with a different refresh rate is rechecked each second. No display settings
are changed. **This production calculation still needs the final live and full-test checks.** P4 remains under review;
the residual display interval includes the unavoidable source/render cadence and measured render jitter.

1. **60-limit capture cadence is the immediate issue.** `gpu-chrome60` receives and displays 60.006 FPS with no drops,
   but display interval p95 is 28.904 ms. The capture timestamps themselves have p95 **27.860 ms**, so the large gaps
   already exist before transport/presentation. Native interval configuration is **83,333 ticks = 8.333 ms**, while
   the source monitor is 144 Hz (6.944 ms). This appears to suppress alternate source updates, then the frame pacer
   further samples that stream. Investigate source-monitor-aware capture intervals or test the existing `video4`
   diagnostic before deciding whether P4 presentation buffering is warranted. Adding buffering cannot recover missing
   source captures. Do not silently mark the original P4 gate as passed/not needed without recording the evidence.
2. Complete final GPU fidelity capture grid and regression smoke scenarios: check-setup, check-spectrum,
   check-hud-menus. Run final full local checks, package/JAR checks, trace privacy checks, and git diff checks.
3. Evaluate P5 at a genuinely near-full output. The last quick .441 ms write result used 1280x720 source/output after
   the resize storm, **not an 8 MB frame**. It is not sufficient evidence for the full P5 gate.
4. Record the final gate decisions and any hardware-limited checks. Manual visual review and a normal play session
   remain required before committing/releasing. Do not publish or tag automatically.

## Evidence, fixtures and resume details

- All current scratch/harnesses/traces are under ignored `.local-tools/verification/pip-quality/`.
- Main comparison directories: `baseline-perf-144/` and `gpu-poll-144/`. Each has `window.json`, one native CSV,
  one Java CSV, `render.jsonl`, and `process-counters.json`. Run `python scripts/pip-timings.py <directory>`.
- `gpu-chrome60/`: current final-code 60-limit trace and counter run, including the cadence issue above.
- Earlier CPU and failed/coarse-poll GPU runs are superseded; retain/archive them, do not mistake them for final results.
- `baseline-src/` is an isolated detached Git worktree at 78e23ae, with P0 diagnostics and the current smoke controls
  copied in. Its capture/render behavior remains v1/linear/NativeImage. Preserve this worktree/history.
- Baseline originals: `docs/images_private/pip-quality/baseline-originals/`. New CPU grid:
  `docs/images_private/pip-quality/screenshots/grid-s*.png`. JSON comparisons are in `grid-results.json` and
  `baseline-grid-results.json` in the private verification directory. Regenerate reports after comparator changes.
- `compare-grid.py`, `compare-baseline-grid.py`, `static-grid.py`, `baseline-grid.py`, `process-counters.ps1`,
  and `measure.ps1` are private harnesses. `measure.ps1` starts a quiet window, samples counters, switches PiP off
  for a hidden reference and requests client shutdown. New runs need fresh directories.
- Chrome test sources use explicitly disposable profiles under this verification directory. Do not access the user's
  real browser profile. Current source title prefix: **DarkMusic PiP Pattern gpulive**; static pattern title prefix:
  **DarkMusic PiP Pattern static**. Launch the local HTML as a Chrome --app with background/occlusion throttling
  disabled for animated timing runs. Confirm the source exists; a missing source now stops the smoke run.
- Desktop automation service was unavailable after recovery attempts (native pipe missing). Existing automated
  smoke controls and task-owned fixtures were used instead. Do not assume CUA window handles exist.
- `fixture/` is a disposable WinForms resize fixture, not shipped. Control files in `fixture-control/`: `size`,
  `storm`, `complete`, `state.json`, `stop`. Storm completed with count 98. Standard window sizing was clamped to
  roughly 1940x1100 on this desktop even when 4K was requested; `gpu-4k60/` is thus a **clamped-source regression run**,
  not proof of live 4K/60. Its source was finally 1280x720 after the storm. The task-owned fixture has been stopped.
- Smoke controls in a run directory: `select-fixture`, `phase` (video/video4/off), `quality.json`, `capture`,
  `stop-minecraft`. Quality accepts width, visible, crop percent string, GUI scale, exact, editor, and test actions
  stop/start/focus/return. Change JSON/id for each action. Capture names accept only letters/digits/underscore/hyphen.
- `scripts/pip-fidelity.py` can take full WGC frame size, canvas offset, explicit framebuffer bounds and identity mode.
  Browser chrome/rounded window corners and anti-aliased text are excluded from exact static checks.
- **Reference correction:** Pillow BOX does not implement fractional pixel coverage at arbitrary ratios. The comparator
  now uses exact area coverage, also reports Pillow BOX for comparison, and uses bilinear for enlargement. Example:
  3-to-2 reduction of [0,0,255] is [0,170] with exact area, [0,255] with Pillow BOX. Preserve this documented deviation.
- Source settings provided by the user: Acer EK241Y P6, sRGB, no HDR; initially Super Sharpness on, Radeon Image
  Sharpening 2 and desktop sharpening on at 50%. User confirmed turning those and Video Upscale off for the baseline.
  Do not change system settings; notify the user when measurements finish so they can restore their preferences.
- All audio checks use `run/config/darkmusic/music`. Post-clean smoke runs should pass the existing managed tools
  directory (a verified bundle under `run/config/darkmusic/tools/managed`) and SmoothFont. Do not generate dummy audio.
- An untracked `scripts/__pycache__/` was generated by comparator imports; move it into the ignored verification/archive
  area before delivery. Normalize formatter-induced CRLF-only diffs back to existing LF after IDE formatting.

