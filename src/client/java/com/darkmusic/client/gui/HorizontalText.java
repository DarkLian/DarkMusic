package com.darkmusic.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, Unicode-safe panning for read-only lines; ordinary wheel scrolling stays vertical.
 */
public final class HorizontalText {
    private final LinkedHashMap<String, Line> lines = new LinkedHashMap<>(32, .75f, true);
    private final List<Zone> zones = new ArrayList<>();
    private double originX, originY, scale = 1;

    public static boolean shiftDown() {
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    public static boolean horizontalIntent(double dx, double dy) {
        return dx != 0 || (dy != 0 && shiftDown());
    }

    public static double clamp(double value, int maximum) {
        return Double.isFinite(value) ? Math.clamp(value, 0, Math.max(0, maximum)) : 0;
    }

    public void transform(double x, double y, double value) {
        originX = x;
        originY = y;
        scale = value;
    }

    public void begin() {
        zones.clear();
    }

    public void draw(GuiGraphicsExtractor g, Font font, String key, String text, int x, int y, int width, int color) {
        if (width <= 0) return;
        Line line = lines.computeIfAbsent(key, k -> new Line());
        if (!Objects.equals(line.text, text)) {
            line.text = text;
            line.measured = font.width(text);
            line.offset = 0;
            line.visible = null;
        }
        line.maximum = Math.max(0, line.measured - width);
        line.offset = clamp(line.offset, line.maximum);
        zones.add(new Zone(key, (int) Math.floor(originX + x * scale), (int) Math.floor(originY + y * scale), (int) Math.ceil(width * scale), (int) Math.ceil((font.lineHeight + 2) * scale)));
        while (lines.size() > 128) lines.remove(lines.keySet().iterator().next());
        g.enableScissor(x, y, x + width, y + font.lineHeight + 2);
        int offset = (int) Math.round(line.offset);
        if (line.visible == null || line.paintedOffset != offset || line.paintedWidth != width) {
            String prefix = font.plainSubstrByWidth(text, offset);
            line.visible = font.plainSubstrByWidth(text.substring(prefix.length()), width + font.lineHeight + 2);
            line.shift = offset - font.width(prefix);
            line.paintedOffset = offset;
            line.paintedWidth = width;
        }
        g.text(font, line.visible, x - line.shift, y, color);
        if (line.maximum > 0) {
            int thumb = Math.max(8, width * width / Math.max(1, line.measured));
            int left = x + (int) Math.round((width - thumb) * line.offset / line.maximum);
            g.fill(left, y + font.lineHeight + 1, left + thumb, y + font.lineHeight + 2, 0xFF555968);
        }
        g.disableScissor();
    }

    public boolean scroll(double x, double y, double dx, double dy, boolean shift) {
        double amount = dx != 0 ? -dx * 24 : shift ? -dy * 24 : 0;
        if (amount == 0 || !Double.isFinite(amount)) return false;
        for (int i = zones.size() - 1; i >= 0; i--) {
            var zone = zones.get(i);
            if (zone.contains(x, y) && pan(zone.key(), amount)) return true;
        }
        return false;
    }

    public boolean pan(String key, double amount) {
        Line line = lines.get(key);
        if (line == null || line.maximum == 0) return false;
        line.offset = clamp(line.offset + amount, line.maximum);
        return true;
    }

    private static final class Line {
        String text, visible;
        int measured, maximum, paintedOffset = -1, paintedWidth, shift;
        double offset;
    }

    private record Zone(String key, int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
