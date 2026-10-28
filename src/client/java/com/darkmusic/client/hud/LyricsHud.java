package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.lyrics.Lrc;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.render.HudFade;
import com.darkmusic.client.render.HudSurface;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Lyrics have their own screen anchor and scale, independent of the player card.
 */
public final class LyricsHud {
    private static final HudLyricsRenderer LIVE = new HudLyricsRenderer(), PREVIEW = new HudLyricsRenderer();

    private LyricsHud() {
    }

    public static boolean enabled(Settings settings) {
        return settings.lyricsEnabled && settings.lyricsHud;
    }

    public static void register(DarkMusicClient app) {
        HudLayer.add(app, "lyrics", g -> {
            if (app.settings != null && enabled(app.settings) && app.playback != null && app.playback.state().track() != null)
                // Hides with the player HUD when idle; fading suits a centered line better than sliding.
                HudFade.run(1 - NowPlayingHud.idleAmount(app.settings), () -> render(app, g, false));
        });
    }

    public static HudLayout.Bounds bounds(Settings settings, int width, int height) {
        int contentWidth = settings.lyricsHudWidth, contentHeight = HudLyricsLayout.height(settings.lyricsNextLine);
        double scale = Math.min(settings.lyricsHudScale, Math.min(Math.max(.01, (width - 16.0) / contentWidth),
            Math.max(.01, (height - 16.0) / contentHeight)));
        int w = (int) Math.ceil(contentWidth * scale - 1e-9), h = (int) Math.ceil(contentHeight * scale - 1e-9);
        return new HudLayout.Bounds(8 + (int) Math.round(Math.max(0, width - 16 - w) * settings.lyricsHudX),
            8 + (int) Math.round(Math.max(0, height - 16 - h) * settings.lyricsHudY), w, h, contentWidth, scale);
    }

    /**
     * Keep the HUD empty until the recording reaches its first lyric timestamp.
     */
    public static Cue cue(Lrc data, double position) {
        int current = data.current(position);
        if (current < 0) return new Cue("", "");
        return new Cue(current >= 0 ? data.lines().get(current).text() : "",
            current + 1 < data.lines().size() ? data.lines().get(current + 1).text() : "");
    }

    public static void render(DarkMusicClient app, GuiGraphicsExtractor g, boolean preview) {
        if (!enabled(app.settings)) return;
        renderAt(app, g, preview, bounds(app.settings, g.guiWidth(), g.guiHeight()));
    }

    public static void renderPreview(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, double previewScale) {
        if (!enabled(app.settings)) return;
        int w = app.settings.lyricsHudWidth, h = HudLyricsLayout.height(app.settings.lyricsNextLine);
        double scale = Math.min(previewScale, Math.min(width / (double) w, height / (double) h));
        renderAt(app, g, true, new HudLayout.Bounds(x + (int) ((width - w * scale) / 2),
            y + (int) ((height - h * scale) / 2), (int) Math.ceil(w * scale), (int) Math.ceil(h * scale), w, scale));
    }

    private static void renderAt(DarkMusicClient app, GuiGraphicsExtractor g, boolean preview, HudLayout.Bounds b) {
        var audio = app.playback.state();
        var track = audio.track();
        app.lyrics.request(track);
        var data = app.lyrics.get(track);
        var cue = cue(data, audio.position());
        String current = cue.current(), next = cue.next();
        if (data.lines().isEmpty()) {
            if (!preview) return;
            var document = app.lyrics.document(track);
            // Local reads and provider requests are asynchronous; neither implies missing lyrics.
            String key = !app.lyrics.inspected(track) || app.lyrics.lookup.snapshot(track).status()
                == LyricsLookup.Status.SEARCHING ? "lyrics.status.searching"
                : document.instrumental() ? "lyrics.instrumental"
                : document.available() ? "lyrics.plain" : "lyrics.darkmusic.missing";
            current = Text.get(key);
        } else if (preview && current.isEmpty()) {
            current = next.isEmpty() ? data.lines().getFirst().text() : next;
            next = "";
        }
        if (!preview && current.isEmpty() && next.isEmpty()) return;
        var s = app.settings;
        g.pose().pushMatrix();
        try {
            g.pose().translate(b.x(), b.y());
            g.pose().scale((float) b.scale());
            int h = HudLyricsLayout.height(s.lyricsNextLine);
            // Same shape, gradient and bevel as the player card, at the lyrics' own opacity.
            if (s.lyricsHudOpacity > 0)
                HudSurface.draw(g, s, s.lyricsHudWidth, h, (int) Math.round(s.lyricsHudOpacity * 255) << 24);
            (preview ? PREVIEW : LIVE).draw(g, Minecraft.getInstance().font, current, next, s.lyricsHudWidth, 0,
                s.lyricsNextLine, s.lyricsHudColor, s.lyricsHudNextColor, s.lyricsTextPlate ? HudText.lyricsPlate(s.lyricsHudOpacity) : 0);
        } finally {
            g.pose().popMatrix();
        }
    }

    public record Cue(String current, String next) {
    }
}
