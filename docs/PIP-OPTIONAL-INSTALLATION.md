# PiP extension (Windows x64)

Picture-in-Picture shows another application's window inside Minecraft. Its capture helper and the .NET runtime it
needs are not in the DarkMusic JAR. They come as a separate download,
`darkmusic-pip-dependencies-VERSION-windows-x64.zip` (about 76 MB), which DarkMusic installs only when you ask. There
is no extra mod, and the ZIP does not go in `mods`.

PiP needs Windows 10 or 11 on x64, build 19041 or later, with Windows Graphics Capture support. PiP Audio Spectrum
needs build 20348 or later. PiP is not available on macOS or Linux.

## Setting up

1. Open the Picture-in-Picture icon in the player, or `/da pip`. Opening the menu downloads nothing, lists no windows
   and starts no capture.
2. **Check PiP Requirements** checks Windows and any package already installed. It never downloads.
3. **Install PiP Dependencies**, then confirm. DarkMusic downloads the package for its own version from the DarkMusic
   GitHub release and checks its size and SHA-256 against the values built into the JAR before using it. It needs
   about 80 MB to download and 300 MB once extracted. **Cancel PiP Setup** stops it.
   - The files go in `config/darkmusic/pip-native` in this Minecraft instance. Nothing is installed system-wide and
     no restart is needed.
   - A graphics check runs afterwards. If it fails, PiP cannot be turned on.
4. **Enable PiP Access**, then confirm that PiP may list your open windows, capture the one you choose and register
   its focus shortcuts. This is DarkMusic's own consent; it does not change Windows permissions.
5. **Choose Window**, then **Start Capture**.

The controls (layout, crop, frame limit, shortcuts and the spectrum) are described under
[Picture-in-Picture](USER_GUIDE.md#picture-in-picture-windows-x64) in the user guide.

**PiP Audio Spectrum** asks for its own consent. It analyses the selected application's audio to draw the spectrum.
Nothing is recorded, uploaded or replayed, and no microphone is used. Browser tabs can share a process, so other tabs'
sound may show too. Apps that play sound from a separate process, such as some Microsoft Store apps, show no
spectrum. Change it while capture is stopped.

## Turning PiP off or removing it

**Disable PiP Access** stops capture and its shortcuts at once and also withdraws the audio spectrum consent. The
installed files stay for next time. Capture never starts by itself, even after a restart, and DarkMusic never
reinstalls the package without asking.

To remove the files, close Minecraft and delete `config/darkmusic/pip-native`. Your PiP layout and settings are kept
elsewhere and stay.

## After updating DarkMusic

Each DarkMusic version uses its own matching package. After an update, **Check PiP Requirements** reports that the
dependencies are not installed for this version: install them again. Your layout, settings and consent are kept.

DarkMusic keeps only the current version's ZIP. Older runtime folders are removed by a later check once they have
been unused for 24 hours; files that are still locked are left for the next one.

## Troubleshooting

| Message or problem | What to do |
|---|---|
| Requires Windows x64, build 19041 or later | PiP cannot run on this computer |
| Could not initialize graphics capture on this device | The package is installed, but PiP cannot run on this graphics setup |
| Dependencies could not be installed | Check your connection and free space, then install again |
| PiP helper files changed | Close Minecraft, move `config/darkmusic/pip-native` aside, then install again |
| Another Minecraft client is setting up PiP | Wait for it to finish, then try again |
| Application audio capture failed | Video keeps running. Stop and start capture to retry |
| The whole screen tears while capturing | Turn off Exclusive Fullscreen in Video Settings and restart Minecraft; disable Cubes Without Borders |

Resizing the source window can drop a frame while capture adjusts. Audio capture retries twice on its own before it
reports a failure. No recovery step grants access or captures a different window.

## How the package is checked

- The JAR carries only a manifest with the package's version, file name, size and SHA-256. It contains no helper
  executable or runtime files.
- The download comes only from the DarkMusic GitHub release, can be cancelled and stops at the size the manifest
  states. Extraction is limited to 300 MB and rejects paths that leave the folder or repeat.
- Setup takes a lock shared by every Minecraft client on the computer. A runtime in use holds a lease, so cleanup never
  removes it.

## For developers

- The helper builds with every normal build (the `bundlePipHelper` task; `-PjavaOnly` skips it). It needs .NET SDK
  **8.0.421**, pinned in `src/pipHelper/global.json` with roll-forward disabled and a locked restore.
- The .NET runtime inside the helper is pinned separately, by `HelperRuntimePatch` in `PipHelper.csproj` and
  `MediaHelper.csproj` (currently **8.0.33**, newer than the SDK's own 8.0.27). Raise it in both files when a .NET 8
  security patch ships; the build downloads the matching runtime packs from NuGet.
- `runClient` installs the ZIP from its own build, after the same confirmation. A local SDK can produce a package that
  differs from the release one, so a development run never pairs its manifest with the published ZIP. A self-built
  JAR works only with the ZIP built alongside it; do not turn off verification to mix builds.
- Tests, the opt-in `-PpipRuntimeIntegration -PdesktopIntegration` check against the published package and the PiP
  smoke run are described in [Development and verification](TESTING.md) and
  [PiP verification](history/PIP-VERIFICATION.md).
