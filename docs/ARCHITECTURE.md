# DarkMusic architecture

How the client is put together: who owns what, which thread touches it, and how services start, get replaced
and stop. Per-batch history is in [ARCHITECTURE_PROGRESS.md](ARCHITECTURE_PROGRESS.md).

## Dependency direction

```
Minecraft/Fabric callbacks, mixins, screens (gui), HUD (hud, render), PiP (pip), chat commands (command)
        │  narrow Sources interfaces / adapters; never DarkMusicClient (ArchitectureTest: DECOUPLED)
        ▼
com.darkmusic.client (root): DarkMusicClient = composition root + Fabric integration,
        Folder/Media/Client actions, Shortcuts, lifecycle (ClientState, Startup, ResetOperation, RestoreOperation, Shutdown, Cleanup, ClientDispatch), LibraryReconciliation
        ▼
backup ▸ download ▸ cleanup ▸ audio | lyrics | playlist ▸ library ▸ media ▸ tools ▸ config ▸ text
        (core packages; strictly downward, enforced by ArchitectureTest.CORE)
```

Screens get services through their own nested `Sources` interface, which declares what that screen uses. Screens
open other screens through `gui.Navigation`, which names the destination and its context (song, colour set,
deletion list). The root's `ScreenNavigation` builds each destination with that destination's own Sources.
No screen's Sources extends another screen's Sources; it may extend only shared pieces (`SettingsSource`,
`AudioPage.Sources`, `ImportOverlay.Sources`, `PlayerProgress.Sources`, `TrackThumbnails`).
`ArchitectureTest.screensDoNotInheritTheSourcesOfScreensTheyOpen` enforces this with no exceptions. Pages of one
group (audio, PiP, download tools) share their group's Sources.

Core packages never reference `DarkMusicClient`. At most `MAX_CLIENT_DEPENDENTS` files may name it (a ratchet:
only lowered). The source guard proves reference direction only, not runtime isolation.

## Threads and executors

| Executor / thread | Owner (owned / borrowed by) | Work | Bound and rejection |
|---|---|---|---|
| Minecraft client thread | Minecraft | all UI, admission of operations, publication of results, `ClientState` | — |
| `darkmusic-storage` (`io`, single) | `DarkMusicClient` owns; borrowed by `ConfigStore`/`LatestWriter`, `PlaylistStore`, histories, `DuplicateReviewStore`, `LibraryReconciliation`, reset/restore operations | config/playlist/history/import-history writes, reset storage, restore journal, backup capture | unbounded queue; serial order is part of correctness. Shut down last at exit; a write enqueued after that throws `RejectedExecutionException` (only reachable after `closed`) |
| `darkmusic-browse` (2 threads, queue 16, abort) | `DarkMusicClient` owns; borrowed by `FolderActions`, `MediaActions`, screens | folder checks, imports, metadata read/edit, deletion | rejected → `error.folder_busy`; media operations release their flag at once |
| `darkmusic-audio` + preload | `JavaSoundBackend` (per playback generation) | decode, DSP, output line | one worker |
| scan, root scan, watch | `MusicLibrary` (per library generation) | indexing, snapshots | generation counter rejects stale scans |
| download pool, analysis, repair | `DownloadService` (per generation) | yt-dlp/FFmpeg jobs, bookkeeping | fixed workers |
| lyrics, lookup, storage, captions | `LyricsService` / `LyricsLookup` (app lifetime) | lyric discovery and files | bounded queues |
| artwork, waveform, loudness | caches (per playback generation) | decoding for display/analysis | bounded |
| cleanup, source index | `DuplicateScanner`, `LibrarySourceIndex` (lazy, closed by reset) | duplicate/source evidence | single |
| update, Discord, media controls, PiP helpers | their services (app lifetime) | network/IPC/native helper processes | single |

## Ownership map

Lifetime: **app** = created once, closed at shutdown; **gen** = replaced by Reset to Defaults; **lazy** = built on
first use and dropped by reset; **screen** = owned by one screen.

| Responsibility | Mutable state | Thread | Readers | Lifetime / replaced by | Close / flush contract |
|---|---|---|---|---|---|
| Lifecycle flags (`ClientState`) | closed, resetting, mediaBusy, restoring, outputChanging | client (closed also read by workers) | every admission check | app | `closed` set once at stopping |
| Settings (`SettingsStore`) | current `Settings` object (mutable fields) | client | screens, HUD, PiP, services via apply | app; object replaced by load/reset/restore | `save` = apply then enqueue write; `persist` = enqueue only |
| Config persistence (`ConfigStore`, `LatestWriter`) | latest JSON, pending write and its acknowledgement | client enqueues, storage writes | backup export (`snapshotJson`, latest requested) | app | coalesced, serial; `write` returns an acknowledgement that completes when that JSON or a newer one is on disk; `SettingsStore.lastWrite()`; errors also reported by callback |
| Playback (`PlaybackController` + `JavaSoundBackend`) | queue, sequence/order versions, transition plans | client (controller), audio worker | screens, HUD, PiP, Discord, media controls | gen | `close` invalidates plans and joins the worker ≤1.5 s; `awaitStopped` |
| Library (`MusicLibrary`) | roots, immutable snapshots, scan generation | scan threads publish snapshots | everything that lists tracks | gen | `close` non-blocking; `awaitStopped` waits for scanners and watcher |
| Downloads (`DownloadService`) | jobs, workspace locks, receipts | download pool | download screens, HUD | gen | `close` cancels and waits ≤4 s |
| Audition (`AuditionPlayer`) | one preview clip | own worker | download screens | gen (set by screens) | `close`, `awaitStopped` |
| Artwork / waveform caches | decoded images, waveforms | own workers | HUD, screens | gen | `close` |
| Playlists (`PlaylistStore`) | playlists, revision | client; writes on storage | screens, reconciliation, shortcuts | app (`playlistsReady` after async read) | writes via storage executor |
| Listening/import history | heard counts, batches | client; writes on storage | screens, playback source | app (after `ready`) | `flush(true)` at shutdown |
| Lyrics (`LyricsService`, `LyricsLookup`) | caches, per-track operations | own executors | HUD, lyrics screens | app | `close` |
| Cleanup / source evidence | duplicate pairs, review store, source index | own workers | cleanup and download screens | lazy | `close`; reset drops them |
| PiP (`PipController`) | capture session, runtime lease | helper threads | PiP screens, HUD layer | app | `close` stops then releases runtime asynchronously |
| Updates, Discord, Media Controls | jobs, IPC, helper process | own threads | settings screens, tick | app | `close` |
| Library reconciliation (`LibraryReconciliation`) | revision markers, one pending playlist cleanup, generation | client (polled per tick); playlist absence check on storage | autoplay (in `DarkMusicClient`) | app; `reset()` starts a new generation | — |

## Lifecycle operations

All admission happens on the client thread. `ClientState` holds the flags; `alive` = not closed and not resetting,
`idle` = alive and no media operation, `accepting` = idle and not switching output.

| Operation | Admission | Acquires | Invalidates | Commit point | Publication | Failure | Final state |
|---|---|---|---|---|---|---|---|
| Startup (`Startup`, `ready`) | config loaded, not closed | histories, playlists, caches, PiP, playback, library, downloads, each registered in a `Cleanup` | — | services built | settings were published just before; services as built; playlists ready after their own read | built services closed, fields and settings withdrawn, `error.startup` warned | not-yet-loaded state (as before config load); restart needed |
| Reset (`ResetOperation`) | `idle` | `resetting` (blocks all other admission and per-tick work) | folder checks (generation), screen requests, autoplay, cleanup/source index, playback | old services closed **and** stopped, then storage cleared (storage executor) | replacements built and installed while still resetting; then `release` ends resetting and saves | old worker not stopped / storage error: storage untouched or partial, replacements built with prior settings, future fails. Building fails: built replacements closed, stays resetting, `reset.darkmusic.restart`. Closing first or storage refused: nothing published | defaults (or prior) published; saved config read back behind the save |
| Restore (`RestoreOperation`) | `accepting`, playlists and history ready, shortcut keys valid | `mediaBusy` + `restoring` | history writes paused | `RestoreJournal.install` returns; recovery state read right after (storage executor) | client thread replaces settings, playlists, history, key binds; applies to playback; flags released, then the future completes | install fails or the storage task dies: nothing applied, `backup.failed` (an unknown journal keeps the flags). Apply fails: on disk, `backup.restart`. A failing release is reported, never lost. Closing in between: completes. Client thread refuses the result: the future fails, flags untouched | flags released, kept while a journal recovery is pending or unknown |
| Output switch | `accepting`, backend exists | `outputChanging` | — | backend reports the new line | applies only if the backend is still the one asked | synchronous refusal: flag never taken; completion always releases the flag and completes the future | flag released |
| Media edits/import/delete (`MediaActions`) | `idle` / `accepting` | `mediaBusy`, media token | — | file change on the browse pool | client thread; captured player/store/library identity decides whether UI state updates | rejected submission releases immediately; deletion reconciles playlists even after replacement | flag released by the operation that took it (admission prevents overlap) |
| Shutdown (`Shutdown`) | once | — | `closed` set after the final save | — | — | each step guarded by `Cleanup`; later steps still run | resume point and settings saved, admission closed, pickers/media/browse cancelled, consumers closed before their dependencies (updates, Discord, media controls, PiP, source links, cleanup, audition, lyrics, downloads, library, playback, caches), history flushed, storage drained (2 s) |

### Completion and shutdown

Reset and restore hand their last step to the client thread through `ClientDispatch`. The returned future always
ends. If that step throws, the future fails with the exception. If the client thread refuses the step, which only
happens while Minecraft closes, the future fails with the refusal, and client-owned state (`resetting`, the restore
flags) is left as it is. Restore releases its flags before completing its future, so a caller that reacts to the
result sees them released. If a restore's storage task fails outside its own handling, the journal state counts as
unknown and the restrictions stay.

`DarkMusicClient.buildReplaceable` is the one place that builds what reset replaces: artwork, waveforms, playback,
library and downloads. It installs each in its field and registers it with the caller's `Cleanup`, for both startup
and reset.

### Exclusive operations and overlap

`mediaBusy` is taken only on the client thread after checking it is clear, so at most one media operation, restore
or reset holds it; the holder releases it. Reset requires `idle` but not `accepting`: an output switch may still be
in flight, and closing the old backend completes that switch as not applied. Edits require only `idle`, so an output
switch can overlap an edit; the edit waits for playback to go idle on its captured player.

### Callbacks across replacement

Reset replaces playback, library, downloads, caches and audition. Callbacks follow one of two rules:

- **Captured generation**: compare the captured object with the current one before touching UI state. Media actions
  (player, playlist store, library), output switching (backend), playlist loading (store), folder checks (generation
  counter), restore and reset (`closed`, plus exclusive admission).
- **Current service on purpose**: the lyric-caption hooks and the download rescan callback read the current
  download service or library, because their work belongs to whichever library is live. A completion from a closed
  download service can at most trigger a rescan or lyric lookup for a file that exists.

File changes that already happened are reconciled regardless: deletion removes playlist entries even after the
player was replaced, because the disk change cannot be undone.

## Audio: Sound Environment filter bypass

The environment high-pass and low-pass are off at their slider ends (20 Hz and 20 kHz). A bypassed filter still
runs on every sample with its output unused, so its history and coefficient ramp follow the live signal. A
per-filter mix fades between dry and filtered output with the same 12 ms smoothing as the other controls. Turning a
filter back on therefore starts from the present signal and never replays audio from before the bypass. Whole-
environment fade-in/out and transition engines are unchanged.

The analyzer's environment curve (`toneResponse`) adds only the filters that are on, so Blank Custom reads flat,
like playback. It shows tone only: the constant headroom trim, intensity mix, drive, delays and reverb are not part
of it (as the analyzer label says).

## Subsystem dispositions

Reviewed against the plan's section 5 and 6 focus areas.

| Area | Disposition | Reason / evidence | Remaining risk |
|---|---|---|---|
| Application lifecycle (startup, reset, restore, shutdown) | **Improved** | `Startup`, `ResetOperation`, `RestoreOperation`, `Shutdown`, `Cleanup`, each driven by tests without Minecraft; every future completes; partial startup unwinds (batches 2, 9, 10) | Which services are built (`ready`, `ResetSteps.publish`) is wiring in `DarkMusicClient`, covered by compilation, review and smoke |
| Library reconciliation | **Improved** | `LibraryReconciliation` owns markers and generation; fixed markers surviving reset (batch 3) | — |
| Settings application and replacement | **Improved** | `applySettings` with documented order; replacement audit and tests; `SettingsSource.update(edit)` for edit-then-commit changes with save timing unchanged (batches 4, 12) | Screens' in-place previews remain by design |
| Screen navigation | **Improved** | `Navigation` + `ScreenNavigation` for every screen; reach of `MusicScreen.Sources` 62 → 31 methods, 31 → 4 interfaces (batches 5 and 8) | — |
| Playback and audio | **Already adequate**; DSP bypass **fixed** | `AudioBackend` seam, sequence/order versions and transition claims, `NextAudio` transfer protocol (`TransitionOrderTest`, `UpNextTest`, `SeekDiscardTest`, `UpNextPreloadTest`); environment filter bypass fixed (batch 1) | Queue-policy extraction from `PlaybackController` not attempted: its tests pin the timing guarantees and nothing showed a defect |
| Lyrics | **Already adequate** | Per-track operation identity, owner cancellation, bounded queues, immutable cache views (`LyricsSafetyTest`, lyrics tests) | — |
| Library and file operations | **Already adequate** | Captured identities, cancellation tokens, full-content checks, deletion reconciles after commit (`MetadataCommitSafetyTest`, `AudioDeletionTest`, `AudioDeletionFilesystemTest`, `WatcherIsolationTest`) | `mediaBusy` release relies on exclusive admission rather than an operation lease; admission makes overlap unreachable (see Exclusive operations) |
| Downloads | **Already adequate**; reset wait **fixed** | Pipeline/commit split, per-item tool paths, committed-before-optional-follow-up (`DownloadCommitTest`, `DownloadRetryTest`); `awaitStopped` now covers the bookkeeping repair (batch 6) | — |
| Persistence and backup | **Already adequate**; reset late writes **fixed** | Serial `io`, coalescing `LatestWriter`, journaled restore (`StorageTest`, backup and consent tests, `ResetTest`); loudness and bookkeeping writers awaited before reset clears (batch 6) | A save queues the write; its acknowledgement (`lastWrite`) is checked at shutdown, and reset reads the config back. `atomicWrite` has a non-atomic fallback, so there is no universal crash-durability claim. |
| HUD and rendering | **Already adequate** | HUD/PiP sources resolve current services each frame, by design across reset (HUD tests) | — |
| Native integrations and updates | **Already adequate** | Consent, size/SHA-256 manifests, runtime leases, helper processes outside the JAR (`PipRuntimeTest`, `PipBundleTest`, `MediaControlsTest`, `DiscordPresenceTest`, update tests) | `ExternalAudio` codec configuration is process-wide, a documented single-client assumption |
| Concurrency contracts | **Improved** where defects were found | Executor ownership above; stale-work rules per callback (Callbacks across replacement); deterministic tests with queued executors for reset, restore and reconciliation | Most services keep their own executors with their own semantics; no shared utility was introduced because none had identical semantics |

## Before and after

| Measure | v3.5.1 (`0792986`) | Now |
|---|---|---|
| Methods reachable from `MusicScreen.Sources` | 62 across 31 interfaces | 31 across 4 |
| Methods reachable from `SettingsScreen.Sources` | 39 across 17 interfaces | 12 across 4 |
| Files depending on `DarkMusicClient` (ratchet) | 5 | 5 |
| `DarkMusicClient` lines | 1223 | 1352 |

The coordination left `DarkMusicClient`: phase order, failure semantics, revision markers and stale-result rules
now live in `ResetOperation`, `RestoreOperation`, `LibraryReconciliation` and `Cleanup`. Its line count still grew,
because the composition root now spells out what was implicit: the reset steps and the reconciliation adapter,
which read its fields, the explicit list of Sources it implements, and the startup registration of acquired
services.

**Common changes now:**
- *Adding a destination screen*: its Sources, one `Navigation` method, one `ScreenNavigation` method and bound;
  `DarkMusicClient` implements the new Sources. No other screen changes.
- *Replacing a library service*: construct it in `ready` and in `ResetSteps.publish`, close it in `shutdown` (each a
  registration line). Consumers read it through the getter or the reconciliation adapter, so screens and HUD do not
  change. They are still typed on the concrete `MusicLibrary`, so a different implementation type would also change
  those signatures.
