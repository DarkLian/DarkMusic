# Architecture work: progress log

A record of the 2026-10-30 architecture work, started from v3.5.1 (`0792986`), and of its follow-up review the
same day (batches 15 and 16), and of the maintenance batch 17. The current design is described in
[ARCHITECTURE.md](ARCHITECTURE.md); this file keeps the evidence: what was found, what changed, how it was checked,
and what is still open. On resuming, compare `git log` with the batch index below.

## Environment notes

- The shell's default `JAVA_HOME` is Java 8. Set `JAVA_HOME=%USERPROFILE%\.jdks\ms-25.0.4.1` for every Gradle call.
- The private collection is Opus only, so `TestMusic` tests skip unless MP3 excerpts exist. `scripts/verify.ps1`
  prepares them; for focused runs use `scripts/verify.ps1 -Tests <class>`, which makes three 45-second excerpts
  under `.local-tools/verification/runs/<guid>`, deletes them afterwards, and checks the source hashes. Confirm
  `skipped="0"` in the result XML for the tests that matter.
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
| L6 | Reset publishes replacements even when an old worker did not stop within 15 s | Kept, with evidence: storage is left untouched, and nothing the old services can still finish replaces user data (see ARCHITECTURE.md, Reset after a timeout). One late write found, kept as a known risk: T2 |
| R1 | Reconciliation markers survived reset, so a new library whose revisions restart could count as already reconciled (cleanup skipped, autoplay early) | `LibraryReconciliation` with a reset generation (batch 3) |
| P1 | Reset could clear `loudness-cache.json` or the download history while a final write was still queued, and the write brought it back | `awaitStopped` waits for those writers (batch 6; tests batch 9) |
| P2 | The restore's recovery check read the disk on the client thread | Read on the storage executor (batch 9) |
| O1 | Restore completed its future before releasing its flags, and lost a failing release | Release first, then complete; failures reported (batch 13) |
| O2 | A refused client dispatch left reset and restore futures pending | `ClientDispatch` (batch 13) |
| O3 | A restore storage task dying with a non-`Exception` skipped the continuation | Treated as install failure with unknown journal (batch 13) |
| H1 | Quitting during a restore wrote the old listening history over the restored file: `flush` ignored suspension, and its write queued behind the install on the serial storage executor | `flush` writes nothing while suspended, keeping the progress unsaved (batch 15) |
| H2 | The same shutdown flush during a pending restore journal made the next start's recovery fail with a conflict instead of rolling back | Same fix (batch 15) |
| H3 | An apply failure before the history was replaced released the restrictions, and the next tick flushed stale history over the restore. Reached only by an injected failure; the fix is defensive | A failed apply keeps the restore restrictions until restart (batch 15). Since batch 16 the backup is prepared inside the install, so a bad backup fails as `backup.failed` before anything is written; the hold stays as a guard |
| H4 | A pending journal for a restore without history suspended listening anyway | Only a restore that selected history suspends it (batch 15) |
| H5 | A merge restore merged the history captured when Apply previewed it, so listening while the confirmation was open was lost (`BackupRestoreTest`: 5 plays on disk instead of 6) | The restore captures what it merges with when it starts, after suspending history, and prepares on the storage executor (batch 16) |
| T1 | Reset's storage task closed the old services without failure tolerance: one throwing close left the later ones running beside their replacements. No production close was shown to throw | `ResetOperation.stop` closes all, waits for each, logs those still running (batch 15) |
| T2 | A replaced loudness cache's late final write can drop readings its replacement saved since (reproduced, then the probe removed) | Not changed: preview and comparison players already run their own caches on the file the same way, and an ownership rule would have stopped the main cache saving after a preview closed. Cache only, rebuilt from the files |
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
| 16 | `49a2a37` | Backup capture, read, export and restore preparation move from `BackupScreen` to the client (`previewRestore`, `readBackup`, `exportBackup`, `restoreBackup(data, selection)`); merge restores keep listening done during the confirmation (H5) | `BackupRestoreTest` (9; the new merge test fails on the old behaviour), backup suites, `ArchitectureTest`, smoke compile; earlier restore fixes removed in turn still fail their tests |
| 17 | `2292520`, `12359fb`, `6aefa87` | Maintenance: build split into `gradle/smoke.gradle` and `gradle/native-helpers.gradle`, bundled libraries listed once, shared helper packaging; tracked `scripts/smoke.ps1` and `verify.ps1 -Tests`; shared smoke reflection; shared CI toolchain action, SDK taken from the helpers' pin | Archive listings before and after (identical entries), configuration cache with the smoke task, runner self-test and controlled timeout, SDK-pin test fails on a mismatch, full window below |
| 15 | `c3bc34d` | Restore owns its files through shutdown and failed applies (H1–H4, `BackupRestore`); reset closes every old service (T1) | `BackupRestoreTest` (8, real stores and journal, files reopened; 5 fail on the old behaviour), `ResetStopTest` (real download, library and playback services), `ClosedLibraryWritesTest`; each fix part removed in turn fails its tests |

## Verification history

| Run | Result |
|---|---|
| Baseline `verify.ps1` at `0792986` (headless) | 719 tests, 709 passed, 10 skipped |
| Latest `verify.ps1` (headless, after batch 17) | 776 tests, 766 passed, 10 skipped, 0 failed |
| Latest `verify.ps1 -Clean -DesktopIntegration` (after batch 17) | 776 tests, 770 passed, 6 skipped, 0 failed; the skips are live managed install, live LRCLIB, two PiP install, filesystem permissions and a symlink fixture |
| Smoke windows after batches 7, 12, 14, 15 and 17 | default, reset (each with restart), advanced, cleanup, queue, lyrics, and preview (from batch 12): all pass in the latest window. Private collection: all 55 files have the same SHA-256 before and after every window |
| Manual checklist (user, after batch 7) | Done, no problems reported. It covered filter endpoints by ear, quitting with playback, a download and PiP active, reset and restore by hand, hub navigation, and the new zh_tw messages |
| Manual checklist (user, after batch 17) | Done, no problems reported: playback, seek and queue; setting preview, save and restart; restore with history, quick quit and restart; reset during playback then playback; download preview; PiP setup and Media Controls state |
| Manual checklist (user, after batch 16) | Done, no problems reported: preview counts while toggling categories, merge restore with history during playback after a confirmation held open over 30 s, replace restore of settings, playlists and key binds then restart, export then reopen |
| Manual checklist (user, after batch 15) | Done, no problems reported: restore with history then a quick quit, playlists-only restore during playback, settings and key binds restore, reset then playback, settings and profiles after both, download preview then playback |

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
  - an apply failure after the install reports `backup.restart` and keeps the restore restrictions until restart
    (media actions blocked, settings changes not applied or saved, no resume point at quit);
  - the restore flags are settled before the result is reported;
  - a merge restore merges with the state when it starts, not when it was previewed;
  - a backup whose settings cannot be read fails at confirmation with `backup.failed` when key binds are selected;
  - while a restore that selected history owns the file, listening progress stays unsaved; if Minecraft closes
    and the install then rolls back, up to about 15 s of earlier progress is lost.
- **Backup screen:** a refused storage submission for export or read resets the busy state instead of escaping the
  button handler.
- **Shutdown:** settings are saved first, PiP closes before playback and lyrics before downloads, and a final
  settings write that never reached disk is logged.
- **Messages:** three new strings in both catalogs (`reset.darkmusic.restart`, `backup.restart`, `error.startup`).

## Maintenance plan (batch 17): completion index

The 2026-10-30 maintenance-complexity plan, measured against the code at `d84a22e`. The plan files themselves stay
outside the repository.

| Batch | Disposition |
|---|---|
| 0 Baseline | Done: packaging listings of every archive before the change, recorded test counts, change paths below |
| 1 Reproducible runtime checks | Done: `scripts/smoke.ps1`, `verify.ps1 -Tests`, shared smoke reflection. A timeout stops the whole Gradle run, including a client not yet launched (`--no-daemon`); checked on a synthetic process tree and in the testing window |
| 2 Build responsibilities | Done: `gradle/smoke.gradle`, `gradle/native-helpers.gradle`, `bundledLibraries`, shared helper packaging; artifact entries unchanged |
| 3 CI and docs | Done: `.github/actions/setup-toolchain`, SDK from the helpers' pin, testing guide rewritten around current commands. NuGet updates not added: neither helper references a NuGet package, only runtime packs |
| 4 Lifecycle evidence | Partly done. Covered by tests: a failing close, a service that does not stop, shutdown before publication, a reset timeout (real services and stores), and an old audio line stuck in write while its replacement plays on the real device (passed on the Windows default device, 2026-10-31). Not covered: the client's own adapters (`buildReplaceable`, `ResetSteps`, the shutdown step bodies), which still run only in the smoke checks |
| 5 Application coupling | Not started on purpose: no change path showed duplicated policy |

Change paths, before and after:

| Task | Before | After |
|---|---|---|
| Upgrade the helpers' .NET SDK | 4 edits (two `global.json`, two workflows) | 2 edits (both `global.json`); CI reads PiP's; a test fails on a mismatch |
| Upgrade a bundled codec | 2 lists in `build.gradle` | 1 entry in `bundledLibraries` |
| Change CI toolchain setup | 2 workflows | 1 composite action |
| Change a lifecycle operation, add a setting | operation, adapter, screens, catalogs, tests | unchanged: each file owns a distinct concern |

## Open items

- **Service holder** (moving the service fields into one object): deferred. Reset would get a single atomic swap,
  but publication already happens on the client thread and the callbacks compare captured identities, so no observed
  defect needs it. It would cost about 500 field-access edits, mostly in the smoke sources. Worth revisiting if the
  service set grows or a generation-level test becomes necessary.
- `ready`, `ResetSteps` and `buildReplaceable` stay as wiring inside `DarkMusicClient`. They are covered by
  compilation, review and the smoke checks, not by unit tests; batch 4 of the maintenance plan is therefore not
  complete. `BackupRestoreTest` runs shutdown through
  `Shutdown.run` with its own steps, so the client's own step bodies are not exercised there.
- **Unfinished futures at exit:** `Minecraft.execute` queues and never refuses, and the queue is dropped when the
  game loop stops. A reset or restore whose last step is queued then never completes its future; nothing waits for
  it.
- **Reset after a timeout:** `ResetStopTest` holds an old player stuck in its line's write while the replacement
  plays on the real default device (passed on the Windows default device, 2026-10-31). One device and driver only; a line that never returns
  stays open until Minecraft exits. The late loudness write (T2) is accepted.
- Settings ownership: no change. Retained screens re-read settings when shown again and the confirmation screen
  cannot be left while a reset or restore runs, so no edit reaches a replaced settings object (checked by reading the
  code, not by a test).
- **Loom client-only manifest list:** a clean build run as separate `clean` and `build` invocations (as
  `verify.ps1 -Clean`) writes the JAR manifest without `Fabric-Loom-Client-Only-Entries`; one invocation writes it.
  The build before batch 17 behaves the same. DarkMusic is client-only, so players are unaffected; a CI-built JAR
  has not been inspected.
- **Helper reproducibility:** a clean `dotnet publish` gives helper binaries of the same size with different bytes,
  so each build's manifest hashes are its own.
- **Batch 17 CI:** the build workflow with the composite action passed on Ubuntu, Windows and macOS at `5093019`,
  and Dependabot scanned the action. The release workflow runs only on a tag and has not run with these changes.
- **Release:** released in v3.6.0 (2026-10-31).

## Rerunning the runtime checks

`./scripts/smoke.ps1 -Scenario suite -DesktopVerify` runs the smoke modes in sequence (default, reset, each with
restart, then advanced, cleanup, queue, lyrics and preview), each with a 20-minute limit, then
`verify.ps1 -Clean -DesktopIntegration`, and hashes the private collection before and after. See
[the testing guide](TESTING.md#in-game-smoke-checks). The smoke build sets fullscreen 1920×1080, GUI scale 2 and
SmoothFont. Before attributing a smoke failure to new
code, rerun that mode on a worktree of the previous release.
