package com.darkmusic.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Original vector-master icons, rasterized locally; one textured quad per icon.
 */
public enum UiIcon {
    MUSIC, PLAY, PAUSE, PREVIOUS, NEXT, STOP, SHUFFLE, REPEAT, REPEAT_ONE,
    VOLUME, SPEED, LOCATE, FOLDER, REFRESH, DOWNLOAD, SETTINGS, LYRICS, PLAYLIST,
    ARTIST, ALBUM, SEARCH, MORE, BACK, ADD, DELETE, EDIT, CHECK, WARNING, TOOLS, PREVIEW, REPEAT_ALL, HEART, HEART_FILLED, LINK, CHAIN, MUTED, SEEK_BACK, SEEK_FORWARD, HISTORY, PIP;

    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("darkmusic", "textures/gui/icons.png");
    public static final int CELL = 64, ATLAS_WIDTH = CELL * values().length;

    public static UiIcon forLabel(String label) {
        String[] keys = {"text.darkmusic.new", "text.darkmusic.delete", "text.darkmusic.rename", "text.darkmusic.add_songs",
            "text.darkmusic.save", "text.darkmusic.cancel", "text.darkmusic.tools", "text.darkmusic.analyze",
            "text.darkmusic.download", "download.darkmusic.listen", "download.darkmusic.stop_listening",
            "audio.darkmusic.title", "environment.title", "lyrics.darkmusic.title", "text.darkmusic.choose_music_folder",
            "text.darkmusic.edit_hud_layout", "reset.darkmusic.button"};
        UiIcon[] icons = {ADD, DELETE, EDIT, ADD, CHECK, BACK, TOOLS, SEARCH, DOWNLOAD, PLAY, STOP,
            VOLUME, VOLUME, LYRICS, FOLDER, EDIT, WARNING};
        for (int i = 0; i < keys.length; i++) {
            String text = com.darkmusic.client.text.Text.get(keys[i]);
            if (label.equals(text) || label.startsWith(text + ":")) return icons[i];
        }
        return null;
    }

    /**
     * The Back glyph turned by quarter turns: 0 left, 1 up, 2 right, 3 down.
     */
    public static void chevron(GuiGraphicsExtractor g, int x, int y, int size, int color, int quarterTurns) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(x + size / 2f, y + size / 2f);
            g.pose().rotate((float) (Math.PI / 2 * quarterTurns));
            BACK.draw(g, -size / 2, -size / 2, size, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    public void draw(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int enlarged = Math.max(size + 1, Math.round(size * 1.15f));
        x -= (enlarged - size) / 2;
        y -= (enlarged - size) / 2;
        if (this == PAUSE) {
            // Keep the atlas glyph's original envelope; widen the strokes inward only.
            int left = x + Math.round(enlarged * 21 / 64f), right = x + Math.round(enlarged * 43 / 64f);
            int top = y + Math.round(enlarged * 15 / 64f), bottom = y + Math.round(enlarged * 49 / 64f);
            // Small controls must retain a gap when stroke widths round to whole pixels.
            int bar = Math.max(1, Math.min(Math.round(enlarged * 7 / 64f), (right - left - 1) / 2));
            g.fill(left, top, left + bar, bottom, color);
            g.fill(right - bar, top, right, bottom, color);
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, ordinal() * CELL, 0,
            enlarged, enlarged, CELL, CELL, ATLAS_WIDTH, CELL, color);
    }
}
