package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;

/**
 * Temporary effective mix only. No Minecraft options writes and no gameplay or packet hooks.
 */
public final class ListeningMix {
    private static volatile double focus = 1;
    private static volatile long duckUntil, lastDuck;

    private ListeningMix() {
    }

    /**
     * @param audio   current audio settings, or null before they load
     * @param playing whether DarkMusic is playing inside a world
     */
    public static boolean tick(AudioSettings audio, boolean playing) {
        double target = 1;
        if (audio != null && playing && audio.listening == AudioSettings.Listening.FOCUS)
            target = audio.focusGain;
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

    public static void sound(String category, String id, AudioSettings audio) {
        if (audio == null || !audio.ducking) return;
        boolean priority = category.equals("HOSTILE") || category.equals("VOICE") || category.equals("UI") || id.contains("player.hurt");
        long now = System.nanoTime();
        if (priority && now - lastDuck > 500_000_000L) {
            lastDuck = now;
            duckUntil = now + (long) (audio.duckHold * 1e9);
        }
    }

    public static boolean ducking() {
        return System.nanoTime() < duckUntil;
    }
}
