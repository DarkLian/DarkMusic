package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

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
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
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
            toggle(Text.get("text.darkmusic.autoplay_on_launch"), y, () -> app.settings.autoplay, v -> app.settings.autoplay = v, Text.get("text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default"));
            y += 24;
            action(Text.get("text.darkmusic.choose_music_folder"), y, () -> show(new FolderScreen(app, this)), Text.get("text.darkmusic.choose_the_local_folder_containing_your_music_subfolders_are_included"));
            y += 24;
            toggle(Text.get("text.darkmusic.suppress_vanilla_background_music"), y, () -> app.settings.suppressVanillaMusic, v -> {
                app.settings.suppressVanillaMusic = v;
                if (v) minecraft.getMusicManager().stopPlaying();
            }, Text.get("text.darkmusic.stop_minecraft_s_background_music_while_keeping_ordinary_sound_effects"));
            y += 24;
            action(Text.get("text.darkmusic.music_visualizer"), y, () -> show(new VisualizerSettingsScreen(app, this)), Text.get("text.darkmusic.independent_player_hud_spectra_bars_sensitivity_smoothing_size_and_hud_position"));
        } else {
            toggle(Text.get("text.darkmusic.now_playing_hud"), y, () -> app.settings.hudEnabled, v -> app.settings.hudEnabled = v, Text.get("text.darkmusic.show_the_current_song_during_gameplay_hidden_with_f1_and_while_menus_are_open"));
            y += 24;
            toggle(Text.get("text.darkmusic.hud_artwork"), y, () -> app.settings.hudArtwork, v -> app.settings.hudArtwork = v, Text.get("text.darkmusic.show_embedded_artwork_on_the_left_of_the_hud_preserving_its_proportions"));
            y += 24;
            toggle(Text.get("text.darkmusic.hud_progress"), y, () -> app.settings.hudProgress, v -> app.settings.hudProgress = v, Text.get("text.darkmusic.show_the_current_track_s_progress_below_the_title_and_artist"));
            y += 24;
            action(Text.get("text.darkmusic.edit_hud_layout"), y, () -> show(new HudEditorScreen(app, this)), Text.get("text.darkmusic.drag_the_preview_anywhere_on_screen_and_adjust_the_scale_of_the_entire_hud"));
        }
        button(page == 0 ? Text.get("text.darkmusic.hud_settings") : Text.get("text.darkmusic.general_settings"), height - 28, () -> {
            page = 1 - page;
            rebuildWidgets();
        }, Text.get("text.darkmusic.switch_settings_pages_changes_save_automatically_escape_returns"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, Text.get("text.darkmusic.darkmusic_settings"), 12, 12, 0xFFB5B0FF);
        super.extractRenderState(g, x, y, d);
    }
}
