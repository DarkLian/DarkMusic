package com.darkmusic.client.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LrcTest {
    @Test
    void lrcUnicodeOffsetsRepeatedTimestampsSeekAndSpeedTimeline() {
        var lrc = Lrc.parse("[offset:250]\n[00:01.50][00:03.005]音樂 🎵\n[00:02]next\n[99:99]invalid");
        assertEquals(3, lrc.lines().size());
        assertEquals(-1, lrc.current(1.249));
        assertEquals(0, lrc.current(1.25));
        assertEquals("音樂 🎵", lrc.lines().get(lrc.current(2.8)).text());
        assertEquals(1, lrc.current(2));
        assertEquals(-1, lrc.current(0));
    }
}
