# DarkMusic architecture

How the client is put together: who owns what, which thread touches it, and how services start, get replaced
and stop. Per-batch history is in [ARCHITECTURE_PROGRESS.md](ARCHITECTURE_PROGRESS.md).

## Dependency direction

```
Minecraft/Fabric callbacks, mixins, screens (gui), HUD (hud, render), PiP (pip), chat commands (command)
        │  narrow Sources interfaces / adapters; never DarkMusicClient (ArchitectureTest: DECOUPLED)
        ▼
com.darkmusic.client (root): DarkMusicClient = composition root + Fabric integration,
        Folder/Media/Client actions, Shortcuts, lifecycle flags (ClientState)
        ▼
backup ▸ download ▸ cleanup ▸ audio | lyrics | playlist ▸ library ▸ media ▸ tools ▸ config ▸ text
        (core packages; strictly downward, enforced by ArchitectureTest.CORE)
```

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
| Library reconciliation (in `DarkMusicClient.tick`) | revision markers, cleanup pending flag, autoplay pending | client; playlist scan on storage | — | gen markers reset on reset | — |

## Audio: Sound Environment filter bypass

The environment high-pass and low-pass are off at their slider ends (20 Hz and 20 kHz). A bypassed filter still
runs on every sample with its output unused, so its history and coefficient ramp follow the live signal. A
per-filter mix fades between dry and filtered output with the same 12 ms smoothing as the other controls. Turning a
filter back on therefore starts from the present signal and never replays audio from before the bypass. Whole-
environment fade-in/out and transition engines are unchanged.

The analyzer's environment curve (`toneResponse`) adds only the filters that are on, so Blank Custom reads flat,
like playback. It shows tone only: the constant headroom trim, intensity mix, drive, delays and reverb are not part
of it (as the analyzer label says).
