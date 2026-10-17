package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class SettingsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private int page;

    public SettingsScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("DarkMusic Settings"), parent);
        this.app = app;
    }

    private void button(String label, int y, Runnable action, String help) {
        addRenderableWidget(Help.tip(Button.builder(Component.literal(label), b -> action.run()).bounds(12, y, width - 24, 20).build(), help));
    }

    private void action(String label, int y, Runnable action, String help) {
        addRenderableWidget(Help.tip(new SettingRow(12, y, width - 24, label, null, action), help));
    }

    private void toggle(String label, int y, BooleanSupplier get, Consumer<Boolean> set, String help) {
        addRenderableWidget(Help.tip(new SettingRow(12, y, width - 24, label, get, () -> {
            set.accept(!get.getAsBoolean());
            app.save();
        }), help));
    }

    @Override
    protected void init() {
        int y = 34;
        if (page == 0) {
            toggle("Autoplay on Launch", y, () -> app.settings.autoplay, v -> app.settings.autoplay = v, "Automatically start the library after Minecraft launches. Disabled by default.");
            y += 24;
            action("Choose Music Folder", y, () -> show(new FolderScreen(app, this)), "Choose the local folder containing your music. Subfolders are included.");
            y += 24;
            toggle("Suppress Vanilla Background Music", y, () -> app.settings.suppressVanillaMusic, v -> {
                app.settings.suppressVanillaMusic = v;
                if (v) minecraft.getMusicManager().stopPlaying();
            }, "Stop Minecraft's background music while keeping ordinary sound effects.");
            y += 24;
            action("Music Visualizer", y, () -> show(new VisualizerSettingsScreen(app, this)), "Independent player/HUD spectra, bars, sensitivity, smoothing, size, and HUD position.");
        } else {
            toggle("Now-Playing HUD", y, () -> app.settings.hudEnabled, v -> app.settings.hudEnabled = v, "Show the current song during gameplay. Hidden with F1 and while menus are open.");
            y += 24;
            toggle("HUD Artwork", y, () -> app.settings.hudArtwork, v -> app.settings.hudArtwork = v, "Show embedded artwork on the left of the HUD, preserving its proportions.");
            y += 24;
            toggle("HUD Progress", y, () -> app.settings.hudProgress, v -> app.settings.hudProgress = v, "Show the current track's progress below the title and artist.");
            y += 24;
            action("Edit HUD Layout", y, () -> show(new HudEditorScreen(app, this)), "Drag the preview anywhere on screen and adjust the scale of the entire HUD.");
        }
        button(page == 0 ? "HUD Settings" : "General Settings", height - 28, () -> {
            page = 1 - page;
            rebuildWidgets();
        }, "Switch settings pages. Changes save automatically; Escape returns.");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "DarkMusic Settings", 12, 12, 0xFFB5B0FF);
        super.extractRenderState(g, x, y, d);
    }
}
