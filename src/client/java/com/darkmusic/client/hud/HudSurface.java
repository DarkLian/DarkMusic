package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Background geometry stays inside the existing movable HUD bounds.
 */
public final class HudSurface {
    private HudSurface() {
    }

    public static int inset(Settings.HudShape shape, int width, int height, int row) {
        int radius = Math.min(6, Math.min(width, height) / 2);
        int edge = Math.min(row, height - 1 - row);
        if (shape == Settings.HudShape.RECTANGLE || edge >= radius || radius == 0) return 0;
        if (shape == Settings.HudShape.CHAMFERED) return radius - edge;
        double dy = radius - edge - .5;
        return Math.max(0, (int) Math.ceil(radius - Math.sqrt(radius * radius - dy * dy)));
    }

    public static void draw(GuiGraphicsExtractor g, Settings settings, int width, int height) {
        int alpha = HudLayout.background(settings) & 0xFF000000;
        for (int row = 0; row < height; ) {
            int inset = inset(settings.hudShape, width, height, row);
            int next = spanBottom(settings.hudShape, width, height, 0, width, row, height);
            if (settings.hudRaised) {
                if (row < 2) next = Math.min(next, 2);
                else if (row < height - 2) next = Math.min(next, height - 2);
            }
            boolean sides = settings.hudRaised && row >= 2 && row < height - 2;
            int bevel = settings.hudRaised ? row < 2 ? 0x34394F : row >= height - 2 ? 0x080A12 : 0x111420 : 0x111420;
            int side = sides ? 1 : 0;
            surface(g, settings, inset + side, row, width - inset - side, next, width, alpha, bevel);
            if (sides) {
                surface(g, settings, inset, row, inset + 1, next, width, alpha, 0x292F44);
                surface(g, settings, width - inset - 1, row, width - inset, next, width, alpha, 0x080A12);
            }
            row = next;
        }
    }

    private static void surface(GuiGraphicsExtractor g, Settings settings, int left, int top, int right, int bottom,
                                int width, int alpha, int bevel) {
        if (right <= left || bottom <= top) return;
        int a = tint(settings.hudBackgroundColors.first(), bevel);
        int b = settings.hudBackgroundGradient ? tint(settings.hudBackgroundColors.last(), bevel) : a;
        if (a == b) g.fill(left, top, right, bottom, alpha | a);
        else com.darkmusic.client.gui.UiTheme.gradient(g, left, top, right - left, bottom - top,
            alpha | (com.darkmusic.client.gui.UiTheme.mix(a, b, left / (float) width) & 0xFFFFFF),
            alpha | (com.darkmusic.client.gui.UiTheme.mix(a, b, right / (float) width) & 0xFFFFFF));
    }

    private static int tint(int color, int bevel) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int value = (color >> shift & 255) + (bevel >> shift & 255) - (0x111420 >> shift & 255);
            result |= Math.clamp(value, 0, 255) << shift;
        }
        return result;
    }

    /**
     * Clip spectrum corners to the chosen silhouette without allocating render-time masks.
     */
    public static void fill(GuiGraphicsExtractor g, Settings.HudShape shape, int width, int height, int left, int top, int right, int bottom, int color) {
        if (shape == Settings.HudShape.RECTANGLE) {
            g.fill(left, top, right, bottom, color);
            return;
        }
        for (int row = top; row < bottom; ) {
            int inset = inset(shape, width, height, row);
            int start = Math.max(left, inset), end = Math.min(right, width - inset);
            int next = spanBottom(shape, width, height, left, right, row, bottom);
            if (end > start) g.fill(start, row, end, next, color);
            row = next;
        }
    }

    /**
     * Adjacent rows with identical clipped edges are one rectangle, including at HUD corners.
     * Splitting them makes GUI overlap-layer searches quadratic for dense beveled waves.
     */
    public static int spanBottom(Settings.HudShape shape, int width, int height, int left, int right, int top, int bottom) {
        int inset = inset(shape, width, height, top);
        int start = Math.max(left, inset), end = Math.min(right, width - inset);
        int next = top + 1;
        while (next < bottom) {
            int candidate = inset(shape, width, height, next);
            if (Math.max(left, candidate) != start || Math.min(right, width - candidate) != end) break;
            // All interior rows have zero inset; only the two corner bands need inspection.
            next = next >= 6 && next < height - 6 ? Math.min(bottom, height - 6) : next + 1;
        }
        return next;
    }
}
