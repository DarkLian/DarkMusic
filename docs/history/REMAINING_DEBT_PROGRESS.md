# Remaining debt progress (2026-11-01)

Implementing the local REMAINING_DEBT_PLAN.md from 18d95ae. The user authorized continuous implementation and
verification, with commits waiting for approval. Six untracked local plan files are preserved.

## Completed batches

| Batch | Change | Evidence |
|---|---|---|
| G | Measure synthetic lambdas with stable enclosing-method keys; read exact Code.code_length from class files. | MethodLengthTest and ArchitectureTest pass. Parser checks overloaded methods and WIDE operands. One existing lambda exceeds 60 lines: PipRuntime.begin$lambda, 83; MAX_DEBT rises once from 12 to 13 to record previously invisible debt. |
| U1 | Named factories for command subtrees; alias loop, attended-install consent and suggestion ordering retained. | LocalCommandsTest, NavigationAndCommandsTest and guards pass using private real-audio excerpts. MAX_DEBT 12. |
| U2 | Settings search and navigation sections are private methods; reflected fields stay on the screen. | GUI suites, architecture/method/smoke-reflection guards pass. MAX_DEBT 11. Final native smoke and visual comparison pending. |
| U3 | Lyric synchronization and version rows are separate visual groups; write-admission actions retain their order. | GUI/lyrics suites and guards pass. MAX_DEBT 10. Native lyrics smoke and comparison pending. |
| U4 | Utility divider and copied-link feedback are private render groups in their original drawing order. | GUI suites and guards pass. MAX_DEBT 9. Native default smoke and comparison pending. |
| U5 | Duplicate comparison columns, review actions and asynchronous detail loading are private sections. | GUI/cleanup suites and guards pass. MAX_DEBT 8. Native cleanup smoke and comparison pending. |
| U6 | Current/manual lists, automatic list and queue actions are private sections; double-click and checked-row rules retained. | GUI/UpNext suites and guards pass; all reflected fields preserved. MAX_DEBT 7. Native queue/cleanup checks pending. |
| U7 | PiP setup/admission rows remain in populate; capture controls form a private section. | GUI/PiP suites and guards pass. MAX_DEBT 6. Native PiP checks and comparison pending. |
| U8 | Color preset grid, paging and color fields are private visual sections. | GUI suites and guards pass; reflected fields preserved. MAX_DEBT 5. Native smoke/comparison pending. |
| U9 | Playlist controls, lists and entry actions are private sections; compact-state readback happens before rebuilding. | GUI/playlist suites and guards pass, reflected fields preserved. MAX_DEBT 4. Native default/queue/cleanup/reset and comparison pending. |
| U10 | Environment header, short controls, advanced controls and saved environments are private sections; Off/blank/device/outdoor predicates named. | GUI/environment-preset suites and guards pass. MAX_DEBT 3. Native queue and comparison pending. |
| U11 | Audio profile, normalization, equalizer, effects, transitions/game controls and saved-profile actions are private sections. | GUI/audio-settings suites and guards pass. Original-hint, neutral disabling, save status and rename/delete scope preserved. MAX_DEBT 2. Native advanced/queue checks pending. |

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

Baseline captures: 15 screen/HUD states, two original 1920x1080 GUI-scale-2 captures each, all pairs pixel-identical
without masks. Stored privately in docs/images_private/remaining-before. Playback paused, visualizer/transitions off;
real collection/artwork and SmoothFont, isolated capture-run configuration. First harness attempt failed because
resetting isolated settings cleared tool paths; the harness now reapplies the existing managed tool paths.
