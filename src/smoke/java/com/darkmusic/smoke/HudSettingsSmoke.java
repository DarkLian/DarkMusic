package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.SettingsScreen;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.List;
import java.util.Locale;

final class HudSettingsSmoke {
    static void check(Minecraft mc, DarkMusicClient app) throws Exception {
        var s = app.settings();
        boolean enabled = s.hudEnabled, lyrics = s.lyricsHud, waveform = s.waveformProgress;
        var style = s.hudStyle;
        try {
            s.hudEnabled = false;
            s.lyricsHud = false;
            s.waveformProgress = true;
            for (var hudStyle : Settings.HudStyle.values()) {
                s.hudStyle = hudStyle;
                for (int[] size : new int[][]{{320, 240}, {426, 240}, {960, 540}}) {
                    var screen = new SettingsScreen(app, null);
                    screen.init(size[0], size[1]);
                    var labels = screen.children().stream().filter(c -> c instanceof AbstractWidget).map(c -> ((AbstractWidget) c).getMessage().getString()).toList();
                    for (String key : List.of("circle.darkmusic.style", "hud.image_source", "hud.darkmusic.position", "hud.darkmusic.direction", "hud.darkmusic.spectrum_height", "text.darkmusic.hud_progress", "text.darkmusic.edit_hud_layout", "hud.darkmusic.background_colors", "hud.darkmusic.spectrum"))
                        if (labels.stream().anyMatch(label -> label.startsWith(Text.get(key))))
                            throw new IllegalStateException("Hidden HUD control still present: " + key);
                    for (String key : List.of("style.darkmusic.spectrum", "finish.darkmusic.spectrum", "colors.darkmusic.spectrum", "quality.darkmusic.peaks", "finish.darkmusic.progress", "colors.darkmusic.progress", "progress.darkmusic.waveform", "waveform.style"))
                        if (labels.stream().noneMatch(label -> label.startsWith(Text.get(key))))
                            throw new IllegalStateException("Shared player control disappeared: " + key);
                    String selected = Text.get("style.darkmusic.spectrum") + ": " + Text.get("style.darkmusic.spectrum." + s.spectrumStyle.name().toLowerCase(Locale.ROOT));
                    if (!labels.contains(selected))
                        throw new IllegalStateException("HUD-off style must edit the linear main-player spectrum");
                }
            }
            var screen = new SettingsScreen(app, null);
            mc.gui.setScreen(screen);
            SmokeUi.set(screen, "selectedSection", 4);
            screen.init(960, 540);
            var master = SmokeUi.find(mc, Text.get("text.darkmusic.now_playing_hud"));
            SmokeUi.press(mc, master);
            if (!s.hudEnabled || SmokeUi.find(mc, Text.get("hud.darkmusic.spectrum")) == null)
                throw new IllegalStateException("Re-enabling HUD must restore its controls immediately");
            SmokeUi.press(mc, SmokeUi.find(mc, Text.get("text.darkmusic.now_playing_hud")));
            if (s.hudEnabled || SmokeUi.find(mc, Text.get("hud.darkmusic.spectrum")) != null)
                throw new IllegalStateException("Disabling HUD must remove its controls immediately");
            if (s.hudStyle != Settings.HudStyle.SPECTRUM)
                throw new IllegalStateException("HUD toggle changed the saved layout");
            System.out.println("HUD_SETTINGS_VISIBILITY_PASS:all_layouts;compact_and_wide;shared_controls;immediate_toggle");
        } finally {
            s.hudEnabled = enabled;
            s.lyricsHud = lyrics;
            s.waveformProgress = waveform;
            s.hudStyle = style;
        }
    }
}
