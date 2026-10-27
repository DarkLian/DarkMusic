package com.darkmusic.client.hud;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Settings;

import java.util.concurrent.TimeUnit;

/**
 * Time-based HUD motion: sliding away after a pause and fading in a new song. One instance per live HUD; client
 * thread only.
 */
public final class HudMotion {
    static final long SLIDE = TimeUnit.MILLISECONDS.toNanos(350), FADE = TimeUnit.MILLISECONDS.toNanos(400);
    // Fading away when idle is deliberately slow; coming back is always quick.
    static final long FADE_AWAY = TimeUnit.MILLISECONDS.toNanos(2500);
    private String track = "";
    private long changedAt, activeAt, lastFrame;
    private double hidden;

    /**
     * Advances to {@code now} and returns how far the HUD has slid away: 0 shown, 1 hidden. Call once per frame.
     */
    public double hidden(Settings settings, AudioBackend.State state, long now) {
        String id = state.track() == null ? "" : state.track().id();
        if (!id.equals(track)) {
            track = id;
            changedAt = now;
            activeAt = now;
        }
        if (state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.LOADING
            || state.status() == AudioBackend.Status.SEEKING) activeAt = now;
        boolean away = settings.hudIdleHide > 0 && now - activeAt > TimeUnit.SECONDS.toNanos(settings.hudIdleHide);
        long elapsed = lastFrame == 0 ? Long.MAX_VALUE / 2 : now - lastFrame;
        lastFrame = now;
        long pace = away && settings.hudIdleAnimation == Settings.Motion.FADE ? FADE_AWAY : SLIDE;
        double step = Math.clamp(elapsed / (double) pace, 0, 1);
        hidden = away ? Math.min(1, hidden + step) : Math.max(0, hidden - step);
        return hidden;
    }

    public boolean fullyHidden() {
        return hidden >= 1;
    }

    /**
     * The last value {@link #hidden} returned.
     */
    public double amount() {
        return hidden;
    }

    /**
     * 0 just after the song changed, rising to 1 when its fade-in is done.
     */
    public double arrival(long now) {
        return Math.clamp((now - changedAt) / (double) FADE, 0, 1);
    }

    public static double ease(double t) {
        double x = Math.clamp(t, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /**
     * Scales a color's alpha; 0 means "do not draw", since text treats a zero alpha as opaque.
     */
    public static int fade(int argb, double amount) {
        int alpha = (int) Math.round((argb >>> 24) * Math.clamp(amount, 0, 1));
        return alpha < 8 ? 0 : (argb & 0xFFFFFF) | alpha << 24;
    }

    /**
     * How far to move something of this width so it leaves the screen through the nearer side edge.
     */
    public static double exit(int x, int width, int screenWidth, double amount) {
        double eased = ease(amount);
        return x + width / 2.0 < screenWidth / 2.0 ? -(x + width + 8) * eased : (screenWidth - x + 8) * eased;
    }
}
