# Known limits quick fixes (2026-11-01)

Baseline: `d1b0a79`. Implementing the untracked KNOWN_LIMITS_FIX_PLAN.md. Preserve all eight local plan files.

## Current status

- K1: add failing pipeline recovery/lease tests and update F5, then enable existing recovery under its lock.
- K2: pin a queued lyric write against metadata editing/deletion; add a bounded storage barrier before file work.
  Preserve captured services, busy release, confirmed deletion identities and source audio.
- One commit per fix, with failing evidence saved before production changes. IDEA formats source edits.
- Full clean verification and native preview/lyrics (including LyricsWriteSmoke) follow implementation.
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
