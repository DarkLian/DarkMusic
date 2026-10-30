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

## Batch 3: library reconciliation (2026-10-30)

**Invariant improved:** after each scan, the services that keep per-track state are reconciled by one owner, which
holds the revision markers and the one outstanding playlist cleanup. Work left over from a replaced library cannot
mark the new library as reconciled.

**Changes:**
- `LibraryReconciliation` (root package) now does the per-revision work that used to be split between
  `DarkMusicClient.tick` and `cleanMissingPlaylistEntries`: lyric choices, duplicate reviews and scans, the source
  index and its evidence, queued playback, and playlist cleanup. Each consumer keeps its own evidence rule, listed
  in the class comment. Every consumer gets the whole snapshot, never a reduced "tracks changed" event.
- `tick` now calls it and keeps only the history flush and the autoplay decision.
- Reset calls `reconciliation.reset()`. It starts a new generation, clears the markers, and makes any cleanup still
  running for the old library ignore its result.
- Inputs come through an adapter that reads the fields on each call, as `hud` does, so reset and restore
  replacements are seen.

**Defect fixed:** the markers (`queuedLibraryRevision`, `cleanedScan`, `cleanedPlaylists`) previously survived
reset. A replacement library counts revisions from the start again, so when its revision matched an old marker:
- the per-revision reconcile was skipped;
- playlist cleanup counted as done, which also allowed autoplay;
- a cleanup still running from before the reset could set the markers for the new library.

Shown by `aCheckFromBeforeAResetCannotMarkTheNewLibraryReconciled`, which fails when `reset()` is emptied
(mutation check).

**Checked, no change needed:** playlist cleanup does not prune entries in unreadable subtrees.
`PlaylistStore.missing` removes an entry only when `Files.notExists` holds, and an inaccessible file reports
neither existing nor not existing.

**Checks:** `LibraryReconciliationTest` (5 tests) uses disposable text files and queued storage and client
executors. It covers:
- removal only from complete roots, and only for files absent on disk;
- a playlist edit during the check;
- changed roots or a busy client when the result arrives;
- no start while media operations hold the files;
- an old-generation result after a reset.

`ArchitectureTest`, `PlaylistIndexTest` and `ResetOperationTest` passed. The smoke sources compile.

**Runtime-unverified:** startup autoplay timing and playlist cleanup with a real library in game. Covered by the
default and cleanup smoke checks in the testing window.

## Batch 4: settings application and replacement (2026-10-30)

**Invariant improved:** the whole-settings operation is named for what it does. Its order is documented: derived
values are normalized first, then the settings are applied to services, then written on save. The semantics of a
settings replacement are written down and tested.

**Changes:**
- `configureVisualizer` is renamed `applySettings` in the client, the screens (`AudioPage.Sources`) and the smoke
  sources. It sets the theme, codec paths, download format, lyrics preferences, profile and environment names,
  playback DSP and spectrum, so the old name hid most of what it did. The profile and environment name matching now
  runs first. That is behaviour-neutral: it reads and writes only `audio` and `environment`, which nothing earlier
  in the method uses.
- `SettingsSource.save` and `AudioPage.Sources.applySettings` say exactly what they promise. Applying happens while
  a control moves. Saving applies the settings and then queues the write. A queued write is not yet on disk.
- `SettingsStoreTest.anEditToAReplacedObjectIsNeverWritten` is new.

**Replacement semantics (audit result, no framework added).** `SettingsStore.replace` happens in three places, and
each is triggered from its own flow:
- **Load** happens before any screen can open, because `open` requires settings.
- **Reset** starts from Settings → confirmation.
- **Restore** starts from Settings → Backup → confirmation.

While a replacement happens, only the confirmation screen that started it is active. The screens beneath it
(Music, Settings, Backup) rebuild their widgets in `init` when shown again, and `SettingsScreen` additionally resets
its preview state when the object changed (`shownSettings`). No gui class keeps a `Settings` field except that one.
The only nested target kept is `ColorsScreen`'s colours, and it cannot be on screen during a replacement. Every save
goes through the store, which writes the current object. Closing a screen after a reset saves the reset settings,
never an obsolete copy.

**Coupled settings operations, already adequate:**
- Profiles and environments already have explicit model operations in `AudioSettings` and
  `SoundEnvironmentSettings`: `select`, `selectCustom`, `savePreset`, `loadPreset` and `saveSelected`, with
  `matchProfile` and `matchPreset` as normalization.
- `AudioProfileTest`, `EnvironmentPresetTest`, `PlaybackConfigurationTest` and `CustomizationTest` cover
  Original/Custom/saved semantics, legacy and missing values, and the playback copy at the worker boundary. All
  passed.
- Moving to an `update(s -> ...)` store would change when saves happen, because many screens save on close. That
  was deferred earlier on purpose and stays deferred: it is a behaviour change, not a structural one.

**Checks:** `SettingsStoreTest` (6), `ArchitectureTest`, `AudioProfileTest`, `EnvironmentPresetTest`,
`PlaybackConfigurationTest` and `CustomizationTest` passed. `compileSmokeJava -Psmoke` passed.

## Batch 5: screen navigation (2026-10-30)

**Invariant improved:** the player and Settings reach the screens they open through `gui.Navigation`. Their Sources
declare only what they use themselves, so they no longer inherit the dependencies of every reachable screen.
Before this change, `MusicScreen.Sources` extended, directly or transitively, the Sources of almost every screen in
the mod.

**Changes:**
- `gui.Navigation` lists 20 destinations, each with the context it needs (parent, song, colour target, HUD element)
  and nothing about services.
- `ScreenNavigation<S extends ...>` (root package) builds each destination with its own Sources. Its type bound lists
  exactly those Sources, so it never names `DarkMusicClient` and the dependents cap stays at 5.
- `MusicScreen.Sources` now extends only `ImportOverlay.Sources` and `PlayerProgress.Sources`, the components it
  draws. It declares its own needs explicitly: playback, library, playlists and their readiness, history, lyrics,
  artwork, media flag, queue editing, import, open folder, warnings.
- `SettingsScreen.Sources` now extends `AudioPage.Sources` and `PlayerProgress.Sources`, and declares playback,
  library and history.
- `DarkMusicClient` implements each destination's Sources explicitly, the honest list for a composition root. It
  exposes `navigation()`. Screen constructor signatures are unchanged, so the smoke sources compile as they were.
- `LyricsConsent.request` now takes `SettingsSource`, which is all it uses, instead of `LyricsScreen.Sources`.
  `UpdateScreen`'s constructor is now public like the others.
- New guard `ArchitectureTest.screensDoNotInheritTheSourcesOfScreensTheyOpen`. A screen's Sources may extend shared
  pieces (`SettingsSource`, `AudioPage.Sources`, `ImportOverlay.Sources`, `PlayerProgress.Sources`,
  `TrackThumbnails`), never another screen's Sources. The 11 screens not migrated are listed and may only be
  removed.

**Not migrated (listed in the guard):** the local chains for downloads (tools, paths, duplicates, existing
downloads), the cleanup chain (library manager → smart cleanup → comparison → deletion/ignored, music folders,
existing downloads), metadata → deletion, PiP → colours, playlists → track picker, song actions → Up Next. These are
one or two levels deep and grant only neighbouring services. Their migration is the same mechanical step and is
recorded as follow-up work.

**Adding a destination now:** give the screen its Sources, add a `Navigation` method, and build it in
`ScreenNavigation` (whose bound gains that Sources), which `DarkMusicClient` then implements. No other screen's Sources changes.

**Interaction preserved:** each destination gets the same parent (`this`) and the same arguments as before, so
back navigation, the metadata `saved` callback, the colours target and the HUD element selection are unchanged.
`show(...)` is unchanged.

**Checks:** `ArchitectureTest` (4), all `gui` tests, `LocalizationTest` and `CustomizationTest` passed. Client and
smoke compile.

**Runtime-unverified:** clicking through player → Settings → Audio Options and back, and the other hub destinations,
in game. Covered by the default, HUD and advanced smoke checks.

## Batch 6: late writes versus reset storage (2026-10-30)

**Invariant improved:** before reset clears the files, it waits for every writer of a file it is about to clear.
This covers the plan's requirement that pending old writes must not overwrite reset state.

**Audit.** What `ResetStorage.clear` deletes, and who writes it:

| File | Writer | Waited for before clear? |
|---|---|---|
| `library-cache.json` | `MusicLibrary` scanner | yes, `awaitStopped` |
| `import-history.json` | `ImportHistory` via `io` | yes, the reset task runs on `io` (FIFO) |
| `loudness-cache.json` | `LoudnessCache` writer; the audio worker queues the final flush as it ends | **no**: `awaitStopped` joined only the worker |
| `downloaded.txt`, `.darkmusic-downloads.json` per root | download pool; **bookkeeping repair** executor | pool yes; **repair no**: `awaitStopped` skipped it |

Restore was checked as well. Config, playlist and history writes all go through `io`, so a write queued before
the install runs first, and the install overwrites it. During restore, the `restoring` gate and the paused history
block any later write.

**Fixes:**
- `JavaSoundBackend.awaitStopped` now also waits, within the same deadline, for the loudness cache's final write.
- `DownloadService.awaitStopped` now also awaits the bookkeeping executor. `close` has already cancelled its
  scheduled work, so only a repair already running can still finish.

Without these, a reset could leave a loudness cache behind, or a download-history entry that blocks downloading
that video again.

**No new deterministic test:** both writers run inside services that offer no seam to hold the final write, and
adding test-only plumbing to the audio worker was judged not worth it. Verified by review and by the affected
suites: `AudioWorkerTest`, `OutputRoutingTest`, `NativeAudioTest`, the loudness tests, all download tests and
`ResetTest` (8, real excerpts, 0 skipped). All passed.

**Publish-phase gates checked:**
- `Shortcuts.restoreDefaults` has no admission gate.
- In `FolderActions.activate`, `library.setRoots` is ungated and its save is gated. Since the reset rework, that
  save is skipped while resetting and happens in `release`.

So nothing that used to run is silently skipped.

## Batch 7: consolidation (2026-10-30)

- `Cleanup.close(name, resource)` skips resources that were never created. The shutdown chain now uses it instead
  of repeated null-check lambdas. `CleanupTest` covers it.
- `ARCHITECTURE.md` now records executor ownership (owned or borrowed) and rejection behaviour, the subsystem
  dispositions (improved, already adequate or deferred, each with its risk), and before/after measures.
- The `darkmusicclient-decoupling-series` agent memory now describes the navigation pattern.

**Final automated check:** `scripts/verify.ps1` (private collection through excerpts; no `-Clean`, no
`-DesktopIntegration`) gave **744 tests: 734 passed, 10 skipped, 0 failed**. The baseline was 719/709/10, so there
are 25 new tests and the skips are the same desktop and live-only set. `compileSmokeJava -Psmoke` passed.

**Open items:**
- 11 local navigation chains, listed in `ArchitectureTest`.
- The `update(s -> ...)` settings store, deferred as a behaviour change.
- No deterministic test holds the loudness and bookkeeping final writes.
- `RestoreJournal.recoveryPending` reads the disk on the client thread.
- The clean, desktop-integration and smoke runs listed below.

## Deferred verification: coordinated testing window

None of the following was run during this work: no Minecraft, no desktop dialogs, no audible or device checks.
Run them together when the computer is free. The smoke build already sets fullscreen 1920×1080, GUI scale 2 and
`run/resourcepacks/SmoothFont_1.21.zip` and the private collection. Pass the managed tools on every run that plays
music, because the collection is Opus.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-25.0.4.1"
$tools = (Get-ChildItem run/config/darkmusic/tools/managed -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$common = @('-Psmoke', '-PmusicDirectory=run/config/darkmusic/music', "-PdownloadToolsDirectory=$tools", '-PvisualSmoke')
./gradlew.bat runSmokeClient @common                                # player, playback, shortcuts, Settings navigation, autoplay
./gradlew.bat runSmokeClient @common -PverifyHudRestart             # restart of the default run: settings survived shutdown
./gradlew.bat runSmokeClient @common -PresetSmoke                   # Reset to Defaults: ResetOperation path, tools and folders kept
./gradlew.bat runSmokeClient @common -PresetSmoke -PverifyHudRestart # restart of the reset run (same run folder)
./gradlew.bat runSmokeClient @common -PadvancedSmoke     # audio pages reached through Navigation, environment
./gradlew.bat runSmokeClient @common -PcleanupSmoke      # reconciliation consumers, duplicate review
./gradlew.bat runSmokeClient @common -PqueueSmoke        # Up Next, playlists, reconcileQueued
./gradlew.bat runSmokeClient @common -PlyricsSmoke       # lyrics screens; LyricsConsent now takes SettingsSource
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/verify.ps1 -Clean -DesktopIntegration
```

**Rule:** before blaming this work for a smoke failure, rerun that mode on a worktree at `0792986`. The smoke checks
have drifted before.

## Testing window results (2026-10-30, user away)

The smoke runs used fullscreen 1920×1080, GUI scale 2, SmoothFont, the private collection and the managed tools.

| Run | Result |
|---|---|
| default | pass |
| default + `-PverifyHudRestart` | pass |
| reset (`-PresetSmoke`) | pass |
| reset + `-PverifyHudRestart` | **failed**, then pass after a smoke-check fix (below) |
| advanced (`-PadvancedSmoke`) | pass |
| cleanup (`-PcleanupSmoke`) | pass |
| queue (`-PqueueSmoke`) | pass |
| lyrics (`-PlyricsSmoke`) | pass |
| `verify.ps1 -Clean -DesktopIntegration` | **744 tests: 738 passed, 6 skipped, 0 failed**. The skips are live network, managed-tool install, linked install and filesystem-permission cases, the same pattern as the v3.5.1 release (719/713/6) |
| Private collection | all 55 files have the same SHA-256 before and after |

**The reset-restart failure was pre-existing smoke drift.** "Preserved playlist was not persisted" failed in the same
way on a `0792986` worktree. The check was written for v1.0.2 and expected exactly one playlist after the restart.
Since v2.1.0, the permanent Favorite Music playlist makes it two. The check now requires Favorite Music plus the
playlist kept by name ("Keep this playlist"). Both reset modes then pass on the current code.

**Manual checklist (user, 2026-10-30): done, no problems reported.** It covered:
- listening while moving the environment filters to and from their end positions;
- quitting with playback, a download and PiP active;
- Reset to Defaults and a backup restore by hand;
- clicking through the player and Settings destinations and back;
- reading the three new zh_tw messages.

**Release status:** not released. Per AGENTS.md, a tag still needs pushing approval and a normal play session.

## Batch 8: remaining navigation chains (2026-10-30, approved follow-up)

The 11 remaining screens now open their destinations through `Navigation`, which gains 11 destinations:
- colours with a preview;
- download tools, download paths and a download diagnostic;
- a duplicate download and existing downloads;
- smart cleanup, ignored matches and the duplicate comparison;
- audio deletion and the track picker.

Each screen's Sources now extends only shared pieces and declares what the screen and its group pages call. The
declarations were copied from the interfaces they used to inherit. `SongActionsScreen` adds `TrackThumbnails`
because it draws a song card. The guard now allows no exceptions.

**Interaction preserved:** each destination keeps its parent and the arguments it had before, including the
deletion completion screen, the track-picker callback and the PiP spectrum colour preview.

**Checks:** client and smoke compile; `ArchitectureTest` (4), the `gui` tests and `LocalizationTest` passed.

**Runtime-unverified until the next smoke run:** the downloads, cleanup, playlist and track picker, song actions
and PiP colour paths. These are covered by the cleanup and queue smoke modes, and by the download preview client.

## Batch 9: restore recovery check and late-write tests (2026-10-30, approved follow-up)

- **The restore recovery check is off the client thread.** `RestoreOperation` now reads `recoveryPending` on the
  storage executor right after the install, whether or not the install succeeded, and passes the result to
  `end(boolean)`. A journal that cannot be read keeps the restrictions. A refused storage submission (shutdown only)
  releases them, because nothing was installed and admission already guaranteed no recovery was pending.
  New test: `RestoreOperationTest.aPendingRecoveryIsReadOnStorageAndKeepsTheFlags`.
- **Deterministic late-write tests.** `StoppedMeansWritesDoneTest` holds the loudness cache's writer and the
  download bookkeeping executor busy, closes the service, and asserts that `awaitStopped` reports not-stopped until
  the writer finishes. The held task ignores interrupts, as an in-progress file write would. Both tests fail against
  the pre-batch-6 `awaitStopped` (verified) and pass now. They reach the private executors by reflection, so no
  production code was added for them.

## Batch 10: testable startup and shutdown sequences (2026-10-30, approved follow-up)

- `Startup` owns the build-or-unwind sequence. A failed build closes what was acquired, in reverse, then withdraws
  the partial state. It reports the original error with any close failures attached as suppressed, and saves only a
  complete build.
- `Shutdown` owns the closing order, taking the services as a record: final save → `closed` → cancel work in flight →
  integrations, PiP, links, cleanup, audition, lyrics, downloads, library, playback, caches → history → storage.
- `DarkMusicClient.start` and `shutdown` now only supply the pieces.
- The resume-point record and the final save stay isolated (`try/finally`). Cancelling the picker, the media
  operation and the browse pool runs as its own guarded `Cleanup`.
- `StartupShutdownTest` (3 tests) drives both sequences without Minecraft: a complete startup; a failed startup with
  a close that also fails; the closing order with one failing close.

**Remaining limit:** which services are built is still wiring inside `DarkMusicClient`: `ready` and `ResetSteps`.
Item 9 (a service holder) would make that list testable too. `DarkMusicClient` is now 1341 lines, because the
anonymous step classes are explicit.

## Batch 11: write acknowledgement (2026-10-30, approved follow-up)

- **Acknowledged writes.** `LatestWriter.write` returns an acknowledgement. It completes once that JSON, or a newer
  one that replaced it before it was written, is on disk. It fails with the write, or with the executor's refusal
  (the call still throws, as before). Coalescing is unchanged: replaced writes share the acknowledgement of the one
  write that happens.
- **`snapshotJson` is unchanged.** It is still the latest requested settings, used by backup export, and is not
  relabelled as committed. `ConfigStore.save` passes the acknowledgement up, and `SettingsStore.lastWrite()` holds
  the latest one. A skipped save does not change it.
- **Where it is used.** `Shutdown` gains a last step, after the storage drain, that logs when the final settings
  were still queued or failed to write. Previously that loss was silent. Reset keeps its own read-back of the
  config, which runs behind the save on the same executor.
- **Not changed.** Screens and actions still do not wait on writes, so save timing for the player is unchanged.
  Failed writes are still announced by the existing writer callback.
- **Checks.** `WriteAcknowledgementTest` (4 cases: queued is not written; a replaced write is covered by the newer
  one; a failed write fails its acknowledgement; a refused write leaves the writer usable). `SettingsStoreTest`,
  `StartupShutdownTest`, `WriterRecoveryTest`, `StorageTest`, `BackupExportTest` and `PlaylistIndexTest` passed.
  Smoke compiles.

## Batch 12: settings update operation (2026-10-30, approved; "same timing" chosen)

- `SettingsSource.update(edit)` is a default method. It edits the current settings object, applies it, and queues
  the write, all in one call. It does nothing before the config loads. The edit always lands on the live object,
  never on one that reset or restore has replaced.
- Migrated every place that edited a setting and then saved straight away:
  - the root actions: speed, volume, shuffle, repeat, output device, music folder reset, and the HUD and lyrics HUD
    shortcuts;
  - lyrics consent, the download format toggle and the PiP size scroll.
- **Unchanged on purpose, as chosen:**
  - disk writes happen when they did before;
  - previews while a control moves still edit in place and save on release;
  - screens still save when they close.
- **Checks:** `SettingsStoreTest` (8, with 2 new: the update edits the current object and runs apply then write, and
  does nothing before load), `ArchitectureTest`, the `gui` tests, `LocalCommandsTest`, `CustomizationTest` and
  `SpeedAndHudTest` passed. Smoke compiles.

**Follow-up check (items 3–8):** `scripts/verify.ps1` (headless, private collection through excerpts) gave
**756 tests: 746 passed, 10 skipped, 0 failed**. The skips are the same desktop and live-only set.
`DarkMusicClient` is 1355 lines.

**Runtime-unverified, needed before release:** the smoke window again, for the newly migrated navigation chains
(downloads, cleanup, playlists and track picker, song actions, PiP colours) and the `update` call sites (speed,
volume, shuffle, repeat, output device, HUD shortcuts, lyrics consent). Then `verify.ps1 -Clean
-DesktopIntegration`. Same commands as the testing window above.

## Follow-up testing window (2026-10-30, user away)

Smoke runs used fullscreen 1920×1080, GUI scale 2, SmoothFont, the private collection and the managed tools.

| Run | Result |
|---|---|
| default, default + restart | pass, pass |
| reset, reset + restart | pass, pass |
| advanced, cleanup, queue, lyrics | all pass |
| preview (`-PpreviewSmoke`: download screens and HUD previews) | pass |
| `verify.ps1 -Clean -DesktopIntegration` | **756 tests: 750 passed, 6 skipped, 0 failed** (the same 6 live, install and permission skips as before) |
| Private collection | all 55 files have the same SHA-256 before and after |

This covers the navigation chains migrated in batch 8 and the `update` call sites from batch 12, at runtime. Still
manual: nothing new beyond the earlier checklist, which still applies to these changes. The main remaining manual
check is clicking through download, cleanup, playlist and track-picker, and PiP colour screens and back.

## Batch 13: operation completion fixes (2026-10-30, review follow-up)

Three review findings, each verified in the code before the fix:

1. **Completion order.** `RestoreOperation` completed its future inside `try` and released the flags in `finally`,
   so a caller's `whenComplete` ran while `mediaBusy`/`restoring` were still set. A failing `end` after success was
   silently lost, because the future was already complete. **Fix:** `conclude` decides the outcome, runs
   `end(...)`, then completes the future. A failing `end` becomes the result after a success, or is attached as
   suppressed to an earlier failure. Either way it is logged.
2. **Refused client dispatch.** Both operations called `client.execute(...)` inside `whenComplete`/`thenAccept`. A
   refusal only failed the unobserved dependent stage, so the returned future never ended. **Fix:** the new
   `ClientDispatch.run` posts the final step and fails the future when the client thread refuses it or the step
   throws. Client-owned state stays as it is in that case. That only happens while Minecraft is closing:
   `resetting` stays set, the restore flags stay held, and a pending journal recovery runs on the next start.
3. **Exceptional storage completion.** An `Error` from `install`, or anything else that is not an `Exception`,
   completed the storage stage exceptionally, and `thenAccept` then skipped the continuation. **Fix:**
   `whenComplete` turns that into an install failure with an unknown journal state. The restrictions stay, the
   future fails with `backup.failed`, and `end` runs.

**Tests.** All waits now have a 5-second timeout, so a regression fails rather than hangs. That was learned the hard
way: the first run against the old code hung.
- `RestoreOperationTest` (+4): completion only after the flags are released (success and apply failure); a failing
  `end` is reported, or attached to the original failure; refused client dispatch; a storage task that dies.
- `ResetOperationTest` (+2): refused client dispatch; a client step that throws.
- Against the previous operations, 5 of the 6 new tests fail. "A client step that throws" already passed there and
  stays as a guard.

## Batch 14: one place builds the replaceable services (2026-10-30, review follow-up)

**Assessment.** `ready(...)` and `ResetSteps.publish(...)` both built artwork, waveforms, playback, library and
downloads. Each registered every service for cleanup and configured playback from the settings, in slightly
different orders. A separate service-owner class was considered and rejected. It would need the config folder,
settings, histories, library, lyrics and the closed flag, which makes it either a coordinator with the same
unrestricted access or a wide new interface. Nothing is gained over a private method in the composition root.

**Change.** The private method `buildReplaceable(Cleanup)` builds the generation that reset replaces, in one order.
Each service is installed in its field at once, which keeps current-service access for the HUD, PiP and callbacks.
Each is registered with the caller's cleanup, so partial construction still unwinds. Playback is configured and the
settings are applied before the library exists. That keeps startup's order, so codec paths are set before any scan.
For reset, `applySettings` now runs before the library and downloads exist. That is equivalent:
`createDownloads` sets the format itself, and `applySettings` does not touch the library.

**What stays with the callers, on purpose:**
- startup builds the app-lifetime services (histories, playlists, PiP), wires the lyric hooks once, sets autoplay,
  and warns about a bad saved folder;
- reset replaces the settings first, and for defaults clears the warning and restores the shortcuts.

PiP is now built before the replaceable set. Its constructor stores only the sources and a lazy settings check.

**Checks:** the focused tests passed (`ResetOperationTest`, `RestoreOperationTest`, `StartupShutdownTest`,
`CleanupTest`, `ArchitectureTest`, `LibraryReconciliationTest`, `SettingsStoreTest`), and smoke compiles. The full
headless `verify.ps1` gave **762 tests: 752 passed, 10 skipped, 0 failed**.

**Runtime-unverified (next coordinated window):**
- the default smoke run (startup, autoplay) and the reset smoke runs (rebuild after reset), with their restarts;
- `verify.ps1 -Clean -DesktopIntegration`.

**Further `DarkMusicClient` extraction (item 9).** Moving the service fields into a holder object would give reset
one atomic swap. It would also make the built set constructible in tests, but only with an injected client executor
and audio backend factory. All publication already happens on the client thread, and the callbacks compare captured
identities, so the atomic swap fixes no observed defect. The cost is about 500 field-access edits, mostly in the
smoke sources. No concrete benefit justifies it now. It stays deferred, as an option if the service set grows or a
generation-level test becomes necessary.
