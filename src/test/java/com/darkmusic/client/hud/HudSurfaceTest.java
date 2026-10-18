package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class HudSurfaceTest {
    @Test
    void disabledLyricsIgnoreStoredHudAndNextLineTogglesWithoutChangingSpectrumGeometry() {
        var settings = new Settings();
        settings.lyricsEnabled = false;
        settings.lyricsHud = false;
        var baseline = HudLayout.content(settings, Integer.MAX_VALUE);
        for (boolean hud : new boolean[]{false, true}) {
            for (boolean next : new boolean[]{false, true}) {
                settings.lyricsHud = hud;
                settings.lyricsNextLine = next;
                assertEquals(baseline, HudLayout.content(settings, Integer.MAX_VALUE));
            }
        }
        settings.lyricsEnabled = true;
        settings.lyricsHud = true;
        var withLyrics = HudLayout.content(settings, Integer.MAX_VALUE);
        assertEquals(baseline.width(), withLyrics.width());
        assertEquals(baseline.spectrumY(), withLyrics.spectrumY());
        assertEquals(baseline.spectrum(), withLyrics.spectrum());
    }

    @Test
    void mergedSpansPreserveEveryClippedRowIncludingGlowOutsideTheBounds() {
        var random = new Random(0xD4A2);
        for (var shape : Settings.HudShape.values()) {
            for (int sample = 0; sample < 10000; sample++) {
                int width = 1 + random.nextInt(240), height = 1 + random.nextInt(130);
                int left = random.nextInt(width + 4) - 2, right = left + 1 + random.nextInt(12);
                int top = random.nextInt(height + 4) - 2, bottom = top + 1 + random.nextInt(34);
                int rows = 0;
                for (int row = top; row < bottom; ) {
                    int next = HudSurface.spanBottom(shape, width, height, left, right, row, bottom);
                    assertTrue(next > row && next <= bottom);
                    int inset = HudSurface.inset(shape, width, height, row);
                    int start = Math.max(left, inset), end = Math.min(right, width - inset);
                    for (int original = row; original < next; original++) {
                        int expected = HudSurface.inset(shape, width, height, original);
                        assertEquals(Math.max(left, expected), start);
                        assertEquals(Math.min(right, width - expected), end);
                        rows++;
                    }
                    row = next;
                }
                assertEquals(bottom - top, rows);
            }
        }
    }

    @Test
    void lyricsHeightDoesNotFragmentInteriorSpectrumColumns() {
        // These columns include body, trails, bottom bevel and glow. Before the fix,
        // no-lyrics height 82 split each bottom strip into as many as six rectangles.
        for (var shape : Settings.HudShape.values()) {
            for (int height : new int[]{82, 98, 110}) {
                for (int left = 7; left < 212; left++) {
                    for (int top = 49; top < 82; top++) {
                        assertEquals(83, HudSurface.spanBottom(shape, 220, height, left, left + 1, top, 83));
                    }
                }
            }
        }
    }
}
