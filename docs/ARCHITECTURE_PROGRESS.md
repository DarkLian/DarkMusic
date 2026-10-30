# Architecture work: progress log

Work log for the architecture improvement plan (local, untracked `DarkMusic-Architecture-Improvement-Plan.md`).
The maintained description of the result is [ARCHITECTURE.md](ARCHITECTURE.md). Each entry says what changed, why,
what was checked, and what is still open. "Runtime-unverified" means not yet exercised in Minecraft or on a real
audio device.

On resuming: check `git status` and `git log`, and compare the latest entry with the code.

## Environment notes

- The shell's default `JAVA_HOME` is Java 8. Set `JAVA_HOME=%USERPROFILE%\.jdks\ms-25.0.4.1` for every Gradle call.
- The private collection is Opus only, so `TestMusic` skips unless MP3 excerpts are prepared. Focused audio test
  runs go through the ignored `.local-tools/verification/architecture/focused-test.ps1`. It makes three 45-second
  MP3 excerpts under `.local-tools/verification/runs/<guid>`, deletes them in `finally`, and checks that the source
  hashes are unchanged. After each run, confirm `skipped="0"` in the result XML.
- `src/smoke` is compiled only with `-Psmoke`, so compile it whenever client members or Sources interfaces change.

## Baseline (v3.5.1, `0792986`, 2026-10-30)

`scripts/verify.ps1` (no `-Clean`, no `-DesktopIntegration`; private collection through excerpts) gave
**719 tests: 709 passed, 10 skipped, 0 failed** in about 2 minutes. The skips are the desktop, live-network and
native-helper cases that need `-DesktopIntegration` or live flags.

Slowest classes, measured before any optimisation:

| Class | Time (s) |
|---|---|
| DownloadIntegrationTest | 16.5 |
| LoudnessIntegrationTest | 16.1 |
| NativeAudioTest | 11.8 |
| MultilingualLyricsTest | 10.7 |
| StorageTest | 8.5 |
| OutputRoutingTest | 6.2 |

The whole run takes about 2 minutes, so no test optimisation is planned.

### Risks found while mapping lifecycle and ownership (by reading the code)

| # | Where | Problem | Status |
|---|---|---|---|
| L1 | `resetDefaults` client-thread completion | No try/finally. If building a replacement or `folders.activate` throws, the returned future never completes, and if it throws before `resetting = false`, the client stays disabled. | Batch 2 |
| L2 | `restoreBackup` in-memory apply | The flags are released in `finally`, but an exception while applying leaves `finished` never completed, even though the restore is already installed on disk. | Batch 2 |
| L3 | `selectOutputDevice` | If `backend.output(...)` throws synchronously, `outputChanging` stays set for good. | Batch 2 |
| L4 | Startup `ready()` | If it throws on the client thread, partially built services remain while settings are already published. `tick` can then dereference a null library every tick. | Batch 2 |
| L5 | Stopping callback and `shutdown()` | Two separate sequences, with no protection between steps: one throwing `close` skips every later close, the history flush and the storage executor shutdown. | Batch 2 |
| L6 | Reset after an old worker fails to stop | Replacements are published while the old worker may still run. Storage is untouched in that case. | Documented, kept (see batch 2) |
| D1 | Environment high-/low-pass bypass | A bypassed filter froze its history, so turning it back on replayed old audio. | Fixed in batch 1 |
| D2 | Environment analyzer | Bypassed filters were still included in the response. | Fixed in batch 1 |

## Batch 1: correctness, environment filter bypass (2026-10-30)

**Invariant improved:** turning a bypassed environment filter back on never replays audio from before the bypass,
and the analyzer describes the filters that playback actually uses.

**Bypass contract:**
- A bypassed filter keeps running with its output unused, so its history and coefficient ramp follow the current
  signal.
- A per-filter mix (12 ms, the same smoothing as the other controls) fades between dry and filtered output in both
  directions.
- A snap configuration (preset load, start, sample-rate change) sets the mix immediately.
- Whole-environment fades, transition engines and the clearing of acoustic tails are unchanged.
- `Engine.response` adds the high-/low-pass only when they are on. The analyzer label already describes the curve as
  tone filters only.

**Reproduction on unchanged code**, using a real-collection excerpt followed by zeros:
- After reactivation, silent input produced a peak of **0.276**. The reported tone reproduction had found about
  0.0146.
- Blank Custom read **−3.01 dB** at 20 Hz and at 20 kHz (44.1 kHz).
- 4 of the 5 new tests failed. The case with a change during an engine transition passed on old code too, because
  the incoming engine starts cleared; it is kept as a guard.

**Checks:**
- `EnvironmentFilterBypassTest` (new, 5 tests), `UnifiedAudioDspTest`, `UnifiedAudioBehaviorTest`,
  `DspContinuityTest`, `EnvironmentPresetTest`, `PlaybackConfigurationTest` and `AudioCorrectness231Test`:
  40 tests, all passed, 0 skipped, with real excerpts.
- The new tests cover both endpoints, three bypass/reactivation rounds, stereo, two sample rates, reactivation
  during an engine transition, and continuity: the output never steps further than the music itself. They also
  check that Blank Custom plays at one flat gain, which is the headroom trim, matching the flat analyzer curve.

**Hot-path cost.** Same real excerpt, best of 30 rounds after warm-up, two runs each:

| Configuration | Before (ns/frame) | After (ns/frame) |
|---|---|---|
| Blank Custom (both filters bypassed) | 28.8 | 38.0 |
| Walkie-Talkie (filters on) | 67.8 | 62.9 |

The bypassed case costs about 9 ns per frame more, roughly 0.05% of the 20.8 µs real-time budget per frame at
48 kHz. There are no new allocations or locks.

**Runtime-unverified:** listening to slider moves to and from the filter endpoints in game. This goes on the final
checklist.

**Next:** lifecycle ownership (L1–L6).
