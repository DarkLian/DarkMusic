package com.darkmusic.client.gui;

/**
 * Shared dimensions keep the expanded media panel separate from the song list.
 */
public record PlayerLayout(int navigationWidth, int panelX, int panelWidth, int listY, int listHeight, int artworkSize,
                           int spectrumY, int spectrumHeight) {
    public static PlayerLayout of(int width, int height) {
        int nav = width >= 640 ? Math.clamp(width / 9, 86, 112) : width >= 480 ? 120 : 86;
        int y = height < 320 ? 58 : 82;
        int available = Math.max(28, height - 144 - (width < 640 ? 24 : 0) - y);
        int panel = width >= 640 ? Math.clamp(width / 4, 140, 240) : 0;
        int spectrum = Math.min(80, Math.max(16, available / 3));
        int art = panel == 0 ? 0 : Math.min(panel, Math.max(16, available - spectrum - 8));
        return new PlayerLayout(nav, width - 12 - panel, panel, y, available, art, y + art + 8, spectrum);
    }
}
