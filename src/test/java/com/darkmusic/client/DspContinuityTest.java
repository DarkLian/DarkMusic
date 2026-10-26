package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audio inputs derive exclusively from the private real collection.
 */
class DspContinuityTest {
    private Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private float[] samples() throws Exception {
        var clip = TestMusic.clip();
        byte[] bytes = clip.bytes();
        float[] out = new float[bytes.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8)) / 32768f;
        return out;
    }

    private void handoff(AudioProcessor p, double rate, int channels) throws Exception {
        var m = AudioProcessor.class.getDeclaredMethod("begin", double.class, int.class, boolean.class);
        m.setAccessible(true);
        m.invoke(p, rate, channels, true);
    }

    @Test
    void sameFormatHandoffMatchesAnUninterruptedDeviceStream() throws Exception {
        var clip = TestMusic.clip();
        float[] input = samples();
        int channels = clip.format().getChannels();
        double rate = clip.format().getSampleRate();
        var s = new AudioSettings();
        s.neutral = false;
        s.equalizer = true;
        s.bands[3] = 4;
        s.width = .4;
        s.compressor = true;
        s.enhancements.crossfeed = true;
        s.enhancements.dynamic[0].enabled = true;
        s.reverb = AudioSettings.Reverb.SMALL_ROOM;
        s.validate();
        for (var preset : List.of(SoundEnvironmentSettings.Preset.OFF, SoundEnvironmentSettings.Preset.DEEP_CAVE)) {
            var environment = new SoundEnvironmentSettings();
            environment.select(preset);
            environment.validate();
            var uninterrupted = new AudioProcessor();
            var split = new AudioProcessor();
            uninterrupted.configure(s, environment);
            split.configure(s, environment);
            uninterrupted.begin(rate, channels);
            split.begin(rate, channels);
            uninterrupted.initialVolume(.8);
            split.initialVolume(.8);
            int block = 512 * channels, boundary = (input.length / 2 / block) * block;
            for (int at = 0; at < input.length; at += block) {
                int n = Math.min(block, input.length - at);
                float[] expected = Arrays.copyOfRange(input, at, at + n), actual = expected.clone();
                if (at == boundary) handoff(split, rate, channels);
                uninterrupted.process(expected, n, .8, 1);
                split.process(actual, n, .8, 1);
                assertArrayEquals(expected, actual, 0f, "Handoff must preserve DSP histories: " + preset);
            }
            assertEquals(s.width, (double) field(split, "width"), 0);
            handoff(split, rate / 2, channels);
            assertEquals(1.0, (double) field(split, "width"), 0, "Changed format resets state before first preparation");
        }
    }

    @Test
    void requestedWidthIsAppliedFromTheFirstPreparedFrame() throws Exception {
        var clip = TestMusic.clip();
        var p = new AudioProcessor();
        var s = new AudioSettings();
        s.neutral = false;
        s.width = .2;
        s.validate();
        p.configure(s);
        p.begin(clip.format().getSampleRate(), clip.format().getChannels());
        var prepare = AudioProcessor.class.getDeclaredMethod("prepare");
        prepare.setAccessible(true);
        prepare.invoke(p);
        assertEquals(.2, (double) field(p, "width"), 0);
    }

    @Test
    void continuousEnvironmentEditsKeepHistoryAndAcceptUpdatesDuringPresetFade() throws Exception {
        var clip = TestMusic.clip();
        float[] input = samples();
        int channels = clip.format().getChannels();
        double rate = clip.format().getSampleRate();
        var p = new SoundEnvironmentProcessor();
        p.begin(rate);
        var settings = new SoundEnvironmentSettings();
        settings.select(SoundEnvironmentSettings.Preset.DEEP_CAVE);
        p.configure(settings.playbackCopy());
        p.prepare();
        for (int i = 0; i < input.length / 2; i += channels)
            p.process(input[i], channels == 2 ? input[i + 1] : input[i], false);
        Object[] engines = (Object[]) field(p, "engines");
        int active = (int) field(p, "current");
        Object engine = engines[active];
        float[][] history = (float[][]) field(engine, "history");
        float[][] before = {history[0].clone(), history[1].clone()};
        int cursor = (int) field(engine, "cursor");
        for (int edit = 0; edit < 20; edit++) {
            var changed = settings.playbackCopy();
            changed.parameters.highPass = 30 + edit;
            changed.parameters.distance = edit / 40.0;
            changed.parameters.pan = .4;
            changed.parameters.wet = .2;
            changed.parameters.decay = 1 + edit / 20.0;
            p.configure(changed);
            p.prepare();
            assertFalse((boolean) field(p, "transitioning"));
            assertSame(history, field(engine, "history"));
            assertArrayEquals(before[0], history[0]);
            assertArrayEquals(before[1], history[1]);
            assertEquals(cursor, field(engine, "cursor"));
        }
        settings.select(SoundEnvironmentSettings.Preset.BATHROOM);
        p.configure(settings.playbackCopy());
        p.prepare();
        assertTrue((boolean) field(p, "transitioning"));
        var changed = settings.playbackCopy();
        changed.parameters.pan = -.5;
        p.configure(changed);
        p.prepare();
        Object next = engines[1 - active];
        assertEquals(-.5, ((SoundEnvironmentSettings.Parameters) field(next, "p")).pan, 0);
        for (int i = 0; i < Math.min(input.length, (int) (rate * .1) * channels); i += channels) {
            p.process(input[i], channels == 2 ? input[i + 1] : input[i], false);
            assertTrue(Double.isFinite(p.left) && Double.isFinite(p.right));
        }
        assertFalse((boolean) field(p, "transitioning"));
    }
}
