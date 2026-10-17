package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VisualizerSettingsScreen extends LocalScreen {
    private final DarkMusicClient app;

    public VisualizerSettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.music_visualizer"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        var s = app.settings;
        addRenderableWidget(Help.tip(new SettingRow(12, 34, width - 24, Text.get("text.darkmusic.music_player_visualizer"), () -> s.playerVisualizer, () -> {
            s.playerVisualizer = !s.playerVisualizer;
            app.save();
        }), Text.get("text.darkmusic.real_audio_spectrum_below_the_artwork_compact_layouts_use_the_right_of_the_status")));
        addRenderableWidget(Help.tip(new SettingRow(12, 58, width - 24, Text.get("text.darkmusic.hud_visualizer"), () -> s.hudVisualizer, () -> {
            s.hudVisualizer = !s.hudVisualizer;
            app.save();
        }), Text.get("text.darkmusic.independent_of_the_now_playing_hud_hidden_with_f1_and_while_menus_are_open")));
        addRenderableWidget(Help.tip(new ValueSlider(12, 82, width - 24, (s.visualizerBars - 8) / 56.0, v -> Text.get("format.darkmusic.bars", s.visualizerBars), v -> {
            s.visualizerBars = 8 + (int) Math.round(v * 56);
            app.configureVisualizer();
        }, app::save, 14), Text.get("text.darkmusic.8_64_logarithmic_frequency_bands_from_bass_to_treble_narrow_areas_combine_bars_to")));
        addRenderableWidget(Help.tip(new ValueSlider(12, 106, width - 24, (s.visualizerSensitivity - .25) / 3.75, v -> Text.get("format.darkmusic.sensitivity", String.format(java.util.Locale.ROOT, "%.2f", s.visualizerSensitivity)), v -> {
            s.visualizerSensitivity = .25 + v * 3.75;
            app.configureVisualizer();
        }, app::save, 15), Text.get("text.darkmusic.boost_quiet_spectra_or_reduce_strong_levels_samples_follow_playback_speed_and_dark")));
        addRenderableWidget(Help.tip(new ValueSlider(12, 130, width - 24, s.visualizerSmoothing / .95, v -> Text.get("format.darkmusic.smoothing", Math.round(s.visualizerSmoothing * 100)), v -> {
            s.visualizerSmoothing = v * .95;
            app.configureVisualizer();
        }, app::save, 19), Text.get("text.darkmusic.higher_values_give_slower_attack_and_decay_pausing_freezes_the_actual_spectrum_sto")));
        int half = (width - 28) / 2;
        addRenderableWidget(new ValueSlider(12, 154, half, (s.visualizerWidth - 80) / 240.0, v -> Text.get("format.darkmusic.hud_width", s.visualizerWidth), v -> s.visualizerWidth = 80 + (int) Math.round(v * 240), app::save, 30));
        addRenderableWidget(Help.tip(new ValueSlider(16 + half, 154, half, (s.visualizerHeight - 24) / 96.0, v -> Text.get("format.darkmusic.height", s.visualizerHeight), v -> s.visualizerHeight = 24 + (int) Math.round(v * 96), app::save, 24), Text.get("text.darkmusic.hud_height_and_preferred_player_height_limited_by_available_space_without_moving_p")));
        addRenderableWidget(Button.builder(Text.component("text.darkmusic.edit_visualizer_hud_layout"), b -> show(new VisualizerEditorScreen(app, this))).bounds(12, height - 28, width - 24, 20).build());
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
