# v3.6.2 release progress (2026-11-01)

The user confirmed all listening checks and then explicitly confirmed physical unplug and requested
"Release v3.6.2". All eleven R1 rows are passed on the user's reports. Specific tracks/devices were not supplied.
Publication, ordinary commits/push and tagging are authorized. Preserve the seven untracked local plan files.

## Current status

- Version 3.6.2, README/guide and release notes prepared. Notes describe four user-visible fixes since v3.6.1;
  internal restructuring gets one line. Optional Windows extensions remain separate verified assets.
- Full clean desktop validation passed at version 3.6.2: **960 tests, 14 explicit skips, zero failures/errors**.
  Fixed PCM/recipe/vertex goldens and desktop checks ran. The optional original-player-config snapshot check
  skipped because the config changed after its historical snapshot; its fixed recipe golden still passed.
- The nine-scenario native suite is running with the private collection, existing managed tools and SmoothFont.
  A normal play session must pass before tagging.
- All five artifacts exist and pass ZIP integrity checks. fabric.mod.json is version 3.6.2; both bundled
  windows-x64 extension manifests match their external ZIP names, sizes and SHA-256. The mod JAR has no native
  executable/archive payload. The extra artifact checker initially used paths without windows-x64; correcting
  that test harness resolved its KeyError. No build artifact change was needed.
- Commit/push only the reviewed release changes, verify main CI, then tag v3.6.2 and monitor the release workflow.
  The workflow verifies the main ancestry/version tag, tests three hosts and publishes five assets.
- No v3.6.2 tag or release existed at preflight. No production source arithmetic changes are part of this release bump.

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
