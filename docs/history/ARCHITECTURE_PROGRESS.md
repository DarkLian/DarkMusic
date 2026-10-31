# Architecture work: progress log

A record of the 2026-10-30 architecture work, started from v3.5.1 (`0792986`), and of its follow-up review the
same day (batches 15 and 16), and of the maintenance batch 17. The current design is described in
[ARCHITECTURE.md](../ARCHITECTURE.md), with the limits still open at its end; this file keeps the evidence: what
was found, what changed and how it was checked. The work is finished.

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
| L6 | Reset publishes replacements even when an old worker did not stop within 15 s | Kept, with evidence: storage is left untouched, and nothing the old services can still finish replaces user data (see ARCHITECTURE.md, Reset after a timeout). One late write found: T2, fixed after v3.6.0 |
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
| T2 | A replaced loudness cache's late final write can drop readings its replacement saved since (reproduced, then the probe removed) | Fixed after v3.6.0, without an ownership rule (which would have stopped the main cache saving after a preview closed): every cache merges with the file under one lock; where readings cover two versions of a file, the version on disk now wins (the newest modification time when the file cannot be checked), so neither a late writer nor an unrelated save restores a replaced version (follow-up review, batch 19); and a file that became unreadable is preserved (`LoudnessPersistenceTest`) |
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
| 18 | `98e2dff`, `728e7c1`, then T2 with this row | v3.6.0 source audit: turning True Peak on or off fades out, switches between the direct and the 64-frame-delayed path on silence and fades back in, instead of mixing the two (which cancelled some frequencies); the limiter restarts clean. Inspections without a reading are forgotten oldest first instead of all at once, and expired retry waits go first. T2 fixed (merge on save). Shutdown dispatch, caption network boundary and crash durability were already documented; tag reuse by size and modification time added to ARCHITECTURE.md | `UnifiedAudioDspTest` toggle test (fails on old code at the cancelling tone), `LoudnessPersistenceTest` (2 new, both fail on old code); 8 audio and loudness classes pass. Negative-cache eviction and the limiter's clean restart have no direct test; listening check (user, 2026-10-31): a single toggle is inaudible; toggling rapidly strings the short dips into a wobble |
| 19 | The commit adding this row | Follow-up review of batch 18: a late writer could still keep its older version of a file over the newer one, and an unrelated save could bring back a replaced version, because the saving cache's own view decided. The version the file has now decides instead, or the newest modification time when it cannot be checked. Retry waits beyond 4,096 drop the soonest-ending first instead of all | `LoudnessPersistenceTest` (10; the late-writer, unrelated-save and retry-wait tests fail on `7298d94`) plus a direct test of the version choice |

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

## DarkMusicClient split (2026-10-31)

Measured against `92313e1` (v3.6.1). Earlier decoupling made screens independent of `DarkMusicClient` by giving each
a `Sources` interface, but the client was the only implementation, so every call taken out of a screen came back as a
method on the client (1,357 lines, 26 `Sources`, 16 public mutable fields). The split moved the code out and added
guards that fail when it grows back. It changes structure only: no executor or thread changes. One assignment moved:
startup now sets the pending autoplay after lyric lookup is configured instead of just before. Nothing reads it in
between, and a failure there withdraws startup either way.

| Piece | Holds |
|---|---|
| `DarkMusicClient` (290 lines) | Fabric entry point, startup, the client tick, shutdown, autoplay; wires the rest |
| `ClientServices` | every service (private, with getters), `ClientState`, executors, settings store, the HUD/PiP/reconciliation adapters, `buildReplaceable`, `withdraw`, `apply` |
| `ClientSources` | the 26 screen `Sources`; each method one forwarding statement |
| `PlaybackCommands`, `ScreenRequests`, `BackupActions`, `OutputActions`, `ClientReset`, `Warnings` | the logic that was on the client, moved unchanged |

- `FolderActions`, `MediaActions`, `Shortcuts` and `ClientActions` now receive `ClientServices` and the collaborators
  they use; `MAX_CLIENT_DEPENDENTS` fell from 5 to 1 (the sound mixin).
- New `ArchitectureTest` guards: `MAX_CLIENT_LINES` (290), `darkMusicClientIsOnlyTheEntryPoint`,
  `clientSourcesOnlyForwards`, `noPublicMutableClientFields`. Each was checked to fail on an injected violation.
- `ClientServices.buildServices` takes the startup part of `ready` with its order kept: histories, then saved
  shortcuts, then playlists, PiP, the replaceable services and lyric lookup.
- Smoke code reaches the services through `services()`, the screens' sources through `sources()`. `HudMenusSmoke`
  reflected `HudLayer.inMenus(DarkMusicClient, Screen)`, stale since `5563d69`; it now uses `HudSources`. It runs only
  with `-PpipSmoke`.
- Checks: `verify.ps1 -Clean` 793 tests (789 plus the four guards), 11 skipped, as before; the JAR differs from
  v3.6.1 only in the moved classes. In the testing window: `smoke.ps1 -Scenario suite -DesktopVerify` passed every
  scenario, `-DesktopIntegration` included (793 tests, 6 skipped), with the private collection unchanged. The first
  `default` run failed because one check set the client's `warningUntil` by name; it now sets it on `Warnings`, and
  `default` and `default-restart` passed on the rerun. The PiP smoke with `check-hud-menus` passed.

## Structure follow-ups (2026-10-31)

Measured against `6128318`. The client split moved the place where hurried changes land; this work guarded the new
holder, turned screen mutations into operations, gave ranged settings one definition, and shortened the longest
classes and methods. It changes structure only: no thread, executor or persisted format changes.

- **Guards.** `MAX_SERVICES_LINES` and `clientServicesHoldsNoPolicy` for `ClientServices`; `MethodLengthTest` (no
  method grows past 60 compiled lines, 40 listed at their length and ratcheted down); `SmokeReflectionTest` (names
  smoke code reflects must still exist); `ScreenOperationsTest` and `SettingsSettersTest` (below). Each was checked
  to fail on an injected violation.
- **Screen operations.** `gui.TransportOperations`, `QueueOperations`, `PlaylistOperations` and `PlayerQueries`,
  implemented by `PlaybackCommands` and `PlaylistActions`. Each operation keeps the admission its call sites used, so
  no open screen behaves differently: play/toggle/next/previous/seek `idle`; stop/pause/resume `alive` (the sites had
  no guard; `idle` would stop Stop during an import); queue `canEditQueue`; playlists `playlistsReady` and no media
  operation, stricter than the sites but unreachable because those controls are inactive meanwhile. `play` is a
  transport operation: under `canEditQueue` it would also be refused during an output switch.
- **Settings ranges.** `config.Range`, applied by `validate()` and by a same-named setter, for the 95 fields whose
  range code outside `config` wrote (132 sites). Fields with dependent, nullable or derived values keep their plain
  field. A reflection dump of `Settings` after `validate()` for the real `config.json` and for every numeric field
  at edge values is byte-identical before and after.
- **Large classes.** `SettingsScreen` 830 to ~395 lines (layout toolkit, four section classes, search);
  `DownloadService` keeps one monitor, with its tools, caption fetch, duplicate review and preview state moved out;
  `LyricsLookup` gives its file record and its retry schedule their own classes and keeps the in-memory choice state,
  which shares its monitor; `MusicScreen.init`/`tick` split into methods; `JavaSoundBackend.run` 284 to 29 lines by
  method extraction only, same order within an iteration.
- **Narrower than planned.** `MusicScreen`'s `PlayerBrowser`/`TransportBar` were not extracted, because smoke
  reflects 12 of the fields they would own. `LyricsLookup`'s choice map stayed, for the shared monitor.
  `SettingsScreen` keeps its live preview, whose fields smoke also reflects.
- **Found on the way.** Two smoke checks set `DownloadService.preview` by reflection; they now set it on the new
  `previews` holder. The name-only reflection check could not see it, since another class declares `preview`.
- **Checks.** `verify.ps1 -Clean` 798 tests (797 plus the setters guard), 11 skipped, as before; smoke sources
  compile; the JAR differs only in class files; the download integration tests ran. In the testing window:
  `smoke.ps1 -Scenario suite -DesktopVerify` passed every scenario, `-DesktopIntegration` included (798 tests,
  6 skipped), with the private collection unchanged; the PiP smoke passed `check-hud-menus` and `check-setup`;
  `-PlyricsIntegration` passed. `-PcaptureMusic` exposed two older faults, fixed in their own change: choosing,
  adding or removing a folder before settings were ever activated threw on the null folder list (`FolderActions`
  now starts from the destination, as activation does, through `MusicFolders.folders`), and the capture's own
  click predated the Settings sections, so it could not reach Reset to Defaults (it now uses `SmokeUi.click`). The
  capture then passed. The listening check (crossfade, gapless, an unplugged explicit device) is the player's own.

## Maintenance follow-ups (2026-10-31)

What the structure work left out, from v3.6.1 plus `6128318`..`10fe212`.

- **Build.** `build.gradle` lost the Fabric and Loom template comments, a stray `;`, and the unused `maven-publish`
  plugin and its empty `publishing` block. Every built archive lists the same entries with the same CRCs; the
  dependency reports match; `tasks` loses only the Publishing group.
- **Tests live with their subject.** The root test package went from 73 files to 14: the root package's own tests,
  the structure guards and `PackagedJarTest`. 51 files moved with `git mv` to the package of the class they test
  (`git log --follow` keeps their history), `TestMusic` among them into `testing`, where `Tracks` and `ToolsRunner`
  joined it and `StorageTest.await` became `Await.of`. The other 8 root files were among thirteen classes named
  after a review, audit, polish pass or release; those were split by subject and deleted, and the table below maps
  each to its new classes. `ArchitectureTest` now keeps the root list and
  rejects such names. The test list is unchanged: the same 799 test methods (12 skipped), by name and result.

| Old class | Now |
|---|---|
| `AdvancedAudioTest` | `audio.AudioProcessorTest` (5), `audio.SpectrumTest`, `config.AudioSettingsTest`, `lyrics.LrcTest` |
| `AuditRegressionTest` | `download.DownloadTest` (4), `library.MusicLibraryTest` (2), `config.ConfigStoreTest` (2), `playlist.PlaylistStoreTest` |
| `CustomizationTest` | `config.SettingsTest`, `gui.ColorSpectrumTest`, `hud.ArtworkHudLayoutTest`, `hud.HudLayoutTest`, `library.SourceLinksTest`, `playlist.PlaylistStoreTest` |
| `OpusMetadataRegressionTest` | `library.OpusMetadataTest` |
| `PlayerRefinementsTest` | `config.SettingsTest`, `gui.PlayerLayoutTest`, `hud.VolumeFeedbackTest` |
| `ReviewRegressionTest` | `config.AudioSettingsTest`, `download.DownloadPipelineTest`, `lyrics.LyricsServiceTest` |
| `SpeedAndHudTest` | `audio.PcmRateConverterTest` (2), `config.SettingsTest`, `hud.HudLayoutTest`, `hud.HudScaleTest`, `library.ArtworkCacheTest` |
| `UiPolishTest` | `download.DownloadJsonTest`, `gui.HelpTest`, `gui.ScrollBarTest`, `lyrics.LyricsServiceTest` (2) |
| `audio.AudioCorrectness231Test` | `audio.LoudnessCacheTest`, `config.AudioSettingsTest` |
| `gui.AudioUx231Test` | `gui.AudioParameterTest`, `gui.EnvironmentPickerScreenTest` |
| `gui.FrontendPolishTest` | `config.SettingsTest` (2), `gui.AudioAnalyzerScreenTest`, `gui.EqualizerGraphTest`, `gui.MusicScreenTest`, `gui.PlayerLayoutTest`, `gui.UiStatusTest`, `gui.ValueSliderTest`, `hud.ArtworkHudLayoutTest`, `hud.HudLayoutTest`, `hud.HudTextTest` |
| `cleanup.ReviewPerformanceTest` | `cleanup.DuplicateStorageTest`, `cleanup.DuplicateMatcherTest` |
| `library.ReviewReliabilityTest` | `library.LibraryFilesTest`, `library.MediaFilesTest`, `library.MusicLibraryTest`, `library.TrackTest` |

Records above and in [RELEASE-CHECKS.md](RELEASE-CHECKS.md) keep the old names; the methods kept theirs, so a
search for a method name finds it.

- **Reflection names its owner.** Smoke reflects only through `SmokeUi.field/set(owner, Declaring.class, "name")`;
  `SmokeReflectionTest` checks each pair against that class, so a field moved to another class fails even when a
  different class declares the same name (the blind spot the `DownloadService.preview` move showed). Gradle had
  never re-run that test after a smoke-only change, because the smoke sources were no input of the test task; they
  are now. Smoke reads screens through read-only queries (`MusicScreen.browser()`, `SettingsScreen.previewBounds()`,
  `DownloadScreen.view()`) and finds widgets by label. Ratchets: smoke reflection 191 -> 105, test `getDeclared*`
  92 -> 72.
- **The deferred screen splits.** `MusicScreen` (945 -> 719 lines) hands its library browsing to `PlayerBrowser`
  (no Minecraft types) and its dock to `TransportBar`; `SettingsScreen` (398 -> 350) its live preview to
  `SettingsPreview`.
- **Four large classes.** `MusicLibrary` (644 -> 577): the folder watcher is `LibraryWatcher`, which took the lock
  that already guarded it; `scan` 89 -> 35 and `RootJob.run` 93 -> 29 lines. `LoudnessCache` (602 -> 434): the
  readings and `loudness-cache.json` are `LoudnessStore`, with the locks written down as they are (flushLock, then
  SAVING, then persistence; the cache's monitor never takes them); a real analysis wrote a byte-identical file
  before and after. `RowList` (650 -> 626): drag-and-drop is `RowDrag`; `drawRow` 66 -> 26. `DownloadScreen`: its
  changes go through `DownloadOperations`, which `ScreenOperationsTest` now enforces, and `init` 116 -> 9 lines.
  Tests that reflected on the moved state call the new classes' package-private API instead.
- **Checks.** `verify.ps1 -Clean -JavaOnly`: 802 tests (the 799 before, plus the reflection ratchet and the two
  test-layout guards), 12 skipped, none failed, the download integration tests included; every test method has
  the result it had before. Smoke sources compile. The `build.gradle` cleanup alone left every packaged archive
  with the same entries and CRCs; the later batches add and change classes only. A real loudness analysis wrote
  the same `loudness-cache.json` bytes before and after `LoudnessStore`. In the testing window: the smoke suite
  passed every scenario after two smoke mappings were corrected (a read on the playlist editor named the track
  picker; the Download button's label follows the mode, so `view()` now gives it); `-PcaptureMusic` and the PiP
  checks `check-hud-menus`, `check-setup` and `check-spectrum` passed; `-DesktopIntegration` 802 tests, 6 skipped,
  none failed; no run changed the private collection.

## Open items

The items still open when this work ended are the Known limits in [ARCHITECTURE.md](../ARCHITECTURE.md#known-limits).
The Loom manifest and helper-reproducibility build notes are in [the testing guide](../TESTING.md#build).
