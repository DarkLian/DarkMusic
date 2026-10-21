package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SeekDiscardTest {
    private final AudioFormat format = new AudioFormat(48000, 16, 2, true, false);

    @Test
    void repeatedZeroReadsAreBoundedAndFailClearly() {
        var reads = new AtomicInteger();
        var input = new AudioInputStream(InputStream.nullInputStream(), format, 100) {
            @Override
            public int read(byte[] b, int off, int length) {
                reads.incrementAndGet();
                return 0;
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IOException.class, () -> JavaSoundBackend.discardFrames(input, 50, new byte[4096], () -> true)));
        assertEquals(8, reads.get());
    }

    @Test
    void temporaryEmptyReadAndCancellationPreserveFrameAlignment() throws Exception {
        var reads = new AtomicInteger();
        var input = new AudioInputStream(InputStream.nullInputStream(), format, 100) {
            @Override
            public int read(byte[] b, int off, int length) {
                return reads.getAndIncrement() == 0 ? 0 : length;
            }
        };
        assertEquals(50, JavaSoundBackend.discardFrames(input, 50, new byte[16], () -> true));
        int count = reads.get();
        assertEquals(0, JavaSoundBackend.discardFrames(input, 50, new byte[16], () -> false));
        assertEquals(count, reads.get());
    }
}
