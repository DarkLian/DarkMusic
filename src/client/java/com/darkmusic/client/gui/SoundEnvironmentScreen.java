package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.*;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class SoundEnvironmentScreen extends AudioPage {
    private boolean advanced;
    private String notice = "";

    public SoundEnvironmentScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "environment.title");
    }

    @Override
    protected void changed() {
        var e = app.settings.environment;
        e.custom = !e.parameters.same(SoundEnvironmentSettings.recipe(e.preset)) || e.intensity != 1 || e.texture || e.ambience;
        super.changed();
    }

    @Override
    protected void adjusted() {
        app.settings.environment.custom = true;
    }

    @Override
    protected void populate() {
        var e = app.settings.environment;
        var p = e.parameters;
        section("environment.title");
        info("environment.scope");
        toggle("environment.enabled", () -> e.enabled, v -> {
            if (v && e.preset == SoundEnvironmentSettings.Preset.OFF) e.select(SoundEnvironmentSettings.Preset.BEDROOM);
            e.enabled = v;
        });
        labeledAction(Text.get("environment.preset") + ": " + Text.get("dsp.value." + e.preset.name().toLowerCase(java.util.Locale.ROOT)),
            Text.get("environment.preset.help"), () -> show(new EnvironmentPickerScreen(app, this)));
        info(() -> Text.get("environment.description." + e.preset.name().toLowerCase(java.util.Locale.ROOT)));
        if (e.custom) info("environment.custom_recipe");
        slider("environment.intensity", e.intensity * 100, 0, 100, 100, v -> e.intensity = v / 100, "%");
        info(() -> Text.get(e.ownsReverb() ? "environment.owns_reverb" : "environment.allows_reverb"));
        section("environment.parameters");
        if (!e.active()) info("environment.inactive");
        int parametersStart = rowCount();
        var recipe = SoundEnvironmentSettings.recipe(e.preset);
        boolean device = e.preset.ordinal() >= SoundEnvironmentSettings.Preset.OLD_RADIO.ordinal() && e.preset.ordinal() <= SoundEnvironmentSettings.Preset.CASSETTE.ordinal();
        boolean outdoors = e.preset == SoundEnvironmentSettings.Preset.OPEN_MEADOW || e.preset == SoundEnvironmentSettings.Preset.FOREST_CLEARING || e.preset == SoundEnvironmentSettings.Preset.UNDERWATER;
        if (device || advanced) toggle("environment.texture", () -> e.texture, v -> e.texture = v);
        if (outdoors || advanced) toggle("environment.ambience", () -> e.ambience, v -> e.ambience = v);
        if (p.acoustic) slider("environment.distance", p.distance, 0, 1, 100, v -> p.distance = v, "");
        if (p.acoustic && (recipe.wet > 0 || advanced)) {
            if (p.reverbEngine == AudioEnhancements.ReverbEngine.ALGORITHMIC)
                slider("environment.room_size", p.roomSize, .3, 2.5, 44, v -> p.roomSize = v, "");
            slider("environment.wet", p.wet, 0, .55, 55, v -> p.wet = v, "");
        }
        if (recipe.echoGain > 0 || advanced)
            slider("environment.echo_gain", p.echoGain, 0, .55, 55, v -> p.echoGain = v, "");
        if (recipe.modulationMs > 0 || advanced)
            slider("environment.modulation", p.modulationMs, 0, 3, 60, v -> p.modulationMs = v, " ms");
        if (advanced && e.preset != SoundEnvironmentSettings.Preset.OFF) {
            slider("dsp.high_pass", p.highPass, 20, 2000, 198, v -> {
                p.highPass = v;
                p.lowPass = Math.max(p.lowPass, v * 1.2);
            }, " Hz");
            slider("dsp.low_pass", p.lowPass, Math.max(100, p.highPass * 1.2), 20000, 199, v -> p.lowPass = v, " Hz");
            slider("environment.presence", p.presenceGain, -6, 6, 48, v -> p.presenceGain = v, " dB");
            slider("environment.presence_frequency", p.presenceFrequency, 100, 12000, 119, v -> p.presenceFrequency = v, " Hz");
            slider("environment.drive", p.drive, 0, 3, 60, v -> p.drive = v, "");
            if (!p.acoustic) slider("environment.distance", p.distance, 0, 1, 100, v -> p.distance = v, "");
            slider("environment.pan", p.pan, -1, 1, 40, v -> p.pan = v, "");
            toggle("environment.mono", () -> p.mono, v -> p.mono = v);
            toggle("environment.acoustic", () -> p.acoustic, v -> p.acoustic = v);
            if (p.acoustic) {
                choice("dsp.reverb_engine", p.reverbEngine, AudioEnhancements.ReverbEngine.values(), v -> p.reverbEngine = v);
                if (p.reverbEngine == AudioEnhancements.ReverbEngine.CONVOLUTION) {
                    choice("dsp.impulse", p.impulse, AudioEnhancements.Impulse.values(), v -> p.impulse = v);
                    info("dsp.modelled_ir");
                } else {
                    slider("environment.decay", p.decay, .1, 8, 79, v -> p.decay = v, " s");
                    slider("environment.predelay", p.predelay, 0, .25, 50, v -> p.predelay = v, " s");
                }
                slider("environment.damping", p.damping, .05, .95, 45, v -> p.damping = v, "");
            }
            slider("environment.echo_time", p.echoTime, .03, 1, 97, v -> p.echoTime = v, " s");
            slider("environment.modulation_rate", p.modulationHz, .1, 6, 59, v -> p.modulationHz = v, " Hz");
            if (e.texture || e.ambience)
                slider("environment.noise", p.noiseDb, -72, -30, 42, v -> p.noiseDb = v, " dBFS");
            for (int i = 0; i < p.reflectionTimes.length; i++) {
                int n = i;
                section("environment.reflection");
                slider("environment.reflection_time", p.reflectionTimes[n], .001, 1.6, 1599, v -> p.reflectionTimes[n] = v, " s");
                slider("environment.reflection_gain", p.reflectionGains[n], -.35, .35, 70, v -> p.reflectionGains[n] = v, "");
            }
        }
        enableSince(parametersStart, e.enabled && e.preset != SoundEnvironmentSettings.Preset.OFF);
        info("environment.dependencies");
        toggle("environment.advanced", () -> advanced, v -> advanced = v);
        section("environment.saved");
        info(() -> Text.get("environment.saved_count", e.userPresets.size(), PresetNames.ENVIRONMENT_LIMIT));
        info(() -> app.warning.equals(Text.get("error.save_settings")) ? app.warning : notice);
        action("environment.save", () -> show(PresetDialogs.save(this, "environment.save", e.userPresets, PresetNames.ENVIRONMENT_LIMIT, (name, replace) -> {
            e.savePreset(name, replace);
            notice = Text.get("preset.saved");
            app.save();
        })));
        for (String name : List.copyOf(e.userPresets.keySet())) {
            info(() -> name);
            action("environment.load", () -> {
                e.loadPreset(name);
                app.save();
                rebuildWidgets();
            });
            action("environment.rename", () -> show(TextPromptScreen.validated(this, Text.get("environment.rename"), name, 256, renamed -> {
                PresetNames.rename(e.userPresets, name, renamed);
                notice = Text.get("preset.renamed");
                app.save();
                return true;
            })));
            action("environment.delete", () -> show(new ConfirmationScreen(this, Text.component("environment.delete"), Text.component("environment.delete_confirm", name), Text.component("text.darkmusic.delete"), () -> {
                e.userPresets.remove(name);
                notice = Text.get("preset.deleted");
                app.save();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            })));
        }
        action("environment.reset", () -> {
            e.select(e.preset);
            changed();
        });
    }
}
