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
    private ScrollBar scrollbar;
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
    private int formX, selectedSection, buildingSection;
    private final int[] sectionScroll = new int[6];
    private boolean previewOnly, previewVisible;

    public SettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
        this.app = app;
    }

    private void section(String key) {
        sections.add(new Section(Text.get(key), cursor));
        buildingSection++;
        cursor += 22;
    }

    private void put(AbstractWidget widget, String help) {
        if (!help.isEmpty()) Help.tip(widget, help);
        controls.add(new Placed(addRenderableWidget(widget), cursor, buildingSection));
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
        var slider = new ValueSlider(12, 0, formWidth, initial, label, v -> {
            change.accept(v);
            app.configureVisualizer();
        }, app::save, steps);
        slider.plain(true);
        put(slider, Text.get(help));
    }

    @Override
    protected void init() {
        if (shownSettings != null && shownSettings != app.settings) {
            previewScale = 1;
            scaleChanged = 0;
            scroll = 0;
            previewWheel = new com.darkmusic.client.hud.HudScale();
            java.util.Arrays.fill(sectionScroll, 0);
            selectedSection = 0;
        }
        shownSettings = app.settings;
        controls.clear();
        sections.clear();
        cursor = 0;
        buildingSection = -1;
        boolean wide = width >= 800 && height >= 360;
        if (wide) previewOnly = false;
        formX = wide ? 136 : 12;
        formWidth = wide ? Math.min(400, (width - 172) / 2) : width - 24;
        previewX = wide ? formX + formWidth + 12 : 12;
        previewY = wide ? 44 : 68;
        previewWidth = wide ? width - previewX - 12 : width - 24;
        previewHeight = height - previewY - 44;
        previewVisible = wide || previewOnly;
        top = wide ? 44 : 68;
        bottom = height - 12;
        for (int i = 0; i < 6; i++) {
            final int index = i;
            if (wide || i == selectedSection) {
                var button = addRenderableWidget(UiButton.builder(Text.component("phase2.section." + i), b -> {
                    sectionScroll[selectedSection] = scroll;
                    selectedSection = wide ? index : (selectedSection + 1) % 6;
                    scroll = sectionScroll[selectedSection];
                    previewOnly = false;
                    rebuildWidgets();
                }).bounds(12, wide ? 44 + i * 28 : 38, wide ? 112 : 140, 24).build());
                UiButton.selected(button, () -> selectedSection == index);
            }
        }
        if (!wide)
            addRenderableWidget(UiButton.builder(Text.component(previewOnly ? "phase2.settings_form" : "phase2.preview"), b -> {
                previewOnly = !previewOnly;
                rebuildWidgets();
            }).bounds(width - 120, 38, 108, 24).build());
        if (previewVisible) {
            int layoutButtonWidth = Math.min(previewWidth, font.width(Text.get("text.darkmusic.edit_hud_layout")) + 32);
            UiButton.icon(addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.edit_hud_layout"),
                    button -> show(new HudEditorScreen(app, this)))
                .bounds(previewX + (previewWidth - layoutButtonWidth) / 2, previewY + previewHeight + 4, layoutButtonWidth, 24).build()), UiIcon.EDIT, false);
        }
        var s = app.settings;
        section("text.darkmusic.general_settings");
        toggle("ui.darkmusic.show_hover_info", () -> s.showHoverInfo, v -> s.showHoverInfo = v, "ui.darkmusic.show_hover_info_help");
        action("audio.darkmusic.title", () -> show(new AudioSettingsScreen(app, this)), "audio.darkmusic.options_help");
        toggle("lyrics.darkmusic.enabled", () -> s.lyricsEnabled, v -> {
            s.lyricsEnabled = v;
            rebuildWidgets();
        }, "lyrics.darkmusic.enabled_help");
        if (s.lyricsEnabled) {
            action("lyrics.darkmusic.title", () -> show(new LyricsScreen(app, this)), "lyrics.darkmusic.help");
            action("lyrics.darkmusic.languages", () -> show(new TextPromptScreen(this, Text.get("lyrics.darkmusic.languages_prompt"), s.lyricsLanguages, value -> {
                s.lyricsLanguages = com.darkmusic.client.download.LyricsLanguages.normalize(value);
                app.save();
            })), "lyrics.darkmusic.languages_help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("lyrics.darkmusic.languages") + ": " + (s.lyricsLanguages.equals("auto") ? Text.get("lyrics.darkmusic.language_auto") : s.lyricsLanguages)));
        }
        toggle("text.darkmusic.autoplay_on_launch", () -> s.autoplay, v -> s.autoplay = v, "text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default");
        action("text.darkmusic.choose_music_folder", () -> DesktopFolderPicker.open(app, this::isCurrent), "text.darkmusic.choose_the_local_folder_containing_your_music_subfolders_are_included");
        folderY = cursor;
        folderPath = app.library.root().toString();
        folderLines = font.split(net.minecraft.network.chat.Component.literal(folderPath), formWidth - 14);
        cursor += folderLines.size() * 10 + 4;
        section("text.darkmusic.hud_settings");
        toggle("text.darkmusic.now_playing_hud", () -> s.hudEnabled, v -> s.hudEnabled = v, "hud.darkmusic.master_help");
        if (s.lyricsEnabled) {
            toggle("lyrics.darkmusic.hud", () -> s.lyricsHud, v -> s.lyricsHud = v, "lyrics.darkmusic.hud_help");
            toggle("lyrics.darkmusic.next", () -> s.lyricsNextLine, v -> s.lyricsNextLine = v, "lyrics.darkmusic.next_help");
        }
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
            action("circle.darkmusic.pulse", () -> {
                if (!s.circularIconPulse) {
                    s.circularIconPulse = true;
                    s.visualQuality.beatReactive = false;
                } else if (!s.visualQuality.beatReactive) s.visualQuality.beatReactive = true;
                else s.circularIconPulse = false;
                app.save();
                rebuildWidgets();
            }, "circle.darkmusic.pulse_help");
            String pulse = !s.circularIconPulse ? "off" : s.visualQuality.beatReactive ? "beat" : "level";
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("circle.darkmusic.pulse") + ": " + Text.get("circle.darkmusic.pulse." + pulse)));
        }
        if (classic) {
            action("style.darkmusic.shape", () -> {
                s.hudShape = Settings.HudShape.values()[(s.hudShape.ordinal() + 1) % Settings.HudShape.values().length];
                app.save();
                rebuildWidgets();
            }, "style.darkmusic.shape_help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("style.darkmusic.shape") + ": " + Text.get("style.darkmusic.shape." + s.hudShape.name().toLowerCase(Locale.ROOT))));
            toggle("style.darkmusic.raised", () -> s.hudRaised, v -> s.hudRaised = v, "style.darkmusic.raised_help");
        }
        section("hud.darkmusic.section_spectrum");
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
        section("hud.darkmusic.section_appearance");
        finish("finish.darkmusic.spectrum", () -> s.spectrumFinish, v -> s.spectrumFinish = v);
        finish("finish.darkmusic.progress", () -> s.progressFinish, v -> s.progressFinish = v);
        action("colors.darkmusic.spectrum", () -> show(new ColorsScreen(app, this, true)), "colors.darkmusic.help");
        action("colors.darkmusic.progress", () -> show(new ColorsScreen(app, this, false)), "colors.darkmusic.help");
        if (classic)
            slider(s.hudBackgroundTransparency, v -> Text.get("hud.darkmusic.transparency", Math.round(s.hudBackgroundTransparency * 100)), v -> s.hudBackgroundTransparency = v, 100, "hud.darkmusic.transparency_help");
        section("hud.darkmusic.section_effects");
        action("quality.darkmusic.preset", () -> {
            s.visualQuality.next();
            app.save();
            rebuildWidgets();
        }, "quality.darkmusic.help");
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("quality.darkmusic.preset") + ": " + Text.get("quality.darkmusic." + s.visualQuality.preset().name().toLowerCase(Locale.ROOT))));
        toggle("quality.darkmusic.frequency", () -> s.visualQuality.frequencySmoothing, v -> s.visualQuality.frequencySmoothing = v, "quality.darkmusic.frequency_help");
        toggle("quality.darkmusic.peaks", () -> s.visualQuality.peakHold, v -> s.visualQuality.peakHold = v, "quality.darkmusic.peaks_help");
        toggle("quality.darkmusic.trails", () -> s.visualQuality.trails, v -> s.visualQuality.trails = v, "quality.darkmusic.trails_help");
        toggle("quality.darkmusic.glow", () -> s.visualQuality.glow, v -> s.visualQuality.glow = v, "quality.darkmusic.glow_help");
        section("reset.darkmusic.section");
        action("reset.darkmusic.button", () -> show(new ConfirmationScreen(this, Text.component("reset.darkmusic.title"),
            Text.component("reset.darkmusic.body"), Text.component("reset.darkmusic.confirm"), app::resetDefaults)), "reset.darkmusic.help");
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
        app.configureVisualizer();
    }

    private void position() {
        int origin = sections.get(selectedSection).y();
        int end = selectedSection < sections.size() - 1 ? sections.get(selectedSection + 1).y() : cursor;
        scroll = Math.clamp(scroll, 0, Math.max(0, end - origin - (bottom - top)));
        sectionScroll[selectedSection] = scroll;
        if (scrollbar != null) {
            scrollbar.configure(formX - 10, top, bottom - top, end - origin, bottom - top);
            scrollbar.visible &= !previewOnly;
        }
        for (var p : controls) {
            int y = top + p.y() - origin - scroll;
            p.widget().setX(formX);
            p.widget().setY(y);
            p.widget().visible = !previewOnly && p.section() == selectedSection && y >= top && y + 20 <= bottom;
            if (!p.widget().visible && getFocused() == p.widget()) setFocused(null);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (previewVisible && app.settings.hudEnabled && x >= previewX && x < previewX + previewWidth && y >= previewY + 20 && y < previewY + previewHeight && dy != 0) {
            previewScale = previewWheel.scroll(previewScale, dy);
            fitPreview();
            scaleChanged = System.nanoTime();
            return true;
        }
        if (!previewOnly && x >= formX && x <= formX + formWidth && y >= top && y <= bottom) {
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
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SETTINGS);
        if (!previewOnly) UiTheme.panel(g, formX - 2, top - 2, formWidth + 4, bottom - top + 4);
        if (previewVisible) {
            UiTheme.panel(g, previewX, previewY, previewWidth, previewHeight);
            g.text(font, Text.get("hud.darkmusic.live_preview"), previewX + 6, previewY + 6, UiTheme.ACCENT);
            g.enableScissor(previewX + 2, previewY + 20, previewX + previewWidth - 2, previewY + previewHeight - 2);
            for (int cy = previewY + 20; cy < previewY + previewHeight - 2; cy += 16)
                for (int cx = previewX + 2; cx < previewX + previewWidth - 2; cx += 16)
                    g.fill(cx, cy, Math.min(cx + 16, previewX + previewWidth - 2), Math.min(cy + 16, previewY + previewHeight - 2), ((cx - previewX) / 16 + (cy - previewY) / 16) % 2 == 0 ? 0xFF20283A : 0xFF293246);
            fitPreview();
            NowPlayingHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30, previewScale, longText);
            g.disableScissor();
            if (System.nanoTime() - scaleChanged < 2_000_000_000L) {
                String scale = Text.get("hud.darkmusic.preview_scale", Math.round(previewScale * 100));
                g.text(font, scale, previewX + previewWidth - font.width(scale) - 8, previewY + 6, UiTheme.ACCENT);
            }
        }
        if (!previewOnly) {
            g.enableScissor(formX, top, formX + formWidth, bottom);
            var section = sections.get(selectedSection);
            int sy = top - scroll;
            g.fill(formX, sy + 17, formX + formWidth, sy + 18, UiTheme.BORDER);
            g.text(font, section.title(), formX + 2, sy + 3, UiTheme.ACCENT);
            int pathY = top + folderY - section.y() - scroll + 3;
            if (selectedSection == 0)
                for (var line : folderLines) {
                    g.text(font, line, formX + 7, pathY, UiTheme.SECONDARY);
                    pathY += 10;
                }
            g.disableScissor();
        }
        super.extractRenderState(g, x, y, delta);
    }

    @Override
    public void removed() {
        super.removed();
        app.configureVisualizer();
    }

    private record Placed(AbstractWidget widget, int y, int section) {
    }

    private record Section(String title, int y) {
    }
}
