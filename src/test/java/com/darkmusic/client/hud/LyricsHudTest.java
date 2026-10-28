package com.darkmusic.client.hud;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.lyrics.Lrc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LyricsHudTest {
    @Test
    void firstLineWaitsForItsTimestampAndSeekingBackClearsBothLines() {
        var data = Lrc.parse("[00:01]First\n[00:03]Second\n[00:04]\n[00:06]Third");
        assertEquals(new LyricsHud.Cue("", ""), LyricsHud.cue(data, 0));
        assertEquals(new LyricsHud.Cue("First", "Second"), LyricsHud.cue(data, 1));
        assertEquals(new LyricsHud.Cue("", "Third"), LyricsHud.cue(data, 5));
        assertEquals(new LyricsHud.Cue("Third", ""), LyricsHud.cue(data, 7));
        assertEquals(new LyricsHud.Cue("", ""), LyricsHud.cue(data, 0));
        assertEquals(new LyricsHud.Cue("", ""), LyricsHud.cue(new Lrc(List.of()), 0));
    }

    @Test
    void everyMusicLayoutHasIndependentLyricsAndFitsCompactScreensAtEveryScale() {
        var s = new Settings();
        s.validate();
        s.lyricsEnabled = s.lyricsHud = true;
        for (var style : Settings.HudStyle.values()) {
            s.hudStyle = style;
            var music = HudLayout.bounds(s, 960, 540);
            for (int width : new int[]{320, 426, 960}) {
                for (double scale : new double[]{.5, 1, 3}) {
                    s.lyricsHudScale = scale;
                    s.lyricsHudWidth = 600;
                    s.lyricsNextLine = true;
                    var b = LyricsHud.bounds(s, width, 240);
                    assertTrue(b.x() >= 8 && b.y() >= 8);
                    assertTrue(b.x() + b.width() <= width - 8);
                    assertTrue(b.y() + b.height() <= 232);
                    assertEquals(music, HudLayout.bounds(s, 960, 540));
                }
            }
            s.hudEnabled = false;
            assertTrue(LyricsHud.enabled(s));
        }
    }

    @Test
    void settingsKeepExistingPreferencesAndValidateNewFields() {
        var s = ConfigStore.JSON.fromJson("{\"lyricsEnabled\":true,\"lyricsHud\":true,\"lyricsNextLine\":true}", Settings.class);
        s.validate();
        assertTrue(s.lyricsNextLine);
        assertEquals(300, s.lyricsHudWidth);
        assertEquals(.5, s.lyricsHudX);
        s.lyricsHudX = Double.NaN;
        s.lyricsHudY = Double.POSITIVE_INFINITY;
        s.lyricsHudScale = Double.NaN;
        s.lyricsHudWidth = Integer.MAX_VALUE;
        s.lyricsHudOpacity = -1;
        s.validate();
        assertEquals(.5, s.lyricsHudX);
        assertEquals(.9, s.lyricsHudY);
        assertEquals(1, s.lyricsHudScale);
        assertEquals(600, s.lyricsHudWidth);
        assertEquals(0, s.lyricsHudOpacity);
        var saved = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(s), Settings.class);
        assertEquals(LyricsHud.bounds(s, 960, 540), LyricsHud.bounds(saved, 960, 540));
    }

    @Test
    void wrapsTwoLinesWithoutSplittingUnicodeOrOverflowing() {
        var lines = HudLyricsLayout.wrap("An unusually long lyric line with several words", 18, String::length);
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.length() <= 18));
        assertEquals(List.of(), HudLyricsLayout.wrap("", 18, String::length));
        assertEquals(List.of(), HudLyricsLayout.wrap("Text", 0, String::length));
        var unicode = HudLyricsLayout.wrap("日本語😀歌詞😀".repeat(100), 18, String::length);
        assertEquals(2, unicode.size());
        assertTrue(unicode.stream().noneMatch(line -> line.chars().anyMatch(c -> c == 0xfffd)));
        assertTrue(unicode.stream().noneMatch(line -> Character.isHighSurrogate(line.charAt(line.length() - 1))));
    }
}
