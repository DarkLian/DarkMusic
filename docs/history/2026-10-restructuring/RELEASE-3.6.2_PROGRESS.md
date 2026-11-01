# v3.6.2 release progress (2026-11-01)

The user confirmed all listening checks and then explicitly confirmed physical unplug and requested
"Release v3.6.2". All eleven R1 rows are passed on the user's reports. Specific tracks/devices were not supplied.
Publication, ordinary commits/push and tagging are authorized. Preserve the seven untracked local plan files.

## Current status

- **Published:** [DarkMusic v3.6.2](https://github.com/DarkLian/DarkMusic/releases/tag/v3.6.2), marked latest,
  with all five assets. Annotated tag v3.6.2 points to tested release commit f7d5dc1; the tag was never moved.
- Clean Windows release validation: **960 tests, 14 explicit skips, zero failures/errors**. Fixed PCM, recipes,
  vertices and desktop checks ran. The additional optional local-config snapshot skip reports a changed player
  config; its fixed serialization golden still passed. Player configuration was not reset or rewritten.
- All **nine native scenarios passed** at version 3.6.2: default/restart, reset/restart, advanced, cleanup, queue,
  lyrics and preview. Actual-device normal playback passed before tagging. All **53 private collection files
  unchanged**, with SmoothFont and isolated smoke settings. No original audio or public screenshots changed.
- All five local artifacts pass ZIP integrity and manifest checks. The artifact checker initially omitted the
  windows-x64 path segment; its corrected check passes without changing build artifacts.
- All five **published** assets were downloaded and independently checked for size, SHA-256 and ZIP integrity.
  The public JAR is version 3.6.2, contains no native executable/archive payload, and both external-extension
  manifests match the published ZIPs and release-note hashes. The Media Controls executable hash also matches.
  All Java class bytes and other resources match the locally tested JAR; only the two package manifests differ,
  binding the hosted extension archives rather than the local archives. Sources/third-party sources are published.
- Candidate [main CI 41294142766](https://github.com/DarkLian/DarkMusic/actions/runs/41294142766) passed all hosts.
  [Release workflow 41294743340](https://github.com/DarkLian/DarkMusic/actions/runs/41294743340) passed all three
  host builds and publication. The new Gradle wrapper-validation pin was exercised in both workflows.
- [PR #9](https://github.com/DarkLian/DarkMusic/pull/9) was reviewed and merged as 86d3a6d before the candidate.
  All eleven human listening/device rows passed on the user's reports; explicit release authorization is recorded.

## Evidence and resume

Current scratch is ignored `.local-tools/verification/release-3.6.2`. Preserve logs, summaries, collection hashes
and publication IDs there. Existing automated baseline is 960 tests/13 explicit skips with four native scenarios,
all-platform CI and live LRCLIB passing. This release reruns clean validation and the full suite at version 3.6.2.

## Pull request #9

The user additionally requested handling the open pull request. Reviewed #9: one pinned action SHA changes,
from Gradle wrapper-validation v6.4.0 to v6.4.1. Its Windows/Linux/macOS checks passed. The official annotated
v6.4.1 tag resolves to 8da46b539cd8fe6ab27ca75128919a18d757604c, matching the PR; the action remains Node 24
with the same inputs. Merged with the expected PR head SHA as squash commit 86d3a6d, then fast-forwarded locally.
This is CI-tooling-only and does not change the application/artifacts being locally validated. Main/release CI
will verify the new pin before publication. The upstream security advisory concerns dependency-submission;
this repository uses wrapper-validation, so no application vulnerability is asserted.

## Release candidate

The version/documentation candidate is committed before tagging. Normal main CI checks the exact commit and new
wrapper-validation pin while the local full native suite runs. After both pass, tag that tested commit and let
the existing release workflow rebuild/test all three platforms and publish. Final publication evidence will be
recorded on main after the workflow completes; no tag is moved or rewritten.

Local validation evidence: release-3.6.2/desktop-results.json and desktop-xml, artifacts.json/artifacts.log,
collection-before.json and smoke per-scenario logs. The local mod JAR is 4,343,158 bytes; native extensions remain
external packages. The helper runtime itself is unchanged from 3.6.1. No public screenshots were added.

## Publication verification

Release ID 430489626; candidate commit f7d5dc1244478463a4b330e62813f33ec6663d9b; annotated tag object
2493d0d4594aa554f6b6a9f696bf366b8ac28b22. The remote peeled tag matches the candidate and GitHub reports v3.6.2
as latest, non-draft and non-prerelease. The workflow publishes exactly these assets:

- darkmusic-3.6.2.jar
- darkmusic-3.6.2-sources.jar
- darkmusic-3.6.2-third-party-sources.zip
- darkmusic-pip-dependencies-3.6.2-windows-x64.zip
- darkmusic-media-controls-3.6.2-windows-x64.zip

Published mod JAR SHA-256: `f49b0ea30aaaf2af347243e96d10d6c6b5c3d23e362955b0968094d5c36a308a`.
The helper archive hashes are appended to the GitHub release notes by the publication workflow and verified
against both downloaded ZIPs and the manifests in the public JAR. Native packages remain optional and outside mods.

Evidence: desktop-results.json/desktop-xml, artifacts.json, executable-check.py, smoke/summary.txt and scenario
logs, collection-before/after.json, published-release.json, published-verification.json and
local-published-comparison.json under ignored release-3.6.2. Final documentation is committed on main after
publication; the tested release tag remains on f7d5dc1. All seven local plans are preserved untracked.
