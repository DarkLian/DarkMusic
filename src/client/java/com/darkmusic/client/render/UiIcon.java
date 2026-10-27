package com.darkmusic.client.render;

import com.darkmusic.client.text.Text;
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
            String text = Text.get(keys[i]);
            if (label.equals(text) || label.startsWith(text + ":")) return icons[i];
        }
        return null;
    }

    /**
     * A pixel-aligned chevron in a size-by-size box: 0 left, 1 up, 2 right, 3 down.
     * Drawn from whole-pixel steps rather than a rotated texture, so small arrows stay crisp.
     */
    public static void chevron(GuiGraphicsExtractor g, int x, int y, int size, int color, int direction) {
        int arm = Math.max(1, (size - 1) / 2), span = 2 * arm + 1, thickness = size >= 9 ? 2 : 1;
        int depth = arm + thickness;
        boolean horizontal = direction % 2 == 0;
        // Center the glyph's real extent in the box.
        int ox = x + (size - (horizontal ? depth : span)) / 2, oy = y + (size - (horizontal ? span : depth)) / 2;
        for (int k = 0; k <= arm; k++) {
            int along = switch (direction) {
                case 0, 1 -> arm - k;
                default -> k;
            };
            if (horizontal) {
                g.fill(ox + along, oy + k, ox + along + thickness, oy + k + 1, color);
                g.fill(ox + along, oy + span - 1 - k, ox + along + thickness, oy + span - k, color);
            } else {
                g.fill(ox + k, oy + along, ox + k + 1, oy + along + thickness, color);
                g.fill(ox + span - 1 - k, oy + along, ox + span - k, oy + along + thickness, color);
            }
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
