package com.darkmusic.client.pip;

import com.darkmusic.client.config.Settings;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipSettingsTest {
    @Test
    void oldSettingsGainPreferencesWithoutStartingCapture() {
        var settings = new Gson().fromJson("{\"volume\":0.42,\"hudEnabled\":false}", Settings.class);
        settings.validate();
        assertEquals(.42, settings.volume);
        assertFalse(settings.hudEnabled);
        assertNotNull(settings.pip);
        assertFalse(settings.pip.inMenus);
        assertEquals(60, settings.pip.frameRate);
        assertEquals(119, settings.pip.focusKey);
        assertEquals(120, settings.pip.returnKey);
        assertFalse(new Gson().toJson(settings.pip).contains("Handle"));
    }

    @Test
    void invalidSettingsAreBounded() {
        var s = new PipSettings();
        s.width = Double.NaN;
        s.opacity = -1;
        s.x = Double.POSITIVE_INFINITY;
        s.left = .99;
        s.right = .2;
        s.top = Double.NaN;
        s.frameRate = Integer.MAX_VALUE;
        s.focusKey = 123;
        s.returnKey = 119;
        s.validate();
        assertEquals(.3, s.width);
        assertEquals(.1, s.opacity);
        assertEquals(.68, s.x);
        assertTrue(s.right - s.left >= .049999);
        assertEquals(480, s.frameRate);
        assertNotEquals(s.focusKey, s.returnKey);
        assertFalse(PipSettings.validKey(123));
        assertFalse(PipSettings.validKey(1));
    }

    @Test
    void monitorMatchingAndManualLimits() {
        var s = new PipSettings();
        assertEquals(60, s.effectiveFrameRate(144));
        s.frameRate = 0;
        s.validate();
        assertEquals(0, s.frameRate);
        assertEquals(144, s.effectiveFrameRate(144));
        assertEquals(60, s.effectiveFrameRate(0));
        assertEquals(480, s.effectiveFrameRate(1000));
        s.frameRate = 24;
        assertEquals(24, s.effectiveFrameRate(240));
        assertArrayEquals(new int[]{0, 15, 24, 30, 48, 60, 90, 100, 120, 144, 165, 180, 200, 240, 280, 300, 360, 480}, PipSettings.frameRateOptions(100));
        assertEquals(1, java.util.Arrays.stream(PipSettings.frameRateOptions(144)).filter(n -> n == 144).count());
        assertFalse(java.util.Arrays.stream(PipSettings.frameRateOptions(1000)).anyMatch(n -> n == 1000));
        s.frameRate = 0;
        assertEquals(0, new Gson().fromJson(new Gson().toJson(s), PipSettings.class).frameRate);
    }

    @Test
    void placementSnapsToVisibleCenterAndClampsEdges() {
        for (var screen : new int[][]{{320, 240}, {426, 240}, {960, 540}, {959, 539}}) {
            var s = new PipSettings();
            var b = s.bounds(screen[0], screen[1], 1920, 1080);
            int cx = (screen[0] - b.width()) / 2, cy = (screen[1] - b.height()) / 2;
            s.move(cx + 2, cy - 2, screen[0], screen[1], 1920, 1080, true);
            b = s.bounds(screen[0], screen[1], 1920, 1080);
            assertEquals(cx, b.x());
            assertEquals(cy, b.y());
            s.move(cx + 2, cy - 2, screen[0], screen[1], 1920, 1080, false);
            b = s.bounds(screen[0], screen[1], 1920, 1080);
            assertEquals(cx + 2, b.x());
            s.move(10000, -10000, screen[0], screen[1], 1920, 1080, true);
            b = s.bounds(screen[0], screen[1], 1920, 1080);
            assertEquals(screen[0] - b.width(), b.x());
            assertEquals(0, b.y());
        }
    }

    @Test
    void boundsStayVisibleAtCompactAndWideSizes() {
        for (var screen : new int[][]{{320, 240}, {426, 240}, {960, 540}, {1920, 1080}})
            for (var image : new int[][]{{1920, 1080}, {1080, 1920}, {3840, 2160}, {1, 1}}) {
                var s = new PipSettings();
                s.x = 1;
                s.y = 1;
                s.width = 1;
                var b = s.bounds(screen[0], screen[1], image[0], image[1]);
                assertTrue(b.x() >= 0 && b.y() >= 0);
                assertTrue(b.x() + b.width() <= screen[0]);
                assertTrue(b.y() + b.height() <= screen[1]);
                assertTrue(b.contains(b.x(), b.y()));
                assertFalse(b.contains(b.x() + b.width(), b.y()));
            }
    }

    @Test
    void croppedAspectAndReset() {
        var s = new PipSettings();
        s.left = .25;
        s.right = .75;
        var full = s.bounds(960, 540, 1920, 1080);
        assertEquals(288, full.width());
        assertEquals(324, full.height());
        s.resetCrop();
        assertEquals(0, s.left);
        assertEquals(1, s.right);
        assertEquals(162, s.bounds(960, 540, 1920, 1080).height());
    }

    @Test
    void exactCropRejectsInvalidInputWithoutChangingPreferences() {
        var s = new PipSettings();
        assertTrue(s.cropPercent("10, 20, 80, 90"));
        assertEquals(.1, s.left);
        assertEquals(.9, s.bottom);
        for (String input : new String[]{"", "1 2", "a 0 100 100", "NaN 0 100 100", "0 0 101 100", "99 0 100 100", "0 100 100 0"}) {
            assertFalse(s.cropPercent(input));
            assertEquals(.1, s.left);
            assertEquals(.9, s.bottom);
        }
        assertTrue(s.cropPercent("10 10 15 15"));
    }

    @Test
    void failuresExposeOnlyLocalizedAllowlistedKeys() {
        assertEquals("pip.bundle_missing", PipNativeTools.failure(new java.util.concurrent.CompletionException(new java.io.IOException("pip.bundle_missing"))));
        assertEquals("pip.capture_error", PipNativeTools.failure(new java.io.IOException()));
        assertEquals("pip.capture_error", PipNativeTools.failure(new java.io.IOException("private/path/unknown")));
    }

    @Test
    void windowIdentityAndUntrustedTitles() {
        var s = new PipSource(1, 2, 3, "§cTitle\nTab", "browser", false);
        assertTrue(s.valid());
        assertEquals("Title Tab", s.Title());
        assertFalse(new PipSource(0, 2, 3, null, null, false).valid());
        assertEquals(512, new PipSource(1, 2, 3, "a".repeat(2000), "b", false).Title().length());
    }
}
