package com.darkmusic.client.audio;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ConvolutionCostTest {
    @Test
    void benchmarkWorstSupportedImpulseWithoutChangingItsQuality() {
        var reverb = new ConvolutionReverb(192000, ConvolutionReverb.Impulse.DARK_CAVE);
        Await.until(Duration.ofSeconds(10), reverb::ready, "192 kHz cave impulse preparation");
        for (int i = 0; i < 8192; i++) {
            reverb.sample(Math.sin(i * .1) * .1, 0);
            reverb.sample(Math.cos(i * .13) * .1, 1);
        }
        long start = System.nanoTime();
        double energy = 0;
        for (int i = 0; i < 65536; i++) {
            double left = reverb.sample(Math.sin(i * .1) * .1, 0), right = reverb.sample(Math.cos(i * .13) * .1, 1);
            assertTrue(Double.isFinite(left) && Double.isFinite(right));
            energy += left * left + right * right;
        }
        assertTrue(energy > 0);
        System.out.printf(Locale.ROOT, "CONVOLUTION_192K_DARK_CAVE: %.2f ms for %.2f ms stereo audio%n", (System.nanoTime() - start) / 1e6, 65536 / 192.0);
    }
}
