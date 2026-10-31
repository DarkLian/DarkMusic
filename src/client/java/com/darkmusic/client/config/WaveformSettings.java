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

    public static final Range HEIGHT = Range.whole(6, 18);
    public static final Range DETAIL = Range.whole(48, 512);
    public static final Range SPACING = Range.whole(0, 4);
    public static final Range SMOOTHING = Range.of(0, 1, .35);
    public static final Range CONTRAST = Range.of(.5, 2.5, 1.4);
    public static final Range OPACITY = Range.of(.15, 1, 1);

    public void validate() {
        if (style == null) style = Style.FILLED;
        height = HEIGHT.apply(height);
        detail = DETAIL.apply(detail);
        spacing = SPACING.apply(spacing);
        unplayedColor &= 0xffffff;
        smoothing = SMOOTHING.apply(smoothing);
        contrast = CONTRAST.apply(contrast);
        opacity = OPACITY.apply(opacity);
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void height(int value) {
        height = HEIGHT.apply(value);
    }

    public void detail(int value) {
        detail = DETAIL.apply(value);
    }

    public void spacing(int value) {
        spacing = SPACING.apply(value);
    }

    public void smoothing(double value) {
        smoothing = SMOOTHING.apply(value);
    }

    public void contrast(double value) {
        contrast = CONTRAST.apply(value);
    }

    public void opacity(double value) {
        opacity = OPACITY.apply(value);
    }
}
