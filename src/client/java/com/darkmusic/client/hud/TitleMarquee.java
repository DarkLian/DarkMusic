package com.darkmusic.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A clipped, seamless loop. Only overflowing titles move; playback is untouched.
 */
public final class TitleMarquee {
    private String title = "";
    private int measured, available;
    private long started;

    public static double offset(int measured, int available, long elapsedNanos) {
        if (measured <= available) return 0;
        double seconds = Math.max(0, elapsedNanos / 1_000_000_000.0 - 1);
        return seconds * 24 % (measured + 32.0);
    }

    public void draw(GuiGraphicsExtractor g, Font font, String value, int x, int y, int width, int color) {
        long now = System.nanoTime();
        if (!title.equals(value) || available != width) {
            title = value;
            measured = font.width(value);
            available = width;
            started = now;
        }
        int shift = (int) offset(measured, width, now - started);
        g.enableScissor(x, y, x + width, y + font.lineHeight + 1);
        g.text(font, value, x - shift, y, color);
        if (measured > width) g.text(font, value, x - shift + measured + 32, y, color);
        g.disableScissor();
    }
}
