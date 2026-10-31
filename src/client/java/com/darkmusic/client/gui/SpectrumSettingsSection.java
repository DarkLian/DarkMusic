package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.text.Text;

import java.util.Locale;

/**
 * The Spectrum section of Settings: style, placement, finish, quality, colours and levels.
 */
final class SpectrumSettingsSection {
    private SpectrumSettingsSection() {
    }

    static void build(SettingsLayout layout, SettingsScreen.Sources app, Settings s) {
        var screen = layout.screen();
        var style = HudSettingsSection.HudKind.of(s);
        boolean artwork = style.artwork(), spectrumOnly = style.spectrumOnly(), linear = style.linear();
        layout.section("hud.darkmusic.section_spectrum");
        var chosenStyle = linear ? s.spectrumStyle : s.circularSpectrumStyle;
        layout.choice("style.darkmusic.spectrum", () -> Text.get((linear ? "style.darkmusic.spectrum." : "circle.darkmusic.spectrum.") + SettingsLayout.name(chosenStyle)), () -> {
            if (linear)
                s.spectrumStyle = switch (s.spectrumStyle) {
                    case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                    case ROUNDED_BARS -> Settings.SpectrumStyle.SMOOTH_WAVE;
                    case FILLED_SPIKE -> Settings.SpectrumStyle.BARS;
                    default -> SettingsScreen.next(s.spectrumStyle);
                };
            else s.circularSpectrumStyle = switch (s.circularSpectrumStyle) {
                case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                case ROUNDED_BARS -> Settings.SpectrumStyle.FILLED_WAVE;
                case FILLED_WAVE -> Settings.SpectrumStyle.FILLED_SPIKE;
                default -> Settings.SpectrumStyle.BARS;
            };
        }, linear ? "style.darkmusic.spectrum_help" : "circle.darkmusic.spectrum_help");
        if (s.hudEnabled && linear) {
            if (!spectrumOnly) {
                if (artwork)
                    layout.choice("hud.darkmusic.position", "hud.darkmusic.position.", () -> s.artworkSpectrumPosition, v -> s.artworkSpectrumPosition = v,
                        "hud.darkmusic.artwork_position_help");
                else
                    layout.choice("hud.darkmusic.position", "hud.darkmusic.position.", () -> s.spectrumPosition, v -> s.spectrumPosition = v,
                        "hud.darkmusic.position_help");
            }
            if (!artwork || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.ABOVE
                || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.BELOW) {
                layout.choice("hud.darkmusic.direction", "hud.darkmusic.direction.", () -> s.spectrumDirection, v -> s.spectrumDirection = v,
                    "hud.darkmusic.direction_help");
            }
        }
        layout.finish("finish.darkmusic.spectrum", () -> s.spectrumFinish, v -> s.spectrumFinish = v);
        layout.toggle("quality.darkmusic.frequency", () -> s.visualQuality.frequencySmoothing, v -> s.visualQuality.frequencySmoothing = v, "quality.darkmusic.frequency_help");
        layout.toggle("quality.darkmusic.peaks", () -> s.visualQuality.peakHold, v -> s.visualQuality.peakHold = v, "quality.darkmusic.peaks_help");
        layout.toggle("quality.darkmusic.trails", () -> s.visualQuality.trails, v -> s.visualQuality.trails = v, "quality.darkmusic.trails_help");
        layout.toggle("quality.darkmusic.glow", () -> s.visualQuality.glow, v -> s.visualQuality.glow = v, "quality.darkmusic.glow_help");
        layout.action("colors.darkmusic.spectrum", () -> screen.show(app.navigation().colors(screen, true)), "colors.darkmusic.help");
        layout.waveformSlider((s.spectrumOpacity - .1) / .9, v -> Text.get("spectrum.darkmusic.opacity", Math.round(s.spectrumOpacity * 100)), v -> s.spectrumOpacity(.1 + v * .9), 18, "spectrum.darkmusic.opacity_help");
        if (!chosenStyle.bars() && (linear || chosenStyle != Settings.SpectrumStyle.FILLED_SPIKE))
            layout.waveformSlider((s.visualQuality.detail - 96) / 288.0, v -> Text.get("spectrum.darkmusic.detail", s.visualQuality.detail), v -> s.visualQuality.detail(96 + (int) Math.round(v * 288)), 12, "spectrum.darkmusic.detail_help");
        layout.slider(s.visualizerSmoothing / .95, v -> Text.get("format.darkmusic.smoothing", Math.round(s.visualizerSmoothing * 100)), v -> s.visualizerSmoothing(v * .95), 19, "text.darkmusic.higher_values_give_slower_attack_and_decay_pausing_freezes_the_actual_spectrum_sto");
        layout.slider((s.visualizerSensitivity - .25) / 3.75, v -> Text.get("format.darkmusic.sensitivity", String.format(Locale.ROOT, "%.2f", s.visualizerSensitivity)), v -> s.visualizerSensitivity(.25 + v * 3.75), 15, "text.darkmusic.boost_quiet_spectra_or_reduce_strong_levels_samples_follow_playback_speed_and_dark");
        layout.slider((s.visualizerBars - 8) / 56.0, v -> Text.get("format.darkmusic.bars", s.visualizerBars), v -> s.visualizerBars(8 + (int) Math.round(v * 56)), 14, "text.darkmusic.8_64_logarithmic_frequency_bands_from_bass_to_treble_narrow_areas_combine_bars_to");
        if (s.hudEnabled)
            layout.slider(linear ? ((artwork ? s.artworkSpectrumHeight : s.classicSpectrumHeight) - 8) / 92.0 : (s.circularSpectrumHeight - 4) / 56.0,
                v -> Text.get("hud.darkmusic.spectrum_height", artwork ? s.artworkSpectrumHeight : linear ? s.classicSpectrumHeight : s.circularSpectrumHeight),
                v -> {
                    if (artwork) s.artworkSpectrumHeight(8 + (int) Math.round(v * 92));
                    else if (linear) s.classicSpectrumHeight(8 + (int) Math.round(v * 92));
                    else s.circularSpectrumHeight(4 + (int) Math.round(v * 56));
                }, linear ? 92 : 56, "hud.darkmusic.spectrum_height_help");
    }
}
