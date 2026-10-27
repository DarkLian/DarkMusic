package com.darkmusic.client;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.pip.PipAudio;
import com.darkmusic.client.pip.PipSpectrumSettings;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.*;

import static org.junit.jupiter.api.Assertions.*;

class PipAudioTest {
    private byte[] header(int frames, int flags, long sequence) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(0x414D4444).putInt(1).putInt(44100).putInt(2).putInt(frames).putInt(flags).putLong(sequence).array();
    }

    @Test
    void rejectsMalformedHeadersAndTruncatedPayloads() throws Exception {
        for (int[] shape : new int[][]{{0, 0}, {8193, 0}, {1, 2}})
            try (var audio = new PipAudio()) {
                assertThrows(IOException.class, () -> audio.read(new ByteArrayInputStream(header(shape[0], shape[1], 1))));
            }
        try (var audio = new PipAudio()) {
            var wrong = header(1, 0, 1);
            wrong[0] = 0;
            assertThrows(IOException.class, () -> audio.read(new ByteArrayInputStream(wrong)));
            assertEquals(0, audio.packets());
        }
        try (var audio = new PipAudio()) {
            assertThrows(EOFException.class, () -> audio.read(new ByteArrayInputStream(header(1, 0, 1))));
        }
    }

    @Test
    void realMusicProducesLevelsAndDuplicateSequencesAreRejected() throws Exception {
        var clip = TestMusic.clip();
        var format = new javax.sound.sampled.AudioFormat(44100, 16, 2, true, false);
        byte[] pcm;
        try (var converted = javax.sound.sampled.AudioSystem.getAudioInputStream(format, clip.stream())) {
            pcm = converted.readNBytes(44100 * 4 * 2);
        }
        var data = new ByteArrayOutputStream();
        long sequence = 0;
        for (int pos = 0; pos + 4096 <= pcm.length; pos += 4096) {
            data.write(header(1024, 0, ++sequence));
            data.write(pcm, pos, 4096);
        }
        try (var audio = new PipAudio()) {
            audio.configure(new PipSpectrumSettings());
            audio.read(new ByteArrayInputStream(data.toByteArray()));
            assertTrue(audio.available());
            assertEquals(sequence, audio.packets());
            float[] bands = new float[64];
            audio.analyzer().copyTo(bands);
            boolean audible = false;
            for (float level : bands) {
                assertTrue(Float.isFinite(level));
                audible |= level > 0;
            }
            assertTrue(audible);
            data.write(header(1024, 0, sequence));
            data.write(pcm, 0, 4096);
            try (var second = new PipAudio()) {
                second.configure(new PipSpectrumSettings());
                assertThrows(IOException.class, () -> second.read(new ByteArrayInputStream(data.toByteArray())));
            }
            audio.close();
            assertFalse(audio.available());
        }
    }

    @Test
    void closedReaderCannotPublishAndFailuresAreIndependentOfVideo() throws Exception {
        var audio = new PipAudio();
        audio.close();
        audio.read(new ByteArrayInputStream(header(1, 0, 1)));
        assertFalse(audio.available());
        assertEquals(0, audio.packets());
        try (var other = new PipAudio()) {
            other.failure("pip.audio_unsupported");
            assertEquals("pip.audio_unsupported", other.status());
            assertFalse(other.available());
        }
        assertFalse(new Settings().pip.audioSpectrum);
    }
}
