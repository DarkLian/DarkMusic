package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TempoNoProgressTest {
    @Test
    void primingDecoderYieldsToTheAudioWorkerInsteadOfSpinning() {
        var reads = new AtomicInteger();
        var input = new AudioInputStream(InputStream.nullInputStream(), new AudioFormat(44100, 16, 2, true, false), AudioSystem.NOT_SPECIFIED) {
            @Override
            public int read(byte[] b, int o, int len) {
                reads.incrementAndGet();
                return 0;
            }
        };
        var converter = new PcmRateConverter(input);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(0, converter.read(new float[2048], 2048, 1.2, 44100, true));
            assertEquals(0, converter.read(new float[2048], 2048, 1.2, 44100, true));
        });
        assertTrue(reads.get() <= 4);
        assertEquals(0, converter.positionFrames());
    }
}
