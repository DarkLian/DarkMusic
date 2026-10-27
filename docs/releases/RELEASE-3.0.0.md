# DarkMusic v3.0.0

## Watch while you play

**Picture-in-Picture for Windows x64** displays a selected application window inside Minecraft, including exclusive fullscreen.

- Open the PiP icon beside Manage Audio Files, or use `/da pip` / `/darkmusic pip`.
- Choose an open window and start capture. Move, resize or crop the view, adjust opacity, and align it using the layout editor's center guides.
- Press **Ctrl+Alt+F8** to operate the selected application and **Ctrl+Alt+F9** to return to Minecraft. Change the function keys when capture is stopped if another application uses them.
- Display preferences are saved; capture never starts automatically after relaunch. Showing PiP in menus defaults to off.
- Keep the source window open and unminimized. Audio continues through its original application; DarkMusic does not replay or record it.

## A spectrum for your other audio

- Optional **PiP Audio Spectrum** responds to the selected application's process-tree audio, independently of your music player.
- Customize its style, colors, opacity, effects, sensitivity, smoothing, band count and height in PiP Spectrum Settings.
- Place the spectrum above, below, left or right of PiP and preview the combined placement in Edit PiP Layout.
- Audio analysis defaults off and requires Windows build 20348 or later. Browser tabs sharing a process tree can contribute audio; per-tab isolation is not guaranteed. No microphone capture is used.

## Player and HUD refinements

- PiP has its own main-player shortcut and command, without adding another Settings category.
- Turning off the Now-Playing HUD hides its layout-only controls while preserving shared main-player spectrum and progress settings.
- The PiP layout editor keeps its controls clear of the selected view where space allows, with alignment guides drawn above capture.
- English and Traditional Chinese localization, keyboard controls and compact layouts are included.

## Performance and supported systems

**A high capture frame limit is not a guaranteed delivered frame rate.** On the tested 144 Hz system, the default path delivered about **47 FPS behind fullscreen Minecraft**. Runtime-compatible experimental 4 ms WGC capture delivered about 144 FPS in windowed mode, but about 66 FPS behind exclusive fullscreen. This release leaves that experimental override out of the normal defaults.

The measured fullscreen shortfall is upstream of Java reception and texture upload. It remains under investigation; perceptually identical browser/video playback and sustained fullscreen 144 FPS are not claimed. Read the [capture investigation](https://github.com/DarkLian/DarkMusic/blob/v3.0.0/docs/PIP-WGC-INVESTIGATION.md) and [resource measurements](https://github.com/DarkLian/DarkMusic/blob/v3.0.0/docs/PIP-PERFORMANCE.md).

PiP capture is Windows x64 only. The existing music player remains available on Windows, Linux and macOS. Protected content, minimized sources, HDR and all multi-monitor/elevated-application combinations are not supported or verified. Windows may refuse focus changes; Alt+Tab remains available. Intermittent capture freezes or white output reported during focus/display transitions remain unresolved.

## Compatibility and verification

- Requires Minecraft 26.2, Java 25, Fabric Loader 0.19.5+ and Fabric API for 26.2.
- Existing settings, music files, playlists, Up Next, lyrics and audio profiles are preserved. PiP preferences are additive; no music DSP or playback route was replaced.
- The Windows capture helper includes its own .NET runtime and redistribution notices; users do not need to install a separate SDK. No system service or driver is installed.
- Clean Windows release validation passed **544 tests**, with two optional live-provider/tool-install checks skipped (546 total, zero failures/errors), using the private real collection and managed tools. Packaged helper integrity/licenses, source archives and 1,584 matching localization keys were verified.
- Actual Windows capture, focus/return, layout editing, process-audio analysis, default/4 ms acquisition probes, OBS comparison and Minecraft pipeline measurements were exercised. These are not proof of every browser, GPU, Windows build or operating-system runtime scenario.

No new public screenshots are included; existing documentation images remain available.
