package com.darkmusic.client.render;

import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

class HorizontalTextTest {
    @Test
    void liveCountdownUpdatesPreserveTheReadersPositionAndInvalidatePaintedText() {
        var line = new HorizontalText.Line();
        line.update("Request failed | Retrying in 120 s", 300, true);
        line.offset = 146.5;
        line.visible = "Retrying in 120 s";
        line.update("Request failed | Retrying in 119 s", 299, true);
        assertEquals(146.5, line.offset);
        assertNull(line.visible);
        assertEquals(299, line.measured);
        line.update("Request failed | Retrying in 99 s", 290, true);
        assertEquals(146.5, HorizontalText.clamp(line.offset, line.measured - 120));
        line.update("Searching", 50, true);
        assertEquals(0, HorizontalText.clamp(line.offset, line.measured - 120));
    }

    @Test
    void ellipsizeKeepsFittingTextAndMarksTruncatedText() {
        ToIntFunction<String> measure = String::length;
        BiFunction<String, Integer, String> prefix = (text, width) -> text.substring(0, Math.min(text.length(), width));
        assertEquals("Short", HorizontalText.ellipsize("Short", 5, measure, prefix));
        assertEquals("Pirates…", HorizontalText.ellipsize("Pirates Of The Caribbean", 9, measure, prefix));
        assertEquals("", HorizontalText.ellipsize("Long", 0, measure, prefix));
        assertEquals("", HorizontalText.ellipsize(null, 10, measure, prefix));
        String emoji = "ab🎵cd";
        assertEquals("ab…", HorizontalText.ellipsize(emoji, 4, measure, prefix));
    }

    @Test
    void replacingOrdinaryContentStillStartsAtTheBeginning() {
        var line = new HorizontalText.Line();
        line.update("Long song title", 250, false);
        line.offset = 90;
        line.update("Different song", 200, false);
        assertEquals(0, line.offset);
        assertNull(line.visible);
    }
}
