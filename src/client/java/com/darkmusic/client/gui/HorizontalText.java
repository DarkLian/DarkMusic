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
        draw(g, font, key, text, x, y, width, color, false, 0, color);
    }

    public void drawUpdating(GuiGraphicsExtractor g, Font font, String key, String text, int x, int y, int width, int color) {
        draw(g, font, key, text, x, y, width, color, true, 0, color);
    }

    public void drawStatus(GuiGraphicsExtractor g, Font font, String key, String status, String detail, int x, int y, int width, int statusColor, int detailColor) {
        String text = status + (status.isBlank() || detail.isBlank() ? "" : " | ") + detail;
        draw(g, font, key, text, x, y, width, detailColor, true, status.length(), statusColor);
    }

    private void draw(GuiGraphicsExtractor g, Font font, String key, String text, int x, int y, int width, int color, boolean preserveOffset, int statusLength, int statusColor) {
        if (width <= 0) return;
        Line line = lines.computeIfAbsent(key, k -> new Line());
        if (!Objects.equals(line.text, text) || line.font != font) {
            line.update(text, font.width(text), preserveOffset);
            line.font = font;
        }
        // Clipped ends show an ellipsis; panning still reaches every character.
        int mark = line.measured > width ? font.width(ELLIPSIS) : 0;
        line.maximum = Math.max(0, line.measured - width + mark);
        line.offset = clamp(line.offset, line.maximum);
        zones.add(new Zone(key, (int) Math.floor(originX + x * scale), (int) Math.floor(originY + y * scale), (int) Math.ceil(width * scale), (int) Math.ceil((font.lineHeight + 2) * scale)));
        while (lines.size() > 128) lines.remove(lines.keySet().iterator().next());
        int offset = (int) Math.round(line.offset);
        boolean before = mark > 0 && offset > 0, after = mark > 0 && offset < line.maximum;
        int start = x + (before ? mark : 0), end = x + width - (after ? mark : 0);
        if (end <= start) return;
        if (before) g.text(font, ELLIPSIS, x, y, color);
        if (after) g.text(font, ELLIPSIS, end, y, color);
        g.enableScissor(start, y, end, y + font.lineHeight + 2);
        if (line.visible == null || line.paintedOffset != offset || line.paintedWidth != end - start) {
            String prefix = font.plainSubstrByWidth(text, offset);
            line.visible = font.plainSubstrByWidth(text.substring(prefix.length()), end - start + font.lineHeight + 2);
            line.shift = offset - font.width(prefix);
            line.visibleStart = prefix.length();
            line.paintedOffset = offset;
            line.paintedWidth = end - start;
        }
        int visibleStart = line.visibleStart;
        int colored = Math.clamp(statusLength - visibleStart, 0, line.visible.length());
        if (colored > 0) {
            String status = line.visible.substring(0, colored);
            g.text(font, status, start - line.shift, y, statusColor);
            g.text(font, line.visible.substring(colored), start - line.shift + font.width(status), y, color);
        } else g.text(font, line.visible, start - line.shift, y, color);
        g.disableScissor();
    }

    static final String ELLIPSIS = "…";

    /**
     * Fits one non-interactive line, ending with an ellipsis instead of a cut glyph.
     */
    public static String ellipsize(Font font, String text, int width) {
        return ellipsize(text, width, font::width, (value, limit) -> font.plainSubstrByWidth(value, limit));
    }

    static String ellipsize(String text, int width, java.util.function.ToIntFunction<String> measure,
                            java.util.function.BiFunction<String, Integer, String> prefix) {
        if (text == null || text.isEmpty() || measure.applyAsInt(text) <= width) return text == null ? "" : text;
        int mark = measure.applyAsInt(ELLIPSIS);
        if (width < mark) return "";
        String kept = prefix.apply(text, width - mark);
        // Never split a surrogate pair at the cut.
        if (!kept.isEmpty() && Character.isHighSurrogate(kept.charAt(kept.length() - 1)))
            kept = kept.substring(0, kept.length() - 1);
        return kept.stripTrailing() + ELLIPSIS;
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

    static final class Line {
        Font font;
        String text, visible;
        int measured, maximum, paintedOffset = -1, paintedWidth, shift, visibleStart;
        double offset;

        void update(String text, int measured, boolean preserveOffset) {
            this.text = text;
            this.measured = measured;
            if (!preserveOffset) offset = 0;
            visible = null;
        }
    }

    private record Zone(String key, int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
