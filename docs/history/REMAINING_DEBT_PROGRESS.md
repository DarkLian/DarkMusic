# Remaining debt progress (2026-11-01)

Implementing the local REMAINING_DEBT_PLAN.md from 18d95ae. The user authorized continuous implementation and
verification. The user subsequently confirmed proceeding, authorized commit/push and directed continuation with
H5/H6. Six untracked local plan files are preserved.

## Current status

G, all U/L batches, H0 and H1–H4 are implemented and verified. MAX_DEBT is **0**. The only remaining long
methods are the two original per-sample hot entries and the recipe data entry. H5/H6 have not been attempted:
the plan explicitly requires the owed human listening/device check first. Results were requested asynchronously
at the start. The user has now confirmed proceeding with H5/H6, resolving the prerequisite for implementation;
no detailed listening rows or physical unplug observations were supplied, so none are invented.
AudioProcessor is unchanged; the environment Engine only gained package visibility for the H0 fixtures.

- Final `scripts/verify.ps1 -Clean -DesktopIntegration`: **937 tests, 12 explicit skips, zero failures/errors**.
  All new exact-output checks and the eight-by-twenty backend PCM golden scenarios ran without skipping.
  Six skips are the opt-in timing tests; their separate before/after measurements ran successfully.
- All nine native suite scenarios passed across the suite and the corrected Lyrics rerun. Final post-H1–H4
  default, lyrics and preview scenarios passed again, including the actual lyric-write/admission checks.
- PiP HUD/menu/setup/spectrum checks, published-runtime installation and the final post-split spectrum rerun passed.
- Visual verification: **60 full-resolution RGBA comparisons, zero differing pixels**, no masks/cropping/resizing.
  Both unchanged-code repeats and every baseline/final pair passed; the broken initial comparison is retained
  as an audit correction below, not counted as passing evidence.
- Hosted-style absence check: **26 tests, 9 expected skips, zero failures/errors** (six opt-in costs and three
  private-audio-dependent checks). Circular geometry and the architecture/method guards still ran.
- All **53 private collection files unchanged**. Live LRCLIB was executed and failed; direct endpoint recheck
  returned **HTTP 502**, matching the previous baseline. It was not converted to a skip.
- IDEA formatting and `git diff --check` pass. Final UI source is restored, agent debugger probes/config removed,
  and the user's disabled exception breakpoints preserved. Commit and push are now authorized; no tag or release.

The batch table is chronological: these final checks supersede earlier notes saying native/visual checks were pending.

## Completed batches

| Batch | Change | Evidence |
|---|---|---|
| G | Measure synthetic lambdas with stable enclosing-method keys; read exact Code.code_length from class files. | MethodLengthTest and ArchitectureTest pass. Parser checks overloaded methods and WIDE operands. One existing lambda exceeds 60 lines: PipRuntime.begin$lambda, 83; MAX_DEBT rises once from 12 to 13 to record previously invisible debt. |
| U1 | Named factories for command subtrees; alias loop, attended-install consent and suggestion ordering retained. | LocalCommandsTest, NavigationAndCommandsTest and guards pass using private real-audio excerpts. MAX_DEBT 12. |
| U2 | Settings search and navigation sections are private methods; reflected fields stay on the screen. | GUI suites, architecture/method/smoke-reflection guards pass. MAX_DEBT 11. Final native smoke and visual comparison pending. |
| U3 | Lyric synchronization and version rows are separate visual groups; write-admission actions retain their order. | GUI/lyrics suites and guards pass. MAX_DEBT 10. Native lyrics smoke and comparison pending. |
| U4 | Utility divider and copied-link feedback are private render groups in their original drawing order. | GUI suites and guards pass. MAX_DEBT 9. Native default smoke and comparison pending. |
| U5 | Duplicate comparison columns, review actions and asynchronous detail loading are private sections. | GUI/cleanup suites and guards pass. MAX_DEBT 8. Native cleanup smoke and comparison pending. |
| U6 | Current/manual lists, automatic list and queue actions are private sections; double-click and checked-row rules retained. | GUI/UpNext suites and guards pass; all reflected fields preserved. MAX_DEBT 7. Native queue/cleanup checks pending. |
| U7 | PiP setup/admission rows remain in populate; capture controls form a private section. | GUI/PiP suites and guards pass. MAX_DEBT 6. Native PiP checks and comparison pending. |
| U8 | Color preset grid, paging and color fields are private visual sections. | GUI suites and guards pass; reflected fields preserved. MAX_DEBT 5. Native smoke/comparison pending. |
| U9 | Playlist controls, lists and entry actions are private sections; compact-state readback happens before rebuilding. | GUI/playlist suites and guards pass, reflected fields preserved. MAX_DEBT 4. Native default/queue/cleanup/reset and comparison pending. |
| U10 | Environment header, short controls, advanced controls and saved environments are private sections; Off/blank/device/outdoor predicates named. | GUI/environment-preset suites and guards pass. MAX_DEBT 3. Native queue and comparison pending. |
| U11 | Audio profile, normalization, equalizer, effects, transitions/game controls and saved-profile actions are private sections. | GUI/audio-settings suites and guards pass. Original-hint, neutral disabling, save status and rename/delete scope preserved. MAX_DEBT 2. Native advanced/queue checks pending. |
| U12 | One immutable FrameLayout per frame; card, artwork/image/caption/spectrum, text/status/progress groups retain draw order and matrix/scissor boundaries. | HUD/render suites and guards pass. MAX_DEBT 1 (only the exposed PiP install lambda remains). Native default/preview and before/after comparisons pending. |
| Visual U audit | The first comparison script was invalid: Pillow's RGBA bounding-box default ignored RGB differences when alpha matched. | RGB recheck found title marquees, translucent editor over animated panorama, and inconsistent hover/status regions. Original captures are retained; do not count the initial comparison as passing. Deterministic capture harness and baseline rerun are required. |
| L1 | Named picker job, process launcher and result/wait phase; ownership cleanup and OPEN/CANCELLED order retained. | New admission/cancellation/forced-stop/start/exit/cleanup tests pass before/after the split. Actual child dialog approve/cancel passes on the desktop before/after. |
| L2 | Named captured-release job and staging, manifest-properties and installer-copy phases. | New direct staging tests pass before/after: valid manifest/helper, checksum/identity/version/dependency/ZIP rejection before installer publication, installed-JAR preservation. Existing update/recovery/pruning/packaging and command-consent suites pass. No actual user mod was staged/replaced. |
| L3 | Named setup job with immutable RuntimePackage value passed to archive preparation, unpack/repair and activation. | New busy/close, graphics refusal/cancellation and distinct error-label tests pass before/after; all PiP/packaging/architecture/method suites pass. MAX_DEBT is now 0. Live runtime and native PiP checks pending. |
| H0 exactness | Before changing any hot loop, fixed direct DSP hashes for neutral/EQ/limiter/full chain and all 24 presets, plus waveform/band arrays, spectrum rectangles/gradients and circular vertices. | Three identical repeats each, then a separate non-recording golden run passed. Raw IEEE bits are hashed. Only hashes/counts are tracked; private audio stays in memory. Six opt-in cost checks use 10,000 warm-up calls and 15 rounds of 1,000 calls, median and p10–p90/full spread; normal builds skip them. Timing baseline pending native checks finishing. |
| Native U/L | Eight of nine suite scenarios passed; all three PiP native checks and published-runtime integration passed. | Original collection 53 files unchanged. Lyrics timed out in stage 5 after its actual write/admission checks passed. Debugger reproduction proved a pre-existing harness race: target position 5 was published during SEEKING, and Toggle paused again. The harness now waits for PAUSED too; verification pending. Live LRCLIB still fails. |
| Native harness follow-up | Controlled seek delay after the fix kept speed at 1.0 and never toggled while SEEKING; resuming the worker completed the whole lyrics smoke. | Passing run 40987579-15c6-4646-bd89-c939d474877c. Agent breakpoints and temporary IDEA configuration removed, user breakpoints unchanged. Separate non-debug lyrics rerun and then two quiet timing baselines are running sequentially. |
| H0 cost | Two quiet, identical-workload timing baselines recorded, same JDK and machine. | Primary comparison is baseline 2; baseline 1 retained to show repeatability and GC noise. Timing results are observations, never CI thresholds. Keep only exact-output candidates whose absolute median change is smaller than the larger before/after p10–p90 spread. |
| H1 | Full-track envelope folding/normalization is a private final phase; per-sample accumulation unchanged. | Exact goldens and waveform/guard suites pass. Primary median 70,933.9 → 70,875.0 ns/analysis (−0.083%); larger spread 5,450.5 ns. Keep; hot entry removed. |
| H2 | FFT window/transform is a private per-block phase, separate from onset/band publication. | Exact waveform/band/DSP goldens, SpectrumTest and guards pass. Primary median 28,406.0 → 26,331.8 ns/block (−7.302%); larger spread 6,219.1 ns. Keep under the stated noise rule; no speedup claim. Hot entry removed. |
| H3 | Circular progress ring and spectrum contour are private per-frame phases; bar/vertex arithmetic unchanged. | Every raw vertex golden and HUD/guard suites pass. Primary median 146,775.4 → 155,520.5 ns/frame (+5.958%); larger spread 15,823.7 ns. Keep under the stated noise rule; result does not prove zero cost on all systems. Hot entry removed. |
| H4 | Spectrum contour and bar row are private per-frame phases; update, halo, mesh submission order retained. | Exact rectangle/gradient goldens, render/spectrum suites and guards pass. Primary median 14,857.7 → 15,188.8 ns/frame (+2.228%); larger spread 746.9 ns. Keep; hot entry removed. |

G2 bytecode sizes: AudioProcessor 1731, environment Engine 1474, SpectrumAnalyzer 1099, TrackWaveform 804,
CircularHudMesh.Rings 1570, SpectrumRenderer.bars 1428 bytes. No hot method exceeds 8000 bytes.
Reports include descriptors so overloads remain distinguishable. Lambda ordinal suffixes are dropped, sibling
lambdas share the longest span, and other synthetic/bridge methods remain excluded.

## Pending prerequisites and limits

- The previous refactor's human listening rows 1–9 and physical output unplug check have no recorded results.
  The user was asked asynchronously; H5/H6 remain behind that prerequisite unless explicitly waived.
- Live LRCLIB previously returned HTTP 502. Recheck without hiding failure or converting it to a skip.
- No release, tag, commit or push is part of this implementation turn.

## Resume record

Current evidence and separate commit boundaries are under ignored .local-tools/verification/remaining.
checkpoint.ps1 captures trees in an alternate index, preserving the user's index and local plans; it creates no commits.
Source edits use IDEA apply_patch and its built-in formatter. Deterministic visual verification completed through V3.

H0 test seams leave hot method bodies unchanged: package-visible environment Engine, a direct analyzer wrapper,
read-only renderer mesh query, and null-extractor mesh submission for headless geometry capture (no GPU work).
Timing is loop/geometry CPU cost, including a concrete consuming vertex sink; GPU driver/submission cost is excluded.
Machine: AMD Ryzen 7 7800X3D (8 cores/16 threads), Microsoft OpenJDK 25.0.4.1. Timing runs wait for native/build
jobs to finish. IDE/system background processes are retained; no claim of a dedicated benchmark machine.

Runtime investigation uses the ij-debugger skill. lyrics-race-evidence.md records natural passing and controlled
worker-only delayed runs, actual states and stacks. No production playback arithmetic/control flow changed.
Temporary ignored IDEA run configuration DarkMusic Remaining Lyrics was removed after follow-up; the user's
two disabled exception breakpoints were preserved. Timing runs were stopped while waiting, before measurements.

Visual audit recovery: v2 capture fixes playback at paused position 83, moves the pointer outside controls, uses
short display labels derived from real metadata to avoid title scrolling, and draws an opaque test backdrop behind
the real HUD editor so the animated title panorama cannot bleed through. No original music/metadata/artwork changed.
The original UI source from HEAD is temporarily applied for a fresh baseline; final source is preserved in
.local-tools/verification/remaining/visual-baseline-final. visual-source-state.json records this window.
Restore with visual-baseline.py restore through IDEA apply_patch before running guards or resuming implementation.

V2: all 15 unchanged-code pairs were identical in each run, and 12 screen/HUD states matched before/after.
Three screens differed only in row/button hover backgrounds because setScreen's queued mouse recenter could run
after the one-time cursor move. V3 reasserts a neutral pointer every tick, clears hover and asserts actual mouse
coordinates before capture. Baseline source is being applied again; restore final sources before verification.

V3 completed: **60 comparisons, zero differing pixels**, with every RGBA channel checked and no masks, resizing
or cropping. All 15 baseline repeats, 15 final repeats and 30 baseline/final pairs match. The self-test catches RGB
changes with equal alpha and alpha-only changes. Original HEAD UI source was used for the baseline, with the
already bit-verified final renderer loops; final UI source has been restored and matches its saved contents exactly.
Both capture tasks emitted their current-run pass markers and Gradle BUILD SUCCESSFUL. visual-v3.json and the
original images under remaining-before-v3/remaining-after-v3 are authoritative; v1/v2 remain failed/superseded evidence.
Final default/lyrics/preview smoke, clean desktop verification, PiP spectrum follow-up and hosted-style checks are running.

Final verification completed; the Current status above and final-desktop-results.json, final-no-music-results.json,
final-smoke/summary.txt, final-summary.txt and visual-v3.json are authoritative. Separate source/doc batch trees
remain in batches/sequence.txt and incremental patches, using an alternate index only. All six local plans stay
untracked. The user subsequently authorized separate commits/push and confirmed proceeding with H5/H6.

## Continuation authorization

The user said "confirmed. commit and push and move on with H5 and H6". Publish the already verified batches,
then measure and verify each per-sample candidate independently. Retain a split only under the original exactness
and cost rule. Detailed human listening/device observations remain unrecorded; authorization is not labeled as
an observed listening result. Keep recording progress and preserve the separate batch boundaries.

Initial captures: 15 screen/HUD states, two original 1920x1080 GUI-scale-2 captures each. Their initial RGBA
comparison was invalid (see visual audit above). Stored privately in docs/images_private/remaining-before. Playback paused, visualizer/transitions off;
real collection/artwork and SmoothFont, isolated capture-run configuration. First harness attempt failed because
resetting isolated settings cleared tool paths; the harness now reapplies the existing managed tool paths.
