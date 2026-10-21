# DarkMusic v2.3.2

## Updates and HUD

- Add optional GitHub update notifications, enabled by default and shown once per launch after joining a world or server. A download always requires your approval.
- Add **Settings → General → Check for Updates** for manual checks, including when automatic notifications are off. Checks report available updates, the latest release, staged downloads, or errors.
- Verify official release checksums and compatibility, then install approved downloads after Minecraft closes. Existing settings, music, playlists, assets, and other mods are preserved. Announce completed downloads in client-side chat.
- Add **Spectrum Only** as a fourth HUD style, reusing existing visualization, colors, effects, scaling, and layout editing.
- Add center guides and gentle alignment snapping to Edit HUD Layout. Hold Shift to place freely. Remove the extra backing behind its reset toolbar.
- Refresh the mod icon while retaining the existing circular HUD icon.

## Menus and reliability

- Group Audio Options and Sound Environment in their own Audio settings section.
- Automatically return the player to the currently playing collection and song when entering the main menu. Browsing and searches stay put while the menu remains open.
- Reveal the selected environment when opening its picker. Show descriptions beside the options, fix label spacing, and distinguish category headings, names, and descriptions by color.
- Show a crossed-out speaker when muted.
- Preserve duplicate-download detection when a downloaded file is renamed.
- Restrict automatic download retries to transient failures; manual retries remain available.
- Bound repeated empty decoder reads during seeking to prevent stalled playback workers.
- Add safe metadata replacement on filesystems without atomic moves, retaining recovery data if restoration fails.
- Refresh the library when extensionless audio is deleted.
- Localize remaining library and playlist status/error messages in English and Traditional Chinese.

## Compatibility

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for 26.2. Client-side only. Existing configuration remains compatible, with defaults for new options. No new third-party dependencies.

- `darkmusic-2.3.2.jar`: install this file.
- `darkmusic-2.3.2-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.2-third-party-sources.zip`: bundled-library sources and notices.

[User Guide](https://github.com/DarkLian/DarkMusic/blob/v2.3.2/docs/USER_GUIDE.md)

## Verification

- `clean build` completed with the private real collection and installed conversion/download tools: 240 tests passed, two optional integrations skipped, no failures.
- Automated coverage includes verified update selection and size limits, safe JAR replacement and rollback, preserved user files/configuration defaults, renamed-download history, retry classification, bounded seeking, metadata recovery, extensionless deletion, localization, and packaged contents.
- Windows Minecraft development-client checks verified the manual check screen with notifications off and center snapping against actual HUD bounds. Earlier checks of these changes exercised update consent, once-per-launch notifications, real GitHub downloads, completion chat, post-exit installation, menu navigation, and Spectrum Only rendering.
- Development-launch checks intentionally do not replace an installed mod. Windows installation was tested separately with isolated mod fixtures.

Linux/macOS runtime, physical device hot-plug, extended multiplayer gameplay, and public media-site end-to-end downloading were not verified for this release. Local HTTP downloads and real conversion tools were tested. Existing public screenshots are retained; they predate these menu additions.
