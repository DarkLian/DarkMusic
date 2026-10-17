package com.darkmusic.client;

import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.library.ArtworkCache;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedAndHudTest {
    @Test
    void volumeStepsRoundCleanlyAndStayWithinTheAudibleRange() {
        assertEquals(.75, Settings.clampVolume(.7 + .05));
        assertEquals(.7, Settings.clampVolume(.75 - .05));
        assertEquals(1, Settings.clampVolume(1 + .05));
        assertEquals(0, Settings.clampVolume(-.05));
        assertEquals(.7, Settings.clampVolume(Double.NaN));
    }

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

    @Test
    void realEmbeddedArtworkIsLetterboxedWithoutStretching() throws Exception {
        byte[] bytes = new com.darkmusic.client.library.MetadataReader().artwork(TestMusic.featured());
        var original = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
        var image = ArtworkCache.fitArtwork(original);
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        assertTrue((image.getRGB(128, 128) >>> 24) > 0);
        if (original.getWidth() != original.getHeight()) assertEquals(0, image.getRGB(0, 0));
    }

    @Test
    void hudStaysOnScreenAndLegacyConfigurationMigratesWithoutResettingPreferences() {
        var settings = ConfigStore.JSON.fromJson("{\"schemaVersion\":1,\"volume\":0.32,\"hudPosition\":\"BOTTOM_RIGHT\",\"continueAcrossWorlds\":false}", Settings.class);
        settings.validate();
        assertEquals(1, settings.hudX);
        assertEquals(1, settings.hudY);
        assertEquals(.32, settings.volume);
        assertEquals(1, settings.speed);
        assertTrue(settings.hudArtwork);
        for (int width : new int[]{320, 854, 1920})
            for (int height : new int[]{240, 480, 1080})
                for (double scale : new double[]{.5, 1, 2}) {
                    var b = HudLayout.bounds(width, height, 1, 1, scale, true);
                    assertTrue(b.x() >= 8 && b.y() >= 8);
                    assertTrue(b.x() + b.width() <= width - 8);
                    assertTrue(b.y() + b.height() <= height - 8);
                    assertEquals(width - 16 == b.width() ? 0 : 1, HudLayout.normalized(b.x(), width, b.width()), .001);
                }
        settings.hudScale = Double.NaN;
        settings.speed = 9;
        settings.hudX = -3.0;
        settings.validate();
        assertEquals(1, settings.hudScale);
        assertEquals(3, settings.speed);
        assertEquals(0, settings.hudX);
    }
}
