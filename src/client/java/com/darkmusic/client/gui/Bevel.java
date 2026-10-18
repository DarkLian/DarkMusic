package com.darkmusic.client.gui;

/**
 * Color-only lighting. Geometry, alpha, and the selected palette remain under their owners.
 */
public final class Bevel {
    private Bevel() {
    }

    public static int shade(int color, double light) {
        double amount = Double.isFinite(light) ? Math.clamp(light, -1, 1) : 0;
        int target = amount >= 0 ? 255 : 0;
        double mix = Math.abs(amount);
        int red = (int) Math.round((color >> 16 & 255) * (1 - mix) + target * mix);
        int green = (int) Math.round((color >> 8 & 255) * (1 - mix) + target * mix);
        int blue = (int) Math.round((color & 255) * (1 - mix) + target * mix);
        return color & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static double circularLight(double turn) {
        return (Math.cos(turn * Math.PI * 2) - Math.sin(turn * Math.PI * 2)) / Math.sqrt(2);
    }
}
