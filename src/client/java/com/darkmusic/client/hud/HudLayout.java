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
        return content(settings, maximumWidth, 1, 1);
    }

    public static Content content(Settings settings, int maximumWidth, int imageWidth, int imageHeight) {
        if (settings.hudStyle == Settings.HudStyle.SPECTRUM)
            return new Content(Math.max(1, Math.min(220, maximumWidth)), settings.classicSpectrumHeight,
                0, 0, 0, settings.hudVisualizer, settings.classicSpectrumHeight);
        if (settings.hudStyle == Settings.HudStyle.ARTWORK) {
            var art = ArtworkHudLayout.of(settings, imageWidth, imageHeight);
            return new Content(art.width(), art.height(), art.artworkY(), art.artworkHeight(), art.spectrumY(), settings.hudVisualizer, settings.artworkSpectrumHeight);
        }
        if (settings.hudStyle == Settings.HudStyle.CIRCULAR) {
            var circle = CircularHudLayout.of(settings);
            return new Content(Math.max(1, Math.min(circle.width(), maximumWidth)), circle.height(), 0, circle.height(), 0, settings.hudVisualizer);
        }
        int width = Math.max(1, Math.min(settings.hudArtwork ? 220 : 184, maximumWidth));
        int mainHeight = (settings.hudProgress ? (settings.waveformProgress ? 42 + settings.waveform.height : 46) : 38) + nextLine(settings) + timeLine(settings);
        boolean above = settings.hudVisualizer && settings.spectrumPosition == Settings.SpectrumPosition.ABOVE;
        int extra = settings.hudVisualizer ? GAP + settings.classicSpectrumHeight : 0;
        return new Content(width, mainHeight + extra, above ? extra : 0, mainHeight,
            above ? 0 : mainHeight + GAP, settings.hudVisualizer, settings.classicSpectrumHeight);
    }

    /**
     * Height of the Classic HUD's time line under the progress bar. Without a progress bar the time stays on the
     * artist line.
     */
    public static int timeLine(Settings settings) {
        return settings.hudStyle == Settings.HudStyle.CLASSIC && settings.hudTime && settings.hudProgress
            && settings.hudTimePosition == Settings.TimePosition.BELOW_BAR ? 10 : 0;
    }

    /**
     * Height of the Classic HUD's "Next:" line.
     */
    public static int nextLine(Settings settings) {
        return settings.hudStyle == Settings.HudStyle.CLASSIC && settings.hudNextSong ? 11 : 0;
    }

    public static Bounds bounds(Settings settings, int width, int height) {
        var content = content(settings, Integer.MAX_VALUE);
        return bounds(settings, width, height, content);
    }

    public static Bounds bounds(Settings settings, int width, int height, Content content) {
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
        settings.hudScale(scale);
        settings.hudProgress = progress;
        return bounds(settings, width, height);
    }

    public static double normalized(double coordinate, int screenSize, int size) {
        return Math.clamp((coordinate - 8) / Math.max(1, screenSize - 16.0 - size), 0, 1);
    }


    /**
     * A see-through or light card leaves title text competing with the world behind it; a soft dark
     * band behind the two text lines restores contrast without changing the configured card.
     */
    public static int textScrim(Settings settings) {
        double opacity = 1 - settings.hudBackgroundTransparency;
        int first = settings.hudBackgroundColors.first(), last = settings.hudBackgroundColors.last();
        double light = Math.max(luminance(first), settings.hudBackgroundGradient ? luminance(last) : 0);
        // Transparent cards need help in proportion to their transparency; opaque light cards always do.
        double need = Math.max(Math.clamp((.6 - opacity) / .6, 0, 1), Math.clamp((light - .35) / .4, 0, 1) * opacity);
        int alpha = (int) Math.round(need * 0x60);
        return alpha < 8 ? 0 : alpha << 24 | 0x0B0D16;
    }

    private static double luminance(int rgb) {
        return ((rgb >> 16 & 255) * .2126 + (rgb >> 8 & 255) * .7152 + (rgb & 255) * .0722) / 255;
    }

    public record Content(int width, int height, int mainY, int mainHeight, int spectrumY, boolean spectrum,
                          int spectrumHeight) {
        public Content(int width, int height, int mainY, int mainHeight, int spectrumY, boolean spectrum) {
            this(width, height, mainY, mainHeight, spectrumY, spectrum, SPECTRUM_HEIGHT);
        }

        public int baseline(Settings.SpectrumDirection direction) {
            return direction == Settings.SpectrumDirection.UPWARD ? spectrumY + spectrumHeight : spectrumY;
        }
    }

    public record Bounds(int x, int y, int width, int height, int contentWidth, double scale) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
