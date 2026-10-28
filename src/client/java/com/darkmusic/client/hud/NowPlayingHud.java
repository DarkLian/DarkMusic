package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.ColorStrip;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.HudFade;
import com.darkmusic.client.render.HudSurface;
import com.darkmusic.client.render.SpectrumRenderer;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.WaveformProgress;
import com.darkmusic.client.text.Clock;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class NowPlayingHud {
    private static final SpectrumRenderer LIVE = new SpectrumRenderer();
    private static final SpectrumRenderer PREVIEW = new SpectrumRenderer();

    private static final CircularHudRenderer LIVE_CIRCLE = new CircularHudRenderer(), PREVIEW_CIRCLE = new CircularHudRenderer();

    private static final TitleMarquee LIVE_TITLE = new TitleMarquee(), PREVIEW_TITLE = new TitleMarquee();
    private static final float[] LIVE_ART_LEVELS = new float[64], PREVIEW_ART_LEVELS = new float[64];
    private static final HudMotion MOTION = new HudMotion();

    private NowPlayingHud() {
    }

    public static void register(DarkMusicClient app) {
        HudLayer.add(app, "now_playing", g -> {
            if (app.settings == null || !app.settings.hudEnabled || app.playback == null) return;
            var state = app.playback.state();
            if (state.track() == null) return;
            double away = MOTION.hidden(app.settings, state, System.nanoTime());
            if (away >= 1) return;
            var b = bounds(app, g.guiWidth(), g.guiHeight());
            if (app.settings.hudIdleAnimation == Settings.Motion.FADE)
                HudFade.run(1 - away, () -> renderAt(app, g, b.x(), b.y(), b.scale(), false, false, null));
            else
                renderAt(app, g, b.x() + HudMotion.exit(b.x(), b.width(), g.guiWidth(), away), b.y(), b.scale(), false, false, null);
        });
    }

    /**
     * Whether the live HUD has slid away after a pause.
     */
    public static boolean idleHidden() {
        return MOTION.fullyHidden();
    }

    /**
     * How far the live HUD has hidden while idle, 0 to 1, or 0 when the player HUD is off and so never hides.
     */
    public static double idleAmount(Settings settings) {
        return settings.hudEnabled && settings.hudIdleHide > 0 ? MOTION.amount() : 0;
    }

    /**
     * Preview stand-in. One snapshot read: a rescan may publish an empty library between two reads.
     */
    private static Track firstTrack(DarkMusicClient app) {
        var tracks = app.library.snapshot().tracks();
        return tracks.isEmpty() ? null : tracks.getFirst();
    }

    public static HudLayout.Bounds bounds(DarkMusicClient app, int width, int height) {
        return HudLayout.bounds(app.settings, width, height, content(app));
    }

    public static HudLayout.Content content(DarkMusicClient app) {
        var track = app.playback.state().track();
        if (track == null) track = firstTrack(app);
        var cover = app.settings.hudStyle == Settings.HudStyle.ARTWORK ? HudImages.cover(app, track) : null;
        return HudLayout.content(app.settings, Integer.MAX_VALUE, cover == null ? 1 : cover.contentWidth() - 2 * cover.menuInset(), cover == null ? 1 : cover.contentHeight());
    }

    /**
     * The layout editor uses the exact gameplay renderer, including image fitting and scale.
     */
    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, boolean preview) {
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        renderAt(app, g, b.x(), b.y(), b.scale(), preview, false, null);
    }

    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, HorizontalText text) {
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        renderAt(app, g, b.x(), b.y(), b.scale(), true, false, text);
    }

    public static void renderPreview(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, double previewScale, HorizontalText text) {
        if (!app.settings.hudEnabled) return;
        var layout = content(app);
        double scale = Math.min(previewScale, Math.min(width / (double) layout.width(), height / (double) layout.height()));
        renderAt(app, g, x + (width - layout.width() * scale) / 2, y + (height - layout.height() * scale) / 2, scale, true, true, text);
    }

    private static void renderAt(DarkMusicClient app, GuiGraphicsExtractor g, double x, double y, double scale, boolean preview, boolean settingsPreview, HorizontalText text) {
        var mc = Minecraft.getInstance();
        var state = app.playback.state();
        var track = state.track();
        if (track == null && !preview) return;
        if (track == null) track = firstTrack(app);
        var art = (app.settings.hudStyle == Settings.HudStyle.ARTWORK || app.settings.hudStyle == Settings.HudStyle.CLASSIC && app.settings.hudArtwork) ? HudImages.cover(app, track) : null;
        var layout = content(app);
        int w = layout.width(), h = layout.height(), main = layout.mainY();
        // A new song fades in; previews and the layout editor always show it settled.
        double arrival = preview || !app.settings.hudTransitions ? 1 : MOTION.arrival(System.nanoTime());
        int titleColor = HudMotion.fade(0xFFB5B0FF, arrival), detailColor = HudMotion.fade(0xFFE0DDEE, arrival);
        int veil = HudMotion.fade(0xFF0B0D16, 1 - arrival);
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) x, (float) y);
            g.pose().scale((float) scale);
            if (app.settings.hudStyle == Settings.HudStyle.SPECTRUM) {
                if (layout.spectrum())
                    (settingsPreview ? PREVIEW : LIVE).bars(app, g, SpectrumRenderer.Bars.at(0, 0, w, layout.spectrumHeight(),
                        app.settings.spectrumDirection == Settings.SpectrumDirection.DOWNWARD, settingsPreview));
                return;
            }
            if (app.settings.hudStyle == Settings.HudStyle.ARTWORK) {
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
                if (veil != 0)
                    g.fill(artworkLayout.artworkX(), artworkLayout.artworkY(), artworkLayout.artworkX() + artworkLayout.artworkWidth(),
                        artworkLayout.artworkY() + artworkLayout.artworkHeight(), veil);
                g.pose().popMatrix();
                g.disableScissor();
                if (app.settings.hudProgress) {
                    double progress = CircularHudLayout.progress(state.position(), state.duration(), preview);
                    drawProgress(app, g, track, artworkLayout.artworkX(), artworkLayout.progressY(), artworkLayout.artworkWidth(), progress);
                }
                if (artworkLayout.captionY() >= 0) {
                    int left = artworkLayout.artworkX(), top = artworkLayout.captionY(), width = artworkLayout.artworkWidth();
                    int scrim = HudLayout.textScrim(app.settings);
                    if (scrim != 0) g.fill(left, top + 1, left + width, top + ArtworkHudLayout.CAPTION, scrim);
                    String title = track == null ? Text.get("text.darkmusic.song_title") : track.title();
                    if (titleColor != 0)
                        (preview ? PREVIEW_TITLE : LIVE_TITLE).draw(g, mc.font, title, left + 4, top + 4, Math.max(1, width - 8), titleColor);
                    String artist = track == null ? Text.get("text.darkmusic.artist") : track.artist();
                    if (detailColor != 0)
                        g.text(mc.font, HorizontalText.ellipsize(mc.font, artist, Math.max(1, width - 8)), left + 4, top + 15, detailColor);
                }
                if (layout.spectrum()) {
                    var renderer = settingsPreview ? PREVIEW : LIVE;
                    if (artworkLayout.sideways(app.settings)) {
                        boolean left = app.settings.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.LEFT;
                        g.pose().pushMatrix();
                        try {
                            g.pose().translate(artworkLayout.spectrumX() + (left ? 0 : app.settings.artworkSpectrumHeight),
                                artworkLayout.spectrumY() + (left ? artworkLayout.artworkHeight() : 0));
                            g.pose().rotate((float) (left ? -Math.PI / 2 : Math.PI / 2));
                            renderer.bars(app, g, SpectrumRenderer.Bars.at(0, 0, artworkLayout.artworkHeight(), app.settings.artworkSpectrumHeight, false, settingsPreview));
                        } finally {
                            g.pose().popMatrix();
                        }
                    } else
                        renderer.bars(app, g, SpectrumRenderer.Bars.at(artworkLayout.spectrumX(), artworkLayout.spectrumY(),
                            artworkLayout.artworkWidth(), app.settings.artworkSpectrumHeight,
                            app.settings.spectrumDirection == Settings.SpectrumDirection.DOWNWARD, settingsPreview));
                }
                return;
            }
            if (app.settings.hudStyle == Settings.HudStyle.CIRCULAR) {
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
            int next = HudLayout.nextLine(app.settings);
            int scrim = app.settings.hudTextPlate ? HudLayout.textScrim(app.settings) : 0;
            // Kept clear of the card's chamfer, rounding and raised bevel.
            if (scrim != 0) g.fill(Math.max(4, textX - 3), main + 3, w - 4, main + 33 + next, scrim);
            if (app.settings.hudArtwork) {
                HudImages.draw(g, art, 5, main + 3, 32, 32);
                if (veil != 0) g.fill(5, main + 3, 37, main + 35, veil);
            }
            String title = track == null ? Text.get("text.darkmusic.song_title") : track.title();
            if (text != null) text.transform(x, y, scale);
            if (titleColor != 0)
                (preview ? PREVIEW_TITLE : LIVE_TITLE).draw(g, mc.font, title, textX, main + 6, Math.max(1, w - textX - 6), titleColor);
            String artist = track == null ? Text.get("text.darkmusic.artist") : track.artist();
            // "Playing" is implied by a moving HUD; only a paused, stopped or preview state is worth the space.
            String status = state.track() == null ? Text.get("text.darkmusic.preview")
                : state.status() == AudioBackend.Status.PLAYING ? "" : UiStatus.label(state.status().name());
            String detail = status.isEmpty() ? artist : status + " | " + artist;
            int detailWidth = Math.max(1, w - textX - 6);
            int timeLine = HudLayout.timeLine(app.settings);
            int timeColor = HudMotion.fade(0xFFA8A6BC, arrival);
            if (app.settings.hudTime && timeLine == 0) {
                // Shares the artist line, right-aligned, so the card keeps its height.
                String clock = state.track() == null ? Clock.progress(83, 225) : Clock.progress(state.position(), state.duration());
                int clockWidth = mc.font.width(clock);
                if (timeColor != 0) g.text(mc.font, clock, w - 6 - clockWidth, main + 21, timeColor);
                detailWidth = Math.max(1, detailWidth - clockWidth - 6);
            }
            if (text != null)
                text.draw(g, mc.font, "hud_detail", detail, textX, main + 21, detailWidth, 0xFFE0DDEE);
            else if (detailColor != 0)
                g.text(mc.font, HorizontalText.ellipsize(mc.font, detail, detailWidth), textX, main + 21, detailColor);
            if (next > 0) {
                String upcoming = nextLabel(app, state.track() == null);
                g.text(mc.font, HorizontalText.ellipsize(mc.font, upcoming, Math.max(1, w - textX - 6)), textX, main + 33, 0xFFA8A6BC);
            }
            if (app.settings.hudProgress) {
                double progress = state.duration() > 0 ? Math.clamp(state.position() / state.duration(), 0, 1) : preview ? .4 : 0;
                drawProgress(app, g, track, 6, main + 39 + next, w - 12, progress);
            }
            if (timeLine > 0 && timeColor != 0) {
                // Like the player: position under the start of the bar, length under its end.
                double position = state.track() == null ? 83 : state.position(), length = state.track() == null ? 225 : state.duration();
                int timeY = main + layout.mainHeight() - timeLine - 1;
                g.text(mc.font, Clock.format(position), 6, timeY, timeColor);
                if (Double.isFinite(length) && length > 0) {
                    String end = Clock.format(length);
                    g.text(mc.font, end, w - 6 - mc.font.width(end), timeY, timeColor);
                }
            }
            if (layout.spectrum())
                (settingsPreview ? PREVIEW : LIVE).bars(app, g, SpectrumRenderer.Bars.at(0, layout.spectrumY(), w, layout.spectrumHeight(), app.settings.spectrumDirection == Settings.SpectrumDirection.DOWNWARD, settingsPreview).inHud(h));
        } finally {
            if (text != null) text.transform(0, 0, 1);
            g.pose().popMatrix();
        }
    }

    /**
     * "Next: …" for the Classic HUD, or why nothing follows.
     */
    private static String nextLabel(DarkMusicClient app, boolean preview) {
        if (preview) return Text.get("hud.darkmusic.next", Text.get("text.darkmusic.song_title"));
        if (app.playback.stopAfterCurrent()) return Text.get("command.darkmusic.stopafter_short");
        var upcoming = app.playback.upcoming();
        return upcoming == null ? Text.get("hud.darkmusic.next_none") : Text.get("hud.darkmusic.next", upcoming.title());
    }

    private static void drawProgress(DarkMusicClient app, GuiGraphicsExtractor g, Track track,
                                     int x, int y, int width, double progress) {
        int height = app.settings.waveformProgress ? app.settings.waveform.height + 2 : 3;
        var waveform = app.settings.waveformProgress && app.waveforms != null ? app.waveforms.get(track) : null;
        if (WaveformProgress.draw(g, waveform, app.settings.progressColors, app.settings.waveform, x, y + 1, width, Math.max(3, height - 2),
            progress, app.settings.progressFinish)) return;
        int railY = y + (height - 3) / 2;
        ColorStrip.track(g, x, railY, width, 3, app.settings.progressFinish);
        ColorStrip.draw(g, app.settings.progressColors, x, railY, width, 3,
            (int) (width * progress), app.settings.progressFinish);
    }

}
