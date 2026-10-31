package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EqualizerGraphTest {
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
}
