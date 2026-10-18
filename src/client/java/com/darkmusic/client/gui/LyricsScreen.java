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
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.LYRICS);
        int panelWidth = Math.min(720, width - 24), panelX = (width - panelWidth) / 2;
        UiTheme.panel(g, panelX, 32, panelWidth, height - 44);
        var state = app.playback.state();
        if (state.track() != null)
            longText.draw(g, font, "track", state.track().title() + " | " + state.track().artist(), panelX + 12, 43, panelWidth - 24, UiTheme.SECONDARY);
        var data = app.lyrics.get(state.track());
        int current = data.current(state.position());
        if (!app.settings.lyricsEnabled)
            g.centeredText(font, Text.get("lyrics.darkmusic.disabled"), width / 2, height / 2, 0xffb0adc2);
        else if (data.lines().isEmpty())
            g.centeredText(font, Text.get(app.lyrics.info(state.track()).key()), width / 2, height / 2, 0xffb0adc2);
        else {
            int visible = Math.max(1, (height - 100) / 24), first = Math.max(0, current - visible / 2);
            for (int i = first; i < Math.min(data.lines().size(), first + visible); i++) {
                String line = data.lines().get(i).text();
                if (i == current)
                    g.fill(panelX + 1, 64 + (i - first) * 24, panelX + panelWidth - 1, 86 + (i - first) * 24, UiTheme.SELECTED);
                int measured = font.width(line);
                longText.draw(g, font, "line" + i, line, measured <= panelWidth - 24 ? (width - measured) / 2 : panelX + 12, 69 + (i - first) * 24, Math.min(measured + 1, panelWidth - 24), i == current ? UiTheme.TEXT : UiTheme.MUTED);
            }
        }
        super.extractRenderState(g, x, y, delta);
    }

    @Override
    public net.minecraft.network.chat.Component getNarrationMessage() {
        var state = app.playback.state();
        var data = app.lyrics.get(state.track());
        int current = data.current(state.position());
        return net.minecraft.network.chat.Component.literal(title.getString() + (state.track() == null ? "" : ": " + state.track().title())
            + (current >= 0 && current < data.lines().size() ? ": " + data.lines().get(current).text() : ""));
    }
}
