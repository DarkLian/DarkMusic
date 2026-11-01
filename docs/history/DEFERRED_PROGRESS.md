# Deferred work progress (2026-11-01)

Implementation of the local `DEFERRED_WORK_PLAN.md`, starting from `de11d3c`.
This is an incremental record: a pending item is not a completed check.

## Working agreement

- The user authorized the desktop/audio testing window and continuous implementation through the plan.
- Initial implementation stayed uncommitted for review. The user subsequently authorized commit and push;
  preserve the separate batch boundaries below. No release or tag is authorized by this publication request.
- Preserve all five untracked plan files. Use IDEA's formatter, existing managed tools, and the private collection.
- Update this record after each completed batch so work can resume after context compaction.

## Completed and checked

| Batch | Change | Verification |
|---|---|---|
| V | Baseline runtime checks before new production changes | All nine `smoke.ps1 -Scenario suite -DesktopVerify` scenarios passed. Clean desktop build: 868 tests, 6 skipped, zero failures/errors. All 53 collection files unchanged. |
| V | Baseline PiP and captures | `check-hud-menus`, `check-setup`, `check-spectrum`, and `-PcaptureMusic` passed; 13 original fullscreen GUI-scale-2 captures remain private. |
| X1 | Test the remaining root collaborators | Added `ClientActions.Inputs`, `ClientReset.Inputs` and explicit reset desktop controls. Tests cover chat feedback/admission, actual reset phases and stopping order, screen request world identity/deadline/death/closing, and shortcut migration over plain data. Focused tests plus `ArchitectureTest` and `MethodLengthTest` passed. |
| D2.1 | `DownloadDiagnostics.classify` | Cause inspection returns a value; category selection is separate. Added wrapped-cause, cancellation/storage precedence, depth-limit, HTTP, stage and version-context tests. Diagnostic suites passed before and after; architecture and method-length guards passed. |
| D2.2 | `BackupArchive.prepare` | Settings, root mapping, playlist merging and history merging are separate phases. Added all 64 selection combinations and invalid-input/no-write checks. Backup archive/export/consent/restore suites passed before and after; guards passed. |
| D2.3 | `ArtworkCache.get` | Separate bounded image reading, native-image preparation/ownership, and render-thread publication. Existing artwork/HUD tests passed before and after; new real-cover bounds and unavailable-cover tests and guards passed. |
| D2.4 | `MediaFiles.edit` | Validation, identities, companions, staging, commit and cleanup are named phases. The facade retains rollback suppression and recovery retention. New validation/collision/cancellation checks and metadata safety/space/media/import-history suites passed before and after; guards passed. |
| D2.5 | `DownloadPipeline.download` | Preflight, fetch, collection, conversion, validation, commit and cleanup are separate phases. One per-call `Phase` retains stage/tool attribution and incoming-file ownership. Added failure tests at five phase boundaries and malformed optional metadata; focused suites and real-tool download integration passed before and after; guards passed. |
| D2.6 | `LibrarySourceIndex.drain` | Per-track inspection returns an `Indexed` value; coalescing, publication, dirty-cache retry and monitor identity stay in place. Added unavailable-track, failed-save/retry, unchanged-cache and closed-admission checks. Source-index/download-awareness tests passed before and after; guards passed. |
| D2.7 | `DuplicateScanner.scan` | Reading, matching and fingerprinting return phase values. Reading/fingerprinting return original failure objects with their final counters, preserving outer cancellation/failure handling and final cache persistence. Added failed-save/retry and cancellation-after-progress checks. Duplicate storage, real-audio cleanup, reconciliation and guards passed before and after. |
| D2.8 | `ExternalAudio.openAt` | An explicit probe/process opener makes the existing lifecycle testable. The anonymous pipe is now `DecoderPipe`, retaining its watchdog, volatile flags and process ownership. Five new lifecycle tests, actual Opus metadata/seeking/output/media/loudness suites and guards passed before and after. |
| D2.9 | `ManagedTools.install` | Ordered release/asset planning, manifest creation and asset preparation are named phases. Added install-lock, failed repair and failed pointer-publication checks; the pointer case also passed against the original checkpoint. Managed-tool/deadline/PiP unit suites, guards, and published-tool install/conversion integration passed before and after. |
| D2.10a | `MediaActions.importAudio` | Destination resolution returns an immutable `ImportTarget`; busy flags, captured services, browse execution and client publication keep their order. Media-actions/deletion/companion suites and guards passed before and after. This also checks the unchanged deletion method before D2.10b. |
| D2.10b | `MediaActions.deleteAudio` | Deleted-file provenance cleanup is a named phase; each cleanup exception and the outer failure handling retain their boundaries. Media-actions/deletion/companion suites and guards passed after the split. |
| D2.11 | `RestoreJournal.recover` | Journal validation and complete inspection return a `RecoveryPlan` whose ordered targets are immutable; apply receives only that plan and retains claim/revalidation/rollback behavior. New inspection-before-write and unsafe-journal tests plus archive/restore/operation suites and guards passed before and after. All twelve D2 methods are now below the length limit. |
| D1.0 | Deterministic PCM baseline | All eight scenarios identical over twenty captures each before the state moves; fixed golden hashes recorded separately. |
| D1.2?4 | PlaybackClock and Normalization | New arithmetic/session tests plus named audio suites passed after each move; every fixed PCM golden reproduced. Output change fell below 60 lines and debt fell to 12. |
| D1.5 | Transition decision | State stays on the backend, retaining existing volatile/AtomicReference semantics; reasons recorded in ARCHITECTURE.md. |
| X2 | LibraryLyricsWalk | Named lyrics suites passed before/after; existing service monitor and local-job ownership preserved. |
| X3 | EnvironmentRecipes | All recipe/saved-preset hashes and the actual config round trip stayed byte-identical; focused suites/guards passed. Optional Parameters move omitted. |
| V follow-up | Lyrics Options write admission | Actual native save/replace/restore and refused writes during a queued metadata rename passed on a disposable real-audio copy; copy removed. |
| Final | Four smoke scenarios and clean desktop build | Lyrics, advanced, queue and cleanup passed. 917 tests, 6 skipped, zero failures/errors; all 53 original collection files unchanged. |

Ratchets after these completed batches: `MAX_CLIENT_LINES` 289, `MAX_SERVICES_LINES` 572,
`MAX_DEBT` 12, `MAX_TEST_REFLECTION` 7, `MAX_SMOKE_REFLECTION` 105. The untested-root list is down from 9 to 5.

## Baseline limitations to carry forward

- `-PlyricsIntegration` ran and failed: the production provider raised `PROVIDER`; a direct request to the same
  public LRCLIB endpoint returned HTTP 502. This happened before new production changes. Recheck at the end;
  do not turn the check into a skip or change the provider's behavior to hide it.
- Lyrics Options save/restore and concurrent-metadata admission now have a passing automated native-menu result;
  this is not labeled a human manual check.
- D1's human listening/device-unplug checklist has not run. Automated PCM equality will not be described as a
  listening result.
- Artwork registration failure and native image disposal need the Minecraft runtime; the focused unit tests do
  not claim injected GPU failure coverage.

## Current status

Implementation and feasible automated verification are complete. The user approved committing and pushing
the completed batches on 2026-11-01. The local plans and private evidence remain outside the commits. Final evidence:

- Clean desktop build: **917 tests, 6 explicit skips, zero failures/errors**. PCM and local serialization tests
  both ran without skipping. Totals are preserved in `final-desktop-results.json` before later focused runs.
- Final lyrics, advanced, queue and cleanup smoke scenarios all passed. The advanced scratch-cleanup follow-up
  also passed separately. All 53 collection files stayed unchanged; no temporary lyrics/advanced audio directories remain.
- Hosted-style missing-collection focused run: **52 tests, 4 expected audio-dependent skips, zero failures/errors**.
- Live LRCLIB recheck still fails with `LrclibProvider.Failure(PROVIDER)`; the exact public endpoint again returns
  **HTTP 502**, matching the pre-change baseline. It was executed and failed, not converted to a skip.
- Before publishing these batches, the latest pushed GitHub build `de11d3c` was green: run [41229000785](https://github.com/DarkLian/DarkMusic/actions/runs/41229000785).
  The prior failed push was `ed594ed`. New CI results must be associated with the new pushed head.
- IDEA formatting, smoke compilation, architecture/method/reflection/packaging guards and `git diff --check` passed.

## Remaining human verification

The user authorized commit and push after reviewing the implementation and verification summary. Separate
batch boundaries are preserved below. Human listening/device rows remain unrecorded. The exact protocol is in TESTING.md. No human file/device result
has been invented, and automated PCM or native controls are not described as listening results.

| Listening row | Status | Files/device used for human check |
|---|---|---|
| 1: loud/quiet normalization, 5 s overlap | Pending human listening | None recorded |
| 2: gapless album | Pending human listening | None recorded |
| 3: album normalization | Pending human listening | None recorded |
| 4: seek during overlap | Pending human listening | None recorded |
| 5: speed 1.5, Preserve Pitch, progress/lyrics | Pending human listening | None recorded |
| 6: pause/resume overlap | Pending human listening | None recorded |
| 7: output changes | Pending human listening | None recorded |
| 8: physically unplug explicit output | Pending physical test | None recorded |
| 9: mono/24-bit noise and channels | Pending human listening | None recorded |
| 10: strong EQ to Flat, loud positive-gain limiter | Pending post-H6 listening | None recorded |
| 11: Concert Hall/Telephone/Underwater/Orbital Echo switching | Pending post-H5 listening | None recorded |

The original branch review retained exceptional fault-injection gaps. The subsequent
[remaining-tasks round](REMAINING_TASKS_PROGRESS.md) forces the named restore/rollback, staged validation, cleanup,
disk-space, GPU transfer and deletion-shutdown cases. That evidence does not fill the human rows or prove every
possible filesystem/driver race. Private file/device details remain in ignored evidence when supplied.

## Resume evidence and commit boundaries

Current scratch work is under ignored `.local-tools/verification/deferred`:

- `preflight.md`, `branches-before.md`, baseline logs and JSON totals, collection hashes and extra-check summaries.
- `check.ps1` runs `scripts/verify.ps1` with actual array test filters and prepares/cleans private audio excerpts.
- `checkpoint.ps1` captures source/doc trees using a separate temporary Git index; it never stages the user's index
  or creates commits. `batches/sequence.txt`, `.tree` and incremental `.patch` files preserve completed boundaries.
- Completed boundaries: `01-client-collaborator-tests`, `02-download-diagnostic-phases`,
  `03-backup-restore-preparation`, `04-artwork-cache-phases`, `05-metadata-edit-phases`,
  `06-download-pipeline-phases`, `07-source-index-inspection`, `08-duplicate-scan-phases`,
  `09-external-decoder-lifecycle`, `10-managed-tool-install-phases`, `11-import-destination-plan`,
  `12-deletion-provenance-phase`, `13-restore-recovery-plan`, `14-pcm-baseline`, `15-playback-clock`,
  `16-normalization`, `17-library-lyrics-walk`, `18-environment-recipes`, `19-lyrics-write-runtime-check`,
  `20-final-review-and-protocol`.
- Refactoring helpers emit patches for IDEA's `apply_patch`; IDEA's `reformat_file` formats the resulting sources.
  Native file writes followed immediately by formatting can race the IDE document cache. Use IDE patches for source
  edits. Java sources in Git use LF; preserve those endings.

Prior refactoring plans and the associated commits were read before implementation. The approach remains the same:
small cohesive collaborators, current-service `Inputs`, unchanged operation/cleanup ordering, unchanged locks and
persisted formats, and guards that only tighten.

### D1.0 PCM baseline completed

All eight scenarios produced one identical SHA-256 and frame count over twenty runs each (160 captures).
The fixture uses frame-position client events, preloader fences, a fixed dither seed and frame-derived stall clock.
It records only in-memory PCM; the tracked golden contains hashes and frame counts, with the input clip hash.
`PcmCaptureTest`, `ArchitectureTest` and `MethodLengthTest` passed before any clock/normalization move.
The 24-bit stereo proxy initially reported an unaligned buffer size and prevented EOF draining; aligning the fake
buffer to whole frames fixed the fixture. No production audio arithmetic changed. Full D2 Java verification and
smoke compilation also passed. Next: extract PlaybackClock and reproduce the fixed goldens (record mode off).

### D1.2 PlaybackClock completed (also satisfies D1.4)

Worker-confined source/device segments, submitted/hearing frames, offset and device rate now live in PlaybackClock.
The backend retains volatile state publication and callbacks. New tests cover queued speed boundaries, handoff,
hearing deltas and reopen/reset. All 71 named audio/guard tests passed, including twenty captures per scenario
against the pre-extraction golden. The guard measured changeOutput at 59 compiled lines, so its debt entry was
removed now; MAX_DEBT is 12. No separate output split is necessary. Next: normalization state.

### D1.3 Normalization completed; D1.5 transition decision

The worker's pinned current/incoming sessions, session id and smoothed gains now live in Normalization. Named
operations preserve same-session continuation, announcement restoration, incoming adoption and reset. Output changes
retain this object; normalizationStatus remains volatile on the backend. New carry/reset/failure tests and all
73 named audio/guard tests passed, with all eight golden hashes unchanged over twenty captures each.
Transition state stays on the backend: mix/handoff now read cohesive clock/normalization collaborators, while
upcoming is client-written AtomicReference and mixing/announcement/overlap retain their existing volatile semantics.
Moving that cross-thread cluster would add synchronization risk without clarifying the remaining control flow.
Human listening and physical unplug remain explicitly unperformed; final runtime controls will still be checked.

### X2 completed

LibraryLyricsWalk now owns the incremental library traversal and counters. LyricsService keeps local jobs/cache,
its original public facade and synchronized lifecycle. The walk receives the service monitor: disabling lyrics
re-enters that same monitor for cancellation; ordinary client-tick start/cancel/background remain unsynchronized
as before. Job still only locks LyricsService.this and never reads walk fields. Retry ticking still precedes
availability checks. The five named lyrics suites passed before and after; architecture and method guards passed.
Next: record settings/preset serialization fingerprints before moving the environment recipe table.

### X3 completed

EnvironmentRecipes owns the preset table and space helper. SoundEnvironmentSettings.recipe remains the compatible
public facade; the data-method guard follows EnvironmentRecipes. The optional Parameters move was left out to
preserve the existing public nested type. Before/after suites passed, including all 24 enum recipes, saved-preset
JSON fingerprints and the actual local config: its raw bytes equal its typed JSON round trip, and both it and a
saved preset derived from it retained their pre-move SHA-256. Original config was only read, never written.
Next: runtime Lyrics Options save/restore and busy admission on a disposable real-audio copy, final smoke/build,
and final architecture/testing evidence.

### Runtime lyric-write admission completed

The extended lyrics smoke passed actual Lyrics Options save, replace and restore on a temporary copy of a real
collection song. Both browse workers were fenced while the actual metadata rename held mediaBusy. Direct operations
returned the original busy error; both menu confirmations refused and remained usable; all companion bytes stayed
unchanged. Releasing the fence completed the rename and released admission. The temporary audio directory was deleted.
This is an automated native-menu check, not a claim that a human performed the manual row. Original music was never
written. DARKMUSIC_LYRICS_WRITE_PASS and the scenario-specific smoke pass marker are in final-smoke/lyrics.log.
Advanced, queue, cleanup and the clean desktop build are now running in the same verification window.

### Self-review follow-up

GitHub's latest pushed build at de11d3c is successful (run 41229000785); the older failed run is ed594ed. No remote
mutation was made. Review preserved the initial volatile-speed read after decoder construction when opening a song.
The advanced smoke's pre-existing native-audio directory was under build; its new temporary directory is beneath
.local-tools/verification/deferred and cleanup waits for preparation/playback ownership before deleting excerpts,
including failure cleanup. This test-infrastructure adjustment needs a final advanced rerun. All four final smoke
scenarios have already passed; clean desktop verification is now running. The clock read-order follow-up will also
be checked by the final golden test. The detailed I/O branch map records remaining fault-injection gaps explicitly.

### Final verification completed

The final advanced rerun passed using .local-tools/verification/deferred scratch excerpts and removed the temporary
directory. The final clean desktop suite includes the clock read-order follow-up and reproduces every PCM golden.
The no-music run confirmed explicit skips on the new private-audio checks. The LRCLIB result remains external HTTP
502 before and after production changes. Source/doc review and whitespace checks are complete. The user subsequently authorized commit and push; no release or tag is part of this publication.
