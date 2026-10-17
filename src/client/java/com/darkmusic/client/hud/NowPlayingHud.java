package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class NowPlayingHud {
    private NowPlayingHud() {
    }

    public static void register(DarkMusicClient app) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", "now_playing"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (app.settings == null || !app.settings.hudEnabled || app.playback == null || mc.gui.hud.isHidden() || mc.level == null || mc.gui.screen() != null)
                return;
            if (app.playback.state().track() != null) render(app, g, false);
        });
    }

    public static HudLayout.Bounds bounds(DarkMusicClient app, int width, int height) {
        return HudLayout.bounds(width, height, app.settings.hudX, app.settings.hudY, app.settings.hudScale, app.settings.hudProgress);
    }

    /**
     * The layout editor uses the exact gameplay renderer, including image fitting and scale.
     */
    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, boolean preview) {
        var mc = Minecraft.getInstance();
        var state = app.playback.state();
        var track = state.track();
        if (track == null && !preview) return;
        if (track == null && !app.library.snapshot().tracks().isEmpty())
            track = app.library.snapshot().tracks().getFirst();
        var art = app.settings.hudArtwork && track != null ? app.artwork.get(track) : null;
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        int w = b.contentWidth(), h = app.settings.hudProgress ? 46 : 38;
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) b.x(), (float) b.y());
            g.pose().scale((float) b.scale());
            g.fill(0, 0, w, h, 0xDD111420);
            int textX = art == null ? 6 : 42;
            if (art != null) g.blit(RenderPipelines.GUI_TEXTURED, art, 5, 3, 0, 0, 32, 32, 256, 256, 256, 256);
            g.text(mc.font, mc.font.plainSubstrByWidth(track == null ? "Song title" : track.title(), Math.max(1, w - textX - 6)), textX, 6, 0xFFB5B0FF);
            String detail = (state.track() == null ? "Preview" : com.darkmusic.client.gui.MusicScreen.label(state.status().name())) + " | " + (track == null ? "Artist" : track.artist());
            g.text(mc.font, mc.font.plainSubstrByWidth(detail, Math.max(1, w - textX - 6)), textX, 21, 0xFFE0DDEE);
            if (app.settings.hudProgress) {
                double progress = state.duration() > 0 ? Math.clamp(state.position() / state.duration(), 0, 1) : preview ? .4 : 0;
                g.fill(6, 39, w - 6, 42, 0xFF34394F);
                g.fill(6, 39, 6 + (int) ((w - 12) * progress), 42, 0xFFB5B0FF);
            }
        } finally {
            g.pose().popMatrix();
        }
    }
}
