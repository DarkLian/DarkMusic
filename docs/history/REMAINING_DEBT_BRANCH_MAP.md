# Remaining I/O lambda branch review

This records the L baseline before phase extraction. A named body/test seam is introduced before splitting its phases.

## Desktop folder picker

Admission: OPEN compare-and-set refuses a second picker; only the admitted call clears CANCELLED. The background
job creates result/log files, starts a separate JDK process, closes stdin, then polls. Cancellation destroys it,
waits two seconds, then forces termination. Cancellation wins over both exit status and selected text. Nonzero
exit reads at most 8192 diagnostic bytes and fails. Blank output means cancel; otherwise parse the selected path.
Every exception is wrapped in CompletionException. Finally: kill a surviving child, clear active, remove result
then log independently, release OPEN last. Existing DialogAutomation covers actual approve/cancel in a child.
Missing baseline checks: admission/cancellation ordering, process-start failure, bounded failure log and cleanup.

## Self-update download

Consent remains requestUpdate (/da update install or the mod's own button) and LocalCommands.install's attended
or session-code check. Background notices only offer; they never stage automatically. download refuses closed,
missing settings/release/installed development source sets; pending reports ready, downloading reports busy.
It captures the selected release before dispatch. Fetch validates trusted hosts at every redirect, five redirects,
HTTP 200, bounded body; exact size is checked before creating a job. Write CREATE_NEW, then verify size, SHA-256,
ZIP mod identity/version and every loader dependency before preparing the installer manifest. Old JAR hash,
approved asset name/hash and extracted JDK-only helper precede manifest publication. pending is assigned only
after manifest storage closes. Client notification clears downloading and checks closed. Failure discards only
an unpublished job; independent cleanup failure is logged. Tests already cover installer swap/recovery/pruning,
trusted origins, bounded HTTP bodies and command consent. Missing direct staging checks: identity/version/hash,
manifest/helper ordering and preservation of installed JAR when verification fails.

## PiP check/install

Synchronized begin refuses closed/busy, resets readiness/progress and publishes a fresh token before dispatch.
Requirements build and cancellation precede compatibility; unsupported never reads payloads. Parse bounded
manifest, validate directory, acquire setup lock, best-effort old-package cleanup. Only explicit install with
invalid/missing cache downloads to a temporary path; verify size/hash and cancellation before atomic replace;
temporary cleanup always runs. Missing cache reports required. Verify cache again, check cancellation, unpack;
only explicit install repairs a damaged extracted directory, after releasing this instance's lease. Discard
honors another client's lease. Check cancellation before graphics probe and lease publication, then publish
executable/readiness/status in original order. Prune is best-effort. Failure maps cancellation and known errors,
with check/install fallback labels. Finally synchronizes with close: a closed instance releases the lease,
busy clears last. Existing tests cover no implicit download, unsupported/link rejection, cache repair/reuse,
extracted repair/in-use refusal, cancellation and bad payloads. Missing checks: concurrent admission, close
during preparation, graphics refusal/cancellation immediately before lease publication and fallback error labels.

These are focused branch checks, not exhaustive filesystem fault injection. The separate deferred branch map's
GPU, independent rollback/cleanup/storage and shutdown failures remain outside this plan.

## Completed checks

- Picker: added admission refusal/stale-cancellation isolation, cancellation precedence, stdin-close/destroy/forced
  stop order, launch/exit failure cleanup and reopening. They passed before/after phase extraction; the actual JDK
  dialog approve/cancel check also passed before/after. Independent OS refusals of scratch cleanup remain unforced.
- Update: direct staging checks cover verified manifest/helper contents and original JAR identity, rejection of
  bad ZIP/identity/version/dependency/checksum before installer publication, and preservation of the installed JAR.
  Tests passed before/after phase splitting alongside installer/recovery/pruning/packaging and command consent.
  The HTTP redirect/transport and client notification paths retain existing tests/review; no actual player mod was staged.
- PiP: added busy/close admission, graphics refusal, cancellation immediately before activation, distinct check/install
  fallback errors and temporary-download cleanup. Suites passed before/after. All three native PiP checks and the
  production downloader against the SHA-256-verified published 3.6.1 JAR/runtime package passed.

The original exceptional cleanup-failure seams were not expanded, and successful integration does not prove every
OS/filesystem/close race has been injected. No guard budget was raised to accommodate new code.

## Per-sample DSP phases

H5 keeps input smoothing, acoustic-tail reset, reflection/convolution/filter mix smoothing, mono/tone/drive,
noise generation, low-pass/modulation, delay/reflections/room, pan/trim and history/oscillator advance in order.
H6 keeps gain/duck, diagnostics 0, option smoothing/tone/stereo/reverb/compression/blend, diagnostics 1,
environment/comparison match, diagnostics 2, master gain, limiter feeding/silent transition/fade/legacy limit,
finite sanitization, diagnostics 3 and PCM writes in order. The neutral block retains its own direct path.
All new sample phases are private, below 325 encoded bytes, and allocation-free; current stereo scratch belongs
to the same worker as the DSP histories. Pre-split fixed hashes for every preset, 160 backend captures, comparison
and continuity suites pass. These checks do not establish a human listening/device observation.
