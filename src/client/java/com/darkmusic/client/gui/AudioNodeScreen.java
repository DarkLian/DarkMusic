package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.AudioEnhancements;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

final class AudioNodeScreen extends AudioPage {
    private final int index;
    private final boolean dynamic;

    AudioNodeScreen(DarkMusicClient app, Screen parent, int index, boolean dynamic) {
        super(app, parent, dynamic ? "dsp.dynamic_node" : "dsp.node");
        this.index = index;
        this.dynamic = dynamic;
    }

    @Override
    protected void changed() {
        app.settings.audio.profile = "CUSTOM";
        super.changed();
    }

    @Override
    protected void adjusted() {
        app.settings.audio.profile = "CUSTOM";
    }

    @Override
    protected void populate() {
        var e = app.settings.audio.enhancements;
        var n = (dynamic ? e.dynamic : e.nodes)[index];
        section(dynamic ? "dsp.dynamic_node" : "dsp.node");
        info(() -> Text.get("dsp.node_number", index + 1));
        if (app.settings.audio.neutral) info("dsp.neutral_scope");
        else if (!dynamic && !app.settings.audio.equalizer) info("dsp.eq_enable_hint");
        toggle("dsp.node_enabled", () -> n.enabled, v -> n.enabled = v);
        int parameterStart = rowCount();
        if (!dynamic)
            choice("dsp.node_type", n.type, new AudioEnhancements.Type[]{AudioEnhancements.Type.PEAK, AudioEnhancements.Type.LOW_SHELF, AudioEnhancements.Type.HIGH_SHELF, AudioEnhancements.Type.LOW_PASS, AudioEnhancements.Type.HIGH_PASS}, v -> n.type = v);
        slider("dsp.node_frequency", n.frequency, 20, 20000, 1998, v -> n.frequency = v, " Hz");
        if (dynamic || n.type != AudioEnhancements.Type.LOW_PASS && n.type != AudioEnhancements.Type.HIGH_PASS)
            slider("dsp.node_gain", n.gain, -12, 12, 96, v -> n.gain = v, " dB");
        slider("dsp.node_q", n.q, .25, 8, 155, v -> n.q = v, "");
        if (dynamic) {
            slider("dsp.threshold", n.threshold, -60, 0, 120, v -> n.threshold = v, " dBFS");
            slider("dsp.dynamic_range", n.range, -12, 6, 72, v -> n.range = v, " dB");
            slider("dsp.attack", n.attack, .001, .2, 199, v -> n.attack = v, " s");
            slider("dsp.release", n.release, .02, 2, 198, v -> n.release = v, " s");
        }
        enableSince(parameterStart, n.enabled);
    }
}
