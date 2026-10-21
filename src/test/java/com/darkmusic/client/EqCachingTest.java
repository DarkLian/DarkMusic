package com.darkmusic.client;

import com.darkmusic.client.audio.AudioProcessor;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.config.AudioSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EqCachingTest {
    @Test
    void cachedResponseMatchesRecomputationThroughLiveEditsAndSampleRateChanges() throws Exception {
        float[] real = new float[2048];
        try (var input = TestMusic.clip().stream()) {
            assertTrue(new PcmRateConverter(input).read(real, real.length, 1) > 0);
        }
        var cached = new AudioProcessor();
        var reference = new AudioProcessor();
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.equalizer = true;
        cached.configure(settings);
        reference.configure(settings);
        AudioSettings published = null;
        for (double rate : new double[]{44100, 48000, 96000}) {
            for (int channels : new int[]{1, 2}) {
                cached.begin(rate, channels);
                reference.begin(rate, channels);
                for (int block = 0; block < 700; block++) {
                    if (block % 97 == 0) settings.bands[block % 10] = block % 25 - 12;
                    settings.bass = block < 350 ? 0 : 4;
                    settings.equalizer = block < 400 || block > 440;
                    settings.neutral = block >= 500 && block < 530;
                    float[] a = real.clone(), b = real.clone();
                    // Playback publishes detached snapshots only when values change. The
                    // reference deliberately recomputes the same targets on every block.
                    if (published == null || !published.samePlayback(settings)) {
                        published = settings.playbackCopy();
                        cached.configure(published);
                    }
                    reference.configure(settings.playbackCopy());
                    cached.process(a, a.length, .7, 1);
                    reference.process(b, b.length, .7, 1);
                    assertArrayEquals(a, b, "EQ output changed: rate=" + rate + " channels=" + channels + " block=" + block);
                }
            }
        }
    }
}
