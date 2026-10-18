package com.darkmusic.client.audio;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.AudioSettings;

/**
 * Temporary effective mix only. No Minecraft options writes and no gameplay or packet hooks.
 */
public final class ListeningMix {
    private static volatile double focus = 1;
    private static volatile long duckUntil, lastDuck;

    private ListeningMix() {
    }

    public static boolean tick() {
        var app = DarkMusicClient.INSTANCE;
        double target = 1;
        if (app != null && app.settings != null && app.playback != null && net.minecraft.client.Minecraft.getInstance().level != null && app.playback.state().status() == AudioBackend.Status.PLAYING && app.settings.audio.listening == AudioSettings.Listening.FOCUS)
            target = app.settings.audio.focusGain;
        double old = focus;
        focus += Math.clamp(target - focus, -.15, .15);
        if (Math.abs(focus - target) < .001) focus = target;
        return old != focus;
    }

    public static double category(String category) {
        return switch (category) {
            case "MUSIC", "RECORDS", "AMBIENT", "WEATHER", "NEUTRAL", "BLOCKS" -> focus;
            default -> 1;
        };
    }

    public static void sound(String category, String id) {
        var app = DarkMusicClient.INSTANCE;
        if (app == null || app.settings == null || !app.settings.audio.ducking) return;
        boolean priority = category.equals("HOSTILE") || category.equals("VOICE") || category.equals("UI") || id.contains("player.hurt");
        long now = System.nanoTime();
        if (priority && now - lastDuck > 500_000_000L) {
            lastDuck = now;
            duckUntil = now + (long) (app.settings.audio.duckHold * 1e9);
        }
    }

    public static boolean ducking() {
        return System.nanoTime() < duckUntil;
    }
}
