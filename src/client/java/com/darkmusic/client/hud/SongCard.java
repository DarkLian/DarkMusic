package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.HudFade;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.concurrent.TimeUnit;

/**
 * A short "now playing" card when the song changes. Until it is moved in Edit HUD Layout it sits in the HUD's corner,
 * clear of boss bars and advancement toasts: in the HUD's place when the HUD is off or hidden, otherwise beside it.
 */
public final class SongCard {
    static final int WIDTH = 200, HEIGHT = 40;
    static final long SHOWN = TimeUnit.MILLISECONDS.toNanos(4500);
    // Fade: in quickly, hold, then a slow fade out.
    static final long FADE_IN = TimeUnit.MILLISECONDS.toNanos(400), FADE_HOLD = TimeUnit.MILLISECONDS.toNanos(2500),
        FADE_OUT = TimeUnit.MILLISECONDS.toNanos(2500);
    private static String seen = "";
    private static Track track;
    private static long shownAt;

    private SongCard() {
    }

    public static void register(DarkMusicClient app) {
        HudLayer.add(app, "song_card", g -> draw(app, g, System.nanoTime()));
    }

    /**
     * Whether a song change at this moment shows the card.
     */
    static boolean wanted(Settings settings, boolean hudShowing) {
        return switch (settings.songCard) {
            case OFF -> false;
            case HIDDEN_HUD -> !hudShowing;
            case ALWAYS -> true;
        };
    }

    static long duration(Settings.Motion motion) {
        return motion == Settings.Motion.FADE ? FADE_IN + FADE_HOLD + FADE_OUT : SHOWN;
    }

    /**
     * 0 hidden, 1 fully shown. Slide moves in and out at the same pace; Fade appears quickly and fades out slowly.
     */
    static double presence(Settings.Motion motion, long age) {
        long total = duration(motion);
        if (age < 0 || age > total) return 0;
        if (motion == Settings.Motion.FADE)
            return age < FADE_IN ? HudMotion.ease(age / (double) FADE_IN)
                : age < FADE_IN + FADE_HOLD ? 1 : 1 - (age - FADE_IN - FADE_HOLD) / (double) FADE_OUT;
        return HudMotion.ease(Math.min(age, total - age) / (double) HudMotion.SLIDE);
    }

    /**
     * Where the card is drawn: its own place once moved, otherwise in or beside the HUD's corner.
     */
    public static HudLayout.Bounds bounds(DarkMusicClient app, int screenWidth, int screenHeight) {
        var settings = app.settings;
        int width = Math.min(WIDTH, screenWidth - 16);
        if (settings.songCardX != null && settings.songCardY != null) {
            double scale = Math.min(settings.songCardScale, Math.min(Math.max(.01, (screenWidth - 16.0) / width),
                Math.max(.01, (screenHeight - 16.0) / HEIGHT)));
            int w = (int) Math.ceil(width * scale - 1e-9), h = (int) Math.ceil(HEIGHT * scale - 1e-9);
            return new HudLayout.Bounds(8 + (int) Math.round(Math.max(0, screenWidth - 16 - w) * settings.songCardX),
                8 + (int) Math.round(Math.max(0, screenHeight - 16 - h) * settings.songCardY), w, h, width, scale);
        }
        var hud = NowPlayingHud.bounds(app, screenWidth, screenHeight);
        boolean beside = settings.hudEnabled && !NowPlayingHud.idleHidden();
        boolean right = hud.x() + hud.width() / 2.0 >= screenWidth / 2.0;
        int x = right ? Math.max(8, hud.x() + hud.width() - width) : hud.x();
        int y = hud.y();
        if (beside) {
            y = hud.y() + hud.height() + 4;
            if (y + HEIGHT > screenHeight - 8) y = Math.max(8, hud.y() - HEIGHT - 4);
        }
        return new HudLayout.Bounds(x, y, width, HEIGHT, width, 1);
    }

    private static void draw(DarkMusicClient app, GuiGraphicsExtractor g, long now) {
        var settings = app.settings;
        if (settings == null || app.playback == null) return;
        var current = app.playback.state().track();
        String id = current == null ? "" : current.id();
        if (!id.equals(seen)) {
            seen = id;
            boolean hudShowing = settings.hudEnabled && !NowPlayingHud.idleHidden();
            track = current != null && wanted(settings, hudShowing) ? current : null;
            shownAt = now;
        }
        if (track == null) return;
        long age = now - shownAt;
        double shown = presence(settings.songCardAnimation, age);
        if (shown <= 0) {
            if (age > duration(settings.songCardAnimation)) track = null;
            return;
        }
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        var card = track;
        if (settings.songCardAnimation == Settings.Motion.FADE)
            HudFade.run(shown, () -> card(app, g, card, b.x(), b.y(), b.scale()));
        else card(app, g, card, b.x() + HudMotion.exit(b.x(), b.width(), g.guiWidth(), 1 - shown), b.y(), b.scale());
    }

    /**
     * The layout editor's stand-in, using the playing song when there is one.
     */
    public static void renderPreview(DarkMusicClient app, GuiGraphicsExtractor g) {
        var b = bounds(app, g.guiWidth(), g.guiHeight());
        card(app, g, app.playback == null ? null : app.playback.state().track(), b.x(), b.y(), b.scale());
    }

    private static void card(DarkMusicClient app, GuiGraphicsExtractor g, Track track, double x, double y, double scale) {
        var font = Minecraft.getInstance().font;
        int width = Math.min(WIDTH, (int) Math.round((g.guiWidth() - 16) / scale));
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) x, (float) y);
            g.pose().scale((float) scale);
            g.fill(0, 0, width, HEIGHT, 0xE6111420);
            g.fill(0, 0, 2, HEIGHT, UiTheme.ACCENT | 0xFF000000);
            HudImages.draw(g, track == null ? null : HudImages.cover(app, track), 6, 4, 32, 32);
            int textWidth = Math.max(1, width - 50);
            String title = track == null ? Text.get("text.darkmusic.song_title") : track.title();
            String artist = track == null ? Text.get("text.darkmusic.artist") : track.artist();
            g.text(font, HorizontalText.ellipsize(font, title, textWidth), 44, 9, 0xFFB5B0FF);
            g.text(font, HorizontalText.ellipsize(font, artist, textWidth), 44, 22, 0xFFE0DDEE);
        } finally {
            g.pose().popMatrix();
        }
    }
}
