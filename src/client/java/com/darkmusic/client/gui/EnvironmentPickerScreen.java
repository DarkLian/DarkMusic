package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.SoundEnvironmentSettings.Preset;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

final class EnvironmentPickerScreen extends AudioPage {
    static final List<List<Preset>> GROUPS = List.of(
        List.of(Preset.OFF),
        List.of(Preset.OLD_RADIO, Preset.TELEPHONE, Preset.WALKIE_TALKIE, Preset.VINYL, Preset.CASSETTE),
        List.of(Preset.BEDROOM, Preset.BATHROOM, Preset.CONCERT_HALL, Preset.CATHEDRAL, Preset.WAREHOUSE, Preset.STADIUM),
        List.of(Preset.OPEN_MEADOW, Preset.FOREST_CLEARING, Preset.DEEP_CAVE, Preset.TUNNEL, Preset.NEXT_ROOM, Preset.DISTANT_SPEAKERS),
        List.of(Preset.UNDERWATER, Preset.DREAMSCAPE));
    private final Screen parent;

    EnvironmentPickerScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "environment.choose");
        this.parent = parent;
    }

    @Override
    protected void populate() {
        for (int i = 0; i < GROUPS.size(); i++) {
            section("environment.category." + i);
            for (var preset : GROUPS.get(i)) {
                String id = preset.name().toLowerCase(Locale.ROOT);
                String label = Text.get("dsp.value." + id);
                if (app.settings.environment.preset == preset) label = Text.get("environment.selected", label);
                labeledAction(label, Text.get("environment.description." + id), () -> {
                    app.settings.environment.select(preset);
                    app.save();
                    show(parent);
                });
                info("environment.description." + id);
            }
        }
    }
}
