package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.Track;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * One source choice and fallback for every image-bearing HUD layout.
 */
public final class HudImages {
    public static final Identifier ICON = Identifier.fromNamespaceAndPath("darkmusic", "textures/gui/hud-icon.png");

    private HudImages() {
    }

    public static ArtworkCache.Cover cover(DarkMusicClient app, Track track) {
        return track != null && app.settings.hudImage() == Settings.HudImage.ARTWORK ? app.artwork.get(track) : null;
    }

    public static void draw(GuiGraphicsExtractor g, ArtworkCache.Cover cover, int x, int y, int width, int height) {
        if (cover != null) ArtworkCache.drawMenu(g, cover, x, y, width, height);
        else {
            int size = Math.min(width, height);
            g.blit(RenderPipelines.GUI_TEXTURED, ICON, x + (width - size) / 2, y + (height - size) / 2,
                0, 0, size, size, 512, 512, 512, 512);
        }
    }

    /**
     * Center-crop the real image content to fill a circle, excluding generated gutters.
     */
    public static float circularSpan(ArtworkCache.Cover cover) {
        if (cover == null) return 1;
        int width = Math.max(1, cover.contentWidth() - 2 * cover.menuInset());
        return Math.min(width, cover.contentHeight()) / (float) cover.size();
    }
}
