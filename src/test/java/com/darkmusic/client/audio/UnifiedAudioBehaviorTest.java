package com.darkmusic.client.audio;

import com.darkmusic.client.config.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedAudioBehaviorTest {
    @Test
    void reverbOffClearsOldTailsAndModeChangesFadeWithoutRestarting() {
        var s = new AudioSettings();
        s.neutral = false;
        s.reverb = AudioSettings.Reverb.SMALL_ROOM;
        var slot = new MusicReverb();
        slot.begin(48000);
        slot.prepare(s, false);
        double energy = 0;
        for (int i = 0; i < 8000; i++) {
            slot.process(i == 0 ? .8 : 0, i == 0 ? .8 : 0);
            if (i > 1000) energy += slot.left * slot.left;
        }
        assertTrue(energy > 0);
        s.reverb = AudioSettings.Reverb.DRY;
        for (int b = 0; b < 40; b++) {
            slot.prepare(s, false);
            for (int i = 0; i < 512; i++) slot.process(0, 0);
        }
        s.reverb = AudioSettings.Reverb.SMALL_ROOM;
        slot.prepare(s, false);
        for (int i = 0; i < 12000; i++) {
            slot.process(0, 0);
            assertEquals(0, slot.left, 1e-12);
        }
        for (int i = 0; i < 24000; i++) slot.process(.1, .1);
        double last = slot.left;
        s.reverb = AudioSettings.Reverb.CONCERT_HALL;
        slot.prepare(s, false);
        for (int i = 0; i < 2400; i++) {
            slot.process(.1, .1);
            assertTrue(Math.abs(slot.left - last) < .02);
            last = slot.left;
        }
        s.enhancements.reverbEngine = AudioEnhancements.ReverbEngine.CONVOLUTION;
        slot.begin(384000);
        slot.prepare(s, false);
        assertEquals(4, slot.convolutionState());
    }

    @Test
    void invalidAdditivePreferencesAreDetachedAndValidatedWithoutErasingExistingValues() {
        var a = new AudioSettings();
        a.enhancements.nodes = null;
        a.enhancements.dynamic = new AudioEnhancements.Node[]{null};
        a.enhancements.attack = Double.NaN;
        var copy = a.playbackCopy();
        assertEquals(5, copy.enhancements.nodes.length);
        assertEquals(2, copy.enhancements.dynamic.length);
        assertEquals(.01, copy.enhancements.attack);
        var e = new SoundEnvironmentSettings();
        e.select(SoundEnvironmentSettings.Preset.DEEP_CAVE);
        e.parameters.reflectionTimes = null;
        e.parameters.decay = Double.POSITIVE_INFINITY;
        var snapshot = e.playbackCopy();
        assertEquals(.7, snapshot.parameters.decay);
        assertNotNull(snapshot.parameters.reflectionTimes);
        assertNull(e.parameters.reflectionTimes);
        var invalid = new ConvolutionReverb(384000, ConvolutionReverb.Impulse.HALL);
        assertTrue(invalid.failed());
        assertFalse(invalid.ready());
    }

    @Test
    void environmentRecipesReserveHeadroomWithoutAnImplicitLimiter() {
        for (var preset : SoundEnvironmentSettings.Preset.values()) {
            var e = new SoundEnvironmentSettings();
            e.select(preset);
            var dsp = new SoundEnvironmentProcessor();
            dsp.begin(48000);
            dsp.configure(e);
            dsp.prepare();
            double peak = 0;
            for (int i = 0; i < 96000; i++) {
                double l = .95 * (i % 16000 < 8000 ? Math.sin(i * .13) : Math.copySign(1, Math.sin(i * .071)));
                dsp.process(l, -l * .8, false);
                peak = Math.max(peak, Math.max(Math.abs(dsp.left), Math.abs(dsp.right)));
            }
            assertTrue(peak <= 1.001, preset + " output peak " + peak);
        }
    }

    @Test
    void filtersHaveCalibratedGainAndFiniteLowRateResponses() {
        for (double rate : new double[]{8000, 44100, 48000, 96000}) {
            var b = new Biquad();
            b.initialize(rate);
            double hz = Math.min(1000, rate * .2);
            b.configure(AudioEnhancements.Type.PEAK, rate, hz, 9, 1.4);
            b.snap();
            assertEquals(9, b.response(hz, rate, false), 1e-7);
            double input = 0, output = 0;
            for (int i = 0; i < rate; i++) {
                double x = .05 * Math.sin(2 * Math.PI * hz * i / rate), y = b.sample(x, 0);
                if (i > rate / 2) {
                    input += x * x;
                    output += y * y;
                }
            }
            assertEquals(9, 10 * Math.log10(output / input), .02);
            for (var type : AudioEnhancements.Type.values()) {
                b.configure(type, rate, 20000, 12, 8);
                for (int i = 0; i < 10000; i++) assertTrue(Double.isFinite(b.sample(Math.sin(i * .5) * .2, 0)));
            }
        }
    }

    @Test
    void compressorRatioKneeAndMakeupDriveActualReduction() {
        var e = new AudioEnhancements();
        e.threshold = -18;
        e.ratio = 4;
        var c = new AudioDynamics();
        c.begin();
        c.configure(e, 48000);
        double gain = 1;
        for (int i = 0; i < 96000; i++) gain = c.apply(.5, -.5);
        double expected = (20 * Math.log10(.5) + 18) * .75;
        assertEquals(expected, c.reductionDb, .01);
        assertEquals(-expected, 20 * Math.log10(gain), .01);
        e.makeup = 3;
        c.configure(e, 48000);
        assertEquals(3, 20 * Math.log10(c.apply(.5, -.5) / gain), .01);
        e.knee = 12;
        c.configure(e, 48000);
        assertTrue(Double.isFinite(c.apply(0, 0)));
    }

    @Test
    void dynamicEqDetectsItsOwnBandAndDeesserAttenuatesSibilance() {
        var n = new AudioEnhancements.Node();
        n.enabled = true;
        n.frequency = 3000;
        n.threshold = -30;
        n.range = -9;
        var band = new DynamicBand();
        band.begin(48000);
        band.configure(n);
        for (int block = 0; block < 250; block++) {
            band.block();
            for (int i = 0; i < 512; i++) {
                double x = .5 * Math.sin(2 * Math.PI * 3000 * (block * 512 + i) / 48000);
                band.sample(x, 0);
                band.sample(x, 1);
            }
        }
        assertTrue(band.dynamicGain < -8);
        assertTrue(band.response(3000, false) < -8);
        assertEquals(0, band.response(3000, true), 1e-6);
        assertTrue(Math.abs(band.response(100, false)) < .1);
        var a = new AudioSettings();
        a.neutral = false;
        a.enhancements.deEsser = true;
        a.enhancements.deEsserStrength = 1;
        a.enhancements.deEsserThreshold = -36;
        double high = processedRms(a, 6500), low = processedRms(a, 300);
        assertTrue(high < low * .6, "Filtered de-essing must reduce the detected high band, not broadband level");
    }

    private double processedRms(AudioSettings a, double hz) {
        var p = new AudioProcessor();
        p.configure(a);
        p.begin(48000, 2);
        double energy = 0;
        float[] block = new float[1024];
        for (int b = 0; b < 200; b++) {
            for (int i = 0; i < 512; i++)
                block[2 * i] = block[2 * i + 1] = (float) (.5 * Math.sin(2 * Math.PI * hz * (b * 512 + i) / 48000));
            p.process(block, block.length, 1, 1);
            if (b > 150) for (float x : block) energy += x * x;
        }
        return Math.sqrt(energy / (49 * 1024));
    }

    @Test
    void zeroReverbIsDryAndMonoCrossfeedIsBypassed() {
        var a = new AudioSettings();
        a.neutral = false;
        a.reverb = AudioSettings.Reverb.CONCERT_HALL;
        a.enhancements.reverbAmount = 0;
        a.enhancements.crossfeed = true;
        var p = new AudioProcessor();
        p.configure(a);
        p.begin(48000, 1);
        float[] data = new float[4096];
        for (int i = 0; i < data.length; i++) data[i] = (float) (.3 * Math.sin(i * .4));
        var dry = data.clone();
        p.process(data, data.length, 1, 1);
        assertArrayEquals(dry, data, 1e-6f);
    }

    @Test
    void environmentSlotOwnershipAndSavedProfilesAreIndependent() {
        var settings = new Settings();
        settings.validate();
        var e = settings.environment;
        e.select(SoundEnvironmentSettings.Preset.DEEP_CAVE);
        e.parameters.echoGain = .4;
        e.savePreset("Cave A");
        var saved = e.userPresets.get("Cave A");
        e.parameters.echoGain = .1;
        assertEquals(.4, saved.parameters.echoGain);
        e.loadPreset("Cave A");
        assertEquals(.4, e.parameters.echoGain);
        assertTrue(e.ownsReverb());
        settings.audio.selectProfile("NIGHT");
        assertEquals(SoundEnvironmentSettings.Preset.DEEP_CAVE, e.preset);
        String json = ConfigStore.JSON.toJson(settings);
        var loaded = ConfigStore.JSON.fromJson(json, Settings.class);
        loaded.validate();
        assertTrue(e.samePlayback(loaded.environment));
        assertTrue(settings.audio.samePlayback(loaded.audio));
        e.select(SoundEnvironmentSettings.Preset.VINYL);
        assertFalse(e.ownsReverb());
        var old = ConfigStore.JSON.fromJson("{\"audio\":{\"reverb\":\"CONCERT_HALL\",\"neutral\":false}}", Settings.class);
        old.validate();
        assertFalse(old.environment.active());
        assertEquals(1, old.audio.enhancements.reverbAmount);
        assertEquals(AudioEnhancements.Protection.SOFT, old.audio.enhancements.protection);
    }

    @Test
    void replayGainSourcesFallbackMalformedTagsAndPeakHeadroomAreHonest() {
        var tags = new HashMap<String, String>();
        tags.put("replaygain_track_gain", "3.0 dB");
        tags.put("replaygain_track_peak", "0.5");
        var r = LoudnessCache.tags(tags, AudioSettings.Normalization.ALBUM);
        assertNotNull(r);
        assertEquals(LoudnessCache.Source.TRACK_TAGS, r.source());
        assertEquals(-21, r.lufs());
        tags.put("replaygain_album_gain", "-2 dB");
        tags.put("replaygain_album_peak", "0.8");
        r = LoudnessCache.tags(tags, AudioSettings.Normalization.ALBUM);
        assertEquals(LoudnessCache.Source.ALBUM_TAGS, r.source());
        assertEquals(-16, r.lufs());
        tags.put("replaygain_album_gain", "NaN");
        assertEquals(LoudnessCache.Source.TRACK_TAGS, LoudnessCache.tags(tags, AudioSettings.Normalization.ALBUM).source());
        tags.put("replaygain_track_peak", "Infinity");
        assertNull(LoudnessCache.tags(tags, AudioSettings.Normalization.ALBUM));
    }

    @Test
    void rapidExtremeLiveEditsRemainFiniteAndConvolutionWorksInTheProcessor() throws Exception {
        var a = new AudioSettings();
        a.neutral = false;
        a.equalizer = true;
        a.limiter = true;
        a.reverb = AudioSettings.Reverb.SMALL_ROOM;
        a.enhancements.reverbEngine = AudioEnhancements.ReverbEngine.CONVOLUTION;
        var p = new AudioProcessor();
        p.configure(a.playbackCopy());
        p.begin(48000, 2);
        float[] data = new float[1024];
        for (int b = 0; b < 200; b++) {
            if (b % 5 == 0) {
                a.bands[b % 10] = b % 2 == 0 ? 12 : -12;
                a.width = b % 3 * .5;
                p.configure(a.playbackCopy());
            }
            for (int i = 0; i < 512; i++) data[2 * i] = data[2 * i + 1] = (float) (.7 * Math.sin((b * 512 + i) * .17));
            p.process(data, data.length, 1, 1);
            for (float x : data) assertTrue(Float.isFinite(x) && Math.abs(x) < 1);
            if (b % 10 == 0) Thread.sleep(2);
        }
        assertTrue(p.convolutionReady());
    }
}
