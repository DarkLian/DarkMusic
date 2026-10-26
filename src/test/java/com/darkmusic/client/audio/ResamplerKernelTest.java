package com.darkmusic.client.audio;

import javax.sound.sampled.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResamplerKernelTest {
    private double[] kernel(double ratio) throws Exception {
        // Inspect coefficients only: no dummy audio file, playback or generated PCM.
        var input = new AudioInputStream(java.io.InputStream.nullInputStream(), new AudioFormat(192000, 16, 1, true, false), 0);
        var converter = new PcmRateConverter(input);
        var prepare = PcmRateConverter.class.getDeclaredMethod("prepare", double.class);
        prepare.setAccessible(true);
        prepare.invoke(converter, ratio);
        var field = PcmRateConverter.class.getDeclaredField("weights");
        field.setAccessible(true);
        return ((double[][]) field.get(converter))[0];
    }

    private double db(double[] w, double frequency) {
        double real = 0, imag = 0;
        for (int i = 0; i < w.length; i++) {
            real += w[i] * Math.cos(2 * Math.PI * frequency * i);
            imag += w[i] * Math.sin(2 * Math.PI * frequency * i);
        }
        return 10 * Math.log10(real * real + imag * imag);
    }

    @Test
    void highRatioStopbandImprovesWithoutChangingNormalRateCost() throws Exception {
        assertEquals(32, kernel(1).length);
        assertEquals(512, kernel(64).length);
        var w = kernel(192000.0 / 44100);
        assertEquals(140, w.length);
        assertEquals(0, db(w, .04), .01);
        assertTrue(db(w, .15) < -65);
        System.out.println("192k -> 44.1k kernel: " + w.length + " taps; stopband at 28.8kHz = " + db(w, .15) + " dB");
    }
}
