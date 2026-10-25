package com.darkmusic.client.pip;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.config.VisualQuality;

/**
 * Independent preferences; only the existing spectrum renderer and analysis are reused.
 */
public final class PipSpectrumSettings {
    public Settings.SpectrumStyle style = Settings.SpectrumStyle.BARS;
    public Settings.SurfaceFinish finish = Settings.SurfaceFinish.BEVELED;
    public Settings.ArtworkSpectrumPosition position = Settings.ArtworkSpectrumPosition.BELOW;
    public Settings.SpectrumDirection direction = Settings.SpectrumDirection.UPWARD;
    public VisualColors colors = new VisualColors();
    public VisualQuality quality = defaults();
    public int bands = 36, height = 40;
    public double opacity = 1, sensitivity = 1, smoothing = .6;

    private static VisualQuality defaults() {
        var quality = new VisualQuality();
        quality.select(VisualQuality.Preset.ENHANCED);
        quality.trails = quality.glow = true;
        return quality;
    }

    public void validate() {
        if (style == null) style = Settings.SpectrumStyle.BARS;
        if (finish == null) finish = Settings.SurfaceFinish.BEVELED;
        if (position == null) position = Settings.ArtworkSpectrumPosition.BELOW;
        if (direction == null) direction = Settings.SpectrumDirection.UPWARD;
        if (colors == null) colors = new VisualColors();
        if (quality == null) quality = defaults();
        colors.validate();
        quality.validate();
        bands = Math.clamp(bands, 8, 64);
        height = Math.clamp(height, 8, 100);
        opacity = finite(opacity, .1, 1, 1);
        sensitivity = finite(sensitivity, .25, 4, 1);
        smoothing = finite(smoothing, 0, .95, .6);
    }

    private static double finite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    /**
     * Fill a session-owned rendering adapter, never the user's music settings.
     */
    public void applyTo(Settings rendering) {
        rendering.spectrumStyle = style;
        rendering.spectrumFinish = finish;
        rendering.spectrumColors = colors;
        rendering.visualQuality = quality;
        rendering.visualizerBars = bands;
        rendering.visualizerSensitivity = sensitivity;
        rendering.visualizerSmoothing = smoothing;
        rendering.spectrumOpacity = opacity;
        rendering.hudShape = Settings.HudShape.RECTANGLE;
    }

    public boolean sideways() {
        return position == Settings.ArtworkSpectrumPosition.LEFT || position == Settings.ArtworkSpectrumPosition.RIGHT;
    }

    /**
     * Clamp the spectrum to the screen; at an edge it overlays the video instead of disappearing.
     */
    public PipSettings.Bounds bounds(PipSettings.Bounds video, int screenWidth, int screenHeight) {
        int w = sideways() ? Math.min(height, screenWidth) : video.width();
        int h = sideways() ? video.height() : Math.min(height, screenHeight);
        int x = switch (position) {
            case LEFT -> video.x() - w;
            case RIGHT -> video.x() + video.width();
            default -> video.x();
        };
        int y = switch (position) {
            case ABOVE -> video.y() - h;
            case BELOW -> video.y() + video.height();
            default -> video.y();
        };
        return new PipSettings.Bounds(Math.clamp(x, 0, Math.max(0, screenWidth - w)), Math.clamp(y, 0, Math.max(0, screenHeight - h)), w, h);
    }
}
