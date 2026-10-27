package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.render.UiTheme;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FocusRowsTest {
    @Test
    void tabOrderIncludesOffscreenControlsAndSkipsDisabledRows() {
        var rows = List.of(true, false, true, true);
        assertEquals(0, FocusRows.next(rows, -1, false, true));
        assertEquals(2, FocusRows.next(rows, 0, false, true));
        assertEquals(0, FocusRows.next(rows, 2, true, true));
        assertEquals(0, FocusRows.next(rows, 3, false, true));
        assertEquals(3, FocusRows.next(rows, -1, true, true));
    }

    @Test
    void sectionBoundariesCanReturnFocusToNavigation() {
        assertEquals(-1, FocusRows.next(List.of(true, true), 1, false, false));
        assertEquals(-1, FocusRows.next(List.of(true, true), 0, true, false));
        assertEquals(-1, FocusRows.next(List.of(), -1, false, true));
        assertEquals(-1, FocusRows.next(List.of(false, false), 0, false, true));
    }

    @Test
    void customAccentRemainsReadableWithoutAlteringTheDefaultTheme() {
        assertEquals(0xFFB8A8FA, UiTheme.readableAccent(0xFFB8A8FA, 0xFF191D2C, 0xFFF0EFF9));
        for (int background : new int[]{0xFF000000, 0xFFFFFFFF, 0xFF191D2C}) {
            int text = background == 0xFFFFFFFF ? 0xFF111420 : 0xFFF0EFF9;
            int accent = UiTheme.readableAccent(background, background, text);
            assertTrue(UiTheme.contrast(accent, background) >= 4.5);
        }
        try {
            for (int value = 0; value <= 255; value += 17) {
                var settings = new Settings();
                settings.menuBackgroundColor = value << 16 | value << 8 | value;
                settings.menuAccentColor = settings.menuBackgroundColor;
                UiTheme.apply(settings);
                assertTrue(UiTheme.contrast(UiTheme.TEXT, UiTheme.SURFACE) >= 4.5, "Background " + value);
                assertTrue(UiTheme.contrast(UiTheme.ACCENT, UiTheme.SURFACE) >= 4.5, "Accent on " + value);
                assertTrue(UiTheme.contrast(UiTheme.TEXT, UiTheme.RAISED) >= 4.5, "Controls on " + value);
            }
        } finally {
            UiTheme.apply(new Settings());
        }
    }
}
