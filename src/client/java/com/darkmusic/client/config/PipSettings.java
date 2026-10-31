package com.darkmusic.client.config;

import java.util.TreeSet;

/**
 * Display/audio-analysis preferences only: window identities and active capture never persist.
 */
public final class PipSettings {
    public boolean enabled = false, audioAccess = false;
    public static final int MAX_FRAME_RATE = 480;
    public boolean visible = true, inMenus = false;
    public boolean audioSpectrum = false;
    public PipSpectrumSettings spectrum = new PipSpectrumSettings();
    public double x = .68, y = .08, width = .3, opacity = 1;
    public double left, top, right = 1, bottom = 1;
    public int frameRate = 60, focusKey = 119, returnKey = 120;

    public static final Range X = Range.of(0, 1, .68);
    public static final Range Y = Range.of(0, 1, .08);
    public static final Range WIDTH = Range.of(.1, 1, .3);
    public static final Range OPACITY = Range.of(.1, 1, 1);
    public static final Range LEFT = Range.of(0, .95, 0);
    public static final Range TOP = Range.of(0, .95, 0);

    public void validate() {
        if (spectrum == null) spectrum = new PipSpectrumSettings();
        spectrum.validate();
        x = X.apply(x);
        y = Y.apply(y);
        width = WIDTH.apply(width);
        opacity = OPACITY.apply(opacity);
        left = LEFT.apply(left);
        top = TOP.apply(top);
        right = finite(right, left + .05, 1, 1);
        bottom = finite(bottom, top + .05, 1, 1);
        if (frameRate != 0) frameRate = Math.clamp(frameRate, 15, MAX_FRAME_RATE);
        if (!validKey(focusKey)) focusKey = 119;
        if (!validKey(returnKey) || returnKey == focusKey) returnKey = focusKey == 120 ? 119 : 120;
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void x(double value) {
        x = X.apply(value);
    }

    public void y(double value) {
        y = Y.apply(value);
    }

    public void width(double value) {
        width = WIDTH.apply(value);
    }

    public void opacity(double value) {
        opacity = OPACITY.apply(value);
    }

    public void left(double value) {
        left = LEFT.apply(value);
    }

    public void top(double value) {
        top = TOP.apply(value);
    }

    public static boolean validKey(int key) {
        return key >= 112 && key <= 135 && key != 123;
    }

    /**
     * Zero means match Minecraft's current monitor, sampled when capture starts.
     */
    public int effectiveFrameRate(int monitorRate) {
        return Math.clamp(frameRate == 0 ? (monitorRate > 0 ? monitorRate : 60) : frameRate, 15, MAX_FRAME_RATE);
    }

    public static int[] frameRateOptions(int monitorRate) {
        var options = new TreeSet<Integer>();
        for (int rate : new int[]{0, 15, 24, 30, 48, 60, 90, 120, 144, 165, 180, 200, 240, 280, 300, 360, 480})
            options.add(rate);
        if (monitorRate >= 15 && monitorRate <= MAX_FRAME_RATE) options.add(monitorRate);
        return options.stream().mapToInt(Integer::intValue).toArray();
    }

    private static double finite(double n, double min, double max, double fallback) {
        return Double.isFinite(n) ? Math.clamp(n, min, max) : fallback;
    }

    public void revokeAccess() {
        enabled = false;
        audioAccess = false;
        audioSpectrum = false;
    }

    public void resetCrop() {
        left = top = 0;
        right = bottom = 1;
    }

    public boolean cropPercent(String input) {
        try {
            String[] parts = input.strip().split("[ ,]+");
            if (parts.length != 4) return false;
            double l = Double.parseDouble(parts[0]) / 100, t = Double.parseDouble(parts[1]) / 100, r = Double.parseDouble(parts[2]) / 100, b = Double.parseDouble(parts[3]) / 100;
            if (!Double.isFinite(l + t + r + b) || l < 0 || t < 0 || r > 1 || b > 1 || r - l < .05 - 1e-9 || b - t < .05 - 1e-9)
                return false;
            left = l;
            top = t;
            right = r;
            bottom = b;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Bounds bounds(int screenWidth, int screenHeight, int imageWidth, int imageHeight) {
        double aspect = Math.max(1, imageWidth) * (right - left) / (Math.max(1, imageHeight) * (bottom - top));
        int w = Math.max(24, (int) (screenWidth * width));
        int h = Math.max(16, (int) (w / aspect));
        if (h > screenHeight - 8) {
            h = Math.max(16, screenHeight - 8);
            w = Math.max(24, (int) (h * aspect));
        }
        w = Math.min(w, screenWidth);
        h = Math.min(h, screenHeight);
        return new Bounds((int) Math.clamp(x * screenWidth, 0, screenWidth - w), (int) Math.clamp(y * screenHeight, 0, screenHeight - h), w, h);
    }

    public void move(double targetX, double targetY, int screenWidth, int screenHeight, int imageWidth, int imageHeight, boolean snap) {
        var b = bounds(screenWidth, screenHeight, imageWidth, imageHeight);
        double px = Math.clamp(targetX, 0, screenWidth - b.width()), py = Math.clamp(targetY, 0, screenHeight - b.height());
        int centerX = (screenWidth - b.width()) / 2, centerY = (screenHeight - b.height()) / 2;
        if (snap) {
            if (Math.abs(px - centerX) < 4) px = centerX;
            if (Math.abs(py - centerY) < 4) py = centerY;
        }
        x = px / screenWidth;
        y = py / screenHeight;
    }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }
}
