package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * One scrollable settings form with a fixed, independently contained live preview.
 */
public final class SettingsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final List<Placed> controls = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private com.darkmusic.client.hud.HudScale previewWheel = new com.darkmusic.client.hud.HudScale();
    private double previewScale = 1;
    private Settings shownSettings;
    private long scaleChanged;
    private int folderY;
    private String folderPath = "";
    private List<net.minecraft.util.FormattedCharSequence> folderLines = List.of();
    private int scroll, top, bottom, formWidth, cursor, previewX, previewY, previewWidth, previewHeight;

    public SettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
        this.app = app;
    }

    private void section(String key) {
        sections.add(new Section(Text.get(key), cursor));
        cursor += 22;
    }

    private void put(AbstractWidget widget, String help) {
        if (!help.isEmpty()) Help.tip(widget, help);
        controls.add(new Placed(addRenderableWidget(widget), cursor));
        cursor += 24;
    }

    private void toggle(String key, BooleanSupplier get, Consumer<Boolean> set, String help) {
        put(new SettingRow(12, 0, formWidth, Text.get(key), get, () -> {
            set.accept(!get.getAsBoolean());
            app.save();
        }), Text.get(help));
    }

    private void action(String key, Runnable action, String help) {
        put(new SettingRow(12, 0, formWidth, Text.get(key), null, action), Text.get(help));
    }

    private void finish(String key, java.util.function.Supplier<Settings.SurfaceFinish> get, Consumer<Settings.SurfaceFinish> set) {
        action(key, () -> {
            set.accept(get.get() == Settings.SurfaceFinish.FLAT ? Settings.SurfaceFinish.BEVELED : Settings.SurfaceFinish.FLAT);
            app.save();
            rebuildWidgets();
        }, "finish.darkmusic.help");
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get(key) + ": " + Text.get("finish.darkmusic." + get.get().name().toLowerCase(Locale.ROOT))));
    }

    private void slider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        put(new ValueSlider(12, 0, formWidth, initial, label, v -> {
            change.accept(v);
            app.configureVisualizer();
        }, app::save, steps), Text.get(help));
    }

    @Override
    protected void init() {
        if (shownSettings != null && shownSettings != app.settings) {
            previewScale = 1;
            scaleChanged = 0;
            scroll = 0;
            previewWheel = new com.darkmusic.client.hud.HudScale();
        }
        shownSettings = app.settings;
        controls.clear();
        sections.clear();
        cursor = 0;
        boolean wide = width >= 600;
        formWidth = wide ? Math.min(440, width / 2 - 20) : width - 24;
        previewX = wide ? formWidth + 32 : 12;
        previewY = 32;
        previewWidth = wide ? width - previewX - 12 : width - 24;
        previewHeight = wide ? height - 44 : Math.min(100, height / 3);
        top = wide ? 32 : previewY + previewHeight + 8;
        bottom = height - 12;
        var s = app.settings;
        section("text.darkmusic.general_settings");
        toggle("text.darkmusic.autoplay_on_launch", () -> s.autoplay, v -> s.autoplay = v, "text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default");
        action("text.darkmusic.choose_music_folder", () -> DesktopFolderPicker.open(app, this::isCurrent), "text.darkmusic.choose_the_local_folder_containing_your_music_subfolders_are_included");
        folderY = cursor;
        folderPath = app.library.root().toString();
        folderLines = font.split(net.minecraft.network.chat.Component.literal(folderPath), formWidth - 14);
        cursor += folderLines.size() * 10 + 4;
        toggle("text.darkmusic.suppress_vanilla_background_music", () -> s.suppressVanillaMusic, v -> {
            s.suppressVanillaMusic = v;
            if (v) minecraft.getMusicManager().stopPlaying();
        }, "text.darkmusic.stop_minecraft_s_background_music_while_keeping_ordinary_sound_effects");
        section("text.darkmusic.hud_settings");
        toggle("text.darkmusic.now_playing_hud", () -> s.hudEnabled, v -> s.hudEnabled = v, "hud.darkmusic.master_help");
        action("circle.darkmusic.style", () -> {
            s.hudStyle = s.hudStyle == Settings.HudStyle.CLASSIC ? Settings.HudStyle.CIRCULAR : Settings.HudStyle.CLASSIC;
            app.save();
            rebuildWidgets();
        }, "circle.darkmusic.style_help");
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("circle.darkmusic.style") + ": " + Text.get("circle.darkmusic.style." + s.hudStyle.name().toLowerCase(Locale.ROOT))));
        boolean classic = s.hudStyle == Settings.HudStyle.CLASSIC;
        if (classic)
            toggle("text.darkmusic.hud_artwork", () -> s.hudArtwork, v -> s.hudArtwork = v, "hud.darkmusic.artwork_help");
        toggle("text.darkmusic.hud_progress", () -> s.hudProgress, v -> s.hudProgress = v, classic ? "text.darkmusic.show_the_current_track_s_progress_below_the_title_and_artist" : "circle.darkmusic.progress_help");
        toggle("hud.darkmusic.spectrum", () -> s.hudVisualizer, v -> s.hudVisualizer = v, "hud.darkmusic.spectrum_help");
        if (!classic) {
            toggle("circle.darkmusic.text", () -> s.circularText, v -> s.circularText = v, "circle.darkmusic.text_help");
            toggle("circle.darkmusic.pulse", () -> s.circularIconPulse, v -> s.circularIconPulse = v, "circle.darkmusic.pulse_help");
        }
        action("style.darkmusic.spectrum", () -> {
            if (classic)
                s.spectrumStyle = Settings.SpectrumStyle.values()[(s.spectrumStyle.ordinal() + 1) % Settings.SpectrumStyle.values().length];
            else s.circularSpectrumStyle = switch (s.circularSpectrumStyle) {
                case BARS -> Settings.SpectrumStyle.FILLED_WAVE;
                case FILLED_WAVE -> Settings.SpectrumStyle.FILLED_SPIKE;
                default -> Settings.SpectrumStyle.BARS;
            };
            app.save();
            rebuildWidgets();
        }, classic ? "style.darkmusic.spectrum_help" : "circle.darkmusic.spectrum_help");
        var chosenStyle = classic ? s.spectrumStyle : s.circularSpectrumStyle;
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("style.darkmusic.spectrum") + ": " + Text.get((classic ? "style.darkmusic.spectrum." : "circle.darkmusic.spectrum.") + chosenStyle.name().toLowerCase(Locale.ROOT))));
        if (classic) {
            action("style.darkmusic.shape", () -> {
                s.hudShape = Settings.HudShape.values()[(s.hudShape.ordinal() + 1) % Settings.HudShape.values().length];
                app.save();
                rebuildWidgets();
            }, "style.darkmusic.shape_help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("style.darkmusic.shape") + ": " + Text.get("style.darkmusic.shape." + s.hudShape.name().toLowerCase(Locale.ROOT))));
            toggle("style.darkmusic.raised", () -> s.hudRaised, v -> s.hudRaised = v, "style.darkmusic.raised_help");
            action("hud.darkmusic.position", () -> {
                s.spectrumPosition = s.spectrumPosition == Settings.SpectrumPosition.ABOVE ? Settings.SpectrumPosition.BELOW : Settings.SpectrumPosition.ABOVE;
                app.save();
                rebuildWidgets();
            }, "hud.darkmusic.position_help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("hud.darkmusic.position") + ": " + Text.get("hud.darkmusic.position." + s.spectrumPosition.name().toLowerCase(Locale.ROOT))));
            action("hud.darkmusic.direction", () -> {
                s.spectrumDirection = s.spectrumDirection == Settings.SpectrumDirection.UPWARD ? Settings.SpectrumDirection.DOWNWARD : Settings.SpectrumDirection.UPWARD;
                app.save();
                rebuildWidgets();
            }, "hud.darkmusic.direction_help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("hud.darkmusic.direction") + ": " + Text.get("hud.darkmusic.direction." + s.spectrumDirection.name().toLowerCase(Locale.ROOT))));
        }
        slider(s.visualizerSmoothing / .95, v -> Text.get("format.darkmusic.smoothing", Math.round(s.visualizerSmoothing * 100)), v -> s.visualizerSmoothing = v * .95, 19, "text.darkmusic.higher_values_give_slower_attack_and_decay_pausing_freezes_the_actual_spectrum_sto");
        slider((s.visualizerSensitivity - .25) / 3.75, v -> Text.get("format.darkmusic.sensitivity", String.format(Locale.ROOT, "%.2f", s.visualizerSensitivity)), v -> s.visualizerSensitivity = .25 + v * 3.75, 15, "text.darkmusic.boost_quiet_spectra_or_reduce_strong_levels_samples_follow_playback_speed_and_dark");
        slider((s.visualizerBars - 8) / 56.0, v -> Text.get("format.darkmusic.bars", s.visualizerBars), v -> s.visualizerBars = 8 + (int) Math.round(v * 56), 14, "text.darkmusic.8_64_logarithmic_frequency_bands_from_bass_to_treble_narrow_areas_combine_bars_to");
        finish("finish.darkmusic.spectrum", () -> s.spectrumFinish, v -> s.spectrumFinish = v);
        finish("finish.darkmusic.progress", () -> s.progressFinish, v -> s.progressFinish = v);
        action("colors.darkmusic.spectrum", () -> show(new ColorsScreen(app, this, true)), "colors.darkmusic.help");
        action("colors.darkmusic.progress", () -> show(new ColorsScreen(app, this, false)), "colors.darkmusic.help");
        if (classic)
            slider(s.hudBackgroundTransparency, v -> Text.get("hud.darkmusic.transparency", Math.round(s.hudBackgroundTransparency * 100)), v -> s.hudBackgroundTransparency = v, 100, "hud.darkmusic.transparency_help");
        action("text.darkmusic.edit_hud_layout", () -> show(new HudEditorScreen(app, this)), "hud.darkmusic.help");
        section("reset.darkmusic.section");
        action("reset.darkmusic.button", () -> show(new ConfirmationScreen(this, Text.component("reset.darkmusic.title"),
            Text.component("reset.darkmusic.body"), Text.component("reset.darkmusic.confirm"), app::resetDefaults)), "reset.darkmusic.help");
        position();
        app.configureVisualizer();
    }

    private void position() {
        scroll = Math.clamp(scroll, 0, Math.max(0, cursor - (bottom - top)));
        for (var p : controls) {
            int y = top + p.y() - scroll;
            p.widget().setY(y);
            p.widget().visible = y >= top && y + 20 <= bottom;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (app.settings.hudEnabled && x >= previewX && x < previewX + previewWidth && y >= previewY + 20 && y < previewY + previewHeight && dy != 0) {
            previewScale = previewWheel.scroll(previewScale, dy);
            fitPreview();
            scaleChanged = System.nanoTime();
            return true;
        }
        if (x >= 12 && x <= 12 + formWidth && y >= top && y <= bottom) {
            scroll -= (int) Math.round(dy * 24);
            position();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public void tick() {
        if (!folderPath.equals(app.library.root().toString())) rebuildWidgets();
    }

    private void fitPreview() {
        var layout = com.darkmusic.client.hud.HudLayout.content(app.settings, Integer.MAX_VALUE);
        double fit = Math.min((previewWidth - 16.0) / layout.width(), (previewHeight - 30.0) / layout.height());
        previewScale = Math.min(previewScale, Math.max(.1, Math.floor(fit * 10) / 10));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        g.outline(previewX, previewY, previewWidth, previewHeight, 0xFF34394F);
        g.text(font, Text.get("hud.darkmusic.live_preview"), previewX + 6, previewY + 6, 0xFFB5B0FF);
        g.enableScissor(previewX + 2, previewY + 20, previewX + previewWidth - 2, previewY + previewHeight - 2);
        for (int cy = previewY + 20; cy < previewY + previewHeight - 2; cy += 16)
            for (int cx = previewX + 2; cx < previewX + previewWidth - 2; cx += 16)
                g.fill(cx, cy, Math.min(cx + 16, previewX + previewWidth - 2), Math.min(cy + 16, previewY + previewHeight - 2), ((cx - previewX) / 16 + (cy - previewY) / 16) % 2 == 0 ? 0xFF20283A : 0xFF293246);
        fitPreview();
        NowPlayingHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30, previewScale, longText);
        g.disableScissor();
        if (System.nanoTime() - scaleChanged < 2_000_000_000L) {
            String scale = Text.get("hud.darkmusic.preview_scale", Math.round(previewScale * 100));
            g.text(font, scale, previewX + previewWidth - font.width(scale) - 8, previewY + 6, 0xFFB5B0FF);
        }
        g.enableScissor(12, top, 12 + formWidth, bottom);
        for (var section : sections) {
            int sy = top + section.y() - scroll;
            g.fill(12, sy + 17, 12 + formWidth, sy + 18, 0xFF34394F);
            g.text(font, section.title(), 14, sy + 3, 0xFFB5B0FF);
        }
        int pathY = top + folderY - scroll + 3;
        for (var line : folderLines) {
            g.text(font, line, 19, pathY, 0xFFAAAAC0);
            pathY += 10;
        }
        super.extractRenderState(g, x, y, delta);
        g.disableScissor();
        if (cursor > bottom - top) {
            int length = Math.max(12, (bottom - top) * (bottom - top) / cursor);
            int sy = top + (bottom - top - length) * scroll / Math.max(1, cursor - (bottom - top));
            g.fill(8, sy, 10, sy + length, 0xFFB5B0FF);
        }
    }

    @Override
    public void removed() {
        super.removed();
        app.configureVisualizer();
    }

    private record Placed(AbstractWidget widget, int y) {
    }

    private record Section(String title, int y) {
    }
}
