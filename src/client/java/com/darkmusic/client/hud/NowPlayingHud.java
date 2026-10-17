package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class NowPlayingHud {
    private static final com.darkmusic.client.gui.SpectrumRenderer LIVE = new com.darkmusic.client.gui.SpectrumRenderer();
    private static final com.darkmusic.client.gui.SpectrumRenderer PREVIEW = new com.darkmusic.client.gui.SpectrumRenderer();

    private static final TitleMarquee LIVE_TITLE = new TitleMarquee(), PREVIEW_TITLE = new TitleMarquee();

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
        return HudLayout.bounds(app.settings, width, height);
    }

    /**
     * The layout editor uses the exact gameplay renderer, including image fitting and scale.
     */
    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, boolean preview) {
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        renderAt(app, g, b.x(), b.y(), b.scale(), preview, false, null);
    }

    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, com.darkmusic.client.gui.HorizontalText text) {
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        renderAt(app, g, b.x(), b.y(), b.scale(), true, false, text);
    }

    public static void renderPreview(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, double previewScale, com.darkmusic.client.gui.HorizontalText text) {
        if (!app.settings.hudEnabled) return;
        var layout = HudLayout.content(app.settings, Integer.MAX_VALUE);
        double scale = Math.min(previewScale, Math.min(width / (double) layout.width(), height / (double) layout.height()));
        renderAt(app, g, x + (width - layout.width() * scale) / 2, y + (height - layout.height() * scale) / 2, scale, true, true, text);
    }

    private static void renderAt(DarkMusicClient app, GuiGraphicsExtractor g, double x, double y, double scale, boolean preview, boolean settingsPreview, com.darkmusic.client.gui.HorizontalText text) {
        var mc = Minecraft.getInstance();
        var state = app.playback.state();
        var track = state.track();
        if (track == null && !preview) return;
        if (track == null && !app.library.snapshot().tracks().isEmpty())
            track = app.library.snapshot().tracks().getFirst();
        var art = app.settings.hudArtwork && track != null ? app.artwork.get(track) : null;
        var layout = HudLayout.content(app.settings, Integer.MAX_VALUE);
        int w = layout.width(), h = layout.height(), main = layout.mainY();
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) x, (float) y);
            g.pose().scale((float) scale);
            g.fill(0, 0, w, h, HudLayout.background(app.settings));
            int textX = app.settings.hudArtwork ? 42 : 6;
            if (art != null) com.darkmusic.client.library.ArtworkCache.draw(g, art, 5, main + 3, 32);
            String title = track == null ? Text.get("text.darkmusic.song_title") : track.title();
            if (text != null) text.transform(x, y, scale);
            (preview ? PREVIEW_TITLE : LIVE_TITLE).draw(g, mc.font, title, textX, main + 6, Math.max(1, w - textX - 6), 0xFFB5B0FF);
            String detail = (state.track() == null ? Text.get("text.darkmusic.preview") : com.darkmusic.client.gui.MusicScreen.label(state.status().name())) + " | " + (track == null ? Text.get("text.darkmusic.artist") : track.artist());
            if (text != null)
                text.draw(g, mc.font, "hud_detail", detail, textX, main + 21, Math.max(1, w - textX - 6), 0xFFE0DDEE);
            else
                g.text(mc.font, mc.font.plainSubstrByWidth(detail, Math.max(1, w - textX - 6)), textX, main + 21, 0xFFE0DDEE);
            if (app.settings.hudProgress) {
                double progress = state.duration() > 0 ? Math.clamp(state.position() / state.duration(), 0, 1) : preview ? .4 : 0;
                g.fill(6, main + 39, w - 6, main + 42, 0xFF34394F);
                com.darkmusic.client.gui.ColorStrip.draw(g, app.settings.progressColors, 6, main + 39, w - 12, 3, (int) ((w - 12) * progress));
            }
            if (layout.spectrum())
                (settingsPreview ? PREVIEW : LIVE).bars(app, g, 0, layout.spectrumY(), w, HudLayout.SPECTRUM_HEIGHT, app.settings.spectrumDirection == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD, settingsPreview);
        } finally {
            if (text != null) text.transform(0, 0, 1);
            g.pose().popMatrix();
        }
    }
}
