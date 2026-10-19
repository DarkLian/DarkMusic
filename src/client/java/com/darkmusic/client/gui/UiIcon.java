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
    ARTIST, ALBUM, SEARCH, MORE, BACK, ADD, DELETE, EDIT, CHECK, WARNING, TOOLS, PREVIEW, REPEAT_ALL, HEART, HEART_FILLED;

    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath("darkmusic", "textures/gui/icons.png");
    public static final int CELL = 64, ATLAS_WIDTH = CELL * values().length;

    public static UiIcon forLabel(String label) {
        String[] keys = {"text.darkmusic.new", "text.darkmusic.delete", "text.darkmusic.rename", "text.darkmusic.add_songs",
            "text.darkmusic.save", "text.darkmusic.cancel", "text.darkmusic.tools", "text.darkmusic.analyze",
            "text.darkmusic.download", "download.darkmusic.listen", "download.darkmusic.stop_listening",
            "audio.darkmusic.title", "lyrics.darkmusic.title", "text.darkmusic.choose_music_folder",
            "text.darkmusic.edit_hud_layout", "reset.darkmusic.button"};
        UiIcon[] icons = {ADD, DELETE, EDIT, ADD, CHECK, BACK, TOOLS, SEARCH, DOWNLOAD, PLAY, STOP,
            VOLUME, LYRICS, FOLDER, EDIT, WARNING};
        for (int i = 0; i < keys.length; i++) {
            String text = com.darkmusic.client.text.Text.get(keys[i]);
            if (label.equals(text) || label.startsWith(text + ":")) return icons[i];
        }
        return null;
    }

    public void draw(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, ordinal() * CELL, 0,
            size, size, CELL, CELL, ATLAS_WIDTH, CELL, color);
    }
}
