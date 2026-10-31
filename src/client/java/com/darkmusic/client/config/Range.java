package com.darkmusic.client.config;

/**
 * The values one setting allows. Its class's validate() and the setting's setter both apply it, so the range is
 * written once. Loading applies it to whatever the file holds; a setter applies it to what a control writes.
 */
public record Range(double min, double max, double fallback) {
    public static Range of(double min, double max, double fallback) {
        return new Range(min, max, fallback);
    }

    /**
     * For whole-number settings, which have no value that is not finite.
     */
    public static Range whole(int min, int max) {
        return new Range(min, max, min);
    }

    /**
     * Clamped into the range; a value that is not finite becomes the fallback.
     */
    public double apply(double value) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    public int apply(int value) {
        return Math.clamp(value, (int) min, (int) max);
    }
}
