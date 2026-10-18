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
        int background = HudLayout.background(settings);
        if (settings.hudShape == Settings.HudShape.RECTANGLE && !settings.hudRaised) {
            g.fill(0, 0, width, height, background);
            return;
        }
        for (int row = 0; row < height; row++) {
            int inset = inset(settings.hudShape, width, height, row);
            int color = background;
            // A quiet bevel, not an opaque overlay: transparency affects every surface equally.
            if (settings.hudRaised)
                color = (background & 0xFF000000) | (row < 2 ? 0x34394F : row >= height - 2 ? 0x080A12 : 0x111420);
            boolean sideBevel = settings.hudRaised && row >= 2 && row < height - 2;
            int side = sideBevel ? 1 : 0;
            // Adjacent surfaces must not overlap: alpha blending twice would darken the bevel.
            g.fill(inset + side, row, width - inset - side, row + 1, color);
            if (sideBevel) {
                g.fill(inset, row, inset + 1, row + 1, (background & 0xFF000000) | 0x292F44);
                g.fill(width - inset - 1, row, width - inset, row + 1, (background & 0xFF000000) | 0x080A12);
            }
        }
    }

    /**
     * Clip spectrum corners to the chosen silhouette without allocating render-time masks.
     */
    public static void fill(GuiGraphicsExtractor g, Settings.HudShape shape, int width, int height, int left, int top, int right, int bottom, int color) {
        if (shape == Settings.HudShape.RECTANGLE) {
            g.fill(left, top, right, bottom, color);
            return;
        }
        int middleTop = Math.max(top, 6), middleBottom = Math.min(bottom, height - 6);
        if (middleBottom > middleTop) g.fill(left, middleTop, right, middleBottom, color);
        for (int row = top; row < bottom; row++) {
            if (row >= middleTop && row < middleBottom) continue;
            int inset = inset(shape, width, height, row);
            int start = Math.max(left, inset), end = Math.min(right, width - inset);
            if (end > start) g.fill(start, row, end, row + 1, color);
        }
    }
}
