package com.darkmusic.client.hud;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Fixed lyric bands keep playback changes from resizing or moving the HUD.
 */
public final class HudLyricsLayout {
    public static final int WIDTH = 300, PADDING = 6, LINES = 2;
    public static final int CURRENT_STEP = 13, NEXT_STEP = 11, GAP = 4;
    public static final float CURRENT_SCALE = 1.1f;

    private HudLyricsLayout() {
    }

    public static int height(boolean next) {
        return PADDING * 2 + LINES * CURRENT_STEP + (next ? GAP + LINES * NEXT_STEP : 0);
    }

    /**
     * Bounded, code-point-safe wrapping; only a line exceeding both rows needs an ellipsis.
     */
    public static List<String> wrap(String text, int width, ToIntFunction<String> measure) {
        if (text == null || text.isBlank() || width <= 0) return List.of();
        int end = Math.min(text.length(), 512);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String bounded = text.substring(0, end).replaceAll("\\s+", " ").strip() + (end < text.length() ? "\u2026" : "");
        if (measure.applyAsInt(bounded) <= width) return List.of(bounded);
        int low = 0, high = bounded.codePointCount(0, bounded.length());
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (measure.applyAsInt(bounded.substring(0, bounded.offsetByCodePoints(0, mid))) <= width) low = mid;
            else high = mid - 1;
        }
        int cut = bounded.offsetByCodePoints(0, low);
        if (cut == 0) return List.of();
        int space = bounded.lastIndexOf(' ', cut - 1);
        if (space > cut / 2) cut = space;
        String first = bounded.substring(0, cut).stripTrailing();
        String remaining = bounded.substring(cut).stripLeading();
        if (remaining.isEmpty()) return List.of(first);
        return List.of(first, CircularHudRenderer.ellipsis(remaining, width, measure));
    }
}
