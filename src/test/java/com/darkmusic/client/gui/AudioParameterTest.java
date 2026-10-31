package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioParameterTest {
    @Test
    void logarithmicFrequencyMappingIsInvertibleAndPreciseEditingRejectsInvalidValues() {
        var p = new AudioParameter(20, 20000, true, " Hz");
        assertEquals(20, p.value(0));
        assertEquals(20000, p.value(1));
        assertEquals(Math.sqrt(20 * 20000), p.value(.5), 1e-8);
        for (double hz : new double[]{20, 31, 62, 100, 440, 1000, 6500, 20000}) {
            assertEquals(hz, p.value(p.position(hz)), 1e-8);
            assertEquals(hz, p.parse(Double.toString(hz)));
        }
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "", "20 Hz", "19.9", "20000.1"))
            assertThrows(IllegalArgumentException.class, () -> p.parse(invalid));
        assertEquals(62.5, p.parse("62,5"));
        assertEquals("100 ms", new AudioParameter(.001, .2, false, " s").format(.1));
        assertEquals("2.00 kHz", p.format(2000));
        assertEquals(.5, new AudioParameter(-12, 12, false, " dB").position(0));
    }
}
