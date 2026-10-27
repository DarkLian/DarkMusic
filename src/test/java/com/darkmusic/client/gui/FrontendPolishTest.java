package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.lyrics.LyricsInfo;
import com.darkmusic.client.lyrics.LyricsLookup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontendPolishTest {
    @Test
    void sliderLabelsSplitAtTheLastLabelSeparatorOnly() {
        assertArrayEquals(new String[]{"EQ Band: 31 Hz:", "-2.0 dB"}, ValueSlider.labelParts("EQ Band: 31 Hz: -2.0 dB"));
        assertArrayEquals(new String[]{"Ratio:", "4.0:1"}, ValueSlider.labelParts("Ratio: 4.0:1"));
        assertArrayEquals(new String[]{"強度：", "100%"}, ValueSlider.labelParts("強度：100%"));
        assertArrayEquals(new String[]{"Plain label", ""}, ValueSlider.labelParts("Plain label"));
        assertArrayEquals(new String[]{"Trailing: ", ""}, ValueSlider.labelParts("Trailing: "));
    }

    @Test
    void equalizerSnapsToHalfDecibelsWithinRangeAndLabelsCompactly() {
        assertEquals(-2.0, EqualizerGraph.snap(-2.1));
        assertEquals(1.5, EqualizerGraph.snap(1.4));
        assertEquals(12, EqualizerGraph.snap(40));
        assertEquals(-12, EqualizerGraph.snap(-40));
        assertEquals("31", EqualizerGraph.frequencyLabel(31));
        assertEquals("1k", EqualizerGraph.frequencyLabel(1000));
        assertEquals("16k", EqualizerGraph.frequencyLabel(16000));
        assertEquals("+0.0", EqualizerGraph.gainLabel(-0.01));
        assertEquals("-2.0", EqualizerGraph.gainLabel(-2));
    }

    @Test
    void wideArtworkTextFollowsTheDrawnImageInsteadOfASquareLetterbox() {
        assertEquals(115, MusicScreen.fittedHeight(1280, 720, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(500, 500, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(720, 1280, 204, 184));
        assertEquals(184, MusicScreen.fittedHeight(0, 0, 204, 184));
    }

    @Test
    void missingMeasurementsDropTheirUnit() {
        assertEquals("Detector N/A · Compression 0.0 dB", AudioAnalyzerScreen.units("Detector N/A dBFS · Compression 0.0 dB", "N/A"));
        assertEquals("Peak N/A · Hold N/A", AudioAnalyzerScreen.units("Peak N/A dBFS · Hold N/A dB", "N/A"));
        assertEquals("Peak -3.6 dBFS", AudioAnalyzerScreen.units("Peak -3.6 dBFS", "N/A"));
    }

    @Test
    void lyricsStatesHaveDistinctShapesAndAbsenceIsNotAnError() {
        assertEquals(LyricsStatusIcon.Kind.AVAILABLE, LyricsStatusIcon.kind(LyricsInfo.AVAILABLE));
        assertEquals(LyricsStatusIcon.Kind.INSTRUMENTAL, LyricsStatusIcon.kind(LyricsInfo.INSTRUMENTAL));
        assertEquals(LyricsStatusIcon.Kind.NONE, LyricsStatusIcon.kind(LyricsInfo.UNAVAILABLE));
        assertEquals(LyricsStatusIcon.Kind.NONE, LyricsStatusIcon.kind(LyricsInfo.lookup(LyricsLookup.Status.NOT_FOUND)));
        assertEquals(LyricsStatusIcon.Kind.FAILED, LyricsStatusIcon.kind(LyricsInfo.lookup(LyricsLookup.Status.NETWORK)));
        assertEquals(LyricsStatusIcon.Kind.PENDING, LyricsStatusIcon.kind(LyricsInfo.SEARCHING));
        assertEquals(UiTheme.SECONDARY, UiStatus.lyricsColor(LyricsInfo.INSTRUMENTAL));
        assertEquals(UiTheme.SECONDARY, UiStatus.lyricsColor(LyricsInfo.UNAVAILABLE));
        assertEquals(UiStatus.color(UiStatus.Tone.ERROR), UiStatus.lyricsColor(LyricsInfo.lookup(LyricsLookup.Status.NETWORK)));
    }

    @Test
    void timeReadoutFitsBetweenSeekBarAndTransportAtEveryBreakpoint() {
        for (int width : new int[]{320, 480, 640, 800, 854, 1280, 1720})
            for (int height : new int[]{240, 300, 360, 400, 480, 720}) {
                var layout = PlayerLayout.of(width, height);
                assertTrue(layout.timeY() >= layout.seekY() + 20, width + "x" + height);
                assertTrue(layout.timeY() + 9 <= layout.transportY(), width + "x" + height);
            }
    }

    @Test
    void menusDefaultToABlackBackground() {
        assertEquals(0x000000, new Settings().menuBackgroundColor);
    }

    @Test
    void hudTextScrimFollowsCardTransparencyAndLightness() {
        var settings = new Settings();
        settings.hudBackgroundTransparency = 0;
        settings.hudBackgroundGradient = false;
        settings.hudBackgroundColors.preset = com.darkmusic.client.config.VisualColors.Preset.CUSTOM;
        settings.hudBackgroundColors.start = settings.hudBackgroundColors.end = 0x111420;
        assertEquals(0, HudLayout.textScrim(settings), "An opaque dark card already gives contrast");
        settings.hudBackgroundTransparency = .85;
        int seeThrough = HudLayout.textScrim(settings);
        assertTrue(seeThrough >>> 24 > 0x40, "A mostly transparent card needs a scrim");
        settings.hudBackgroundTransparency = 1;
        assertTrue(HudLayout.textScrim(settings) >>> 24 >= seeThrough >>> 24);
        assertTrue(HudLayout.textScrim(settings) >>> 24 <= 0x60, "The scrim stays soft");
        settings.hudBackgroundTransparency = 0;
        settings.hudBackgroundColors.start = settings.hudBackgroundColors.end = 0xE6E6EE;
        assertTrue(HudLayout.textScrim(settings) >>> 24 > 0, "An opaque light card needs a scrim");
    }
}
