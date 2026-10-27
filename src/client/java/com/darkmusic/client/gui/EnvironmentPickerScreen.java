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
        List.of(Preset.BEDROOM, Preset.BATHROOM, Preset.CONCERT_HALL, Preset.CATHEDRAL, Preset.WAREHOUSE, Preset.STADIUM, Preset.CAR_CABIN, Preset.STAIRWELL),
        List.of(Preset.OPEN_MEADOW, Preset.FOREST_CLEARING, Preset.DEEP_CAVE, Preset.TUNNEL, Preset.NEXT_ROOM, Preset.DISTANT_SPEAKERS),
        List.of(Preset.UNDERWATER, Preset.DREAMSCAPE, Preset.ORBITAL_ECHO));
    private boolean revealSelection = true;

    EnvironmentPickerScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "environment.choose");
    }

    @Override
    protected void populate() {
        for (int i = 0; i < GROUPS.size(); i++) {
            section("environment.category." + i);
            for (var preset : GROUPS.get(i)) {
                String id = preset.name().toLowerCase(Locale.ROOT);
                String label = Text.get("dsp.value." + id);
                int row = rowCount();
                labeledAction(label, Text.get("environment.description." + id), () -> {
                    app.settings.environment.select(preset);
                    app.save();
                    rebuildWidgets();
                }).description(Text.get("environment.description." + id)).option(app.settings.environment.preset == preset);
                if (revealSelection && app.settings.environment.preset == preset) revealRow(row);
            }
        }
        revealSelection = false;
    }
}
