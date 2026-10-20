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

    private static final CircularHudRenderer LIVE_CIRCLE = new CircularHudRenderer(), PREVIEW_CIRCLE = new CircularHudRenderer();

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
        return HudLayout.bounds(app.settings, width, height, content(app));
    }

    public static HudLayout.Content content(DarkMusicClient app) {
        var track = app.playback.state().track();
        if (track == null && !app.library.snapshot().tracks().isEmpty())
            track = app.library.snapshot().tracks().getFirst();
        var cover = track != null && app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK ? app.artwork.get(track) : null;
        return HudLayout.content(app.settings, Integer.MAX_VALUE, cover == null ? 1 : cover.contentWidth() - 2 * cover.menuInset(), cover == null ? 1 : cover.contentHeight());
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
        var layout = content(app);
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
        var art = (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK || app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.CLASSIC && app.settings.hudArtwork) && track != null ? app.artwork.get(track) : null;
        var layout = content(app);
        int w = layout.width(), h = layout.height(), main = layout.mainY();
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) x, (float) y);
            g.pose().scale((float) scale);
            if (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK) {
                HudSurface.draw(g, app.settings, w, h);
                var artworkLayout = ArtworkHudLayout.of(app.settings, art == null ? 1 : art.contentWidth() - 2 * art.menuInset(), art == null ? 1 : art.contentHeight());
                if (art != null) com.darkmusic.client.library.ArtworkCache.drawMenu(g, art,
                    artworkLayout.artworkX(), artworkLayout.artworkY(), artworkLayout.artworkWidth(), artworkLayout.artworkHeight());
                else g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    Identifier.fromNamespaceAndPath("darkmusic", "icon.png"), artworkLayout.artworkX(), artworkLayout.artworkY(), 0, 0,
                    artworkLayout.artworkWidth(), artworkLayout.artworkHeight(), 512, 512, 512, 512);
                if (app.settings.hudProgress) {
                    double progress = CircularHudLayout.progress(state.position(), state.duration(), preview);
                    com.darkmusic.client.gui.ColorStrip.track(g, artworkLayout.artworkX(), artworkLayout.progressY(), artworkLayout.artworkWidth(), 3, app.settings.progressFinish);
                    com.darkmusic.client.gui.ColorStrip.draw(g, app.settings.progressColors, artworkLayout.artworkX(), artworkLayout.progressY(),
                        artworkLayout.artworkWidth(), 3, (int) (artworkLayout.artworkWidth() * progress), app.settings.progressFinish);
                }
                if (layout.spectrum()) {
                    var renderer = settingsPreview ? PREVIEW : LIVE;
                    if (artworkLayout.sideways(app.settings)) {
                        boolean left = app.settings.artworkSpectrumPosition == com.darkmusic.client.config.Settings.ArtworkSpectrumPosition.LEFT;
                        g.pose().pushMatrix();
                        try {
                            g.pose().translate(artworkLayout.spectrumX() + (left ? 0 : app.settings.artworkSpectrumHeight),
                                artworkLayout.spectrumY() + (left ? artworkLayout.artworkHeight() : 0));
                            g.pose().rotate((float) (left ? -Math.PI / 2 : Math.PI / 2));
                            renderer.bars(app, g, 0, 0, artworkLayout.artworkHeight(), app.settings.artworkSpectrumHeight, false, settingsPreview);
                        } finally {
                            g.pose().popMatrix();
                        }
                    } else renderer.bars(app, g, artworkLayout.spectrumX(), artworkLayout.spectrumY(),
                        artworkLayout.artworkWidth(), app.settings.artworkSpectrumHeight,
                        app.settings.spectrumDirection == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD, settingsPreview);
                }
                return;
            }
            if (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.CIRCULAR) {
                (settingsPreview ? PREVIEW_CIRCLE : LIVE_CIRCLE).render(app, g, track, state, preview, settingsPreview);
                lyrics(app, g, w, h);
                return;
            }
            HudSurface.draw(g, app.settings, w, h);
            int textX = app.settings.hudArtwork ? 42 : 6;
            if (art != null) com.darkmusic.client.library.ArtworkCache.drawMenu(g, art, 5, main + 3, 32);
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
                com.darkmusic.client.gui.ColorStrip.track(g, 6, main + 39, w - 12, 3, app.settings.progressFinish);
                com.darkmusic.client.gui.ColorStrip.draw(g, app.settings.progressColors, 6, main + 39, w - 12, 3, (int) ((w - 12) * progress), app.settings.progressFinish);
            }
            if (layout.spectrum())
                (settingsPreview ? PREVIEW : LIVE).bars(app, g, 0, layout.spectrumY(), w, layout.spectrumHeight(), app.settings.spectrumDirection == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD, settingsPreview, h);
            lyrics(app, g, w, h);
        } finally {
            if (text != null) text.transform(0, 0, 1);
            g.pose().popMatrix();
        }
    }

    private static void lyrics(DarkMusicClient app, GuiGraphicsExtractor g, int width, int height) {
        if (!app.settings.lyricsEnabled || !app.settings.lyricsHud) return;
        var audio = app.playback.state();
        app.lyrics.request(audio.track());
        var data = app.lyrics.get(audio.track());
        int current = data.current(audio.position());
        int y = height - HudLayout.lyricsHeight(app.settings) + 4;
        var font = Minecraft.getInstance().font;
        String line = current >= 0 ? data.lines().get(current).text() : Text.get("lyrics.darkmusic.missing");
        g.centeredText(font, CircularHudRenderer.ellipsis(line, width - 12, font::width), width / 2, y, 0xffddd9ff);
        if (app.settings.lyricsNextLine && current + 1 < data.lines().size())
            g.centeredText(font, CircularHudRenderer.ellipsis(data.lines().get(current + 1).text(), width - 12, font::width), width / 2, y + 12, 0xffaaa7bd);
    }
}
