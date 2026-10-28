package com.darkmusic.client.gui;

import com.darkmusic.client.config.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

final class AudioAdvancedScreen extends AudioPage<AudioSettingsScreen.Sources> {
    AudioAdvancedScreen(AudioSettingsScreen.Sources app, Screen parent) {
        super(app, parent, "dsp.advanced");
    }

    @Override
    protected void changed() {
        app.settings().audio.profile = "CUSTOM";
        super.changed();
    }

    @Override
    protected void adjusted() {
        app.settings().audio.profile = "CUSTOM";
    }

    @Override
    protected void populate() {
        var a = app.settings().audio;
        var e = a.enhancements;
        section("dsp.advanced");
        info("dsp.neutral_scope");
        section("dsp.tones");
        choice("dsp.eq_mode", e.eqMode, AudioEnhancements.EqMode.values(), v -> e.eqMode = v);
        if (e.eqMode == AudioEnhancements.EqMode.PARAMETRIC) {
            info("dsp.eq_enable_hint");
            for (int i = 0; i < e.nodes.length; i++) {
                int n = i;
                numberedAction("dsp.node", n + 1, () -> show(new AudioNodeScreen(app, this, n, false)));
            }
        }
        toggle("dsp.bass_tone", () -> e.bassTone, v -> e.bassTone = v);
        if (e.bassTone) {
            slider("dsp.bass_gain", e.bassGain, -12, 12, 48, v -> e.bassGain = v, " dB");
            slider("dsp.bass_frequency", e.bassFrequency, 30, 500, 47, v -> e.bassFrequency = v, " Hz");
        }
        toggle("dsp.treble_tone", () -> e.trebleTone, v -> e.trebleTone = v);
        if (e.trebleTone) {
            slider("dsp.treble_gain", e.trebleGain, -12, 12, 48, v -> e.trebleGain = v, " dB");
            slider("dsp.treble_frequency", e.trebleFrequency, 1000, 16000, 150, v -> e.trebleFrequency = v, " Hz");
        }
        section("dsp.dynamics");
        toggle("audio.darkmusic.night", () -> a.compressor, v -> a.compressor = v);
        if (a.compressor) {
            slider("dsp.threshold", e.threshold, -60, 0, 120, v -> e.threshold = v, " dBFS");
            slider("dsp.ratio", e.ratio, 1, 20, 76, v -> e.ratio = v, ":1");
            slider("dsp.attack", e.attack, .001, .2, 199, v -> e.attack = v, " s");
            slider("dsp.release", e.release, .02, 2, 198, v -> e.release = v, " s");
            slider("dsp.knee", e.knee, 0, 24, 48, v -> e.knee = v, " dB");
            slider("dsp.makeup", e.makeup, 0, 12, 48, v -> e.makeup = v, " dB");
        }
        toggle("audio.darkmusic.limiter", () -> a.limiter, v -> a.limiter = v);
        if (a.limiter)
            choice("dsp.protection", e.protection, AudioEnhancements.Protection.values(), v -> e.protection = v);
        for (int i = 0; i < e.dynamic.length; i++) {
            int n = i;
            numberedAction("dsp.dynamic_node", n + 1, () -> show(new AudioNodeScreen(app, this, n, true)));
        }
        toggle("dsp.deesser", () -> e.deEsser, v -> e.deEsser = v);
        if (e.deEsser) {
            slider("dsp.deesser_frequency", e.deEsserFrequency, 2000, 12000, 100, v -> e.deEsserFrequency = v, " Hz");
            slider("dsp.deesser_threshold", e.deEsserThreshold, -60, -6, 108, v -> e.deEsserThreshold = v, " dBFS");
            slider("dsp.deesser_strength", e.deEsserStrength, 0, 1, 100, v -> e.deEsserStrength = v, "");
        }
        section("dsp.space");
        toggle("dsp.crossfeed", () -> e.crossfeed, v -> e.crossfeed = v);
        if (e.crossfeed) slider("dsp.crossfeed_amount", e.crossfeedAmount, 0, .5, 50, v -> e.crossfeedAmount = v, "");
        choice("dsp.reverb_engine", e.reverbEngine, AudioEnhancements.ReverbEngine.values(), v -> e.reverbEngine = v);
        if (e.reverbEngine == AudioEnhancements.ReverbEngine.CONVOLUTION) {
            choice("dsp.impulse", e.impulse, AudioEnhancements.Impulse.values(), v -> e.impulse = v);
            info("dsp.modelled_ir");
        }
        slider("dsp.reverb_amount", e.reverbAmount, 0, 1, 100, v -> e.reverbAmount = v, "");
        if (a.reverb == AudioSettings.Reverb.DRY) info("audio.reverb_disabled");
        info(() -> Text.get(app.settings().environment.ownsReverb() ? "environment.owns_reverb" : "environment.allows_reverb"));
        section("dsp.tempo");
        toggle("dsp.preserve_pitch", () -> e.preservePitch, v -> e.preservePitch = v);
    }
}
