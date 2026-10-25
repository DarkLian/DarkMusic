# Optional PiP runtime (development)

This change is not part of the already published v3.0.0 package.

All DarkMusic PiP feature code remains in the main mod, including the small native helper. The large .NET/Windows Desktop/C#/WinRT dependencies are a separate, optional ZIP. There is no companion mod and nothing additional to put in `mods`.

## Setup

1. Open the existing PiP icon or `/da pip`. Opening the screen does not download dependencies, enumerate windows or start capture.
2. Choose **Check PiP Requirements**. This checks Windows x64, Windows build 19041 or later and Windows Graphics Capture support. Unsupported systems cannot install or enable PiP.
3. Choose **Install PiP Dependencies** and confirm the download. The exact release URL, SHA-256 and size come from the manifest packaged in DarkMusic. Downloading uses the existing trusted-host, bounded and cancellable managed-tool transport. No automatic network request happens during normal startup or the requirements check.
4. Dependencies stay under this instance's `config/darkmusic/pip-native`. There is no system-wide runtime, service, driver or separate Fabric mod installation. No Minecraft restart is needed. A graphics-device probe runs after installation; failed probes cannot enable capture.
5. Choose **Enable PiP Access** and explicitly confirm window enumeration/capture and focus shortcuts. Then choose a window and start capture. Installation and consent do not start it automatically.
6. PiP Audio Spectrum has separate explicit consent and requires build 20348 or later. Turning it off revokes that audio access. Browser processes may include other tabs; no microphone is used.

**Disable PiP Access** immediately stops capture and its shortcuts. The runtime stays installed for reuse. Existing display preferences survive; missing consent fields default to false when upgrading. A new launch never resumes capture or silently reinstalls dependencies. Use Check Requirements again to verify installed files before use.

The permissions here are DarkMusic consent, not an OS permission grant. Windows restrictions still apply. The pre-install check does not establish that every graphics driver or capture source will work; the installed helper also initializes its actual D3D device before reporting readiness.

## Packaging and updates

The build produces the small base JAR and `darkmusic-pip-dependencies-VERSION-windows-x64.zip`. The base includes `helper.zip`, its checksum and a dependency manifest, but no framework/SDK DLLs. The release workflow publishes the optional ZIP alongside the normal assets. A new runtime checksum requires explicit installation; unchanged cached archives are reused across launches. Previous runtime files are preserved, and no audio/configuration assets are removed.

The development build measured approximately **4.0 MB** for the base JAR versus approximately **79 MB** for published v3.0.0; the optional dependency ZIP is approximately **76 MB**, expanding to less than the enforced 300 MB extraction limit. Exact sizes vary with the SDK/runtime build.

The already published v3.0.0 release does not have the new dependency ZIP. Therefore the new live installation URL can only work once a matching future release publishes it. Local tests use this build's archive and injected transport without publishing or fetching unofficial executables. Do not replace the existing v3.0.0 assets to test this change.

## Verification scope

Tests cover default-off upgrade behavior, no work at construction, no downloads during checks, unsupported systems, explicit installation, cache reuse after restart, cancellation, incorrect checksums, ZIP path traversal and collisions between code/dependency archives. A desktop-only check exercises the real Windows preflight and native graphics probe using a local copy of the built dependencies. These fixtures are disposable runtime files, not synthetic music or artwork.

Existing capture buffering, video protocol, frame pacing and music DSP are unchanged. The previously documented fullscreen throughput and intermittent capture limitations remain. Live GitHub installation for the future release, other Windows builds/GPUs and native non-Windows PiP remain unverified.
