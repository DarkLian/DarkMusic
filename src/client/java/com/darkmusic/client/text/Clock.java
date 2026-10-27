package com.darkmusic.client.text;

import java.util.Locale;

/**
 * Song positions as m:ss, or h:mm:ss from an hour on.
 */
public final class Clock {
    private Clock() {
    }

    public static String format(double seconds) {
        long total = Double.isFinite(seconds) ? Math.max(0, Math.round(Math.floor(seconds))) : 0;
        return total >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    /**
     * "1:23 / 3:45", or just the position when the length is unknown.
     */
    public static String progress(double position, double duration) {
        return Double.isFinite(duration) && duration > 0 ? format(position) + " / " + format(duration) : format(position);
    }
}
