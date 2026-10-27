package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared artwork and two-line song summary for actions and drag previews.
 */
final class SongCard {
    static final int HEIGHT = 64;

    private SongCard() {
    }

    static void draw(DarkMusicClient app, GuiGraphicsExtractor g, Font font, HorizontalText text,
                     Track track, String key, int x, int y, int width) {
        UiTheme.panel(g, x, y, width, HEIGHT);
        int size = HEIGHT - 12;
        var cover = app.artwork.thumbnail(track);
        if (cover != null) ArtworkCache.drawMenu(g, cover, x + 6, y + 6, size);
        else UiIcon.MUSIC.draw(g, x + 18, y + 18, size - 24, UiTheme.MUTED);
        int textX = x + size + 14, textWidth = Math.max(1, width - size - 22);
        int titleY = y + (HEIGHT - font.lineHeight * 2 - 4) / 2;
        text.draw(g, font, key + "_title", track.title(), textX, titleY, textWidth, UiTheme.TEXT);
        text.draw(g, font, key + "_artist", track.artist(), textX, titleY + font.lineHeight + 4, textWidth, UiTheme.SECONDARY);
    }
}
