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
    public double volume = 0.7;
    public boolean shuffle;
    public String repeat = "OFF";
    public boolean autoplay;
    public double speed = 1;
    public boolean hudEnabled;
    public String hudPosition = "TOP_LEFT";
    public Double hudX, hudY;
    public double hudScale = 1;
    public boolean hudArtwork = true;
    public boolean hudProgress = true;
    public boolean suppressVanillaMusic;
    public boolean playerVisualizer = true, hudVisualizer;
    public int visualizerBars = 24, visualizerWidth = 160, visualizerHeight = 48;
    public double visualizerSensitivity = 1, visualizerSmoothing = .6;
    public double visualizerX = 1, visualizerY = .65;
    public String ytDlpPath = "", ffmpegPath = "", ffprobePath = "", javascriptPath = "";

    public static double clampVolume(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, 0, 1) * 100) / 100.0 : .7;
    }

    public void validate() {
        volume = Double.isFinite(volume) ? Math.clamp(volume, 0, 1) : 0.7;
        if (musicDirectory == null) musicDirectory = "";
        if (roots == null) roots = new LinkedHashMap<>();
        if (shortcuts == null) shortcuts = new LinkedHashMap<>();
        if (ytDlpPath == null) ytDlpPath = "";
        if (ffmpegPath == null) ffmpegPath = "";
        if (ffprobePath == null) ffprobePath = "";
        if (javascriptPath == null) javascriptPath = "";
        if (!java.util.Set.of("OFF", "ONE", "ALL").contains(repeat == null ? "" : repeat)) repeat = "OFF";
        if (!java.util.Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(hudPosition == null ? "" : hudPosition))
            hudPosition = "TOP_LEFT";
        speed = com.darkmusic.client.audio.PcmRateConverter.clamp(speed);
        visualizerBars = Math.clamp(visualizerBars, 8, 64);
        visualizerWidth = Math.clamp(visualizerWidth, 80, 320);
        visualizerHeight = Math.clamp(visualizerHeight, 24, 120);
        visualizerSensitivity = Double.isFinite(visualizerSensitivity) ? Math.clamp(visualizerSensitivity, .25, 4) : 1;
        visualizerSmoothing = Double.isFinite(visualizerSmoothing) ? Math.clamp(visualizerSmoothing, 0, .95) : .6;
        visualizerX = Double.isFinite(visualizerX) ? Math.clamp(visualizerX, 0, 1) : 1;
        visualizerY = Double.isFinite(visualizerY) ? Math.clamp(visualizerY, 0, 1) : .65;
        hudScale = Double.isFinite(hudScale) ? Math.round(Math.clamp(hudScale, .5, 2) * 10) / 10.0 : 1;
        hudX = hudX == null || !Double.isFinite(hudX) ? (hudPosition.endsWith("RIGHT") ? 1.0 : 0.0) : Math.clamp(hudX, 0, 1);
        hudY = hudY == null || !Double.isFinite(hudY) ? (hudPosition.startsWith("BOTTOM") ? 1.0 : 0.0) : Math.clamp(hudY, 0, 1);
    }
}
