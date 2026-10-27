package com.darkmusic.client.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Text drawn straight over the world sits on a soft plate sized to the text, never on the whole HUD.
 */
public final class HudText {
    public static final int PLATE = 0x90 << 24 | 0x0B0D16;

    private HudText() {
    }

    /**
     * A rectangle with clipped corners.
     */
    public static void plate(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int color) {
        if (right - left < 3 || bottom - top < 3 || color >>> 24 == 0) return;
        g.fill(left + 1, top, right - 1, top + 1, color);
        g.fill(left, top + 1, right, bottom - 1, color);
        g.fill(left + 1, bottom - 1, right - 1, bottom, color);
    }

    /**
     * Lyrics plates fade out as the configured lyrics background becomes opaque enough to carry the text.
     */
    public static int lyricsPlate(double backgroundOpacity) {
        int alpha = (int) Math.round(Math.clamp(1 - backgroundOpacity * 2, 0, 1) * (PLATE >>> 24));
        return alpha < 8 ? 0 : alpha << 24 | PLATE & 0xFFFFFF;
    }

    /**
     * Notices follow the menu theme so they match DarkMusic screens and stay readable on any theme.
     */
    public static int themed(int color, int alpha) {
        return alpha << 24 | color & 0xFFFFFF;
    }
}
