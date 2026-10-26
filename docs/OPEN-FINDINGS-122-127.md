# Review findings 122–127

Reviewed against development commit `b1b44c3` on 2026-10-26.

| Finding | Assessment and change |
| --- | --- |
| 122: Surface size after window resize | Plausible race, hardened. Compare the copied bitmap's dimensions with the pool dimensions before reading pixels. A mismatch drops the frame, recreates the pool using the frame's content size and clears the pending old-size packet. Invalid memory bounds still fail safely. |
| 123: Permanent optional audio failure | Confirmed behavior, improved. The helper retries only the process-audio route twice, waiting one and two seconds. Java reconnects at fresh packet boundaries, with bounded connection deadlines and three accepted streams maximum. Unsupported capabilities and cancellation do not retry. Persistent failure reports an audio error while video continues. |
| 124: Audio construction kills video | Confirmed, fixed. Pipe creation and audio activation run under the independent audio supervisor. Constructor failure no longer enters the video setup error handler. A failed pipe constructor also releases its linked cancellation source. |
| 125: Hidden WinForms error dialog | Defensive hardening. Set explicit WinForms exception handling and route UI-thread exceptions through the normal capture-error shutdown path. No reachable production exception was reproduced. |
| 126: Watcher I/O under library lock | Confirmed, fixed. Watcher registration/polling/cleanup use their own lock. Library configuration and publication no longer wait for that lock. Closing schedules watcher disposal off the caller's thread; `awaitStopped` includes that cleanup. An unresponsive filesystem may still delay background cleanup. |
| 127: Parent directory modification noise | Confirmed event-filter weakness, fixed. Directory creation/deletion remains relevant; directory modification is ignored. File changes continue through the existing supported/indexed-file checks. |

## Verification

- Full Windows `scripts/verify.ps1 -DesktopIntegration`: **587 tests, 582 passed, five skipped**, zero failures/errors. Existing private audio and managed tools were used; original audio was preserved.
- Final focused checks: **12 tests passed**, including two additional watcher-isolation regressions added after the full run. These verify that a deliberately blocked watcher cannot block folder configuration or `close()`, and that nested-directory modify events do not bypass bookkeeping filters.
- The bundled helper's native self-test exercises bounded retry exhaustion, cancellation, permanent unsupported capability, a real named-pipe collision, size-mismatch decisions and a real WinForms UI exception. Java tests cover reconnect limits, stream closure, cancellation, unsupported status and bounded unavailable-pipe waiting.
- Live WGC test: a disposable native window underwent **98 resize operations**. The helper reported 97 resizes and delivered 26 valid frames at four dimensions, with no capture/pipe errors. Static content only produces occasional frames; this is not a frame-rate benchmark. The specific old-pool mismatch was not independently reproduced.
- Repeat live WGC test with the audio pipe deliberately occupied: **98 resize operations**, 97 reported resizes, 21 valid frames at four dimensions, one final audio error and **no video errors**. This verifies video-only fallback through the production helper path. It does not simulate a real COM failure or a two-second PCM write stall.
- A local Windows nested-folder watch probe observed create/modify events for the bookkeeping file, but no parent-directory modify event on this filesystem. The parent-modify case is covered deterministically, not claimed as reproduced here.

IntelliJ formatting and whitespace checks passed. No settings format, dependency, access-consent or frame-rate default changed. No Minecraft fullscreen/playback test, real-device audio recovery, Chrome resize stress or native non-Windows PiP validation was performed. Native PiP remains Windows-only.
