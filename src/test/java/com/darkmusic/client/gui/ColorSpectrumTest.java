package com.darkmusic.client.gui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class ColorSpectrumTest {
    @Test
    void colorSpectrumMapsHueSaturationAndBrightnessWithoutLosingHexPrecision() {
        assertEquals(0xFF0000, ColorSpectrum.rgb(0, 1, 1));
        assertEquals(0x00FF00, ColorSpectrum.rgb(1f / 3, 1, 1));
        assertEquals(0x0000FF, ColorSpectrum.rgb(2f / 3, 1, 1));
        assertEquals(0xFFFFFF, ColorSpectrum.rgb(.75f, 0, 1));
        assertEquals(0, ColorSpectrum.rgb(.75f, 1, 0));
        for (int color : new int[]{0x123456, 0xFFEECC, 0x578CFF, 0x888888}) {
            float[] hsv = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
            assertEquals(color, ColorSpectrum.rgb(hsv[0], hsv[1], hsv[2]));
        }
    }
}
