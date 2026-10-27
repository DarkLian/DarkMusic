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
    private static final float[] LIVE_ART_LEVELS = new float[64], PREVIEW_ART_LEVELS = new float[64];

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
        var cover = app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK ? HudImages.cover(app, track) : null;
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
        var art = (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK || app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.CLASSIC && app.settings.hudArtwork) ? HudImages.cover(app, track) : null;
        var layout = content(app);
        int w = layout.width(), h = layout.height(), main = layout.mainY();
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) x, (float) y);
            g.pose().scale((float) scale);
            if (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.SPECTRUM) {
                if (layout.spectrum())
                    (settingsPreview ? PREVIEW : LIVE).bars(app, g, 0, 0, w, layout.spectrumHeight(),
                        app.settings.spectrumDirection == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD, settingsPreview);
                return;
            }
            if (app.settings.hudStyle == com.darkmusic.client.config.Settings.HudStyle.ARTWORK) {
                HudSurface.draw(g, app.settings, w, h);
                var artworkLayout = ArtworkHudLayout.of(app.settings, art == null ? 1 : art.contentWidth() - 2 * art.menuInset(), art == null ? 1 : art.contentHeight());
                g.pose().pushMatrix();
                g.enableScissor(artworkLayout.artworkX(), artworkLayout.artworkY(),
                    artworkLayout.artworkX() + artworkLayout.artworkWidth(), artworkLayout.artworkY() + artworkLayout.artworkHeight());
                if (app.settings.artworkReactive) {
                    var levels = settingsPreview ? PREVIEW_ART_LEVELS : LIVE_ART_LEVELS;
                    boolean analyzed = (settingsPreview ? PREVIEW : LIVE).copyLevels(app, levels, settingsPreview);
                    float zoom = analyzed ? CircularHudLayout.iconZoom(levels, app.settings.visualizerBars) : 1;
                    float cx = artworkLayout.artworkX() + artworkLayout.artworkWidth() / 2f;
                    float cy = artworkLayout.artworkY() + artworkLayout.artworkHeight() / 2f;
                    g.pose().translate(cx, cy);
                    g.pose().scale(zoom);
                    g.pose().translate(-cx, -cy);
                }
                HudImages.draw(g, art, artworkLayout.artworkX(), artworkLayout.artworkY(), artworkLayout.artworkWidth(), artworkLayout.artworkHeight());
                g.pose().popMatrix();
                g.disableScissor();
                if (app.settings.hudProgress) {
                    double progress = CircularHudLayout.progress(state.position(), state.duration(), preview);
                    drawProgress(app, g, track, artworkLayout.artworkX(), artworkLayout.progressY(), artworkLayout.artworkWidth(), progress);
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
                var circle = CircularHudLayout.of(app.settings);
                g.pose().pushMatrix();
                try {
                    g.pose().translate((w - circle.width()) / 2f, 0);
                    (settingsPreview ? PREVIEW_CIRCLE : LIVE_CIRCLE).render(app, g, track, state, preview, settingsPreview);
                } finally {
                    g.pose().popMatrix();
                }
                return;
            }
            HudSurface.draw(g, app.settings, w, h);
            int textX = app.settings.hudArtwork ? 42 : 6;
            int scrim = HudLayout.textScrim(app.settings);
            // Kept clear of the card's chamfer, rounding and raised bevel.
            if (scrim != 0) g.fill(Math.max(4, textX - 3), main + 3, w - 4, main + 33, scrim);
            if (app.settings.hudArtwork) HudImages.draw(g, art, 5, main + 3, 32, 32);
            String title = track == null ? Text.get("text.darkmusic.song_title") : track.title();
            if (text != null) text.transform(x, y, scale);
            (preview ? PREVIEW_TITLE : LIVE_TITLE).draw(g, mc.font, title, textX, main + 6, Math.max(1, w - textX - 6), 0xFFB5B0FF);
            String detail = (state.track() == null ? Text.get("text.darkmusic.preview") : com.darkmusic.client.gui.MusicScreen.label(state.status().name())) + " | " + (track == null ? Text.get("text.darkmusic.artist") : track.artist());
            if (text != null)
                text.draw(g, mc.font, "hud_detail", detail, textX, main + 21, Math.max(1, w - textX - 6), 0xFFE0DDEE);
            else
                g.text(mc.font, com.darkmusic.client.gui.HorizontalText.ellipsize(mc.font, detail, Math.max(1, w - textX - 6)), textX, main + 21, 0xFFE0DDEE);
            if (app.settings.hudProgress) {
                double progress = state.duration() > 0 ? Math.clamp(state.position() / state.duration(), 0, 1) : preview ? .4 : 0;
                drawProgress(app, g, track, 6, main + 39, w - 12, progress);
            }
            if (layout.spectrum())
                (settingsPreview ? PREVIEW : LIVE).bars(app, g, 0, layout.spectrumY(), w, layout.spectrumHeight(), app.settings.spectrumDirection == com.darkmusic.client.config.Settings.SpectrumDirection.DOWNWARD, settingsPreview, h);
        } finally {
            if (text != null) text.transform(0, 0, 1);
            g.pose().popMatrix();
        }
    }

    private static void drawProgress(DarkMusicClient app, GuiGraphicsExtractor g, com.darkmusic.client.library.Track track,
                                     int x, int y, int width, double progress) {
        int height = app.settings.waveformProgress ? app.settings.waveform.height + 2 : 3;
        var waveform = app.settings.waveformProgress && app.waveforms != null ? app.waveforms.get(track) : null;
        if (com.darkmusic.client.gui.WaveformProgress.draw(g, waveform, app.settings.progressColors, app.settings.waveform, x, y + 1, width, Math.max(3, height - 2),
            progress, app.settings.progressFinish)) return;
        int railY = y + (height - 3) / 2;
        com.darkmusic.client.gui.ColorStrip.track(g, x, railY, width, 3, app.settings.progressFinish);
        com.darkmusic.client.gui.ColorStrip.draw(g, app.settings.progressColors, x, railY, width, 3,
            (int) (width * progress), app.settings.progressFinish);
    }

}
