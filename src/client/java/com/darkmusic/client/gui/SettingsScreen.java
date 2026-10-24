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
    private ValueSlider playerProgressPreview;
    private final List<Placed> controls = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<Subsection> subsections = new ArrayList<>();
    private com.darkmusic.client.hud.HudScale previewWheel = new com.darkmusic.client.hud.HudScale();
    private double previewScale = 1;
    private Settings shownSettings;
    private long scaleChanged;
    private int folderY;
    private String folderPath = "";
    private List<net.minecraft.util.FormattedCharSequence> folderLines = List.of();
    private int scroll, top, bottom, formWidth, cursor, previewX, previewY, previewWidth, previewHeight;
    private int formX, selectedSection, buildingSection;
    private static final String[] SECTION_KEYS = {"phase2.section.0", "settings.audio_section", "lyrics.section", "settings.appearance_section", "phase2.section.1", "phase2.section.2", "phase2.section.5"};
    private final int[] sectionScroll = new int[SECTION_KEYS.length];
    private boolean previewOnly, previewVisible;

    public SettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
        this.app = app;
    }

    private void section(String key) {
        sections.add(new Section(Text.get(key), cursor));
        buildingSection++;
        cursor += 24;
    }

    private void subsection(String key) {
        subsections.add(new Subsection(Text.get(key), cursor, buildingSection));
        cursor += 24;
    }

    private void heading(GuiGraphicsExtractor g, String text, int y) {
        g.fill(formX, y + 17, formX + formWidth, y + 18, UiTheme.BORDER);
        g.text(font, text, formX + 2, y + 3, UiTheme.ACCENT);
    }

    private void put(AbstractWidget widget, String help) {
        if (!help.isEmpty()) Help.tip(widget, help);
        controls.add(new Placed(addRenderableWidget(widget), cursor, buildingSection));
        cursor += 26;
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

    private void waveformSlider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        var slider = new ValueSlider(12, 0, formWidth, initial, label, change, app::save, steps);
        slider.plain(true);
        put(slider, Text.get(help));
    }

    @Override
    protected void init() {
        backButton();
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
        subsections.clear();
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
        playerProgressPreview = new ValueSlider(previewX + 12, previewY + previewHeight - 27, Math.max(1, previewWidth - 24), .4,
            v -> "", v -> {
        }, () -> {
        });
        PlayerProgress.configure(playerProgressPreview, app, () -> {
            var track = app.playback.state().track();
            return track != null ? track : app.library.snapshot().tracks().isEmpty() ? null : app.library.snapshot().tracks().getFirst();
        });
        top = wide ? 44 : 68;
        bottom = height - 12;
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            final int index = i;
            if (wide || i == selectedSection) {
                var button = addRenderableWidget(UiButton.builder(Text.component(SECTION_KEYS[i]), b -> {
                    sectionScroll[selectedSection] = scroll;
                    selectedSection = wide ? index : (selectedSection + 1) % SECTION_KEYS.length;
                    scroll = sectionScroll[selectedSection];
                    previewOnly = false;
                    rebuildWidgets();
                }).bounds(12, wide ? 44 + i * 28 : 38, wide ? 112 : 140, 24).build());
                if (SECTION_KEYS[i].equals("lyrics.section")) UiButton.icon(button, null, false);
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
        toggle("update.enabled", () -> s.updateNotifications, v -> s.updateNotifications = v, "update.enabled_help");
        action("update.check", () -> show(new UpdateScreen(app, this)), "update.check_help");
        action("backup.title", () -> show(new BackupScreen(app, this)), "backup.scope");
        action("history.clear", () -> show(new ConfirmationScreen(this, Text.component("history.clear"), Text.component("history.clear_help"), Text.component("history.clear"), () -> {
            app.listeningHistory.replace(java.util.Map.of());
            app.listeningHistory.flush(true);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        })), "history.clear_help");
        toggle("text.darkmusic.autoplay_on_launch", () -> s.autoplay, v -> s.autoplay = v, "text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default");
        action("folders.title", () -> show(new MusicFoldersScreen(app, this)), "folders.scope");
        folderY = cursor;
        folderPath = app.library.root().toString();
        folderLines = font.split(net.minecraft.network.chat.Component.literal(folderPath), formWidth - 14);
        cursor += folderLines.size() * 10 + 4;
        section("settings.audio_section");
        action("audio.darkmusic.title", () -> show(new AudioSettingsScreen(app, this)), "audio.darkmusic.options_help");
        action("environment.title", () -> show(new SoundEnvironmentScreen(app, this)), "environment.scope");
        section("lyrics.section");
        toggle("lyrics.darkmusic.enabled", () -> s.lyricsEnabled, v -> {
            s.lyricsEnabled = v;
            rebuildWidgets();
        }, "lyrics.darkmusic.enabled_help");
        if (s.lyricsEnabled) {
            action("lyrics.settings", () -> show(new LyricsSettingsScreen(app, this)), "lyrics.settings.help");
        }
        if (s.lyricsEnabled) {
            toggle("lyrics.darkmusic.hud", () -> s.lyricsHud, v -> s.lyricsHud = v, "lyrics.darkmusic.hud_help");
            action("lyrics.hud.settings", () -> show(new LyricsHudSettingsScreen(app, this)), "lyrics.hud.settings.help");
        }
        section("settings.appearance_section");
        action("settings.darkmusic.menu_background", () -> show(new ColorPickerScreen(this, s.menuBackgroundColor, 0x111420, color -> {
            s.menuBackgroundColor = color;
            app.save();
        })), "settings.darkmusic.menu_background_help");
        action("settings.darkmusic.menu_accent", () -> show(new ColorPickerScreen(this, s.menuAccentColor, 0xB8A8FA, color -> {
            s.menuAccentColor = color;
            app.save();
        })), "settings.darkmusic.menu_accent_help");
        section("text.darkmusic.hud_settings");
        toggle("text.darkmusic.now_playing_hud", () -> s.hudEnabled, v -> s.hudEnabled = v, "hud.darkmusic.master_help");
        action("circle.darkmusic.style", () -> {
            s.hudStyle = Settings.HudStyle.values()[(s.hudStyle.ordinal() + 1) % Settings.HudStyle.values().length];
            app.save();
            rebuildWidgets();
        }, "circle.darkmusic.style_help");
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("circle.darkmusic.style") + ": " + Text.get("circle.darkmusic.style." + s.hudStyle.name().toLowerCase(Locale.ROOT))));
        boolean classic = s.hudStyle == Settings.HudStyle.CLASSIC;
        boolean artwork = s.hudStyle == Settings.HudStyle.ARTWORK;
        boolean spectrumOnly = s.hudStyle == Settings.HudStyle.SPECTRUM;
        boolean linear = classic || artwork || spectrumOnly;
        if (classic)
            toggle("text.darkmusic.hud_artwork", () -> s.hudArtwork, v -> s.hudArtwork = v, "hud.darkmusic.artwork_help");

        if (!spectrumOnly) {
            action("hud.image_source", () -> {
                s.hudImage(s.hudImage() == Settings.HudImage.ARTWORK ? Settings.HudImage.ICON : Settings.HudImage.ARTWORK);
                app.save();
                rebuildWidgets();
            }, "hud.image_source.help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("hud.image_source") + ": " +
                Text.get("hud.image_source." + s.hudImage().name().toLowerCase(Locale.ROOT))));
        }

        toggle("hud.darkmusic.spectrum", () -> s.hudVisualizer, v -> s.hudVisualizer = v, "hud.darkmusic.spectrum_help");
        if (!linear) {
            waveformSlider(s.circularImageGap / 24.0, v -> Text.get("hud.circular_gap", s.circularImageGap),
                v -> s.circularImageGap = (int) Math.round(v * 24), 24, "hud.circular_gap.help");
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
        if (artwork)
            toggle("hud.darkmusic.artwork_reactive", () -> s.artworkReactive, v -> s.artworkReactive = v, "hud.darkmusic.artwork_reactive_help");
        if (linear && !spectrumOnly) {
            slider(s.hudBackgroundTransparency, v -> Text.get("hud.darkmusic.transparency", Math.round(s.hudBackgroundTransparency * 100)), v -> s.hudBackgroundTransparency = v, 100, "hud.darkmusic.transparency_help");
            toggle("hud.darkmusic.background_gradient", () -> s.hudBackgroundGradient, v -> s.hudBackgroundGradient = v, "hud.darkmusic.background_gradient_help");
            action("hud.darkmusic.background_colors", () -> show(new ColorsScreen(app, this, s.hudBackgroundColors, "hud.darkmusic.background_colors")), "colors.darkmusic.help");
        }
        subsection("progress.darkmusic.group");
        if (!spectrumOnly) toggle("text.darkmusic.hud_progress", () -> s.hudProgress, v -> s.hudProgress = v,
            artwork ? "hud.darkmusic.artwork_progress_help" : classic ? "text.darkmusic.show_the_current_track_s_progress_below_the_title_and_artist" : "circle.darkmusic.progress_help");
        toggle("progress.darkmusic.waveform", () -> s.waveformProgress, v -> {
            s.waveformProgress = v;
            rebuildWidgets();
        }, "progress.darkmusic.waveform_help");
        if (!spectrumOnly || s.waveformProgress)
            finish("finish.darkmusic.progress", () -> s.progressFinish, v -> s.progressFinish = v);
        action("colors.darkmusic.progress", () -> show(new ColorsScreen(app, this, false)), "colors.darkmusic.help");
        if (s.waveformProgress) {
            action("waveform.style", () -> {
                s.waveform.style = s.waveform.style == com.darkmusic.client.config.WaveformSettings.Style.FILLED
                    ? com.darkmusic.client.config.WaveformSettings.Style.BARS : com.darkmusic.client.config.WaveformSettings.Style.FILLED;
                app.save();
                rebuildWidgets();
            }, "waveform.style.help");
            controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("waveform.style") + ": " +
                Text.get("waveform.style." + s.waveform.style.name().toLowerCase(Locale.ROOT))));
            action("waveform.unplayed", () -> show(new ColorPickerScreen(this, s.waveform.unplayedColor, 0x555D78, color -> {
                s.waveform.unplayedColor = color;
                app.save();
            })), "waveform.unplayed.help");
            waveformSlider((s.waveform.height - 6) / 12.0, v -> Text.get("waveform.height", s.waveform.height),
                v -> s.waveform.height = 6 + (int) Math.round(v * 12), 12, "waveform.height.help");
            waveformSlider((s.waveform.detail - 48) / 464.0, v -> Text.get("waveform.detail", s.waveform.detail),
                v -> s.waveform.detail = 48 + (int) Math.round(v * 464), 464, "waveform.detail.help");
            if (s.waveform.style == com.darkmusic.client.config.WaveformSettings.Style.BARS)
                waveformSlider(s.waveform.spacing / 4.0, v -> Text.get("waveform.spacing", s.waveform.spacing),
                    v -> s.waveform.spacing = (int) Math.round(v * 4), 4, "waveform.spacing.help");
            waveformSlider(s.waveform.smoothing, v -> Text.get("waveform.smoothing", Math.round(s.waveform.smoothing * 100)),
                v -> s.waveform.smoothing = v, 100, "waveform.smoothing.help");
            waveformSlider((s.waveform.contrast - .5) / 2, v -> Text.get("waveform.contrast", Math.round(s.waveform.contrast * 100) / 100.0),
                v -> s.waveform.contrast = .5 + v * 2, 100, "waveform.contrast.help");
            waveformSlider((s.waveform.opacity - .15) / .85, v -> Text.get("waveform.opacity", Math.round(s.waveform.opacity * 100)),
                v -> s.waveform.opacity = .15 + v * .85, 85, "waveform.opacity.help");
            action("waveform.reset", () -> {
                s.waveform = new com.darkmusic.client.config.WaveformSettings();
                app.save();
                rebuildWidgets();
            }, "waveform.reset.help");
        }
        section("hud.darkmusic.section_spectrum");
        action("style.darkmusic.spectrum", () -> {
            if (linear)
                s.spectrumStyle = switch (s.spectrumStyle) {
                    case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                    case ROUNDED_BARS -> Settings.SpectrumStyle.SMOOTH_WAVE;
                    case FILLED_SPIKE -> Settings.SpectrumStyle.BARS;
                    default -> Settings.SpectrumStyle.values()[s.spectrumStyle.ordinal() + 1];
                };
            else s.circularSpectrumStyle = switch (s.circularSpectrumStyle) {
                case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                case ROUNDED_BARS -> Settings.SpectrumStyle.FILLED_WAVE;
                case FILLED_WAVE -> Settings.SpectrumStyle.FILLED_SPIKE;
                default -> Settings.SpectrumStyle.BARS;
            };
            app.save();
            rebuildWidgets();
        }, linear ? "style.darkmusic.spectrum_help" : "circle.darkmusic.spectrum_help");
        var chosenStyle = linear ? s.spectrumStyle : s.circularSpectrumStyle;
        controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("style.darkmusic.spectrum") + ": " + Text.get((linear ? "style.darkmusic.spectrum." : "circle.darkmusic.spectrum.") + chosenStyle.name().toLowerCase(Locale.ROOT))));
        if (linear) {
            if (!spectrumOnly) {
                action("hud.darkmusic.position", () -> {
                    if (artwork)
                        s.artworkSpectrumPosition = Settings.ArtworkSpectrumPosition.values()[(s.artworkSpectrumPosition.ordinal() + 1) % Settings.ArtworkSpectrumPosition.values().length];
                    else
                        s.spectrumPosition = s.spectrumPosition == Settings.SpectrumPosition.ABOVE ? Settings.SpectrumPosition.BELOW : Settings.SpectrumPosition.ABOVE;
                    app.save();
                    rebuildWidgets();
                }, artwork ? "hud.darkmusic.artwork_position_help" : "hud.darkmusic.position_help");
                controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("hud.darkmusic.position") + ": " + Text.get("hud.darkmusic.position." + (artwork ? s.artworkSpectrumPosition.name() : s.spectrumPosition.name()).toLowerCase(Locale.ROOT))));
            }
            if (!artwork || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.ABOVE
                || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.BELOW) {
                action("hud.darkmusic.direction", () -> {
                    s.spectrumDirection = s.spectrumDirection == Settings.SpectrumDirection.UPWARD ? Settings.SpectrumDirection.DOWNWARD : Settings.SpectrumDirection.UPWARD;
                    app.save();
                    rebuildWidgets();
                }, "hud.darkmusic.direction_help");
                controls.getLast().widget().setMessage(net.minecraft.network.chat.Component.literal(Text.get("hud.darkmusic.direction") + ": " + Text.get("hud.darkmusic.direction." + s.spectrumDirection.name().toLowerCase(Locale.ROOT))));
            }
        }
        finish("finish.darkmusic.spectrum", () -> s.spectrumFinish, v -> s.spectrumFinish = v);
        toggle("quality.darkmusic.frequency", () -> s.visualQuality.frequencySmoothing, v -> s.visualQuality.frequencySmoothing = v, "quality.darkmusic.frequency_help");
        toggle("quality.darkmusic.peaks", () -> s.visualQuality.peakHold, v -> s.visualQuality.peakHold = v, "quality.darkmusic.peaks_help");
        toggle("quality.darkmusic.trails", () -> s.visualQuality.trails, v -> s.visualQuality.trails = v, "quality.darkmusic.trails_help");
        toggle("quality.darkmusic.glow", () -> s.visualQuality.glow, v -> s.visualQuality.glow = v, "quality.darkmusic.glow_help");
        action("colors.darkmusic.spectrum", () -> show(new ColorsScreen(app, this, true)), "colors.darkmusic.help");
        waveformSlider((s.spectrumOpacity - .1) / .9, v -> Text.get("spectrum.darkmusic.opacity", Math.round(s.spectrumOpacity * 100)), v -> s.spectrumOpacity = .1 + v * .9, 18, "spectrum.darkmusic.opacity_help");
        if (!chosenStyle.bars() && (linear || chosenStyle != Settings.SpectrumStyle.FILLED_SPIKE))
            waveformSlider((s.visualQuality.detail - 96) / 288.0, v -> Text.get("spectrum.darkmusic.detail", s.visualQuality.detail), v -> s.visualQuality.detail = 96 + (int) Math.round(v * 288), 12, "spectrum.darkmusic.detail_help");
        slider(s.visualizerSmoothing / .95, v -> Text.get("format.darkmusic.smoothing", Math.round(s.visualizerSmoothing * 100)), v -> s.visualizerSmoothing = v * .95, 19, "text.darkmusic.higher_values_give_slower_attack_and_decay_pausing_freezes_the_actual_spectrum_sto");
        slider((s.visualizerSensitivity - .25) / 3.75, v -> Text.get("format.darkmusic.sensitivity", String.format(Locale.ROOT, "%.2f", s.visualizerSensitivity)), v -> s.visualizerSensitivity = .25 + v * 3.75, 15, "text.darkmusic.boost_quiet_spectra_or_reduce_strong_levels_samples_follow_playback_speed_and_dark");
        slider((s.visualizerBars - 8) / 56.0, v -> Text.get("format.darkmusic.bars", s.visualizerBars), v -> s.visualizerBars = 8 + (int) Math.round(v * 56), 14, "text.darkmusic.8_64_logarithmic_frequency_bands_from_bass_to_treble_narrow_areas_combine_bars_to");
        slider(linear ? ((artwork ? s.artworkSpectrumHeight : s.classicSpectrumHeight) - 8) / 92.0 : (s.circularSpectrumHeight - 4) / 56.0,
            v -> Text.get("hud.darkmusic.spectrum_height", artwork ? s.artworkSpectrumHeight : linear ? s.classicSpectrumHeight : s.circularSpectrumHeight),
            v -> {
                if (artwork) s.artworkSpectrumHeight = 8 + (int) Math.round(v * 92);
                else if (linear) s.classicSpectrumHeight = 8 + (int) Math.round(v * 92);
                else s.circularSpectrumHeight = 4 + (int) Math.round(v * 56);
            }, linear ? 92 : 56, "hud.darkmusic.spectrum_height_help");
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        var rows = controls.stream().filter(p -> p.section() == selectedSection).toList();
        int current = java.util.stream.IntStream.range(0, rows.size()).filter(i -> rows.get(i).widget() == getFocused()).findFirst().orElse(-1);
        if (!previewOnly && event.key() == 258 && current >= 0) {
            int next = FocusRows.next(rows.stream().map(p -> p.widget().active).toList(), current, event.hasShiftDown(), false);
            if (next >= 0) {
                var row = rows.get(next);
                int y = row.y() - sections.get(selectedSection).y();
                if (y < scroll || y + 20 > scroll + bottom - top) scroll = Math.max(0, y - (bottom - top - 24) / 2);
                position();
                setFocused(row.widget());
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (previewVisible && (lyricsPreview() ? com.darkmusic.client.hud.LyricsHud.enabled(app.settings) : app.settings.hudEnabled) && x >= previewX && x < previewX + previewWidth && y >= previewY + 20 && y < previewY + previewHeight - (progressPreview() ? 54 : 0) && dy != 0) {
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
        var layout = NowPlayingHud.content(app);
        int w = lyricsPreview() ? app.settings.lyricsHudWidth : layout.width();
        int h = lyricsPreview() ? com.darkmusic.client.hud.HudLyricsLayout.height(app.settings.lyricsNextLine) : layout.height();
        double fit = Math.min((previewWidth - 16.0) / w, (previewHeight - 30.0 - (progressPreview() ? 54 : 0)) / h);
        previewScale = Math.min(previewScale, Math.max(.1, Math.floor(fit * 10) / 10));
    }

    private boolean lyricsPreview() {
        return SECTION_KEYS[selectedSection].equals("lyrics.section");
    }

    private boolean progressPreview() {
        return SECTION_KEYS[selectedSection].equals("phase2.section.1");
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
            fitPreview();
            if (lyricsPreview())
                com.darkmusic.client.hud.LyricsHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30, previewScale);
            else
                NowPlayingHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30 - (progressPreview() ? 54 : 0), previewScale, longText);
            if (progressPreview()) {
                int divider = previewY + previewHeight - 54;
                g.fill(previewX + 6, divider, previewX + previewWidth - 6, divider + 1, UiTheme.BORDER);
                longText.draw(g, font, "player_progress_preview", Text.get("progress.darkmusic.player_preview"), previewX + 10, divider + 7, previewWidth - 20, UiTheme.ACCENT);
                var state = app.playback.state();
                boolean actual = state.status() == com.darkmusic.client.audio.AudioBackend.Status.PLAYING || state.status() == com.darkmusic.client.audio.AudioBackend.Status.PAUSED;
                playerProgressPreview.syncNow(actual && state.duration() > 0 ? state.position() / state.duration() : .4);
                playerProgressPreview.extractWidgetRenderState(g, Integer.MIN_VALUE, Integer.MIN_VALUE, delta);
            }
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
            heading(g, section.title(), sy);
            for (var group : subsections)
                if (group.section() == selectedSection)
                    heading(g, group.title(), top + group.y() - section.y() - scroll);
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

    private record Subsection(String title, int y, int section) {
    }

    private record Section(String title, int y) {
    }
}
