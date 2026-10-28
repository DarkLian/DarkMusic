package com.darkmusic.client.gui;

import com.darkmusic.client.config.PresetNames;
import com.darkmusic.client.config.SoundEnvironmentSettings.Preset;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Built-in environments, the Custom recipe and saved environments. Choosing the selected built-in again restores its
 * recipe.
 */
final class EnvironmentPickerScreen extends AudioPage<AudioSettingsScreen.Sources> {
    static final List<List<Preset>> GROUPS = List.of(
        List.of(Preset.OFF),
        List.of(Preset.OLD_RADIO, Preset.TELEPHONE, Preset.WALKIE_TALKIE, Preset.VINYL, Preset.CASSETTE),
        List.of(Preset.BEDROOM, Preset.BATHROOM, Preset.CONCERT_HALL, Preset.CATHEDRAL, Preset.WAREHOUSE, Preset.STADIUM, Preset.CAR_CABIN, Preset.STAIRWELL),
        List.of(Preset.OPEN_MEADOW, Preset.FOREST_CLEARING, Preset.DEEP_CAVE, Preset.TUNNEL, Preset.NEXT_ROOM, Preset.DISTANT_SPEAKERS),
        List.of(Preset.UNDERWATER, Preset.DREAMSCAPE, Preset.ORBITAL_ECHO));
    private boolean revealSelection = true;

    EnvironmentPickerScreen(AudioSettingsScreen.Sources app, Screen parent) {
        super(app, parent, "environment.choose");
    }

    @Override
    protected void populate() {
        var e = app.settings().environment;
        for (int i = 0; i < GROUPS.size(); i++) {
            section("environment.category." + i);
            for (var preset : GROUPS.get(i)) {
                String id = preset.name().toLowerCase(Locale.ROOT);
                String label = Text.get("dsp.value." + id);
                boolean selected = e.savedName() == null && !e.custom && e.preset == preset;
                int row = rowCount();
                labeledAction(label, Text.get("environment.description." + id), () -> {
                    e.select(preset);
                    changed();
                }).description(Text.get("environment.description." + id)).option(selected);
                if (revealSelection && selected) revealRow(row);
            }
        }
        String chosen = e.savedName();
        section("environment.custom");
        boolean customSelected = chosen == null && e.custom;
        int customRow = rowCount();
        labeledAction(Text.get("environment.custom"), Text.get("environment.custom.description"), () -> {
            e.selectCustom();
            changed();
        }).besideLabel().option(customSelected);
        if (revealSelection && customSelected) revealRow(customRow);
        section("environment.saved");
        if (e.userPresets.isEmpty()) info("environment.saved_empty");
        for (String name : List.copyOf(e.userPresets.keySet())) {
            int row = rowCount();
            labeledAction(name, Text.get("environment.load"), () -> {
                e.loadPreset(name);
                changed();
            }).besideLabel().option(name.equals(chosen));
            if (revealSelection && name.equals(chosen)) revealRow(row);
        }
        // Rename and Delete act on the saved environment being used.
        if (!e.userPresets.isEmpty()) {
            var rename = labeledAction(chosen == null ? Text.get("environment.rename") : Text.get("environment.rename") + ": " + chosen, Text.get("environment.rename"),
                () -> show(TextPromptScreen.validated(this, Text.get("environment.rename"), chosen, 256, renamed -> {
                    PresetNames.rename(e.userPresets, chosen, renamed);
                    if (chosen.equals(e.selectedSaved) && e.userPresets.containsKey(renamed)) e.selectedSaved = renamed;
                    app.save();
                    return true;
                }))).runs();
            rename.active = chosen != null;
            var delete = labeledAction(chosen == null ? Text.get("environment.delete") : Text.get("environment.delete") + ": " + chosen, Text.get("environment.delete"),
                () -> show(new ConfirmationScreen(this, Text.component("environment.delete"), Text.component("environment.delete_confirm", chosen), Text.component("text.darkmusic.delete"), () -> {
                    e.userPresets.remove(chosen);
                    if (chosen.equals(e.selectedSaved)) e.selectedSaved = null;
                    app.save();
                    return CompletableFuture.completedFuture(null);
                }))).destructive();
            delete.active = chosen != null;
        }
        revealSelection = false;
    }
}
