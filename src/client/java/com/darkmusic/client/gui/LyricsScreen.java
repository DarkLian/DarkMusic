package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

public final class LyricsScreen extends LocalScreen {
    private final DarkMusicClient app;

    public LyricsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("lyrics.darkmusic.title"), parent);
        this.app = app;
    }

    @Override
    public void tick() {
        app.lyrics.request(app.playback.state().track());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xff111420);
        g.text(font, title, 12, 12, 0xffb5b0ff);
        var state = app.playback.state();
        var data = app.lyrics.get(state.track());
        int current = data.current(state.position());
        if (!app.settings.lyricsEnabled)
            g.centeredText(font, Text.get("lyrics.darkmusic.disabled"), width / 2, height / 2, 0xffb0adc2);
        else if (data.lines().isEmpty())
            g.centeredText(font, Text.get("lyrics.darkmusic.missing"), width / 2, height / 2, 0xffb0adc2);
        else {
            int visible = Math.max(1, (height - 70) / 24), first = Math.max(0, current - visible / 2);
            for (int i = first; i < Math.min(data.lines().size(), first + visible); i++) {
                String line = com.darkmusic.client.hud.CircularHudRenderer.ellipsis(data.lines().get(i).text(), width - 32, font::width);
                g.centeredText(font, line, width / 2, 44 + (i - first) * 24, i == current ? 0xffddd9ff : 0xff8b8999);
            }
        }
        super.extractRenderState(g, x, y, delta);
    }
}
