package com.darkmusic.client.render;

import com.darkmusic.client.lyrics.LyricsInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Availability symbols use the same smooth icon strokes regardless of the selected font.
 * Each state has its own shape, so color is never the only cue, and none resembles a remove button.
 */
public final class LyricsStatusIcon {
    public enum Kind {AVAILABLE, INSTRUMENTAL, NONE, FAILED, PENDING}

    private LyricsStatusIcon() {
    }

    public static Kind kind(LyricsInfo info) {
        if (info.state() == LyricsInfo.State.AVAILABLE) return Kind.AVAILABLE;
        if (info.evidence() == LyricsInfo.Evidence.FAILED) return Kind.FAILED;
        if (LyricsInfo.INSTRUMENTAL.equals(info)) return Kind.INSTRUMENTAL;
        if (info.state() == LyricsInfo.State.UNAVAILABLE) return Kind.NONE;
        return Kind.PENDING;
    }

    public static void draw(GuiGraphicsExtractor g, Font font, LyricsInfo info, int x, int y, int size) {
        int color = UiStatus.lyricsColor(info);
        switch (kind(info)) {
            case AVAILABLE -> UiIcon.CHECK.draw(g, x, y, size, color);
            case INSTRUMENTAL -> UiIcon.MUSIC.draw(g, x, y, size, color);
            case FAILED -> UiIcon.WARNING.draw(g, x, y, size, color);
            case NONE -> {
                int inset = Math.max(2, size / 4), thickness = Math.max(1, size / 8);
                int middle = y + size / 2;
                g.fill(x + inset, middle - thickness / 2, x + size - inset, middle - thickness / 2 + thickness + 1, color);
            }
            case PENDING ->
                g.text(font, "?", x + (size - font.width("?")) / 2, y + (size - font.lineHeight) / 2, color);
        }
    }
}
