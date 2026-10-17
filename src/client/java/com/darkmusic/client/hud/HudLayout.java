package com.darkmusic.client.hud;

public final class HudLayout {
    private HudLayout() {
    }

    public static Bounds bounds(int screenWidth, int screenHeight, double x, double y, double scale, boolean progress) {
        int contentHeight = progress ? 46 : 38;
        scale = Math.min(scale, Math.max(.1, (screenHeight - 16.0) / contentHeight));
        int contentWidth = Math.max(1, Math.min(220, (int) ((screenWidth - 16) / scale)));
        int width = (int) Math.ceil(contentWidth * scale), height = (int) Math.ceil(contentHeight * scale);
        return new Bounds(8 + (int) Math.round(Math.max(0, screenWidth - 16 - width) * Math.clamp(x, 0, 1)), 8 + (int) Math.round(Math.max(0, screenHeight - 16 - height) * Math.clamp(y, 0, 1)), width, height, contentWidth, scale);
    }

    public static double normalized(double coordinate, int screenSize, int size) {
        return Math.clamp((coordinate - 8) / Math.max(1, screenSize - 16.0 - size), 0, 1);
    }

    public record Bounds(int x, int y, int width, int height, int contentWidth, double scale) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
