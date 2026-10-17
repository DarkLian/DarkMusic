package com.darkmusic.client.gui;

import com.darkmusic.client.config.VisualColors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fixed full-width gradient coordinates: seeking changes the fill, never the palette.
 */
public final class ColorStrip {
    private ColorStrip() {
    }

    public static void draw(GuiGraphicsExtractor g, VisualColors colors, int x, int y, int width, int height, int filled) {
        if (width <= 0 || height <= 0 || filled <= 0) return;
        filled = Math.min(width, filled);
        if (colors.first() == colors.last()) {
            g.fill(x, y, x + filled, y + height, colors.color(0));
            return;
        }
        int segments = Math.min(96, width);
        for (int i = 0; i < segments; i++) {
            int left = i * width / segments, right = Math.min(filled, (i + 1) * width / segments);
            if (left >= filled) break;
            g.fill(x + left, y, x + right, y + height, colors.color((left + right) / (2.0 * width)));
        }
    }
}
