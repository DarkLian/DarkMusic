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
| L1 | `resetDefaults` client-thread completion | No try/finally. If building a replacement or `folders.activate` throws, the returned future never completes, and if it throws before `resetting = false`, the client stays disabled. | Fixed in batch 2 |
| L2 | `restoreBackup` in-memory apply | The flags are released in `finally`, but an exception while applying leaves `finished` never completed, even though the restore is already installed on disk. | Fixed in batch 2 |
| L3 | `selectOutputDevice` | If `backend.output(...)` throws synchronously, `outputChanging` stays set for good. | Fixed in batch 2 |
| L4 | Startup `ready()` | If it throws on the client thread, partially built services remain while settings are already published. `tick` can then dereference a null library every tick. | Fixed in batch 2 |
| L5 | Stopping callback and `shutdown()` | Two separate sequences, with no protection between steps: one throwing `close` skips every later close, the history flush and the storage executor shutdown. | Fixed in batch 2 |
| L6 | Reset after an old worker fails to stop | Replacements are published while the old worker may still run. Storage is untouched in that case. | Kept on purpose (batch 2) |
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

## Batch 2 — lifecycle ownership (2026-10-30)

**Invariants improved:** every lifecycle operation's future completes on every path; admission flags are released
or deliberately kept with a stated reason; startup and reset never leave actions enabled against a partial service
set; shutdown attempts every step.

**Changes:**
- `ResetOperation` owns the reset phases (begin → stop old + clear storage → build/publish while still resetting →
  release + save → read back). `DarkMusicClient.ResetSteps` supplies only which services to stop, clear and build.
  Replacements built before a failure are registered and closed (`abandon`); the client then stays disabled with
  `reset.darkmusic.restart`. A refused storage submission (only after shutdown) publishes nothing. Reset's
  prepare steps are each guarded, so the storage task always gets to stop the captured services. Resetting now
  ends after folder activation rather than before it; the folder activation's own save was then skipped and the
  single `release` save writes the same final state.
- `RestoreOperation` separates the durable install from applying in memory: install failure → `backup.failed`,
  apply failure → new `backup.restart` (restored on disk, restart finishes it), closing in between → completes.
  Flags are released in `finally` as before. The old trailing `save()` in apply was a no-op (the `restoring` gate
  is still set), so it was removed with a comment; persistence of a restore is the journal.
- `selectOutputDevice`: a synchronous refusal no longer takes `outputChanging`; completion always completes the
  future.
- Startup: `start` builds services through `ready(Cleanup)`; each service is registered as soon as it exists. On
  failure they are closed, fields and settings are withdrawn (the pre-load state every consumer handles), and
  `error.startup` is announced. The playlist-load callback now checks it still targets the store it read for.
- Shutdown: the stopping callback and `shutdown()` are one ordered `Cleanup` (see ARCHITECTURE.md). The final save
  runs before `closed` is set because writes are admitted only while alive. PiP now closes before playback (it reads
  playback) and lyrics before downloads (caption lookups use downloads).
- `Cleanup` (root package): ordered, once-only, failure-tolerant steps.
- Three messages added in both catalogs: `reset.darkmusic.restart`, `backup.restart`, `error.startup`.

**Decisions:**
- L6 kept: when an old worker does not stop within 15 s, storage is left untouched and replacements are still
  built, because the old services are already closed and a client without services is worse. The old worker can
  only finish its own job.
- No new service-generation object: the existing callbacks already compare captured identities, which is the
  generation check; documented per callback in ARCHITECTURE.md instead.

**Checks:** `ResetOperationTest` (6: full reset ordering, worker timeout, build failure, closing before
publication, kept files and failed save, refused storage), `RestoreOperationTest` (4), `CleanupTest` (2),
`ArchitectureTest`, `LocalizationTest`, `ResetTest`, `SettingsStoreTest`, backup tests: all passed (`ResetTest`'s
three real-music cases skipped in this run; covered by the milestone run). `compileSmokeJava -Psmoke` passed.

**Limits:** `DarkMusicClient` cannot be instantiated in tests (Fabric/Minecraft in field initializers), so the
glue in `ResetSteps`, `start` and `shutdown` is verified by compilation, review and the reset smoke check, not by
unit tests. Its line count grew by the step classes; the coordination logic left it.

**Runtime-unverified:** Reset to Defaults and backup restore in game (`-PresetSmoke` covers reset), exit with
playback/downloads active.
