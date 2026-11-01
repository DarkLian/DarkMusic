# Remaining tasks progress (2026-11-01)

Baseline: `a518948`, following the local, untracked `REMAINING_TASKS_PLAN.md`. Preserve all seven local plans.
The existing debt plan is complete; this round addresses the recorded exceptional-path test gaps.

## Current status

- R1: human listening/device rows are pending user observations. An asynchronous request is open; automated
  tests must not be described as human listening or physical unplug evidence.
- R2: starting the real-filesystem fault-injection fixture, then F1/F2, F10, narrow file operations and F9.
  Test destructive paths only in disposable fixtures; audio copies derive from the private collection.
- R3: release decision requested asynchronously. Do not tag/publish until the decision and R1 results exist.
- R4: implement only the suggested long-line ratchet and shared wrapping setting, without a mass reformat.
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
