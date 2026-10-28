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

    void draw(GuiGraphicsExtractor g, Font font, String current, String next, int width, boolean showNext, int color, int nextColor, int plate) {
        if (this.font != font || language != Language.getInstance() || this.width != width || !this.current.equals(current) || !this.next.equals(next)) {
            this.current = current;
            this.next = next;
            this.width = width;
            this.font = font;
            language = Language.getInstance();
            int available = Math.max(0, width - 2 * HudLyricsLayout.PADDING);
            currentLines = HudLyricsLayout.wrap(current, available, font::width);
            nextLines = HudLyricsLayout.wrap(next, available, font::width);
        }
        g.enableScissor(0, 0, width, HudLyricsLayout.height(showNext));
        try {
            int y = HudLyricsLayout.PADDING;
            band(g, font, currentLines, width, y, HudLyricsLayout.CURRENT_STEP, 0xff000000 | color, plate);
            if (showNext)
                band(g, font, nextLines, width, y + HudLyricsLayout.LINES * HudLyricsLayout.CURRENT_STEP + HudLyricsLayout.GAP,
                    HudLyricsLayout.NEXT_STEP, 0xff000000 | nextColor, plate);
        } finally {
            g.disableScissor();
        }
    }

    /**
     * Whole-pixel line positions keep the pixel font sharp; each line gets a plate sized to its text.
     */
    private static void band(GuiGraphicsExtractor g, Font font, List<String> lines, int width, int y, int step, int color, int plate) {
        int top = y + (HudLyricsLayout.LINES - lines.size()) * step / 2;
        for (int i = 0; i < lines.size(); i++) {
            int lineWidth = font.width(lines.get(i)), left = (width - lineWidth) / 2, lineY = top + i * step;
            int plateTop = lineY - (step - 9) / 2;
            HudText.plate(g, left - 4, plateTop, left + lineWidth + 4, plateTop + step, plate);
        }
        for (int i = 0; i < lines.size(); i++)
            g.text(font, lines.get(i), (width - font.width(lines.get(i))) / 2, top + i * step, color);
    }
}
