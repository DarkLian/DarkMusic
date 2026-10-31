package com.darkmusic.client.audio;

import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PcmRateConverterTest {
    private AudioInputStream music(int channels) throws Exception {
        var clip = TestMusic.clip();
        int frames = 4000;
        byte[] data = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++)
            for (int channel = 0; channel < channels; channel++) {
                int input = i * clip.format().getFrameSize() + Math.min(channel, clip.format().getChannels() - 1) * 2;
                data[i * channels * 2 + channel * 2] = clip.bytes()[input];
                data[i * channels * 2 + channel * 2 + 1] = clip.bytes()[input + 1];
            }
        return new AudioInputStream(new ByteArrayInputStream(data), new AudioFormat(clip.format().getSampleRate(), 16, channels, true, false), frames);
    }

    @Test
    void streamingSpeedChangesLengthWithoutChangingOutputRateOrMixingChannels() throws Exception {
        for (int channels : new int[]{1, 2})
            for (double speed : new double[]{.5, 1, 2, 3}) {
                try (var input = music(channels)) {
                    var converter = new PcmRateConverter(input);
                    byte[] buffer = new byte[512];
                    int total = 0, read;
                    long energy = 0;
                    while ((read = converter.read(buffer, buffer.length, speed)) >= 0) {
                        total += read;
                        for (int at = 0; at < read; at += channels * 2) {
                            energy += Math.abs((short) ((buffer[at] & 255) | buffer[at + 1] << 8));

                        }
                    }
                    assertEquals((int) Math.ceil(4000 / speed) * channels * 2, total);
                    assertTrue(energy > 1_000_000);
                    assertEquals(TestMusic.clip().format().getFrameRate(), input.getFormat().getFrameRate());
                }
            }
    }

    @Test
    void switchingSpeedKeepsSourcePositionContinuousAndClampsToTenths() throws Exception {
        try (var input = music(1)) {
            var converter = new PcmRateConverter(input);
            byte[] data = new byte[200];
            assertEquals(200, converter.read(data, 200, 1));
            assertEquals(100, converter.positionFrames());
            assertEquals(200, converter.read(data, 200, 2));
            assertEquals(300, converter.positionFrames());
            assertEquals(200, converter.read(data, 200, .5));
            assertEquals(350, converter.positionFrames());
        }
        assertEquals(.5, PcmRateConverter.clamp(-1));
        assertEquals(3, PcmRateConverter.clamp(4));
        assertEquals(2.9, PcmRateConverter.clamp(2.86));
        assertEquals(1, PcmRateConverter.clamp(Double.NaN));
        assertEquals(1.3, PcmRateConverter.clamp(1.26));
    }
}
