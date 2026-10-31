# Remaining debt progress (2026-11-01)

Implementing the local REMAINING_DEBT_PLAN.md from 18d95ae. The user authorized continuous implementation and
verification, with commits waiting for approval. Six untracked local plan files are preserved.

## Completed batches

| Batch | Change | Evidence |
|---|---|---|
| G | Measure synthetic lambdas with stable enclosing-method keys; read exact Code.code_length from class files. | MethodLengthTest and ArchitectureTest pass. Parser checks overloaded methods and WIDE operands. One existing lambda exceeds 60 lines: PipRuntime.begin$lambda, 83; MAX_DEBT rises once from 12 to 13 to record previously invisible debt. |
| U1 | Named factories for command subtrees; alias loop, attended-install consent and suggestion ordering retained. | LocalCommandsTest, NavigationAndCommandsTest and guards pass using private real-audio excerpts. MAX_DEBT 12. |

G2 bytecode sizes: AudioProcessor 1731, environment Engine 1474, SpectrumAnalyzer 1099, TrackWaveform 804,
CircularHudMesh.Rings 1570, SpectrumRenderer.bars 1428 bytes. No hot method exceeds 8000 bytes.
Reports include descriptors so overloads remain distinguishable. Lambda ordinal suffixes are dropped, sibling
lambdas share the longest span, and other synthetic/bridge methods remain excluded.

## Pending prerequisites and limits

- The previous refactor's human listening rows 1–9 and physical output unplug check have no recorded results.
  The user was asked asynchronously; H5/H6 remain behind that prerequisite unless explicitly waived.
- Live LRCLIB previously returned HTTP 502. Recheck without hiding failure or converting it to a skip.
- No release, tag, commit or push is part of this implementation turn.

## Resume record

Current evidence and separate commit boundaries are under ignored .local-tools/verification/remaining.
checkpoint.ps1 captures trees in an alternate index, preserving the user's index and local plans; it creates no commits.
Source edits use IDEA apply_patch and its built-in formatter. Start with deterministic visual captures before U.
