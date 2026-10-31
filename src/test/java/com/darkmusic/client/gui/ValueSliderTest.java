package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueSliderTest {
    @Test
    void sliderLabelsSplitAtTheLastLabelSeparatorOnly() {
        assertArrayEquals(new String[]{"EQ Band: 31 Hz:", "-2.0 dB"}, ValueSlider.labelParts("EQ Band: 31 Hz: -2.0 dB"));
        assertArrayEquals(new String[]{"Ratio:", "4.0:1"}, ValueSlider.labelParts("Ratio: 4.0:1"));
        assertArrayEquals(new String[]{"強度：", "100%"}, ValueSlider.labelParts("強度：100%"));
        assertArrayEquals(new String[]{"Plain label", ""}, ValueSlider.labelParts("Plain label"));
        assertArrayEquals(new String[]{"Trailing: ", ""}, ValueSlider.labelParts("Trailing: "));
    }
}
