package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.gui.screens.Screen;

public final class LyricsHudSettingsScreen extends AudioPage {
    public LyricsHudSettingsScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "lyrics.hud.settings");
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.LYRICS;
    }

    @Override
    protected void populate() {
        var s = app.settings;
        section("lyrics.hud.settings");
        toggle("lyrics.hud.next", () -> s.lyricsNextLine, v -> s.lyricsNextLine = v);
        slider("lyrics.hud.width", s.lyricsHudWidth, 120, 600, 480, v -> s.lyricsHudWidth = (int) Math.round(v), "");
        slider("lyrics.hud.opacity", s.lyricsHudOpacity * 100, 0, 100, 100, v -> s.lyricsHudOpacity = v / 100, "%");
        action("lyrics.hud.color", () -> show(new ColorPickerScreen(this, s.lyricsHudColor, 0xDDD9FF, v -> {
            s.lyricsHudColor = v;
            app.save();
        })));
        action("lyrics.hud.next_color", () -> show(new ColorPickerScreen(this, s.lyricsHudNextColor, 0xAAA7BD, v -> {
            s.lyricsHudNextColor = v;
            app.save();
        })));
        action("hud.darkmusic.layout", () -> show(new HudEditorScreen(app, this)));
    }
}
