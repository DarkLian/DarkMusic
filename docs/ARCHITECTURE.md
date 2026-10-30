# DarkMusic architecture

How the client is put together: who owns what, which thread touches it, and how services start, get replaced
and stop. Per-batch history is in [ARCHITECTURE_PROGRESS.md](ARCHITECTURE_PROGRESS.md).

## Dependency direction

```
Minecraft/Fabric callbacks, mixins, screens (gui), HUD (hud, render), PiP (pip), chat commands (command)
        │  narrow Sources interfaces / adapters; never DarkMusicClient (ArchitectureTest: DECOUPLED)
        ▼
com.darkmusic.client (root): DarkMusicClient = composition root + Fabric integration,
        Folder/Media/Client actions, Shortcuts, lifecycle (ClientState, ResetOperation, RestoreOperation, Cleanup), LibraryReconciliation
        ▼
backup ▸ download ▸ cleanup ▸ audio | lyrics | playlist ▸ library ▸ media ▸ tools ▸ config ▸ text
        (core packages; strictly downward, enforced by ArchitectureTest.CORE)
```

Screens get services through their own nested `Sources` interface, which declares what that screen uses. The player and
Settings open other screens through `gui.Navigation`; the root's `ScreenNavigation` builds each destination with
the destination's own Sources, so a screen's Sources does not inherit those of the screens it opens
(`ArchitectureTest.screensDoNotInheritTheSourcesOfScreensTheyOpen`, with a shrinking list of local chains not yet
migrated).

Core packages never reference `DarkMusicClient`. At most `MAX_CLIENT_DEPENDENTS` files may name it (a ratchet:
only lowered). The source guard proves reference direction only, not runtime isolation.

## Threads and executors

| Executor / thread | Owner | Work | Bound |
|---|---|---|---|
| Minecraft client thread | Minecraft | all UI, admission of operations, publication of results, `ClientState` | — |
| `darkmusic-storage` (`io`, single) | `DarkMusicClient` (app lifetime) | config/playlist/history/import-history writes, reset storage, restore journal, backup capture | unbounded queue; serial order is part of correctness |
| `darkmusic-browse` (2 threads, queue 16, abort) | `DarkMusicClient` | folder checks, imports, metadata read/edit, deletion | rejected → `error.folder_busy` |
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
| Config persistence (`ConfigStore`, `LatestWriter`) | latest JSON, pending write | client enqueues, storage writes | backup export (`snapshotJson`) | app | coalesced, serial; enqueue is not a durable commit; errors reported by callback |
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
| Startup (`start`/`ready`) | config loaded, not closed | histories, playlists, caches, PiP, playback, library, downloads, each registered in a `Cleanup` | — | services built | settings were published just before; services as built; playlists ready after their own read | built services closed, fields and settings withdrawn, `error.startup` warned | not-yet-loaded state (as before config load); restart needed |
| Reset (`ResetOperation`) | `idle` | `resetting` (blocks all other admission and per-tick work) | folder checks (generation), screen requests, autoplay, cleanup/source index, playback | old services closed **and** stopped, then storage cleared (storage executor) | replacements built and installed while still resetting; then `release` ends resetting and saves | old worker not stopped / storage error: storage untouched or partial, replacements built with prior settings, future fails. Building fails: built replacements closed, stays resetting, `reset.darkmusic.restart`. Closing first or storage refused: nothing published | defaults (or prior) published; saved config read back behind the save |
| Restore (`RestoreOperation`) | `accepting`, playlists and history ready, shortcut keys valid | `mediaBusy` + `restoring` | history writes paused | `RestoreJournal.install` returns (storage executor) | client thread replaces settings, playlists, history, key binds; applies to playback | install fails: nothing applied, `backup.failed`. Apply fails: on disk, `backup.restart`. Closing in between: completes, next start loads it | flags released, kept while a journal recovery is pending |
| Output switch | `accepting`, backend exists | `outputChanging` | — | backend reports the new line | applies only if the backend is still the one asked | synchronous refusal: flag never taken; completion always releases the flag and completes the future | flag released |
| Media edits/import/delete (`MediaActions`) | `idle` / `accepting` | `mediaBusy`, media token | — | file change on the browse pool | client thread; captured player/store/library identity decides whether UI state updates | rejected submission releases immediately; deletion reconciles playlists even after replacement | flag released by the operation that took it (admission prevents overlap) |
| Shutdown (`shutdown`) | once | — | `closed` set after the final save | — | — | each step guarded by `Cleanup`; later steps still run | resume point and settings saved, admission closed, pickers/media/browse cancelled, consumers closed before their dependencies (updates, Discord, media controls, PiP, source links, cleanup, audition, lyrics, downloads, library, playback, caches), history flushed, storage drained (2 s) |

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
