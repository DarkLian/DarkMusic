# DarkMusic v3.2.0

## Clearer menus and controls

- **Player:** elapsed and total time under the seek bar, wide cover art without an empty band, a divider between utility and view icons, and an on-state dot for shuffle, repeat and mute.
- **Setting rows** show what they do: a chevron for pages, a button for actions, a red button for destructive actions, a spinner hint for cycling values, and a check with highlight for picker options. Chevrons are drawn pixel-aligned and stay crisp.
- **Button labels no longer scroll by themselves.** Labels are static; one that cannot fit ends with an ellipsis and shows in full in its tooltip. Long text elsewhere still pans with Shift+scroll. The HUD song title is the only text that loops.
- **Sliders** put the label on the left and the value on the right, with a short handle on a visible track.
- **Graphic EQ** is one vertical 10-band graph with drag and keyboard control (arrows, Page Up/Down for 3 dB, Home to reset).
- **Lyrics status** uses one shape per state (check, note, dash, warning, ?). Missing lyrics are shown as neutral, not as an error.
- **Downloads:** the audio format moved here from Audio Options as **Format: Original / MP3**, with an "of N" page count and a labelled Listen button.
- **Analyzer:** color swatch legends, tick labels, clip counts in the warning color, and "N/A" without a unit.
- Consistent Back buttons, box checkboxes in lists, edge fades on clipped lists, right-hand scrollbars, empty-state text, and destructive styling for clear, reset and delete. Destructive colors follow custom menu themes.
- New installations and **Reset to Defaults** use a black (#000000) menu background. Existing configurations keep their saved color.

## HUD

- **Show HUD in Menus** (Settings → HUD, off by default) keeps the player card, lyrics and the volume, speed and download notices visible while chat or another menu is open. Off, DarkMusic's HUD hides behind menus as before.
- **Circular and lyrics text** is drawn at whole-pixel size on a soft dark plate, so it stays sharp and readable over bright scenery. Lyrics plates fade out as the lyrics background becomes opaque.
- **Artwork HUD** gains **Song Information**: the title and artist below the artwork. It is on by default and can be turned off for the previous look.
- **Classic HUD** shows only the artist while playing; Paused, Stopped or Preview appears in front of it only when it applies.
- The **lyrics background** uses the player card's shape, gradient and bevel.
- **Volume, speed and download notices** follow the menu theme colors instead of a fixed navy.
- See-through or light Classic cards get a soft band behind the title and artist. PiP has a thin frame against bright scenery.

## Compatibility

Existing libraries, playlists, lyrics, Up Next and settings remain compatible. The Artwork HUD becomes 26 pixels taller when Song Information is on. Install **`darkmusic-3.2.0.jar`** with **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.5+** and **Fabric API for 26.2**. PiP stays optional and installs through its menu. Each version pins its own PiP package, so existing PiP users are asked to install the matching v3.2.0 package there; settings and permissions are kept. **Do not put the dependency ZIP in `mods`**. Native PiP requires Windows x64; the music player supports Windows, Linux and macOS.
