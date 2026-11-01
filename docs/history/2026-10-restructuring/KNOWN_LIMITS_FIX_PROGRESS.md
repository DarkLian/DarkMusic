# Known limits quick fixes (2026-11-01)

Baseline: `d1b0a79`. Implementing the untracked KNOWN_LIMITS_FIX_PLAN.md. Preserve all eight local plan files.

## Current status

- K1 implemented and committed as `0ac15fb`: locked first-preparation scratch recovery, with failing/passing evidence.
- K2 implemented: captured lyric storage barrier, bounded failure paths, and stale selected-companion refusal.
- Final focused checks: 59 tests, zero skips/failures. Final clean desktop build: 974 tests, 15 explicit skips,
  zero failures/errors. Architecture/method/hot-helper budgets remain unchanged.
- Native preview and lyrics passed, including LyricsWriteSmoke. All 53 private collection files are unchanged.
- K2 is ready to commit and push alongside K1; GitHub CI status is recorded separately in ignored resume evidence.
- No version bump, tag or new release is part of this request; a later v3.6.3 remains a separate decision.

Scratch/evidence: ignored `.local-tools/verification/known-limits`; real audio derives only from the private
collection and disposable excerpts/copies. Never modify or publish the original collection.

## K1 complete

Two tests fail before the fix (23 tests, two failures, one host-symlink skip): ordinary preparation leaves stale
scratch and the updated F5 assertion fails. After enabling recovery under the existing lease, **41 focused
workspace/download/architecture/method tests pass**, with one explicit local symlink-privilege skip. Recovery
removes only item directories, retains unrelated directories/files and links, never recovers on a second shared
prepare, and refuses a lease held by another workspace. F5 now proves the next preparation removes refused
scratch while published audio and its repair receipt remain intact. The unused boolean overload is removed.
A separate refused-listing test preserves otherwise writable workspaces: recovery logs scan/removal failures
without making a download fail. Before/after XML and logs are saved under known-limits.

## K2 implementation and focused checks

The captured LyricsLookup now supplies a storage no-op barrier after player idle and before edit/deletion file
work. Both workers wait at most ten seconds. Timeout, a full queue or closed storage reports the existing
file-busy message, changes no files and releases media admission. The shared storage accessor already supplied
the test hook; a latch helper uses the actual bounded executor.

Before the fix, both queued-write tests failed. Debugger evidence independently captured metadata staging while
the earlier lyric save remained queued (worker done, save unfinished, media busy). Agent probes were removed
and the user's disabled breakpoints preserved. After the fix, 45 focused action, barrier, admission and structural
tests pass with no skips. Ordering tests pin actual storage writes, rename companion movement and deletion
waiting; ten-second timeouts and full/closed queues pin both operations' unchanged files and released admission.

Deletion confirmation is a separate concern: it captures exact companion identities before execution. A queued
save can add/change a companion after that snapshot, and unchecked companions intentionally remain. The barrier
must not expand consent or silently replace a confirmed identity. Deletion refuses a stale selected
companion snapshot before touching that song. No refreshed identity or extra path is silently authorized.

K1 commit: `0ac15fb`. The final implementation and validation status is recorded above; no release is authorized.


## K2 companion confirmation correction

The plan's deletion claim needed one extra preflight: AudioDeletion captures companions for confirmation before
the worker runs, so a barrier alone cannot authorize a new or changed sidecar. Five of seven companion policy
tests fail before this correction. Preflight now compares selected companions before the first audio mutation:
new, modified (including equal size/time), missing or newly shared files report file-busy and retain the song.
The post-audio identity check remains for independent external changes during Trash. Existing unchecked/shared
companion consent remains intact. A real queued LyricsLookup.save plus barrier test proves stale confirmation
refuses, and a fresh confirmation removes the song and every selected companion without an orphan.

The initial full clean desktop build passed 972 tests with 15 explicit skips before the companion preflight was
added. Focused checks and a new clean desktop run will validate the final source, then native preview/lyrics.


## Final automated build

`scripts/verify.ps1 -Clean -DesktopIntegration` passed on the final source: **974 tests, 15 skips, no failures or
errors**. Private audio is required locally; the PCM golden tests execute without changed expectations. The skips
are the existing opt-in performance/live-network/config/host-permission cases plus the added explicit Windows
symlink-privilege check. Final focused action/deletion/barrier/admission/structural checks: 59 tests, no skips or
failures. Before/after XML, debugger evidence and full logs remain in the ignored verification folder.


## Native checks complete

`scripts/smoke.ps1 -Scenario preview,lyrics` passed on the final source, using SmoothFont and the real private
collection. Preview completed in 2.7 minutes; lyrics completed in 0.7 minutes. LyricsWriteSmoke reported its
actual menu save/replace/restore, busy refusal, companion-byte preservation and completed rename marker.
Both scenarios supplied fresh run-specific pass markers. Hashes before/after confirm all 53 private collection
files are unchanged. Captures/logs stay private; no documentation screenshot was promoted. No version/tag/release
change is included. The eight untracked local plans remain untouched.
