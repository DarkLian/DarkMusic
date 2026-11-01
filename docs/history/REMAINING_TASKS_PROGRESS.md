# Remaining tasks progress (2026-11-01)

Baseline: `a518948`, following the local, untracked `REMAINING_TASKS_PLAN.md`. Preserve all seven local plans.
The existing debt plan is complete; this round addresses the recorded exceptional-path test gaps.

## Current status

- R1: all eleven listening/device rows passed on the user's reports on 2026-11-01, including the separately
  confirmed physical-unplug check. Specific track/device identities were not supplied.
- R2: F1-F10 implemented; clean desktop and native follow-up pass. Two injected behavior bugs are fixed.
  Test destructive paths only in disposable fixtures; audio copies derive from the private collection.
- R3: [v3.6.2 is published](https://github.com/DarkLian/DarkMusic/releases/tag/v3.6.2) with five verified assets.
  Clean release checks, all nine native scenarios and three-platform release CI passed. PR #9 is merged.
- R4: long-line ratchet and shared wrapping setting implemented and verified, without a mass reformat.
  Keep existing wildcard-import and multiple-declaration style.
- Live LRCLIB: the opted-in production-provider check passed on 2026-11-01, without skipping. No endpoint or
  provider change was needed after the previous HTTP 502.

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
Final clean desktop and default/cleanup/lyrics/preview smoke passed across the suite and window follow-up.
No release version has changed.

R1 still needs the user's eleven observations. R3 still needs the user's release decision and R1 completion;
there is no tag/release. A future patch release should also describe the two newly exposed fixes above.
All seven local plan files remain at their original paths so existing references stay valid; no unrelated files
or original music were moved or changed.

## Final verification

- Clean Windows `scripts/verify.ps1 -Clean -DesktopIntegration`: **960 tests, 13 explicit skips, zero failures/errors**.
  All fixed direct DSP and 160 backend PCM goldens, metadata corruption cases, real native artwork allocations and
  actual desktop dialog tests ran. Six costs and optional live/provider/install/platform checks account for skips;
  the separate opted-in live LRCLIB test passed. XML and complete skip details are saved under tasks/final-desktop-*.
- Native cleanup, lyrics and preview passed. Initial default failed the unchanged windowed-framebuffer assertion
  at tick 8. Two unchanged debugger reruns passed; the second recorded full=1920x1080/saved=1280x720 before F11,
  then fullscreen=false and framebuffer/native/saved=1280x720 at tick 8. The initial failure's exact dimensions
  were not captured, so no production window regression is claimed. The smoke now waits at most 100 ticks for
  the required flag/native-monitor/framebuffer state, then keeps the same strict assertions. Separate final
  default smoke passed, as did all 20 architecture/method/smoke-reflection checks. Production code did not change
  after the clean desktop run. The initial failure remains in the evidence; it is not relabeled as passing.
- All **53 private collection files unchanged** across native suite and follow-up. SmoothFont and the real
  collection were used. No public screenshots, original audio edits, version bump, tag or release were created.
- GitHub build run **41291633720** passed Windows, Ubuntu and macOS at dff8c2c. Every hosted test task executed
  with skipped events enabled and none logged a managed-tool symlink skip, so the link checks ran on all three;
  local Windows explicitly skipped for missing privilege. `tasks/ci-hosts.json` records this distinction.
- Agent debugger logpoints/run configuration are removed, no debug session remains, and the user's two disabled
  exception breakpoints are preserved. Scratch evidence and the saved temporary run config remain under tasks.

Durable evidence: tasks/resume.json, a/d/b/c result summaries and before/after XML, live-lrclib.xml,
final-desktop-results.json and archived XML, final-smoke/summary.txt, window-evidence.md and
window-final/summary.txt. Final notes and the bounded smoke-state follow-up use a separate ordinary commit/push.
R1 observations and the R3 release decision remain pending; automated checks do not supply those inputs.

## User listening confirmation (2026-11-01)

The user reported "All listening checks passed" after the final implementation. The shared table in
[DEFERRED_PROGRESS.md](DEFERRED_PROGRESS.md#remaining-human-verification) records the ten listening rows as
passed, including H5 environment switches and H6 EQ/limiter checks. No particular files/devices or detailed
observations are invented. A separate question requests confirmation of the physical-unplug row and the R3
v3.6.2 release decision. Earlier pending-listening notes describe the state before this report.
No code changed, so the verified 960-test build, native follow-ups and final all-platform CI remain applicable.

## Physical-unplug confirmation and release authorization (2026-11-01)

The user said "physical-unplug confirmed. Release v3.6.2". The shared table now records row 8 as passed; all
R1 rows are complete on the user's reports, with no invented files/device identities. This explicitly authorizes
R3 preparation, commit/push, v3.6.2 tagging and GitHub publication after release validation. No further confirmation
is required. Version, install documentation and notes are prepared; release-specific checks and publication are
tracked in RELEASE-3.6.2_PROGRESS.md. Earlier pending statements are historical.

## Completion: v3.6.2 published

R1-R4 are complete. The user confirmed all listening checks and physical unplug and authorized v3.6.2.
Release commit f7d5dc1 includes the reviewed/merged PR #9 pin update. Clean release verification passed
960 tests (14 explicit skips), all nine native scenarios and both main/release CI on Windows/Linux/macOS.
All 53 private files are unchanged. All five published assets were downloaded and verified; both external
extension manifests and the Media Controls executable hash match, with no native payload in the mod JAR.
The public Java class bytes match the locally tested build. The optional player-config snapshot skip records
changed input rather than replacing its original golden. The existing unwired stale-scratch recovery limitation
remains documented as the plan required. The seven local plans stay at their original untracked paths.

[Release verification and publication record](RELEASE-3.6.2_PROGRESS.md).
