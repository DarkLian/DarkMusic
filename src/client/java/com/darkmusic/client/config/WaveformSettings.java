package com.darkmusic.client.config;

/**
 * Shared visual controls; never affect decoded audio or playback.
 */
public final class WaveformSettings {
    public enum Style {FILLED, BARS}

    public Style style = Style.FILLED;
    public int height = 14, detail = 160, spacing = 1, unplayedColor = 0x555D78;
    public double smoothing = .35, contrast = 1.4, opacity = 1;

    public record Look(Style style, int height, int detail, int spacing, int unplayedColor,
                       double smoothing, double contrast, double opacity) {
    }

    public Look look() {
        return new Look(style, height, detail, spacing, unplayedColor, smoothing, contrast, opacity);
    }

    public void validate() {
        if (style == null) style = Style.FILLED;
        height = Math.clamp(height, 6, 18);
        detail = Math.clamp(detail, 48, 512);
        spacing = Math.clamp(spacing, 0, 4);
        unplayedColor &= 0xffffff;
        smoothing = Double.isFinite(smoothing) ? Math.clamp(smoothing, 0, 1) : .35;
        contrast = Double.isFinite(contrast) ? Math.clamp(contrast, .5, 2.5) : 1.4;
        opacity = Double.isFinite(opacity) ? Math.clamp(opacity, .15, 1) : 1;
    }
}
