# Agent handoff

## Working agreement

Codex and Claude take turns. Read this file and inspect `git status`/recent commits before editing. Preserve the other agent's changes; do not start overlapping work or force-push. Record completed work, verification, pending actions and limitations here before handing over. No new release is authorized by the current build-fix request.

## Latest completed turn — Codex, 2026-10-26

### macOS build fix

- Failing workflow: https://github.com/DarkLian/DarkMusic/actions/runs/40237679973 (`c34cda2`). Windows and Linux passed; macOS compiled but three `PipRuntimeTest` simulated-install tests failed.
- The installer rejects symlinked ancestors. macOS temporary fixtures can use `/var` -> `/private/var`, accidentally exercising rejection in successful-install tests.
- This fix canonicalizes only the disposable JUnit fixture using `toRealPath()` in `@BeforeEach`. Production installer checks are unchanged. An added regression explicitly verifies that a linked installation directory is still rejected without downloading or creating its target runtime directory.
- Local focused Windows desktop run: seven cases, six passed, one skipped because this account cannot create the test symlink. The real Windows/WGC and native graphics-device checks passed. Evidence: `.local-tools/verification/pip-macos/test.log`.
- Fix commit: `4f4f7bd`. Confirmed CI success on **macOS, Linux and Windows**: https://github.com/DarkLian/DarkMusic/actions/runs/40250362814 . This includes the linked-installation-directory regression on macOS. The remaining `ExtendedOpenOption` annotation is a pre-existing test compiler warning, not a failure.
- No production capture/installer code changed for this correction. This handoff update is documentation-only; the code under test remains `4f4f7bd`.

### PiP implementation state

- Published v3.0.0 is commit `92e4db2`. Optional-runtime development was pushed as `c34cda2`; it has not been released.
- All PiP feature code stays in the base DarkMusic JAR. **No companion mod.** The base is approximately 4 MB; the optional runtime dependency ZIP is approximately 76 MB. Installation is instance-local under `config/darkmusic/pip-native`, not system-wide or in a second mod.
- Setup separates requirements checks, explicit download confirmation, capture-access consent, optional process-audio consent, window selection and Start Capture. New access flags default false, including upgraded preferences. No capture resumes automatically.
- `docs/PIP-OPTIONAL-INSTALLATION.md` describes the flow and limits. The matching optional ZIP must be published with a future release; the already published v3.0.0 does not provide it. Do not rewrite existing v3.0.0 assets.
- Full Windows regression and fullscreen setup UI checks passed before the push, at 1920x1080, GUI scale 2 with SmoothFont. Private evidence: `.local-tools/verification/pip-optional/`. Tests use injected downloads/local built runtime archives, not a live future release asset.

### New user steering — fullscreen capture retest

- The user reports fixing AMD FreeSync global settings that caused random fullscreen FPS caps. Do not change or revert their GPU/system settings.
- They permit testing fullscreen Minecraft with 144 FPS PiP to check Chrome delivery. **Next task for the incoming agent:** perform that comparison now that CI is fixed. No post-FreeSync Chrome performance measurement was made during this build-fix turn.
- Previous measurements (before that settings change): WGC default getter 16 ms; isolated default approximately 48 FPS; explicit 4 ms approximately 144 FPS isolated/windowed, approximately 66 FPS behind exclusive-fullscreen Minecraft. Default fullscreen path approximately 47 FPS. Report: `docs/PIP-WGC-INVESTIGATION.md`.
- Compare default and explicit 4 ms with the new environment; record actual source cadence, native arrival/transfer, Java reception and upload separately. A 60 FPS video cannot establish 144 FPS content delivery; distinguish browser animation/rendering from video frame rate. Preserve bounded buffers and music isolation. Do not enable the experimental interval as a shipping default merely to run the comparison.
- Prior fixtures/probe scripts: `.local-tools/verification/wgc-144/`; current runtime/setup smoke supports optional installation and does not bypass access consent. Historical capture harnesses need normal setup/access first.

### Turn boundary

- No active Codex source edits or test/capture sessions remain. The macOS correction and this handoff are pushed to `main`; no tag/release was created. Inspect Git before beginning, in case the user or Claude has made later changes.
- The incoming Claude agent can take the next turn. Preserve the documented pending cleanup permissions and use a new ignored verification folder for the post-FreeSync test.

## Safety / cleanup notes

- Never modify or publish original private music. Use the private collection for audio and derived artwork tests, with only disposable derivatives. No dummy audio/artwork.
- Keep unpublished captures under ignored verification directories or `docs/images_private`. Public screenshots require fullscreen 1920x1080, effective GUI scale 2 and SmoothFont.
- Existing authentication, browser profiles and archived Git history must remain untouched. No system-wide installs or OS/GPU/security changes.
- Earlier benchmark media cleanup was automatically rejected; those derived fixtures remain in the ignored older benchmark folder. An accidental earlier OBS recording outside this repository also awaits user cleanup permission. Do not retry those deletions through alternate mechanisms.
