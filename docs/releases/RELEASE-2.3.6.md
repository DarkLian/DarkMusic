# DarkMusic v2.3.6

## Fixes

- Keep arrow-key navigation and Space activation available to focused player controls. Global playback shortcuts still work when those keys are not owned by a focused widget, and text fields retain normal typing.
- Preserve an active search, including cursor and keyboard focus, when the current track changes. Music continues through the playback collection normally. Jump to Playing remains the explicit way to clear search and reveal the current song.
- Retry temporary HTTP failures such as the complete `HTTP Error 503: Service Unavailable` message. Permanent media, authentication, and filesystem failures remain excluded from automatic retry.
- Recover failed update-installation results locally at startup. Show a localized client-side notice once per launch when notifications are enabled, and provide Open Installation Log in Check for Updates. Failed installations are not staged again automatically.

Settings, music, playlists, audio processing, HUD visuals, and existing preferences are preserved. No new dependencies or configuration fields.

## Files and requirements

Minecraft 26.2, Java 25, Fabric Loader 0.19.5+, and Fabric API for Minecraft 26.2. Client-side only.

- `darkmusic-2.3.6.jar`: install this file.
- `darkmusic-2.3.6-sources.jar`: DarkMusic sources.
- `darkmusic-2.3.6-third-party-sources.zip`: bundled-library sources and notices.

## Verification

- Clean local Gradle release build: 249 tests passed, two optional integrations skipped, no failures. Audio-dependent checks used a temporary conversion from the private real collection; the conversion was removed and original files were preserved.
- Windows Minecraft development-client checks passed for focused slider/list/button input, global shortcuts, natural track transitions with an active search, explicit Jump to Playing, and the failed-installation log action. Existing player, playlist, responsive layout, settings, and download-screen smoke checks also passed.
- Regression tests cover complete HTTP error messages, permanent nested failures, installer failure recording and next-launch recovery, pending/successful/malformed/unrelated update records, and search-follow conditions.
- English and Traditional Chinese localization keys match. Packaged metadata reports v2.3.6; no private music or test captures are included.
- Physical keyboard focus transitions, actual chat-link opening after a failed installed-JAR update, and other operating systems were not manually exercised. Desktop folder choosing and fresh managed-tool installation remain optional integrations and were not rerun.
