package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;

/**
 * Artwork and spectrum share one measured, aspect-preserving component; the optional caption sits under the artwork.
 */
public record ArtworkHudLayout(int width, int height, int artworkX, int artworkY, int artworkWidth, int artworkHeight,
                               int spectrumX, int spectrumY, int captionY) {
    public static final int CAPTION = 26;

    public static ArtworkHudLayout of(Settings settings, int imageWidth, int imageHeight) {
        double fit = 180.0 / Math.max(1, Math.max(imageWidth, imageHeight));
        int w = Math.max(1, (int) Math.round(imageWidth * fit));
        int h = Math.max(1, (int) Math.round(imageHeight * fit));
        int extra = settings.hudVisualizer ? HudLayout.GAP + settings.artworkSpectrumHeight : 0;
        int artworkBlock = h + (settings.hudProgress ? (settings.waveformProgress ? settings.waveform.height + 2 : 3) : 0);
        int blockHeight = artworkBlock + (settings.artworkText ? CAPTION : 0);
        int caption = settings.artworkText ? artworkBlock : -1;
        if (!settings.hudVisualizer) return new ArtworkHudLayout(w, blockHeight, 0, 0, w, h, 0, 0, caption);
        return switch (settings.artworkSpectrumPosition) {
            case ABOVE ->
                new ArtworkHudLayout(w, blockHeight + extra, 0, extra, w, h, 0, 0, caption < 0 ? -1 : caption + extra);
            case BELOW ->
                new ArtworkHudLayout(w, blockHeight + extra, 0, 0, w, h, 0, blockHeight + HudLayout.GAP, caption);
            case LEFT, RIGHT -> {
                boolean left = settings.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.LEFT;
                yield new ArtworkHudLayout(w + HudLayout.GAP + settings.artworkSpectrumHeight, blockHeight,
                    left ? settings.artworkSpectrumHeight + HudLayout.GAP : 0, 0, w, h,
                    left ? 0 : w + HudLayout.GAP, 0, caption);
            }
        };
    }

    public boolean sideways(Settings settings) {
        return settings.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.LEFT
            || settings.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.RIGHT;
    }

    public int spectrumWidth(Settings settings) {
        return sideways(settings) ? settings.artworkSpectrumHeight : artworkWidth;
    }

    public int spectrumHeight(Settings settings) {
        return sideways(settings) ? artworkHeight : settings.artworkSpectrumHeight;
    }

    public int progressY() {
        return artworkY + artworkHeight;
    }
}
