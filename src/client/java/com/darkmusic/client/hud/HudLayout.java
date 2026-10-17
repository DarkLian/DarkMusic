package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;

/**
 * Shared local geometry: every enabled surface belongs to one measured component.
 */
public final class HudLayout {
    public static final int SPECTRUM_HEIGHT = 32, GAP = 4;

    private HudLayout() {
    }

    public static Content content(Settings settings, int maximumWidth) {
        int width = Math.max(1, Math.min(settings.hudArtwork ? 220 : 184, maximumWidth));
        int mainHeight = settings.hudProgress ? 46 : 38;
        boolean above = settings.hudVisualizer && settings.spectrumPosition == Settings.SpectrumPosition.ABOVE;
        int extra = settings.hudVisualizer ? GAP + SPECTRUM_HEIGHT : 0;
        return new Content(width, mainHeight + extra, above ? extra : 0, mainHeight,
            above ? 0 : mainHeight + GAP, settings.hudVisualizer);
    }

    public static Bounds bounds(Settings settings, int width, int height) {
        var content = content(settings, Integer.MAX_VALUE);
        double scale = Math.min(settings.hudScale, Math.max(.01, (height - 16.0) / content.height()));
        scale = Math.min(scale, Math.max(.01, (width - 16.0) / content.width()));
        int w = (int) Math.ceil(content.width() * scale - 1e-9), h = (int) Math.ceil(content.height() * scale - 1e-9);
        return new Bounds(8 + (int) Math.round(Math.max(0, width - 16 - w) * settings.hudX),
            8 + (int) Math.round(Math.max(0, height - 16 - h) * settings.hudY), w, h, content.width(), scale);
    }

    public static Bounds bounds(int width, int height, double x, double y, double scale, boolean progress) {
        var settings = new Settings();
        settings.hudX = x;
        settings.hudY = y;
        settings.hudScale = scale;
        settings.hudProgress = progress;
        return bounds(settings, width, height);
    }

    public static double normalized(double coordinate, int screenSize, int size) {
        return Math.clamp((coordinate - 8) / Math.max(1, screenSize - 16.0 - size), 0, 1);
    }

    public static int background(Settings settings) {
        return ((int) Math.round((1 - settings.hudBackgroundTransparency) * 255) << 24) | 0x111420;
    }

    public record Content(int width, int height, int mainY, int mainHeight, int spectrumY, boolean spectrum) {
        public int baseline(Settings.SpectrumDirection direction) {
            return direction == Settings.SpectrumDirection.UPWARD ? spectrumY + SPECTRUM_HEIGHT : spectrumY;
        }
    }

    public record Bounds(int x, int y, int width, int height, int contentWidth, double scale) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
