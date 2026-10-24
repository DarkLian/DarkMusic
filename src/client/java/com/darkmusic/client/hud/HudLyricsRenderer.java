package com.darkmusic.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;

import java.util.List;

/**
 * Reflow only when content, font, or available width changes, never for each spectrum frame.
 */
final class HudLyricsRenderer {
    private String current = "", next = "";
    private int width = -1;
    private Font font;
    private Language language;
    private List<String> currentLines = List.of(), nextLines = List.of();

    void draw(GuiGraphicsExtractor g, Font font, String current, String next, int width, int top, boolean showNext, int color, int nextColor) {
        if (this.font != font || language != Language.getInstance() || this.width != width || !this.current.equals(current) || !this.next.equals(next)) {
            this.current = current;
            this.next = next;
            this.width = width;
            this.font = font;
            language = Language.getInstance();
            int available = Math.max(0, width - 2 * HudLyricsLayout.PADDING);
            currentLines = HudLyricsLayout.wrap(current, (int) (available / HudLyricsLayout.CURRENT_SCALE), font::width);
            nextLines = HudLyricsLayout.wrap(next, available, font::width);
        }
        g.enableScissor(0, top, width, top + HudLyricsLayout.height(showNext));
        try {
            int y = top + HudLyricsLayout.PADDING;
            band(g, font, currentLines, width, y, HudLyricsLayout.CURRENT_STEP, HudLyricsLayout.CURRENT_SCALE, 0xff000000 | color);
            if (showNext)
                band(g, font, nextLines, width, y + HudLyricsLayout.LINES * HudLyricsLayout.CURRENT_STEP + HudLyricsLayout.GAP,
                    HudLyricsLayout.NEXT_STEP, 1, 0xff000000 | nextColor);
        } finally {
            g.disableScissor();
        }
    }

    private static void band(GuiGraphicsExtractor g, Font font, List<String> lines, int width, int y, int step, float scale, int color) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(width / 2f, y + (HudLyricsLayout.LINES - lines.size()) * step / 2f);
            g.pose().scale(scale);
            for (int i = 0; i < lines.size(); i++)
                g.centeredText(font, lines.get(i), 0, Math.round(i * step / scale), color);
        } finally {
            g.pose().popMatrix();
        }
    }
}
