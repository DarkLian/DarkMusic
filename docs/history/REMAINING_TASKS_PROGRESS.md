# Remaining tasks progress (2026-11-01)

Baseline: `a518948`, following the local, untracked `REMAINING_TASKS_PLAN.md`. Preserve all seven local plans.
The existing debt plan is complete; this round addresses the recorded exceptional-path test gaps.

## Current status

- R1: human listening/device rows are pending user observations. An asynchronous request is open; automated
  tests must not be described as human listening or physical unplug evidence.
- R2: F1-F10 implemented and focused tests pass; final clean desktop/native verification is running.
  Test destructive paths only in disposable fixtures; audio copies derive from the private collection.
- R3: release decision requested asynchronously. Do not tag/publish until the decision and R1 results exist.
- R4: long-line ratchet and shared wrapping setting implemented and verified, without a mass reformat.
  Keep existing wildcard-import and multiple-declaration style.
- Live LRCLIB: rerun the real integration, verify the provider endpoint/API if it fails, and retain any outage
  as a dated failure rather than a skip.

## Resume notes

Source edits use IDEA apply_patch and its built-in formatter. Scratch and test evidence belong under ignored
`.local-tools/verification/tasks`. Keep separate batch checkpoints and record failures before fixing behavior.
Use `scripts/verify.ps1` for full checks and the existing managed tools and private real collection.

## Completed evidence

- Seam A / F1: test-only delegating FileSystemProvider uses actual host paths, channels, atomic moves, file keys
  and locks. Six injected recovery operations fail before delegation, retain the journal/originals and account for
  claimed targets; the next recovery completes and removes the journal. No production change.
- F2 (AudioReplacement): independent restore-move refusal retains the original publication exception, its suppressed
  rollback error, original recovery copy and staged edit. No production change. Focused A/architecture/method run:
  **24 tests, zero skips/failures/errors** (`tasks/a-results.json`; original log retained under remaining/tasks-a.log).
- Live LRCLIB: **2026-11-01 08:43 UTC**, the opted-in production-provider integration passed: one test, no skip,
  returning available LRCLIB documents for public test metadata. Saved XML and log are under tasks. The previous
  HTTP 502 did not recur; no provider or endpoint change was needed.

- F10: closed playlist-storage/library/lyrics services, client shutdown and a replacement player preserve the completed
  deletion result. A real ten-second never-signalled backend latch times out without deleting the file. Corrupt
  disposable provenance forces the cleanup exception without losing the deletion result. A queued deletion test
  failed before the fix: shutdown discarded its runnable, leaving mediaBusy true and its future incomplete.
  DeletionJob now atomically owns start versus queued cancellation; cancellation releases the lock and completes
  the future without relying on the discarded runnable. Running cancellation retains partial removed/cancelled
  results until client publication. **33 focused tests pass**, with no skips; before/after XML and logs are saved.

- F2-F4: narrow package-private MediaFiles move/cleanup operations and an after-staging hook default to the original
  platform operations. Real private MP3 copies force wrong tags, missing artwork and changed format at each
  validation boundary; all reject before commit and leave source/lyrics identical. Independent companion rollback
  refusal keeps the original error, suppresses rollback failure and preserves recovery work without cleanup.
  Cleanup refusal after successful metadata publication leaves the result intact. Combined B suites pass:
  **68 tests, 2 explicit skips, zero failures/errors**. The skips are the opt-in desktop dialog and unavailable
  Windows symlink privilege. One initial companion fixture used an unreserved .txt path; it was corrected to the
  actual reserved .lrc.darkmusic.json companion, and the injected move/rollback assertions now execute.

- F5-F8: narrow scratch cleanup and usable-space seams preserve published downloads/repair receipts, folder-picker
  selection/admission and active/previous tool bundles when cleanup or free-space checks fail. Managed-tool
  symlink tests explicitly report the host: Windows currently skips for unavailable privilege; Linux CI must run.
  The B suite above includes these tests. DownloadWorkspace's explicit recovery constructor sweeps stale item
  directories under its lease, but normal production pipeline preparation passes recover=false and no production
  caller uses the recovery constructor. Ordinary startup therefore does not currently sweep refused scratch work;
  this is recorded rather than claiming automatic cleanup. The recovery constructor test preserves published
  audio/receipt while removing only its disposable stale work.

- F9: injected worker/render handoff, native preparation, registration and release functions default to the original
  Minecraft operations. Tests use real NativeImage allocations filled from private embedded artwork, without a
  GPU context. A registration refusal closes its image and a subsequent cover works; latches force close before
  and after the transfer check, and native pixel access confirms disposal. Pending saturation is exactly eight;
  every admitted image is registered or closed and cache close releases all registered images. **21 focused tests
  pass, zero skips/failures/errors**, including architecture/method guards. Native Minecraft rendering still needs
  final smoke follow-up. The failed-registration entry bound is being checked separately.

- F9 follow-up bug: repeated registration refusals exceeded the cache entry bound because the failure branch
  returned before trimming. `repeatedRegistrationFailuresStillRespectTheCacheEntryBound` failed before the fix;
  both failure and success now call the same trim phase. **22 focused tests pass**, zero skips/failures/errors.
  Before/after XML is retained; this behavior fix is a separate commit from the injection seam.

- R4: shared `.editorconfig` now requests a 120-character margin for future Java formatting. A Unicode-character
  count guard in ArchitectureTest ratchets client Java lines over 150 at **569**, exactly the `a518948` baseline;
  no existing guard increased and no mass reformat occurred. Wildcard imports and combined declarations stay in
  the established IDEA style. All **17 architecture/method guard tests pass**.

## Publication and remaining prerequisites

Implementation batches are separate commits: 2339132 (A/F1/F2), 26e6fc6 (queued deletion fix/F10),
92b5b92 (metadata F2-F4), 11afafc (cleanup/space/symlink F5-F8), 4ab6352 (native ownership seam/F9),
007b600 (failed-registration bound fix), 8fdfa47 (R4). Source formatting uses IDEA; no guard budget increased.
Final clean desktop and default/cleanup/lyrics/preview smoke are running. No release version has changed.

R1 still needs the user's eleven observations. R3 still needs the user's release decision and R1 completion;
there is no tag/release. A future patch release should also describe the two newly exposed fixes above.
All seven local plan files remain at their original paths so existing references stay valid; no unrelated files
or original music were moved or changed.
