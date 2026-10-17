package com.darkmusic.client.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable only on the client thread after loading.
 */
public final class Settings {
    public int schemaVersion = 1;
    public String musicDirectory = "";
    public Map<String, String> roots = new LinkedHashMap<>();
    public Map<String, String> shortcuts = new LinkedHashMap<>();
    public transient java.util.Set<String> nativeShortcutNames = java.util.Set.of();
    public double volume = 1;
    public double lastAudibleVolume = 1;
    public boolean shuffle;
    public com.darkmusic.client.audio.PlaybackController.Repeat repeat = com.darkmusic.client.audio.PlaybackController.Repeat.OFF;
    public boolean autoplay;
    public double speed = 1;
    public boolean hudEnabled = true;
    public String hudPosition = "TOP_LEFT";
    public Double hudX, hudY;
    public double hudScale = 1;
    public boolean hudArtwork = true;
    public boolean hudProgress = true;
    public boolean suppressVanillaMusic;
    public boolean hudVisualizer = true;
    public SpectrumPosition spectrumPosition = SpectrumPosition.BELOW;
    public SpectrumDirection spectrumDirection = SpectrumDirection.UPWARD;
    public double hudBackgroundTransparency = 0;
    public VisualColors spectrumColors = new VisualColors(), progressColors = new VisualColors();
    public int visualizerBars = 36;
    public double visualizerSensitivity = 1, visualizerSmoothing = .6;
    public String ytDlpPath = "", ffmpegPath = "", ffprobePath = "", javascriptPath = "";

    public static Settings defaults(Settings previous) {
        var defaults = new Settings();
        // Folder identities are playlist reference data, not preferences.
        defaults.roots.putAll(previous.roots);
        defaults.validate();
        return defaults;
    }

    public static double clampVolume(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, 0, 1) * 100) / 100.0 : 1;
    }

    public void validate() {
        volume = Double.isFinite(volume) ? Math.clamp(volume, 0, 1) : 1;
        lastAudibleVolume = volume > 0 ? volume : Double.isFinite(lastAudibleVolume) && lastAudibleVolume > 0 ? Math.clamp(lastAudibleVolume, .01, 1) : 1;
        if (musicDirectory == null) musicDirectory = "";
        if (roots == null) roots = new LinkedHashMap<>();
        if (shortcuts == null) shortcuts = new LinkedHashMap<>();
        if (ytDlpPath == null) ytDlpPath = "";
        if (ffmpegPath == null) ffmpegPath = "";
        if (ffprobePath == null) ffprobePath = "";
        if (javascriptPath == null) javascriptPath = "";
        if (repeat == null) repeat = com.darkmusic.client.audio.PlaybackController.Repeat.OFF;
        if (!java.util.Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(hudPosition == null ? "" : hudPosition))
            hudPosition = "TOP_LEFT";
        speed = com.darkmusic.client.audio.PcmRateConverter.clamp(speed);
        if (spectrumColors == null) spectrumColors = new VisualColors();
        if (progressColors == null) progressColors = new VisualColors();
        spectrumColors.validate();
        progressColors.validate();
        visualizerBars = Math.clamp(visualizerBars, 8, 64);
        visualizerSensitivity = Double.isFinite(visualizerSensitivity) ? Math.clamp(visualizerSensitivity, .25, 4) : 1;
        visualizerSmoothing = Double.isFinite(visualizerSmoothing) ? Math.clamp(visualizerSmoothing, 0, .95) : .6;
        if (spectrumPosition == null) spectrumPosition = SpectrumPosition.BELOW;
        if (spectrumDirection == null) spectrumDirection = SpectrumDirection.UPWARD;
        hudBackgroundTransparency = Double.isFinite(hudBackgroundTransparency) ? Math.clamp(hudBackgroundTransparency, 0, 1) : 0;
        hudScale = com.darkmusic.client.hud.HudScale.clamp(hudScale);
        hudX = hudX == null || !Double.isFinite(hudX) ? (hudPosition.endsWith("RIGHT") ? 1.0 : 0.0) : Math.clamp(hudX, 0, 1);
        hudY = hudY == null || !Double.isFinite(hudY) ? (hudPosition.startsWith("BOTTOM") ? 1.0 : 0.0) : Math.clamp(hudY, 0, 1);
    }

    public enum SpectrumPosition {ABOVE, BELOW}

    public enum SpectrumDirection {UPWARD, DOWNWARD}
}
