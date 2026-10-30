package com.darkmusic.client;

import com.darkmusic.client.audio.SoundEnvironmentProcessor;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The high- and low-pass filters are off at their slider ends. Switching one back on must not replay sound from
 * before the bypass, and the analyzer must describe the filters that playback actually uses. Music comes from the
 * private real collection; the silent stretches are plain zeros.
 */
class EnvironmentFilterBypassTest {
    private static final Consumer<SoundEnvironmentSettings.Parameters> HIGH_ON = p -> p.highPass = 2000,
        HIGH_OFF = p -> p.highPass = 20, LOW_ON = p -> p.lowPass = 400, LOW_OFF = p -> p.lowPass = 20000;

    private static SoundEnvironmentSettings custom(Consumer<SoundEnvironmentSettings.Parameters> edit) {
        var s = new SoundEnvironmentSettings();
        s.selectCustom();
        edit.accept(s.parameters);
        s.validate();
        return s;
    }

    private static float[] music() throws Exception {
        var clip = TestMusic.clip();
        byte[] bytes = clip.bytes();
        int channels = clip.format().getChannels();
        float[] stereo = new float[bytes.length / 2 / channels * 2];
        for (int frame = 0; frame < stereo.length / 2; frame++)
            for (int c = 0; c < 2; c++) {
                int i = (frame * channels + Math.min(c, channels - 1)) * 2;
                stereo[frame * 2 + c] = (short) ((bytes[i] & 255) | (bytes[i + 1] << 8)) / 32768f;
            }
        return stereo;
    }

    private static void apply(SoundEnvironmentProcessor dsp, SoundEnvironmentSettings s) {
        dsp.configure(s);
        dsp.prepare();
    }

    private static void play(SoundEnvironmentProcessor dsp, float[] music, int frames) {
        for (int i = 0; i < frames; i++) dsp.process(music[2 * i], music[2 * i + 1], false);
    }

    /**
     * Loudest output, in either channel, while the input is silent.
     */
    private static double silence(SoundEnvironmentProcessor dsp, int frames) {
        double peak = 0;
        for (int i = 0; i < frames; i++) {
            dsp.process(0, 0, false);
            peak = Math.max(peak, Math.max(Math.abs(dsp.left), Math.abs(dsp.right)));
        }
        return peak;
    }

    @Test
    void reactivatedFilterDoesNotReplaySoundFromBeforeItsBypass() throws Exception {
        float[] music = music();
        double rate = TestMusic.clip().format().getSampleRate();
        int second = (int) rate;
        for (var filter : new Consumer[][]{{HIGH_ON, HIGH_OFF}, {LOW_ON, LOW_OFF}}) {
            @SuppressWarnings("unchecked") Consumer<SoundEnvironmentSettings.Parameters> on = filter[0], off = filter[1];
            for (double sampleRate : new double[]{rate, rate == 48000 ? 44100 : 48000}) {
                var dsp = new SoundEnvironmentProcessor();
                dsp.begin(sampleRate);
                apply(dsp, custom(on));
                play(dsp, music, second);
                // Repeated bypass and reactivation, each with silence in between.
                for (int round = 0; round < 3; round++) {
                    apply(dsp, custom(off));
                    silence(dsp, second);
                    apply(dsp, custom(on));
                    double peak = silence(dsp, second / 4);
                    assertTrue(peak < 1e-9, (on == HIGH_ON ? "high" : "low") + "-pass at " + sampleRate
                        + " Hz, round " + round + ": silence played back at " + peak);
                }
            }
        }
    }

    @Test
    void reactivationDuringAnEnvironmentTransitionStaysSilent() throws Exception {
        float[] music = music();
        int second = (int) TestMusic.clip().format().getSampleRate();
        var dsp = new SoundEnvironmentProcessor();
        dsp.begin(second);
        apply(dsp, custom(HIGH_ON));
        play(dsp, music, second);
        apply(dsp, custom(HIGH_OFF));
        silence(dsp, second);
        // Mono changes the structure, so a second engine fades in; the filter returns while it does.
        apply(dsp, custom(p -> p.mono = true));
        silence(dsp, 10);
        apply(dsp, custom(p -> {
            p.mono = true;
            HIGH_ON.accept(p);
        }));
        assertTrue(silence(dsp, second / 4) < 1e-9);
    }

    @Test
    void bypassAndReactivationFollowTheMusicWithoutAJump() throws Exception {
        float[] music = music();
        int second = (int) TestMusic.clip().format().getSampleRate();
        double inputStep = 0;
        for (int i = 1; i < music.length / 2; i++)
            inputStep = Math.max(inputStep, Math.abs(music[2 * i] - music[2 * i - 2]));
        for (var filter : new Consumer[][]{{HIGH_ON, HIGH_OFF}, {LOW_ON, LOW_OFF}}) {
            @SuppressWarnings("unchecked") Consumer<SoundEnvironmentSettings.Parameters> on = filter[0], off = filter[1];
            var dsp = new SoundEnvironmentProcessor();
            dsp.begin(second);
            apply(dsp, custom(on));
            double step = 0, previous = 0;
            for (int i = 0; i < 3 * second; i++) {
                if (i == second) apply(dsp, custom(off));
                if (i == 2 * second) apply(dsp, custom(on));
                dsp.process(music[2 * i], music[2 * i + 1], false);
                if (i > second / 2) step = Math.max(step, Math.abs(dsp.left - previous));
                previous = dsp.left;
            }
            assertTrue(step <= inputStep, "output step " + step + " exceeds the music's own " + inputStep);
        }
    }

    @Test
    void analyzerLeavesOutBypassedFilters() {
        for (double rate : new double[]{44100, 48000}) {
            var dsp = new SoundEnvironmentProcessor();
            dsp.begin(rate);
            apply(dsp, custom(p -> {
            }));
            dsp.process(0, 0, false);
            for (double hz : new double[]{20, 100, 1000, 18000, 20000})
                assertEquals(0, dsp.toneResponse(hz), 1e-9, "Blank Custom at " + hz + " Hz, " + rate + " Hz rate");
            apply(dsp, custom(p -> {
                HIGH_ON.accept(p);
                LOW_ON.accept(p);
            }));
            for (int i = 0; i < rate; i++) dsp.process(0, 0, false);
            assertTrue(dsp.toneResponse(100) < -20 && dsp.toneResponse(8000) < -20, "active filters still shape the tone");
        }
    }

    @Test
    void blankCustomPlaysTheMusicAtOneFlatGainAsTheAnalyzerShows() throws Exception {
        float[] music = music();
        int second = (int) TestMusic.clip().format().getSampleRate();
        var dsp = new SoundEnvironmentProcessor();
        dsp.begin(second);
        apply(dsp, custom(p -> {
        }));
        double low = Double.MAX_VALUE, high = 0;
        for (int i = 0; i < 2 * second; i++) {
            dsp.process(music[2 * i], music[2 * i + 1], false);
            // The environment fades in first; afterwards only the constant headroom trim remains.
            if (i > second && Math.abs(music[2 * i]) > .05) {
                double gain = dsp.left / music[2 * i];
                low = Math.min(low, gain);
                high = Math.max(high, gain);
            }
        }
        assertTrue(high > 0 && high - low < 1e-6, "gain varied between " + low + " and " + high);
        assertEquals(0, dsp.toneResponse(20000), 1e-9);
    }
}
