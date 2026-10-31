# Deferred work progress (2026-11-01)

Implementation of the local `DEFERRED_WORK_PLAN.md`, starting from `de11d3c`.
This is an incremental record: a pending item is not a completed check.

## Working agreement

- The user authorized the desktop/audio testing window and continuous implementation through the plan.
- Leave changes uncommitted for review, with separate commit boundaries recorded below. Do not push or release.
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

Ratchets after these completed batches: `MAX_CLIENT_LINES` 289, `MAX_SERVICES_LINES` 572,
`MAX_DEBT` 13, `MAX_TEST_REFLECTION` 7, `MAX_SMOKE_REFLECTION` 105. The untested-root list is down from 9 to 5.

## Baseline limitations to carry forward

- `-PlyricsIntegration` ran and failed: the production provider raised `PROVIDER`; a direct request to the same
  public LRCLIB endpoint returned HTTP 502. This happened before new production changes. Recheck at the end;
  do not turn the check into a skip or change the provider's behavior to hide it.
- The manual Lyrics Options save/restore and concurrent-metadata check still needs a recorded runtime result.
- D1's human listening/device-unplug checklist has not run. Automated PCM equality will not be described as a
  listening result.
- Artwork registration failure and native image disposal need the Minecraft runtime; the focused unit tests do
  not claim injected GPU failure coverage.

## Current batch

D1.0: full Java verification after D2, then the deterministic PCM fixture. The backend clock and normalization
state have not moved. Add seeded dither and an injected stall clock without changing the production constructors'
behavior, prove repeated captures before extraction, and record a separate pre-extraction checkpoint.

## Remaining implementation order

1. Finish full D2 verification and compile the smoke sources. D2 cleanup smoke will be rerun with the final runtime checks.
2. D1.0: seeded dither/injected clock constructor and `audio/PcmCapture`, using real collection clips.
   Prove 20 repeated hashes per scenario before moving clock or normalization state; save the baseline hashes.
3. D1.2 playback clock, D1.3 normalization, D1.4 output change under 60 lines; decide whether transition state stays.
   Preserve arithmetic order and all cross-thread semantics. Run the named audio suites and golden PCM checks
   after each step. Record listening limitations honestly; run advanced and queue smoke checks afterwards.
4. X2 library lyrics walk, preserving the service monitor where existing calls hold it; then X3 environment recipes
   and optionally parameter type, with byte-identical settings/preset round trips.
5. Full verification and relevant runtime checks; finish architecture/testing/history documentation; present changes
   for review and wait for commits.

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
  `12-deletion-provenance-phase`, `13-restore-recovery-plan`.
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
