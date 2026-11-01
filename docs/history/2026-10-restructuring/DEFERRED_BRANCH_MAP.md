# Deferred I/O branch review

Baseline: `de11d3c`. The pre-extraction inventory is retained privately as
`.local-tools/verification/deferred/branches-before.md`; before/after logs are beside it.
The line numbers below refer to that baseline, not the extracted helpers. This is a review map,
not an instrumented claim of complete branch coverage. Normal completion runs a `finally`, but does not
prove that every cleanup operation can fail safely. Explicit gaps are retained below.

| Original method / branch | Test evidence | Boundary retained |
|---|---|---|
| `DownloadDiagnostics.classify`: cause traversal, depth, cancellation/storage/typed priority | `DownloadDiagnosticsTest.causeShapesKeepCancellationStorageTypedPrecedenceAndTheDepthLimit` | Inspect causes before choosing category. |
| HTTP codes, tool/stage fallbacks and successful diagnostic return (189) | `everyHttpBranchAndStageFallbackKeepTheirFixedSafeDetail`, existing recognition/report tests | Same safe detail and version context. |
| Optional source-URI parsing catch (178) | Malformed/unsupported source cases in diagnostics tests | Arbitrary source output is not copied into reports. |
| `BackupArchive.prepare`: root mapping early return/predicate (173,176) | `BackupArchiveTest.remappingMergingAndReplacingPreserveLocalPathsAndUnmatchedReferences`, `everyRestoreSelectionPreservesInstallationStateAndLeavesInputsUntouched` | Retain installed roots and root identities. |
| Selection validation and final result (219) | All 64 selections; `invalidPreparationNeverWritesAnyTarget`; archive/export/consent/restore suites | Preparation writes nothing. |
| `ArtworkCache.get`: null/closed, cached/pending admission (141–144) | Native lyrics/HUD smoke exercises active cache, repeated cache reads and closed-cache fallback; `HudImageTest` | Same key, pending bound and cache identity. |
| Reader disposal and unavailable/invalid artwork catch (173,178) | `ArtworkCacheTest.decodedRealArtworkKeepsBoundsAndUnavailableArtworkRemainsEmpty`, real embedded-artwork test | Decode bounds and reader ownership. |
| Closed before/after native-image transfer (187,193), registration catch (200–204), native disposal finally (219) | Normal transfer and closed-cache fallback exercised by native smoke. GPU registration failure and exact close-race injection remain unverified. | Worker/render ownership and finally cleanup retained by review. |
| Return while loading (224), eviction | Native artwork/HUD checks and bounded real-artwork tests; no exhaustive pending saturation fault injection | Publication stays on the render thread. |
| `MediaFiles.edit`: invalid filename/tags, destination/companion collisions (127,130,142,150) | `MetadataCommitSafetyTest.validationCollisionsAndCancellationLeaveDisposableOriginalsAlone`, `MediaFilesTest.invalidNamesCollisionsAndChangedFilesLeaveOriginalUntouched` | Reject before staging/commit. |
| Changed identity requiring retained recovery (164) | `externalReplacementAfterValidationSurvivesRenameAndInPlaceCommit` | Do not overwrite an external replacement. |
| Staged tags/cover/format validation throws (183,185,188) | Real MP3/Opus/container edit tests verify preservation; individual forced post-tag corruption at each throw is not injected | All validation still precedes commit. |
| Provenance failure, commit catch, rollback catches/rethrow (212–237) | `rollbackDoesNotOverwriteAnExternallyReplacedPublishedFile`, import/source-history and metadata safety suites | Same original exception and suppressed rollback errors; rare independent companion rollback I/O failures remain review-only. |
| Re-read return, RecoveryRequired catch, cleanup finally/catch (242–255) | Real edit/rename tests, metadata safety/space suites | Retained recovery work is never removed; ordinary work is cleaned. Cleanup failure itself is not exhaustively injected. |
| `DownloadPipeline.download`: permission/space throws, duplicate return (144,146,152) | Download/commit tests for admission, storage and duplicate archive identities; real-tool integration | Archive identity remains under its original lock. |
| Fetch failure and optional metadata catch (170,182) | `DownloadTest.everyPipelineFailureKeepsItsStageToolAndScratchCleanup`, `malformedOptionalMetadataAndNoCoverStillSaveTheRealAudio` | Same phase/tool attribution and optional metadata behavior. |
| Cover retry catch and conversion failure (201,222) | `thumbnailFailureFallsBackButConversionAndValidationFailuresNeverSucceed` | Retry only the cover-free conversion. |
| Probe/decode validation throws (227,231) | Phase failure table and conversion/validation cases | No publication before validation. |
| Duplicate discovered at commit (254), provenance warning catch/result (267,271) | `pipelinePublishesOnlyAfterConversionAndSkipsDuplicates`, `DownloadCommitTest.failedProvenanceRemainsRepairableWithoutDownloadingAgain` | Same published-file ownership and repairable receipt behavior. |
| Cleanup finally/catch, typed/generic outer catches (273–287) | Five phase failures, cancellation/retry tests, download diagnostics suites | Incoming path is cleared only after publication; typed failures retain identity. Independent OS refusal of scratch cleanup is not injected. |
| `LibrarySourceIndex.drain`: no request / closed returns (110,122) | `LibrarySourceIndexTest.failedPersistenceRetriesAndUnavailableTracksDoNotClaimACompleteIndex`, refresh/removal and closed admission checks | Same instance monitor and latest pending request. |
| Track inspection catch and persistence catch (152,173) | Unavailable-track and failed-save/retry cases in that test | Incomplete index never claims completeness; dirty cache retries. |
| `DuplicateScanner.scan`: changed file while reading, cancellation and unavailable catch (156–161) | `DuplicateStorageTest.unavailableFilesDoNotAbortAndCancellationRetainsOriginalDocuments`, `cancellationAfterPublishedProgressKeepsTheCountersAndTheSources`; reconciliation suite | Phase values carry final counters and the original failure. |
| Source matching catch (173) | Existing source/archive/storage tests | Matching errors leave source files untouched; arbitrary matcher failure is not separately injected. |
| Fingerprint changed-file throws/cancellation/failure (205–215) | Real `CleanupAudioTest`, fingerprint-toggle/cache tests and cancellation checks; not every timing race is forced | Same cancellation and audio-failure counters. |
| Root cleanup catch, outer cancellation/failure and final cache-save catch (224–240) | Persistence failure/retry, published-progress cancellation and storage corruption tests | Final persistence remains in the outer finally, on success and failure. |
| `ExternalAudio.openAt`: invalid position, unsupported stream/layout (93,97,101) | `ExternalAudioTest.invalidPositionsAndLayoutsNeverStartAProcess` | No child process started for rejected input. |
| Pipe EOF, wait/exit failure and interrupt catches (121–161) | `cleanEofCloseAndAbortKeepTheirProcessOwnership`, `decoderFailureAndFailureToExitAreNotCleanEof`, `interruptedEofPreservesTheInterruptAndClosesThePipe` | Same watchdog, interrupt restoration and EOF failure. Stream-close IOException is swallowed as before; not each close refusal is separately injected. |
| Close/abort finally and failed-open cleanup (174–205) | `failedOpenDestroysTheUnregisteredProcessAndClosesBothStreams`, clean EOF/abort test; real Opus/seeking/loudness suites | Registration transfers only after successful setup. |
| `ManagedTools.install`: symbolic paths, lock admission and disk check (254–277) | Install-lock/failed repair tests and existing path/archive safety tests. Symlink checks depend on host support; disk-exhaustion exception itself is not forced. | Do not touch an active/previous bundle. |
| Existing bundle validation return/catch (292,293) | `aConcurrentInstallAndAFailedRepairKeepThePreviousBundle`, publication/update tests | Failed validation proceeds through existing repair logic. |
| Pointer publication catch/rethrow, result/finally and overlapping lock catch (329–339) | `aFailedPointerPublicationRemovesTheNewBundleAndKeepsTheBlockingDocument`, `publicationIsAtomicAndFailedOrCancelledUpdatesKeepThePreviousBundle`, real managed installation/conversion before and after | Ordered manifest/assets, atomic pointer and cleanup remain unchanged. |
| `MediaActions.importAudio`: busy/root/limit returns (108,120,137) | `MediaActionsTest.anImportWaitsForIdleAndLoadedPlaylists`, `anUnknownRootOrAFullBatchNeverTakesTheLock` | No busy flag on refused admission. |
| Import publication generation return and outer catch (149,156) | Import success/cancellation/refused browse tests; reset/generation tests | Capture services before browse; publish on client thread; release busy flag. |
| `MediaActions.deleteAudio`: admission, wait-idle throw and outer catch (252,270,280) | Deletion admission/result tests; `AudioDeletion*Test` and `DeletionCompanionTest` | Same per-file operation and client publication order. A permanently blocked playback wait is not newly injected here. |
| Provenance cleanup catch, storage-closed catch and stale publication return (276,290,296) | Deletion and storage suites cover ordinary completion/replacement; each independent service-shutdown race and provenance cleanup exception is not separately fault-injected | Original catch boundaries and busy release retained by review. |
| Final deletion result (313) | `aDeletionCancelsAutoplayHoldsTheLockAndPassesTheResultThrough`, native cleanup smoke | Return the same deletion result. |
| `RestoreJournal.recover`: no journal, unsafe/invalid journal, unsafe directory (90–110) | `RestoreJournalTest.absentAndUnsafeJournalsDoNotChangeAnExistingTarget`, malformed archive/journal tests | Validate before reading/applying targets. |
| Missing/invalid/digest-mismatched originals (116,119) | `everyOriginalAndHeldTargetIsInspectedBeforeTheFirstTargetChanges`, malformed/recovery archive tests | All targets inspected before the first change. |
| Held-target conflict, conflict catch, claim cleanup finally and successful return (134–159) | `BackupArchiveTest.interruptedRecoveryPreservesExternalChangesAndReportsConflict`, `legacyJournalDoesNotAuthorizeOverwritingUnknownContent`, `recoveryResumesAfterClaimingARecognizedReplacement`, interrupted restore/failed rollback cases | Immutable ordered plan; revalidate after claim; preserve external contents. |

Every extraction ran its focused suites before and after. Added cases were written and run before the respective
move; the late pointer-publication case was also run against its original checkpoint. The remaining exceptional
branches above are limitations of this verification, not evidence of a behavioral change or a coverage percentage.

## Remaining-tasks fault-injection follow-up (2026-11-01)

The original baseline review above is retained. [REMAINING_TASKS_PROGRESS.md](REMAINING_TASKS_PROGRESS.md) now
supersedes its named unforced gaps: RestoreJournal retry after move/write/delete/journal failure;
AudioReplacement and companion rollback error retention; each MediaFiles staged validation; metadata/download/
picker cleanup refusal; managed-tool disk exhaustion and host-reported symlink checks; real NativeImage GPU
registration/close-transfer/saturation; deletion storage/library/lyrics/client shutdown, stale player, provenance
failure and bounded idle wait. Tests exposed queued-deletion cancellation and failed-texture cache-bound bugs;
each fix has its own commit after failing injected evidence.

This is evidence for the named interleavings and operations, not exhaustive OS/filesystem/driver coverage. Local
Windows symlink privilege is unavailable. Hosted Windows, Linux and macOS test tasks passed without a managed-tool
symlink skip (run 41291633720); the remaining-tasks record retains the local/hosted distinction. Normal download startup does not
currently invoke the stale-work recovery constructor; cleanup refusal leaves scratch work rather than damaging
published audio or receipts. Human listening/device rows remain independent.
