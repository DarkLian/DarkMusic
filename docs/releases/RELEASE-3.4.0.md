# DarkMusic v3.4.0

## Chat commands

Control playback from chat. Every command works as `/da …` or `/darkmusic …`.

- **`/da play [song]`** resumes playback, or plays a song by name. Press Tab while typing to see matching titles from your library.
- **`/da pause`**, **`/da stop`**, **`/da previous`** and **`/da next`** control the player.
- **`/da now`** shows the current song, position, volume and speed, with clickable Previous, Play, Pause, Next, Stop and Lyrics buttons.
- **`/da queue <song>`** adds a song to Up Next.
- **`/da volume [0–100]`**, **`/da mute`** and **`/da speed [0.5–3]`** show or set the value.
- **`/da seek <time>`** jumps to a time such as `1:30` or `90`, or moves by `+10` or `-10` seconds.
- **`/da shuffle [on|off]`** and **`/da repeat [off|one|all]`** work like the player's buttons and are saved.
- **`/da keys`** lists your current DarkMusic shortcuts, with a **Change Keys** button that opens Key Binds.

## A new `/da help`

`/da help` now fits in open chat and uses your menu accent color. Commands are grouped into Playback, Screens and More. Click a command to put it in the chat box, or hover over it for details. Links to the user guide, releases and issue reporting are buttons at the bottom.

## Fixes

- **`/da update` asks first.** It used to start downloading as soon as it found an update. It now shows the new version with **Download and Install** and **Release Notes** buttons, and nothing downloads until you click.
- **No confirmation dialog for DarkMusic buttons.** Clicking a DarkMusic button in chat no longer opens Minecraft's "run this command?" dialog. Installing an update still needs the command typed or DarkMusic's own button, so a link sent by a server cannot start a download.
- **Screens open from chat.** Buttons in chat that open a screen, such as **Change Keys** and **Lyrics**, now replace the chat box instead of doing nothing.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible. Install **`darkmusic-3.4.0.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.4.0 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
