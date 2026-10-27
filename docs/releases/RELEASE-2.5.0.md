# DarkMusic v2.5.0

## Manage your library in-game

- Open Folder follows the selected library folder or subfolder, with the Default Save Folder as fallback for other collections.
- Manage Audio Files adds searchable bulk selection and a full-path review before deletion. Move files to the operating system's Trash where supported, or confirm permanent deletion separately.
- Delete a single audio file from Edit Song Details. Successful deletions update playlists and Favorite Music; failed files can be reviewed again. Sidecars and unrelated files stay on disk.
- Playback releases file handles during deletion and restores a surviving track's position and pause state. Deleting the current track leaves playback stopped.
- Windows Trash operations preserve fullscreen. F11 remains usable, and the development client returns to a practical windowed size.

## Faster, more reliable browsing

- Reuse a library track index across playlist resolution and build song rows only as needed for the visible list.
- Scan music roots independently, publish healthy-folder results promptly, and preserve entries from unavailable or stalled roots.
- Retain successful file watches if some registrations fail. Back off loudness-cache writes after storage failures.
- Improve fractional wheel/trackpad scrolling, clear failed drag previews, and guard playlist selection against concurrent changes.
- Prevent Analyze Library from starting against a partial scan and report blocked folder removal clearly.

## Sound and presentation

- Add Car Cabin, Stairwell, and Orbital Echo, bringing Sound Environment to 23 choices including Off.
- Keep the environment picker open while comparing treatments. Advanced controls appear below Advanced Parameters.
- Reduce convolution work using FFT symmetry without shortening impulse responses, changing latency, or reducing audio detail.
- Improve two-line row spacing and trim symmetric near-black artwork borders for display without stretching images or changing source files.
- Refresh the README and User Guide with supplied 1920×1080 screenshots.

## Verification

- Full local build: **332 tests; 331 passed, one optional managed-tool installation test skipped, zero failures**. A cache-publication race found during testing was fixed before the passing run.
- Following the fullscreen correction, a focused build passed **17 tests with no skips**, and the smoke sources compiled successfully.
- Windows Minecraft checks covered file-review flows, cancellation, partial deletion/retry, real Trash transfer of a disposable audio copy, original-file preservation, fullscreen/minimization, and F11 transitions. No original music files were deleted or published.
- Numerical convolution and true-peak checks cover sample rates through 192 kHz. A local 192 kHz convolution benchmark improved from 121.74 ms to 81.52 ms per 341.33 ms of stereo audio; this is a local comparison, not a hardware guarantee.
- Linux/macOS native Trash, desktop focus, network mounts, and hardware audio scenarios remain unverified locally. Hosted release builds and automated tests run on Windows, Linux, and macOS.

Existing settings, playlists, audio profiles, and music are preserved on upgrade. There are no new third-party dependencies or changes to multiplayer networking.

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+**, and **Fabric API**. Client-side only.

Install `darkmusic-2.5.0.jar`. Project sources and corresponding third-party sources are supplied separately.
