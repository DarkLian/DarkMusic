package com.darkmusic.client.lyrics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LyricInputTest {
    @Test
    void unicodeBomsDecodeWithoutGuessingLegacyEncodings() throws Exception {
        String text = "[00:01.00]音樂と歌";
        assertEquals(text, LyricsService.decode(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(text, LyricsService.decode(("\ufeff" + text).getBytes(StandardCharsets.UTF_8)));
        assertEquals(text, LyricsService.decode(("\ufeff" + text).getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(text, LyricsService.decode(("\ufeff" + text).getBytes(StandardCharsets.UTF_16BE)));
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> LyricsService.decode(new byte[]{(byte) 0x81}));
    }

    @Test
    void enhancedWordTimingIsNotDisplayedAsLyricText() {
        assertEquals("Hello world", Lrc.parse("[00:01.00]<00:01.00>Hello <00:01.50>world").lines().getFirst().text());
    }
}
