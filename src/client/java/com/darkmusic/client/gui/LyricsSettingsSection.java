package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.text.Text;

/**
 * The Lyrics section of Settings: lyrics on or off, their settings page, and the lyrics HUD.
 */
final class LyricsSettingsSection {
    private LyricsSettingsSection() {
    }

    static void build(SettingsLayout layout, SettingsScreen.Sources app, Settings s) {
        var screen = layout.screen();
        layout.section("lyrics.section");
        layout.toggle("lyrics.darkmusic.enabled", () -> s.lyricsEnabled, v -> {
            s.lyricsEnabled = v;
            layout.rebuild();
        }, "lyrics.darkmusic.enabled_help");
        if (s.lyricsEnabled) {
            layout.action("lyrics.settings", () -> screen.show(app.navigation().lyricsSettings(screen)), "lyrics.settings.help");
        }
        if (s.lyricsEnabled) {
            layout.toggle("lyrics.darkmusic.hud", () -> s.lyricsHud, v -> {
                s.lyricsHud = v;
                layout.rebuild();
            }, "lyrics.darkmusic.hud_help");
        }
        if (LyricsHud.enabled(s)) {
            layout.subsection("lyrics.hud.settings");
            layout.toggle("lyrics.hud.next", () -> s.lyricsNextLine, v -> s.lyricsNextLine = v, "lyrics.hud.next.help");
            layout.toggle("lyrics.hud.text_plate", () -> s.lyricsTextPlate, v -> s.lyricsTextPlate = v, "lyrics.hud.text_plate.help");
            layout.waveformSlider((s.lyricsHudWidth - 120) / 480.0, v -> Text.get("lyrics.hud.width") + ": " + s.lyricsHudWidth,
                v -> s.lyricsHudWidth(120 + (int) Math.round(v * 480)), 480, "lyrics.hud.width.help");
            layout.waveformSlider(s.lyricsHudOpacity, v -> Text.get("lyrics.hud.opacity") + ": " + Math.round(s.lyricsHudOpacity * 100) + "%",
                v -> s.lyricsHudOpacity(v), 100, "lyrics.hud.opacity.help");
            layout.action("lyrics.hud.color", () -> screen.show(new ColorPickerScreen(screen, s.lyricsHudColor, 0xDDD9FF, color -> {
                s.lyricsHudColor = color;
                app.save();
            })), "lyrics.hud.color.help");
            layout.action("lyrics.hud.next_color", () -> screen.show(new ColorPickerScreen(screen, s.lyricsHudNextColor, 0xAAA7BD, color -> {
                s.lyricsHudNextColor = color;
                app.save();
            })), "lyrics.hud.next_color.help");
        }
    }
}
