# DarkMusic v3.3.3

## Fixes

- **Lyrics after Reset to Defaults.** After a reset, newly downloaded songs once again look up their lyrics automatically, and caption lookups no longer use the download service that the reset closed.
- **Settings load with an unusual `options.txt`.** If Minecraft's `options.txt` is not valid UTF-8, DarkMusic warns and still loads your settings instead of failing.
- **Download reviews are no longer lost.** If a second batch with songs already in your library is queued while an earlier "already in library" review is still open, the newer review now replaces it. Before, the newer one was silently dropped.
- **Safer self-update.** If the update cannot move the old mod JAR aside, the installer withdraws the new JAR, so the `mods` folder never ends up with both versions or with neither. When both versions have the same file name, a failed swap now restores the original even if the backup cannot be moved back.
- **No clicks when changing tempo at low sample rates.** Pitch-preserving tempo no longer clicks or skips on audio at 22.05 kHz and below.
- **Download range errors stay on the form.** Range and All are checked before queuing, so an invalid range is reported beside the fields.
- **Spectrum HUD at very high detail.** When the spectrum needs more geometry than expected, the extra detail is skipped instead of interrupting HUD rendering.
- Smaller fixes: a library rescan that empties the library cannot throw while the HUD or shortcuts show the first song, PiP setup always releases its runtime when closed during setup, and Circular HUD waveform bar gaps are measured at the base of the bars.

## Under the hood

- One shared SHA-256 helper replaces ten copies.
- Faster duplicate-cache saves when migrating a large legacy cache, and the song list no longer compares itself against rows it has not built yet.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible. Install **`darkmusic-3.3.3.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.3.3 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
