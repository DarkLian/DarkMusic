# DarkMusic architecture

How the client is put together: who owns what, which thread touches it, and how services start, get replaced
and stop. Known limits and common changes are at the end. The October 2026 architecture work that produced this
layout is recorded in [its progress log](history/2026-10-restructuring/ARCHITECTURE_PROGRESS.md).

## Dependency direction

```
Minecraft/Fabric callbacks, mixins, screens (gui), HUD (hud, render), PiP (pip), chat commands (command)
        │  narrow Sources interfaces / adapters; never DarkMusicClient (ArchitectureTest: DECOUPLED)
        ▼
com.darkmusic.client (root):
        DarkMusicClient = Fabric entry point and lifecycle only (init, startup, tick, shutdown); wires the rest
        ClientServices  = every service, ClientState, executors, settings store, Warnings; HUD/PiP/reconciliation adapters
        ClientSources   = implements every screen's Sources; each method forwards to one service or collaborator
        collaborators   = PlaybackCommands, ScreenRequests, BackupActions, OutputActions, FolderActions,
                          MediaActions, Shortcuts, ClientActions, ClientReset, ScreenNavigation, LibraryReconciliation
        lifecycle: ClientState, Startup, ResetOperation, RestoreOperation + BackupRestore, Shutdown, Cleanup,
        ClientDispatch
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

The screens sit in folders by feature, and the folders depend only downward:

```
gui            shared base: Navigation, LocalScreen, AudioPage, the *Operations interfaces and PlayerQueries,
               and the types they name (HudElement, ColorPreview, AudioParameter, LyricsConsent, PlayerProgress)
gui.widget     drawing and input pieces any screen may use (RowList, UiButton, ScrollBar, SongCard, the desktop dialogs)
gui.player  gui.settings  gui.audio  gui.lyrics  gui.download  gui.library  gui.pip     one folder per feature
```

`gui.widget` depends on nothing else in `gui`; the base depends only on `gui.widget`; a feature folder depends only
on the base and `gui.widget`, never on another feature. Screens open each other through `Navigation`, so a feature
needs no import of the next. A class two features share belongs in the base or in `gui.widget`, not in one of them.
`ArchitectureTest.guiFoldersDependOnlyDownward` enforces this on the compiled classes with no exceptions. The other
package rules key on the first path segment, so every `gui.*` folder keeps `gui`'s place in the layering.

Screens change playback, the queue and playlists only through `gui.TransportOperations`, `gui.QueueOperations` and
`gui.PlaylistOperations`, which apply the admission once for every screen and return whether they ran; they read
the player through `gui.PlayerQueries`. Each operation keeps the rule its screens checked before: starting a song,
play/pause, skipping and seeking wait while a media operation owns the files (`idle`); stop, pause and resume need
only `alive`, so an import can still be stopped and a preview can pause the music; the queue needs `canEditQueue`;
playlists need `playlistsReady` and no media operation. `PlaybackCommands` implements the first three and
`PlaylistActions` the last. Screens keep `canEditQueue()`/`mediaBusy()` to decide what to show as enabled.
Downloads change through `gui.DownloadOperations` (`DownloadActions`), which forwards to `DownloadService`: the
download screens offer a control only when it applies, and the service admits each change itself. Lyrics change
through `gui.LyricsOperations` (`LyricsActions`): writing a lyric file next to a song (save, restore, keep the
current file) needs `idle`, because a metadata edit renames a song together with its lyric files and a file written
meanwhile would stay behind under the old name; choices, searches, the cache and the library lookup apply at once.
`ScreenOperationsTest` checks the compiled `gui` classes: none calls a transport or queue method of
`PlaybackController`, or anything but a read of `PlaylistStore`, `DownloadService`, `LyricsService` or
`LyricsLookup`. Each reads list is in the test; `LyricsService.request` and `inspect` count as reads, since they only
ask for lyrics to be loaded for display.

Each collaborator that holds admission rules declares a nested `Inputs` interface with what it reads on every call
(named like the `ClientServices` getters, which implements them all), and takes what never changes through its
constructor: the `ClientState`, the `SettingsStore` and the client thread as an `Executor` (`DarkMusicClient`'s
`CLIENT_THREAD` looks Minecraft up on each call). Tests build them over real core objects and a hand-driven executor.

Core packages never reference `DarkMusicClient`. At most `MAX_CLIENT_DEPENDENTS` files may name it (a ratchet,
only lowered; today 1, the sound mixin). Collaborators receive `ClientServices` and each other, never the client.
`ArchitectureTest` also keeps the client from growing back:

- `darkMusicClientOnlyShrinks`: `MAX_CLIENT_LINES` caps `DarkMusicClient.java` (a ratchet, only lowered).
- `darkMusicClientIsOnlyTheEntryPoint`: `DarkMusicClient` implements only `ClientModInitializer`, and only
  `ClientSources` implements a `Sources` interface or `SettingsSource` (besides `SettingsStore` itself).
- `clientSourcesOnlyForwards`: every `ClientSources` method is one statement, `return a.b(x);`, `a.b(x);` or
  `return a;`, with plain names as arguments: no condition, loop, `new` or lambda.
- `noPublicMutableClientFields`: `DarkMusicClient` (apart from `INSTANCE`) and `ClientServices` have no public
  non-final field; services are read through getters and replaced only inside `ClientServices`.
- `MAX_SERVICES_LINES` caps `ClientServices.java`, and `clientServicesHoldsNoPolicy` keeps it a holder: outside its
  build methods and eight named wiring methods, each method is a getter, a lazy getter or one forwarding statement.

Other guards, on compiled classes (ASM) or the smoke sources:

- `MethodLengthTest`: no method may grow past 60 compiled lines; the ones already longer are listed with their
  length and only shrink. Lambdas, constructors and static initializers are not measured.
- `SettingsSettersTest`: a settings field with a range (`config.Range`, applied by `validate()` and by a same-named
  setter) is set outside `config` only through its setter. The fields are found from the setters.
- `SmokeReflectionTest`: smoke code reflects only through `SmokeUi.field/set(owner, Declaring.class, "name")`
  (a binary class name for a type smoke cannot name), and each pair must exist: a member that moves to another
  class fails even when some other class declares the same name. A wrong class that also declares the name passes,
  and `SmokeUi` then rejects the owner when the smoke runs. `reflectionOnlyShrinks` counts smoke reflection and the
  tests' `getDeclared*` calls (ratchets, only lowered). Smoke reads a screen's state through a read-only query
  (`MusicScreen.browser()`, `SettingsScreen.previewBounds()`, `DownloadScreen.view()`) and finds its widgets by
  their labels, as a player would.
- `SmokeReflectionTest.testsDoNotReflectOnTheirOwnPackage`: a test reaches a class of its own package through a
  package-private accessor ("For tests: ..."), never by reflection, and every test reflection names its class
  (`X.class` or `Class.forName("...")`). The remaining test reflection crosses a package (root into audio, download
  into hud, gui into render).
- `DocumentationLinksTest`: every relative Markdown link and `#anchor` in the repository's documents reaches a file
  and heading that exist, so moving a document cannot leave a dead link.
- `ArchitectureTest.rootTestsAreAllowlisted` and `testClassesNameASubject`: tests live in their subject's package
  (the root holds the root package's tests, these guards and `PackagedJarTest`), named after what they test.
- `ArchitectureTest.rootCollaboratorsHaveTests`: every root class is named by a test other than the structure guards,
  or is listed with the reason it is not (`DarkMusicClient`, `ClientServices`, `ClientSources`, `ScreenNavigation`
  and `ClientDispatch`).
- `MethodLengthTest.deliberateLengthSaysWhyAndDebtOnlyShrinks`: each allowlisted method is debt, or a hot loop or a
  data table with a reason; `MAX_DEBT` counts the debt entries and only goes down.

The source guards prove reference direction and shape only, not runtime isolation.

## Threads and executors

| Executor / thread | Owner (owned / borrowed by) | Work | Bound and rejection |
|---|---|---|---|
| Minecraft client thread | Minecraft | all UI, admission of operations, publication of results, `ClientState` | — |
| `darkmusic-storage` (`io`, single) | `ClientServices` owns; borrowed by `ConfigStore`/`LatestWriter`, `PlaylistStore`, histories, `DuplicateReviewStore`, `LibraryReconciliation`, reset/restore operations | config/playlist/history/import-history writes, reset storage, restore journal, backup capture | unbounded queue; serial order is part of correctness. Shut down last at exit; a write enqueued after that throws `RejectedExecutionException` (only reachable after `closed`) |
| `darkmusic-browse` (2 threads, queue 16, abort) | `ClientServices` owns; borrowed by `FolderActions`, `MediaActions`, screens | folder checks, imports, metadata read/edit, deletion | rejected → `error.folder_busy`; media operations release their flag at once |
| `darkmusic-audio` + preload | `JavaSoundBackend` (per playback generation) | decode, DSP, output line | one worker |
| scan, root scan, watch | `MusicLibrary` (per library generation); the watcher's state is `LibraryWatcher`'s, under its own lock | indexing, snapshots, change polling | generation counter rejects stale scans |
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
| Playback (`PlaybackController` + `JavaSoundBackend`) | Up Next (`UpNextQueue`), play order and history (`PlayOrder`), sequence, transition plans; worker stall checks (`StallWatch`), timeline (`PlaybackClock`) and loudness sessions (`Normalization`) | client (controller and its two parts), audio worker | screens, HUD, PiP, Discord, media controls | gen | `close` invalidates plans and joins the worker ≤1.5 s; `awaitStopped` |
| Library (`MusicLibrary`) | roots, immutable snapshots, scan generation | scan threads publish snapshots | everything that lists tracks | gen | `close` non-blocking; `awaitStopped` waits for scanners and watcher |
| Downloads (`DownloadService`) | jobs, workspace locks, receipts | download pool | download screens, HUD | gen | `close` cancels and waits ≤4 s |
| Audition (`AuditionPlayer`) | one preview clip | own worker | download screens | gen (set by screens) | `close`, `awaitStopped` |
| Artwork / waveform caches | decoded images, waveforms | own workers | HUD, screens | gen | `close` |
| Playlists (`PlaylistStore`) | playlists, revision | client; writes on storage | screens, reconciliation, shortcuts | app (`playlistsReady` after async read) | writes via storage executor |
| Listening/import history | heard counts, batches | client; writes on storage | screens, playback source | app (after `ready`) | `flush(true)` at shutdown; while a restore that selected history owns the file, listening history neither counts nor writes and keeps unsaved progress |
| Lyrics (`LyricsService`, `LyricsLookup`) | caches, per-track operations; client-tick traversal (`LibraryLyricsWalk`) | client tick for walk, own executors for jobs | HUD, lyrics screens | app | `close` |
| Cleanup / source evidence | duplicate pairs, review store, source index | own workers | cleanup and download screens | lazy | `close`; reset drops them |
| PiP (`PipController`) | capture session, runtime lease | helper threads | PiP screens, HUD layer | app | `close` stops then releases runtime asynchronously |
| Updates, Discord, Media Controls | jobs, IPC, helper process | own threads | settings screens, tick | app | `close` |
| Library reconciliation (`LibraryReconciliation`) | revision markers, one pending playlist cleanup, generation | client (polled per tick); playlist absence check on storage | autoplay (in `DarkMusicClient.tick`) | app; `reset()` starts a new generation | — |

## Lifecycle operations

All admission happens on the client thread. `ClientState` holds the flags; `alive` = not closed and not resetting,
`idle` = alive and no media operation, `accepting` = idle and not switching output.

| Operation | Admission | Acquires | Invalidates | Commit point | Publication | Failure | Final state |
|---|---|---|---|---|---|---|---|
| Startup (`Startup`, `ready`) | config loaded, not closed | histories, playlists, PiP, then the replaceable set via `buildReplaceable`; each registered in a `Cleanup` | — | services built | settings were published just before; services as built; playlists ready after their own read | built services closed, fields and settings withdrawn, `error.startup` warned | not-yet-loaded state (as before config load); restart needed |
| Reset (`ResetOperation`) | `idle` | `resetting` (blocks all other admission and per-tick work) | folder checks (generation), screen requests, autoplay, cleanup/source index, playback | every old service closed (one failing close does not skip the rest) **and** stopped, then storage cleared (storage executor, `ResetOperation.stop`) | replacements built by `buildReplaceable` and installed while still resetting; then `release` ends resetting and saves | a close fails or a service does not stop within 15 s: storage untouched, `reset.darkmusic.waiting`, the services still running are logged, replacements built with prior settings (see Reset after a timeout). Storage error after all stopped: storage partly cleared, replacements with prior settings. Either way the future fails. Building fails: built replacements closed, stays resetting, `reset.darkmusic.restart`. Closing first or storage refused: nothing published | defaults (or prior) published; saved config read back behind the save |
| Restore (`RestoreOperation`, `BackupRestore`) | `accepting`, playlists and history ready, shortcut keys valid | `mediaBusy` + `restoring`; history file when selected | settings saves (`!restoring`); listening history counting and writing when selected the backup is merged with the state captured when the restore starts (after history is suspended) and prepared on the storage executor; `RestoreJournal.install` returns; recovery state read right after | client thread replaces settings, playlists, history, key binds; applies to playback; flags released, then the future completes | install fails or the storage task dies: nothing applied, `backup.failed` (an unknown journal keeps the flags). Apply fails: on disk, `backup.restart`, flags held until restart so nothing in memory is saved over it. A failing release is reported, never lost. Closing in between: nothing is applied and shutdown writes none of the restored files | flags released; kept until restart after a failed apply or while a journal recovery is pending or unknown |
| Output switch | `accepting`, backend exists | `outputChanging` | — | backend reports the new line | applies only if the backend is still the one asked | synchronous refusal: flag never taken; completion always releases the flag and completes the future | flag released |
| Media edits/import/delete (`MediaActions`) | `idle` / `accepting` | `mediaBusy`, media token | — | file change on the browse pool | client thread; captured player/store/library identity decides whether UI state updates | rejected submission releases immediately; deletion reconciles playlists even after replacement | flag released by the operation that took it (admission prevents overlap) |
| Shutdown (`Shutdown`) | once | — | `closed` set after the final save | — | — | each step guarded by `Cleanup`; later steps still run | resume point and settings saved, admission closed, pickers/media/browse cancelled, consumers closed before their dependencies (updates, Discord, media controls, PiP, source links, cleanup, audition, lyrics, downloads, library, playback, caches), history flushed, storage drained (2 s) |

### Completion and shutdown

Reset and restore hand their last step to the client thread through `ClientDispatch`. If that step throws, the
future fails with the exception; if the executor refuses the step, the future fails with the refusal. Minecraft's
`execute` never refuses: a task submitted off the client thread is queued, and the queue is dropped once the game
loop stops. So a reset or restore whose last step is queued while Minecraft closes never completes its future; no
screen is left to wait for it. Restore settles its flags before completing its future, so a caller that reacts to
the result sees them already released or held. If a restore's storage task fails outside its own handling, the
journal state counts as unknown and the restrictions stay.

A restore owns the files it installs until its outcome is resolved. Settings are not saved while `restoring`; a
restore that selected history suspends listening history, and `ListeningHistory.flush` writes nothing while
suspended, so the shutdown flush cannot replace a restored file or a file a pending journal will roll back.
Playlists need no gate: shutdown does not write them, and playlist edits, shortcuts and reconciliation require
`idle`. `BackupRestoreTest` drives these paths with the real stores and journal and reopens the files.

`BackupScreen` only chooses the file and categories, previews and confirms. `BackupActions` captures and writes exports
(`exportBackup`), reads archives (`readBackup`), prepares previews (`previewRestore`) and runs the restore from the
archive and selection (`restoreBackup`), all on the storage executor. A preview is for display only: the music keeps
playing while the confirmation is open, so the restore prepares again from the state at its start.

### Reset after a timeout

When an old service does not stop in time, reset still publishes replacements with the prior settings, so the
player keeps working. What the old services can still finish does not replace user data:

- A closed library discards scan results unsaved (`closed`/generation check before its index write;
  `ClosedLibraryWritesTest`).
- Download records are read and written under `DownloadArchive.LOCK`, re-read each time; the download workspace
  refuses a second holder and never recovers another holder's work (`WorkspaceTest`).
- A late loudness-cache write merges with the file rather than replacing it, so readings the replacement saved
  since are kept. The download preview and duplicate comparison run their own caches on the same file and save the
  same way (`LoudnessPersistenceTest`).
- A player stuck in its audio line's write does not stop the replacement playing (`ResetStopTest`, on the Windows
  default device only).

`ClientServices.buildReplaceable` is the one place that builds what reset replaces: artwork, waveforms, playback,
library and downloads. It installs each in its field and registers it with the caller's `Cleanup`, for both startup
and reset. `ClientReset` holds the reset steps: what to stop, clear and rebuild.

### Exclusive operations and overlap

`mediaBusy` is taken only on the client thread after checking it is clear, so at most one media operation, restore
or reset holds it; the holder releases it. Reset requires `idle` but not `accepting`: an output switch may still be
in flight, and closing the old backend completes that switch as not applied. Edits require only `idle`, so an output
switch can overlap an edit; the edit waits for playback to go idle on its captured player.
Edits and deletion then wait up to ten seconds for the captured lyric storage worker to settle previously admitted
writes; rejection or timeout reports file-busy before file changes. When companion removal was selected, deletion
also refuses a stale confirmation snapshot before touching that song, preserving the reviewed paths and identities.

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

## Known limits

- Transition state stays on `JavaSoundBackend`: `upcoming` is an `AtomicReference` written by the client thread;
  `mixing`, `announcement` and `overlap` keep their existing volatile semantics. After the clock and normalization
  moves, `mix` and `handoff` read cohesive collaborators. Moving this remaining cross-thread cluster would add
  synchronization risk without clarifying its control flow.
- Exact PCM and native smoke checks do not replace the human listening and physical device-unplug protocol in
  [TESTING.md](TESTING.md#exact-pcm-regression-checks-and-deferred-work-protocol). The outcomes of
  those checks are in the [release record](history/2026-10-restructuring/RELEASE-3.6.2_PROGRESS.md). The named GPU transfer, cleanup, rollback, disk-space and shutdown cases now have
  [injected tests](history/2026-10-restructuring/REMAINING_TASKS_PROGRESS.md); further independent OS/driver failures are not exhaustive.
- Which services are built (`DarkMusicClient.ready`, `ClientServices.buildHistories`/`buildServices`/
  `buildReplaceable`) is wiring, covered by compilation, review and smoke checks. `ClientResetTest` pins the real
  reset phases and their ordering over fake services. The collaborators' admission rules have unit tests; a successful output switch, a
  successful metadata edit and Trash deletion need a device, the tools or the desktop helper and stay with the
  integration tests and the smoke suite.
- A player stuck in its audio line's write keeps that line open until Minecraft exits. This was tested on the Windows
  default device only.
- Retained screens re-read settings when shown again, and the confirmation screen cannot be left while a reset or
  restore runs, so no edit reaches a replaced settings object. This was checked by reading the code, not by a test.
- `mediaBusy` is released by its holder, relying on exclusive admission rather than an operation lease; admission
  makes overlap unreachable (see Exclusive operations). A deletion job atomically arbitrates queued cancellation
  against worker start, so shutdown can release a discarded queued job without later deleting its files.
- A rescan reuses a file's tags while its size and modification time are unchanged, so a replacement that keeps both
  shows the old tags until one changes.
- `atomicWrite` falls back to a plain replace where the file system cannot move atomically, so there is no universal
  crash-durability claim. A save queues its write; the acknowledgement is checked at shutdown, and reset reads the
  config back.
- `ExternalAudio`'s codec configuration is process-wide: one client per Minecraft process.
- The replaceable services are separate fields of `ClientServices` rather than one generation object swapped at
  once. Reset publishes them on the client thread and callbacks compare captured identities, so a single swap has
  not been needed.

## Common changes

- *Adding a destination screen*: put it in its feature folder under `gui` (a shared piece goes in `gui` or
  `gui.widget`); its Sources, one `Navigation` method, one `ScreenNavigation` method and bound;
  `ClientSources` implements the new Sources with forwarding methods. Logic it needs goes into a named collaborator
  (or a new one), which `ClientSources` forwards to. `DarkMusicClient` and the other screens do not change.
- *A new feature area*: a new `gui.<feature>` folder; `guiFoldersDependOnlyDownward` covers it without a change.
- *Adding a player control or menu action*: put it in the collaborator that owns the area (`PlaybackCommands`,
  `FolderActions`, `MediaActions`, `BackupActions`, `OutputActions`); a new collaborator declares its `Inputs`, takes
  the fixed objects through its constructor, gets a root test, and is wired in `DarkMusicClient`'s fields. A
  collaborator that serves only the screens may be built by `ClientSources` instead (`DownloadActions`,
  `LyricsActions`), which keeps `DarkMusicClient` within its cap.
- *Adding a setting with a range*: a `Range` constant used by the class's `validate()` and a same-named setter that
  applies it; screens set it through the setter. Gson still writes the public field, so the file format is the
  field's name.
- *Changing playback, playlists, downloads or lyrics from a screen*: call the screen's `transport()`, `queue()`,
  `playlistOperations()`, `downloadOperations()` or `lyricsOperations()`; a new kind of change goes into the
  operation interface and its implementation, with the same admission the existing ones use.
- *Testing private state*: add a package-private accessor in the subject's package, documented "For tests: ...",
  and keep the field private.
- *Letting smoke check a screen*: drive it by its labels, or add a read-only query; no setters or widget getters
  for smoke. When reflection is unavoidable, name the declaring class.
- *Changing loudness persistence*: `LoudnessStore` owns `loudness-cache.json` and its locks (flushLock, then
  SAVING, then persistence); `LoudnessCache` keeps scheduling and its monitor, which it never holds while calling
  the store.
- *Replacing a library service*: construct it in `buildReplaceable`, used by both startup and reset, and add it to
  `Shutdown.Services`. Consumers read it through the getter or the reconciliation adapter, so screens and the HUD do
  not change. They are still typed on the concrete `MusicLibrary`, so a different implementation type would also
  change those signatures.

## Deferred refactors: file phases, audio state and library lyrics

The completed batches and verification record are in [deferred progress](history/2026-10-restructuring/DEFERRED_PROGRESS.md).
The [I/O branch review](history/2026-10-restructuring/DEFERRED_BRANCH_MAP.md) distinguishes exercised paths from fault-injection gaps.

`PlaybackClock` owns the audio worker's device/source timeline, submitted and heard frames, offset and device rate.
Its segments keep the expression order used for speed changes and handoffs. `Normalization` owns the worker's
pinned current/incoming loudness sessions and smoothed gains. Its methods name new-session reset, same-session
continuation, announcement restoration and incoming adoption. Reopening an output route retains normalization;
`JavaSoundBackend` still publishes volatile playback state and normalization status and invokes hearing callbacks.
Neither helper creates a thread or acquires a lock. Seeded test dither and a supplied stall clock support exact PCM
comparison; production constructors continue to use the system clock and a fresh dither seed.

`LibraryLyricsWalk` owns the incremental client-tick pass, roots, visited tracks, cursor, provider owner and counters.
It requests local inspection through `Inputs`; `LyricsService` retains its bounded local jobs, immutable published
cache, lookup and lifecycle. Local jobs never touch walk state. Disabling lyrics already holds the service monitor;
the walk receives and re-enters that same monitor for cancellation. Ordinary start/cancel/background calls remain
client-tick operations with no new monitor. Retry ticking still happens before the availability check.

The file operations now expose their existing phases through small value records and private methods:

- Backup preparation separates settings, roots, playlists and history; restore recovery inspects every original
  and held/current target into an immutable ordered `RecoveryPlan` before applying any target.
- Metadata edits separate validation, target/companion identities, staging, commit and work cleanup. The outer
  facade still owns rollback suppression and retained recovery work.
- Downloads separate preflight, fetch, collected source, conversion, validation, publication and cleanup. Their
  one per-call mutable `Phase` carries stage/tool attribution; no shared service state was introduced.
- Artwork separates bounded image reading, native-image ownership and render-thread publication. Source indexing
  returns per-track evidence under its existing coalescing loop. Duplicate reading/matching/fingerprinting return
  phase values with final counters and original failures, leaving final cache persistence in the facade.
- External decoder pipes retain their process/watchdog ownership in `DecoderPipe`. Managed tool installation keeps
  ordered release/asset values, the existing install lock and atomic pointer publication. Import destination and
  deleted-file provenance are named phases; browse/client publication and admission retain their order.

`EnvironmentRecipes` holds the preset table and its space helper. `SoundEnvironmentSettings.recipe` retains its
public facade; `Parameters` remains the existing nested public type. Gson field names/order and saved presets are
unchanged. Root action/reset tests use explicit service inputs and reset desktop controls; screen requests and
shortcut migration have plain-data values. The untested-root allowlist is now five classes. The ratchets are client
289 lines, services 572, debt 12, test reflection 7 and smoke reflection 105; the twelve remaining debt methods are
UI layout/registration work outside this plan.

## Remaining UI and measured hot-loop work

The [remaining-debt record](history/2026-10-restructuring/REMAINING_DEBT_PROGRESS.md) and
[measurements](history/2026-10-restructuring/REMAINING_DEBT_MEASUREMENTS.md) carry the batch boundaries, exactness checks and limits.
MethodLengthTest now measures synthetic lambdas separately under a stable enclosing-method key (compiler numeric
suffixes are dropped; sibling lambdas share their longest span), and reports exact class-file Code lengths.
Recording the previously invisible PiP install lambda added one existing debt entry once. All twelve UI entries
and that lambda have since been removed: MAX_DEBT is zero. Source/reflective ownership ratchets are unchanged.

Screens retain their widget fields and public facades. Private visual sections preserve widget insertion, compact
layout, selection and callback order. LocalCommands retains its alias loop and attended/session-code installation
consent. NowPlayingHud builds one immutable FrameLayout per frame, then draws card/artwork/text/status/progress
groups in the original order, with the original matrix/scissor boundaries.

DesktopFolderPicker retains OPEN admission, admitted-call cancellation reset, child-process ownership and final
release order. UpdateService captures the approved release, verifies the downloaded JAR before preparing installer
properties/helper/manifest, and publishes pending only after the manifest closes. PipRuntime passes one immutable
RuntimePackage through archive preparation, unpack/repair and activation; setup lock, cancellation checks, lease
publication and synchronization with close retain their order. Their [branch review](history/2026-10-restructuring/REMAINING_DEBT_BRANCH_MAP.md)
distinguishes checked paths from independent filesystem fault-injection work.

Measured splits separate waveform envelope finalization, the FFT transform, circular progress/contour geometry,
rectangular contour/bar-row geometry, environment input/space/noise phases and the DSP chain's tone, stereo,
reverb/compression, comparison, environment and protection phases. Every pre-split golden bit is preserved;
median cost changes fit the recorded before/after spread. This does not establish a universal zero-cost result.
New per-sample helpers are private and guarded at 325 encoded bytes. AudioProcessor shares a worker-owned stereo
scratch pair across phases without per-sample allocation; configuration snapshots, arithmetic, diagnostics and
limiter transition order are preserved. Both sample-loop hot entries are removed. EnvironmentRecipes.recipe
remains the sole allowlist entry, classified as data. Human listening results are distinct from automated evidence.

## Remaining-task fault follow-up

[The injected-path record](history/2026-10-restructuring/REMAINING_TASKS_PROGRESS.md) covers restore retry, independent rollback failures,
staged tag/artwork/format rejection, refused cleanup, disk exhaustion, managed-tool symlinks, native artwork
ownership and deletion shutdown. Tests exposed and fixed two behaviors: discarded queued deletions could retain
the media lock and unfinished future, and failed texture registrations could bypass the cache entry bound.
The worker-start/cancel gate and shared artwork trim phase address those cases. No audio arithmetic changed.

## Known-limit quick fixes

[The quick-fix record](history/2026-10-restructuring/KNOWN_LIMITS_FIX_PROGRESS.md) covers stale download scratch recovery under the
workspace lease and a lyric storage barrier before metadata edits/deletion. A queued save may invalidate an earlier
deletion confirmation, so selected companions are rechecked before removing the song. Changed confirmation
snapshots require confirmation again; unchecked and shared companions retain their existing consent rules.
