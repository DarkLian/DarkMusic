package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fixed full-width gradient coordinates: seeking changes the fill, never the palette.
 */
public final class ColorStrip {
    private ColorStrip() {
    }

    public static void draw(GuiGraphicsExtractor g, VisualColors colors, int x, int y, int width, int height, int filled) {
        draw(g, colors, x, y, width, height, filled, Settings.SurfaceFinish.FLAT);
    }

    public static void track(GuiGraphicsExtractor g, int x, int y, int width, int height, Settings.SurfaceFinish finish) {
        g.fill(x, y, x + width, y + height, 0xFF34394F);
        if (finish == Settings.SurfaceFinish.BEVELED && height >= 2) {
            g.fill(x, y, x + width, y + 1, Bevel.shade(0xFF34394F, -.35));
            g.fill(x, y + height - 1, x + width, y + height, Bevel.shade(0xFF34394F, .18));
        }
    }

    public static void draw(GuiGraphicsExtractor g, VisualColors colors, int x, int y, int width, int height, int filled, Settings.SurfaceFinish finish) {
        if (width <= 0 || height <= 0 || filled <= 0) return;
        filled = Math.min(width, filled);
        if (colors.first() == colors.last()) {
            segment(g, x, y, filled, height, colors.color(0), finish);
            return;
        }
        int segments = Math.min(96, width);
        for (int i = 0; i < segments; i++) {
            int left = i * width / segments, right = Math.min(filled, (i + 1) * width / segments);
            if (left >= filled) break;
            segment(g, x + left, y, right - left, height, colors.color((left + right) / (2.0 * width)), finish);
        }
    }

    private static void segment(GuiGraphicsExtractor g, int x, int y, int width, int height, int color, Settings.SurfaceFinish finish) {
        g.fill(x, y, x + width, y + height, color);
        if (finish == Settings.SurfaceFinish.BEVELED && height >= 2) {
            g.fill(x, y, x + width, y + 1, Bevel.shade(color, .3));
            g.fill(x, y + height - 1, x + width, y + height, Bevel.shade(color, -.28));
        }
    }
}
