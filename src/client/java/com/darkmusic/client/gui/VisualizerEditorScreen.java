package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.SpectrumHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class VisualizerEditorScreen extends LocalScreen {
    private final DarkMusicClient app;
    private boolean dragging;
    private double dragX, dragY;

    public VisualizerEditorScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.visualizer_hud_layout"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.reset_position"), b -> {
            app.settings.visualizerX = 1;
            app.settings.visualizerY = .65;
            app.save();
        }).bounds(12, height - 28, width - 24, 20).build());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (super.mouseClicked(event, twice)) return true;
        var b = SpectrumHud.bounds(app.settings, width, height);
        if (event.button() == 0 && b.contains(event.x(), event.y())) {
            dragging = true;
            dragX = event.x() - b.x();
            dragY = event.y() - b.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        var b = SpectrumHud.bounds(app.settings, width, height);
        app.settings.visualizerX = HudLayout.normalized(event.x() - dragX, width, b.width());
        app.settings.visualizerY = HudLayout.normalized(event.y() - dragY, height, b.height());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        app.save();
        return true;
    }

    @Override
    public void removed() {
        app.save();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xCC111420);
        g.text(font, Text.get("text.darkmusic.drag_the_spectrum_play_music_for_a_live_preview_escape_saves"), 12, 12, 0xFFB5B0FF);
        SpectrumHud.render(app, g);
        var b = SpectrumHud.bounds(app.settings, width, height);
        g.outline(b.x(), b.y(), b.width(), b.height(), 0xFFB5B0FF);
        super.extractRenderState(g, x, y, delta);
    }
}
