# Architecture work: progress log

A record of the 2026-10-30 architecture work, started from v3.5.1 (`0792986`). The current design is described in
[ARCHITECTURE.md](ARCHITECTURE.md); this file keeps the evidence: what was found, what changed, how it was checked,
and what is still open. On resuming, compare `git log` with the batch index below.

## Environment notes

- The shell's default `JAVA_HOME` is Java 8. Set `JAVA_HOME=%USERPROFILE%\.jdks\ms-25.0.4.1` for every Gradle call.
- The private collection is Opus only, so `TestMusic` tests skip unless MP3 excerpts exist. `scripts/verify.ps1`
  prepares them. For focused runs, the ignored `.local-tools/verification/architecture/focused-test.ps1` makes three
  45-second excerpts under `.local-tools/verification/runs/<guid>`, deletes them afterwards, and checks the source
  hashes. Confirm `skipped="0"` in the result XML for the tests that matter.
- `src/smoke` compiles only with `-Psmoke`. Compile it whenever client members or Sources interfaces change.
- Give deterministic future waits in tests a timeout, so that a regression fails instead of hanging.

## Findings

| # | Problem (found by reading the code, then reproduced or tested) | Fix |
|---|---|---|
| D1 | A bypassed environment high-/low-pass froze its history; switching it back on replayed old audio (peak 0.276 on silent input with real music) | Bypassed filters keep running unheard; 12 ms dry/filtered fade (batch 1) |
| D2 | The environment analyzer counted bypassed filters (Blank Custom read −3.01 dB at the band edges) | Only active filters count (batch 1) |
| L1 | Reset's client-thread completion had no `try`: a failing rebuild left the future pending, and could leave the client disabled | `ResetOperation` (batch 2) |
| L2 | Restore's in-memory apply could throw after the install and leave the future pending | `RestoreOperation` (batch 2) |
| L3 | A synchronous refusal from `backend.output(...)` left `outputChanging` set for good | Flag taken only after the call succeeds (batch 2) |
| L4 | A failed startup left partial services published; `tick` could dereference a null library | `Startup` unwinds and withdraws (batches 2, 10) |
| L5 | The stopping callback and `shutdown()` were two sequences; one throwing close skipped the rest | One ordered, failure-tolerant `Shutdown` (batches 2, 10) |
| L6 | Reset publishes replacements even when an old worker did not stop within 15 s | Kept on purpose: storage is left untouched, and the old services are already closed |
| R1 | Reconciliation markers survived reset, so a new library whose revisions restart could count as already reconciled (cleanup skipped, autoplay early) | `LibraryReconciliation` with a reset generation (batch 3) |
| P1 | Reset could clear `loudness-cache.json` or the download history while a final write was still queued, and the write brought it back | `awaitStopped` waits for those writers (batch 6; tests batch 9) |
| P2 | The restore's recovery check read the disk on the client thread | Read on the storage executor (batch 9) |
| O1 | Restore completed its future before releasing its flags, and lost a failing release | Release first, then complete; failures reported (batch 13) |
| O2 | A refused client dispatch left reset and restore futures pending | `ClientDispatch` (batch 13) |
| O3 | A restore storage task dying with a non-`Exception` skipped the continuation | Treated as install failure with unknown journal (batch 13) |
| S1 | The reset-restart smoke check expected one playlist; Favorite Music (v2.1.0) makes it two. Fails the same on `0792986` | Smoke check now expects Favorite Music plus the kept playlist |

## Batch index

| Batch | Commit | Change | Evidence |
|---|---|---|---|
| 1 | `88432b5` | Environment filter bypass and analyzer (D1, D2) | `EnvironmentFilterBypassTest` (5, real excerpts; 4 fail on old code); timing 28.8 → 38.0 ns/frame for bypassed filters only |
| 2 | `a45da4b` | `ResetOperation`, `RestoreOperation`, `Cleanup`; startup unwinding; one shutdown order (L1–L5) | `ResetOperationTest`, `RestoreOperationTest`, `CleanupTest` |
| 3 | `03c1c6f` | `LibraryReconciliation` (R1) | `LibraryReconciliationTest` (5; the reset case fails when `reset()` is emptied) |
| 4 | `388ddd2` | `configureVisualizer` → `applySettings`, documented order; replacement audit | `SettingsStoreTest` |
| 5 | `7803ba9` | `Navigation` + `ScreenNavigation` for the player and Settings | `ArchitectureTest` guard; reach 62 → 31 methods |
| 6 | `bd397ef` | Reset waits for late writers (P1) | Audio, download and `ResetTest` suites |
| 7 | `b2ca18f` | `Cleanup.close`; architecture record | Full local run |
| 8 | `a6755fc` | Every remaining screen moved to `Navigation`; guard allows no exceptions | `ArchitectureTest`, gui tests |
| 9 | `58bdfee` | Recovery check on storage (P2); deterministic late-write tests | `StoppedMeansWritesDoneTest` (both fail on pre-batch-6 code) |
| 10 | `4d13f31` | `Startup` and `Shutdown` sequences | `StartupShutdownTest` |
| 11 | `e060f22` | `LatestWriter.write` acknowledgement; `SettingsStore.lastWrite()`; checked at shutdown | `WriteAcknowledgementTest` |
| 12 | `d94a4a8` | `SettingsSource.update(edit)` for edit-then-save changes; save timing unchanged (user's choice) | `SettingsStoreTest` |
| 13 | `b83402f` | `ClientDispatch`; restore completion order (O1–O3) | 6 new operation tests, 5 fail on old code |
| 14 | `93fb183` | `buildReplaceable` builds what reset replaces, for startup and reset | Focused tests, full runs, smoke |

## Verification history

| Run | Result |
|---|---|
| Baseline `verify.ps1` at `0792986` (headless) | 719 tests, 709 passed, 10 skipped |
| Latest `verify.ps1` (headless, after batch 14) | 762 tests, 752 passed, 10 skipped, 0 failed |
| Latest `verify.ps1 -Clean -DesktopIntegration` (after batch 14) | 762 tests, 756 passed, 6 skipped, 0 failed; the skips are live network, managed install and filesystem-permission cases, the same as the v3.5.1 release |
| Smoke windows after batches 7, 12 and 14 | default, reset (each with restart), advanced, cleanup, queue, lyrics, and preview (from batch 12): all pass in the latest window. Private collection: all 55 files have the same SHA-256 before and after every window |
| Manual checklist (user, after batch 7) | Done, no problems reported. It covered filter endpoints by ear, quitting with playback, a download and PiP active, reset and restore by hand, hub navigation, and the new zh_tw messages |

The slowest test classes are about 16 s each, and a full headless run takes about 2 minutes, so no test
optimisation was needed.

## Behaviour changes

- Bypassed environment filters keep running unheard and fade over 12 ms when switched.
- **Reset:**
  - if rebuilding fails, the client stays disabled with `reset.darkmusic.restart`;
  - resetting ends after folder activation;
  - reset waits for late cache and history writes before clearing.
- **Startup:** if it fails part way, it withdraws to the not-loaded state with `error.startup`.
- **Restore:**
  - an apply failure after the install reports `backup.restart`;
  - the restore flags are released before the result is reported.
- **Shutdown:** settings are saved first, PiP closes before playback and lyrics before downloads, and a final
  settings write that never reached disk is logged.
- **Messages:** three new strings in both catalogs (`reset.darkmusic.restart`, `backup.restart`, `error.startup`).

## Open items

- **Service holder** (moving the service fields into one object): deferred. Reset would get a single atomic swap,
  but publication already happens on the client thread and the callbacks compare captured identities, so no observed
  defect needs it. It would cost about 500 field-access edits, mostly in the smoke sources. Worth revisiting if the
  service set grows or a generation-level test becomes necessary.
- `ready`, `ResetSteps` and `buildReplaceable` stay as wiring inside `DarkMusicClient`. They are covered by
  compilation, review and the smoke checks, not by unit tests.
- **Release:** not released. Per AGENTS.md, it still needs a normal play session, then a tag and publication with
  approval.

## Rerunning the runtime checks

The ignored `.local-tools/verification/architecture/testing-window.ps1` does the following:
- runs the smoke modes in sequence (default, reset, each with restart, then advanced, cleanup, queue, lyrics and
  preview), each with a 20-minute limit;
- then runs `verify.ps1 -Clean -DesktopIntegration`;
- hashes the private collection before and after.

It writes per-step logs and a summary to the folder named by `WINDOW_NAME`. The smoke build sets fullscreen
1920×1080, GUI scale 2 and SmoothFont. Launch the script by absolute path. Before attributing a smoke failure to new
code, rerun that mode on a worktree of the previous release.
