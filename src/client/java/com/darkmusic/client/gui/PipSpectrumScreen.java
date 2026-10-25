package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.pip.PipSettings;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Artwork-style spectrum controls, scoped exclusively to PiP.
 */
public final class PipSpectrumScreen extends AudioPage {
    private int previewY;

    public PipSpectrumScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "pip.spectrum.title");
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.PIP;
    }

    @Override
    protected void populate() {
        var s = app.settings.pip.spectrum;
        info("pip.spectrum.independent");
        previewY = cursor;
        cursor += 88;
        section("pip.spectrum.section");
        cycle("style.darkmusic.spectrum", "style.darkmusic.spectrum.", s.style, Settings.SpectrumStyle.values(), v -> s.style = v, "style.darkmusic.spectrum_help");
        cycle("hud.darkmusic.position", "hud.darkmusic.position.", s.position, Settings.ArtworkSpectrumPosition.values(), v -> s.position = v, "pip.spectrum.position.help");
        if (!s.sideways())
            cycle("hud.darkmusic.direction", "hud.darkmusic.direction.", s.direction, Settings.SpectrumDirection.values(), v -> s.direction = v, "hud.darkmusic.direction_help");
        cycle("finish.darkmusic.spectrum", "finish.darkmusic.", s.finish, Settings.SurfaceFinish.values(), v -> s.finish = v, "finish.darkmusic.help");
        effect("quality.darkmusic.frequency", () -> s.quality.frequencySmoothing, v -> s.quality.frequencySmoothing = v);
        effect("quality.darkmusic.peaks", () -> s.quality.peakHold, v -> s.quality.peakHold = v);
        effect("quality.darkmusic.trails", () -> s.quality.trails, v -> s.quality.trails = v);
        effect("quality.darkmusic.glow", () -> s.quality.glow, v -> s.quality.glow = v);
        labeledAction(Text.get("colors.darkmusic.spectrum"), Text.get("colors.darkmusic.help"), () -> show(new ColorsScreen(app, this, s.colors, "colors.darkmusic.spectrum", this::preview, "pip.spectrum.preview")));
        control((s.opacity - .1) / .9, v -> Text.get("spectrum.darkmusic.opacity", Math.round(s.opacity * 100)), v -> s.opacity = .1 + v * .9, 18, "spectrum.darkmusic.opacity_help");
        if (!s.style.bars())
            control((s.quality.detail - 96) / 288.0, v -> Text.get("spectrum.darkmusic.detail", s.quality.detail), v -> s.quality.detail = 96 + (int) Math.round(v * 288), 12, "spectrum.darkmusic.detail_help");
        control(s.smoothing / .95, v -> Text.get("format.darkmusic.smoothing", Math.round(s.smoothing * 100)), v -> s.smoothing = v * .95, 19, "pip.spectrum.smoothing.help");
        control((s.sensitivity - .25) / 3.75, v -> Text.get("format.darkmusic.sensitivity", String.format(Locale.ROOT, "%.2f", s.sensitivity)), v -> s.sensitivity = .25 + v * 3.75, 15, "pip.spectrum.sensitivity.help");
        control((s.bands - 8) / 56.0, v -> Text.get("format.darkmusic.bars", s.bands), v -> s.bands = 8 + (int) Math.round(v * 56), 14, "text.darkmusic.8_64_logarithmic_frequency_bands_from_bass_to_treble_narrow_areas_combine_bars_to");
        control((s.height - 8) / 92.0, v -> Text.get("hud.darkmusic.spectrum_height", s.height), v -> s.height = 8 + (int) Math.round(v * 92), 92, "hud.darkmusic.spectrum_height_help");
        action("pip.placement", () -> show(new PipEditorScreen(app, this, false)));
    }

    private <E extends Enum<E>> void cycle(String key, String prefix, E current, E[] values, java.util.function.Consumer<E> write, String help) {
        labeledAction(Text.get(key) + ": " + Text.get(prefix + current.name().toLowerCase(Locale.ROOT)), Text.get(help), () -> {
            write.accept(values[(current.ordinal() + 1) % values.length]);
            changed();
        });
    }

    private void control(double value, DoubleFunction<String> label, DoubleConsumer write, int steps, String help) {
        var slider = new ValueSlider(formX, 0, formWidth, value, label, write, app::save, steps);
        slider.plain(true);
        Help.tip(slider, Text.get(help));
        place(slider);
    }

    private void effect(String key, java.util.function.BooleanSupplier read, java.util.function.Consumer<Boolean> write) {
        var row = new SettingRow(formX, 0, formWidth, Text.get(key), read, () -> {
            write.accept(!read.getAsBoolean());
            changed();
        });
        place(row);
        Help.tip(row, Text.get(key + "_help"));
    }

    private void preview(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        int size = Math.min(app.settings.pip.spectrum.height, Math.max(1, height - 8));
        boolean sideways = app.settings.pip.spectrum.sideways();
        app.pip.drawSpectrum(g, new PipSettings.Bounds(sideways ? x + (width - size) / 2 : x + 4, sideways ? y + 4 : y + (height - size) / 2,
            sideways ? size : Math.max(8, width - 8), sideways ? Math.max(8, height - 8) : size), true);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int x, int y, float delta) {
        int top = contentY(previewY);
        UiTheme.panel(g, formX, top, formWidth, 80);
        preview(g, formX, top, formWidth, 80);
    }
}
