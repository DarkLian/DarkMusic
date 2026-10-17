package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VisualizerSettingsScreen extends LocalScreen {
    private final DarkMusicClient app;

    public VisualizerSettingsScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("Music Visualizer"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        var s = app.settings;
        addRenderableWidget(Help.tip(new SettingRow(12, 34, width - 24, "Music-Player Visualizer", () -> s.playerVisualizer, () -> {
            s.playerVisualizer = !s.playerVisualizer;
            app.save();
        }), "Real audio spectrum below the artwork; compact layouts use the right of the status row."));
        addRenderableWidget(Help.tip(new SettingRow(12, 58, width - 24, "HUD Visualizer", () -> s.hudVisualizer, () -> {
            s.hudVisualizer = !s.hudVisualizer;
            app.save();
        }), "Independent of the now-playing HUD. Hidden with F1 and while menus are open."));
        addRenderableWidget(Help.tip(new ValueSlider(12, 82, width - 24, (s.visualizerBars - 8) / 56.0, v -> "Bars: " + s.visualizerBars, v -> {
            s.visualizerBars = 8 + (int) Math.round(v * 56);
            app.configureVisualizer();
        }, app::save, 14), "8–64 logarithmic frequency bands, from bass to treble. Narrow areas combine bars to fit."));
        addRenderableWidget(Help.tip(new ValueSlider(12, 106, width - 24, (s.visualizerSensitivity - .25) / 3.75, v -> "Sensitivity: " + String.format(java.util.Locale.ROOT, "%.2fx", s.visualizerSensitivity), v -> {
            s.visualizerSensitivity = .25 + v * 3.75;
            app.configureVisualizer();
        }, app::save, 15), "Boost quiet spectra or reduce strong levels. Samples follow playback speed and DarkMusic volume."));
        addRenderableWidget(Help.tip(new ValueSlider(12, 130, width - 24, s.visualizerSmoothing / .95, v -> "Smoothing: " + Math.round(s.visualizerSmoothing * 100) + "%", v -> {
            s.visualizerSmoothing = v * .95;
            app.configureVisualizer();
        }, app::save, 19), "Higher values give slower attack and decay. Pausing freezes the actual spectrum; stop and seek clear it."));
        int half = (width - 28) / 2;
        addRenderableWidget(new ValueSlider(12, 154, half, (s.visualizerWidth - 80) / 240.0, v -> "HUD Width: " + s.visualizerWidth, v -> s.visualizerWidth = 80 + (int) Math.round(v * 240), app::save, 30));
        addRenderableWidget(Help.tip(new ValueSlider(16 + half, 154, half, (s.visualizerHeight - 24) / 96.0, v -> "Height: " + s.visualizerHeight, v -> s.visualizerHeight = 24 + (int) Math.round(v * 96), app::save, 24), "HUD height and preferred player height, limited by available space without moving playback controls."));
        addRenderableWidget(Button.builder(Component.literal("Edit Visualizer HUD Layout"), b -> show(new VisualizerEditorScreen(app, this))).bounds(12, height - 28, width - 24, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        super.extractRenderState(g, x, y, delta);
    }

    @Override
    public void removed() {
        app.save();
        super.removed();
    }
}
