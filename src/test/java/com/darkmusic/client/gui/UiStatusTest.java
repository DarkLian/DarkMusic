package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class UiStatusTest {
    @Test
    void semanticColorsStayDistinctAndReadableAcrossDarkLightAndCustomThemes() {
        try {
            for (int background : new int[]{0x111420, 0xffffff, 0x000000, 0xc8d4e2, 0x162c36}) {
                var settings = new Settings();
                settings.menuBackgroundColor = background;
                UiTheme.apply(settings);
                var colors = new HashSet<Integer>();
                for (var tone : UiStatus.Tone.values()) {
                    int color = UiStatus.color(tone);
                    assertTrue(colors.add(color), "Status categories must remain distinct");
                    assertNotEquals(UiTheme.SECONDARY, color);
                    for (int surface : new int[]{UiTheme.SURFACE, UiTheme.RAISED, UiTheme.HOVER, UiTheme.SELECTED})
                        assertTrue(UiTheme.contrast(color, surface) >= 4.5, tone + " contrast on " + Integer.toHexString(surface));
                }
            }
        } finally {
            UiTheme.apply(new Settings());
        }
    }

    @Test
    void failuresUncertaintyAndSuccessfulResultsHaveDifferentMeanings() {
        assertEquals(UiStatus.Tone.ERROR, UiStatus.download(DownloadModels.Status.FAILED));
        assertEquals(UiStatus.Tone.SUCCESS, UiStatus.download(DownloadModels.Status.SAVED));
        assertEquals(UiStatus.Tone.SUCCESS, UiStatus.download(DownloadModels.Status.SKIPPED));
        assertEquals(UiStatus.Tone.WARNING, UiStatus.download(DownloadModels.Status.CANCELLED));
        assertEquals(UiStatus.Tone.INFO, UiStatus.download(DownloadModels.Status.DOWNLOADING));
        assertEquals(UiStatus.Tone.ERROR, UiStatus.lyrics(LyricsLookup.Status.NETWORK));
        assertEquals(UiStatus.Tone.WARNING, UiStatus.lyrics(LyricsLookup.Status.NOT_FOUND));
        assertEquals(UiStatus.Tone.WARNING, UiStatus.lyrics(LyricsLookup.Status.RATE_LIMITED));
        assertEquals(UiStatus.Tone.WARNING, UiStatus.lyrics(LyricsLookup.Status.AMBIGUOUS));
    }

    @Test
    void rowStatusIsSeparateFromMetadataAndIncludedInAccessibleText() {
        var row = new RowList.Row("Song", "MP3 | Music folder", "Full path").status("Already downloaded", UiStatus.Tone.SUCCESS);
        assertEquals("MP3 | Music folder", row.subtitle());
        assertEquals("Already downloaded", row.status());
        assertEquals("Song / Already downloaded / MP3 | Music folder / Full path", row.text(" / "));
    }

    @Test
    void savedFileNamesStayMetadataWhilePartialSuccessRemainsAWarning() {
        var item = new DownloadModels.Item(1, "Song", Path.of("Music"), DownloadModels.Status.SAVED, 1, "song.mp3");
        var row = DownloadScreen.jobRow(item);
        assertEquals(UiStatus.Tone.SUCCESS, row.tone());
        assertFalse(row.status().contains("song.mp3"));
        assertEquals("song.mp3 | Music", row.subtitle());
        var partial = DownloadScreen.jobRow(new DownloadModels.Item(2, "Song", Path.of("Music"), DownloadModels.Status.SAVED, 1,
            "song.mp3 \u2014 Source association repair pending"));
        assertEquals(UiStatus.Tone.WARNING, partial.tone());
        assertTrue(partial.status().contains("Source association repair pending"));
        assertFalse(partial.status().contains("song.mp3"));
    }
}
