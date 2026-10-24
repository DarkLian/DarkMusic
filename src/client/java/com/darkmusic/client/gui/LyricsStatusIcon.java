package com.darkmusic.client.gui;

import com.darkmusic.client.lyrics.LyricsInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Availability symbols use the same smooth icon strokes regardless of the selected font.
 */
final class LyricsStatusIcon {
    private LyricsStatusIcon() {
    }

    static void draw(GuiGraphicsExtractor g, Font font, LyricsInfo info, int x, int y, int size) {
        if (info.state() == LyricsInfo.State.AVAILABLE) {
            UiIcon.CHECK.draw(g, x, y, size, UiStatus.lyricsColor(info));
        } else if (info.state() == LyricsInfo.State.UNAVAILABLE || info.evidence() == LyricsInfo.Evidence.FAILED) {
            g.pose().pushMatrix();
            g.pose().translate(x + size / 2f, y + size / 2f);
            g.pose().rotate((float) (Math.PI / 4));
            UiIcon.ADD.draw(g, -size / 2, -size / 2, size, UiStatus.lyricsColor(info));
            g.pose().popMatrix();
        } else {
            g.text(font, "?", x + (size - font.width("?")) / 2, y + (size - font.lineHeight) / 2, UiStatus.lyricsColor(info));
        }
    }
}
