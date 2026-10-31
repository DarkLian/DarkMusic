package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioAnalyzerScreenTest {
    @Test
    void missingMeasurementsDropTheirUnit() {
        assertEquals("Detector N/A · Compression 0.0 dB", AudioAnalyzerScreen.units("Detector N/A dBFS · Compression 0.0 dB", "N/A"));
        assertEquals("Peak N/A · Hold N/A", AudioAnalyzerScreen.units("Peak N/A dBFS · Hold N/A dB", "N/A"));
        assertEquals("Peak -3.6 dBFS", AudioAnalyzerScreen.units("Peak -3.6 dBFS", "N/A"));
    }
}
