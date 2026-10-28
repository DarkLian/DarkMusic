package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.WaveformSettings;
import com.darkmusic.client.hud.HudLyricsLayout;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.hud.HudScale;
import com.darkmusic.client.hud.LyricsHud;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.SpectrumRenderer;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.systemmedia.MediaControlsPackage;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * One scrollable settings form with a fixed, independently contained live preview.
 */
public final class SettingsScreen extends LocalScreen implements HudPreview {
    private final DarkMusicClient app;
    private ScrollBar scrollbar;
    private ValueSlider playerProgressPreview;
    private final SpectrumRenderer playerSpectrumPreview = new SpectrumRenderer();
    private final List<Placed> controls = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<Subsection> subsections = new ArrayList<>();
    private HudScale previewWheel = new HudScale();
    private double previewScale = 1;
    private Settings shownSettings;
    private long scaleChanged;
    private int folderY;
    private String folderPath = "";
    private List<FormattedCharSequence> folderLines = List.of();
    private int scroll, top, bottom, formWidth, cursor, previewX, previewY, previewWidth, previewHeight;
    private int formX, selectedSection, buildingSection;
    private static final String[] SECTION_KEYS = {"phase2.section.0", "settings.audio_section", "lyrics.section", "settings.appearance_section", "phase2.section.1", "phase2.section.2", "phase2.section.5"};
    private final int[] sectionScroll = new int[SECTION_KEYS.length];
    private boolean previewOnly, previewVisible;
    // Search filters rows from every section; it survives rebuilds such as a toggled option.
    private String query = "";
    private String subsectionTitle = "";
    private UiInput search;
    private final List<Section> searchHeadings = new ArrayList<>();
    private final Map<AbstractWidget, Integer> searchRows = new HashMap<>();

    public SettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.darkmusic_settings"), parent);
        this.app = app;
    }

    private void section(String key) {
        subsectionTitle = "";
        sections.add(new Section(Text.get(key), cursor));
        buildingSection++;
        cursor += 24;
    }

    private void subsection(String key) {
        subsectionTitle = Text.get(key);
        subsections.add(new Subsection(subsectionTitle, cursor, buildingSection));
        cursor += 24;
    }

    private void heading(GuiGraphicsExtractor g, String text, int y) {
        g.fill(formX, y + 17, formX + formWidth, y + 18, UiTheme.BORDER);
        g.text(font, text, formX + 2, y + 3, UiTheme.ACCENT);
    }

    private void put(AbstractWidget widget, String help) {
        if (!help.isEmpty()) Help.tip(widget, help);
        String context = (sections.isEmpty() ? "" : sections.getLast().title()) + " " + subsectionTitle;
        controls.add(new Placed(addRenderableWidget(widget), cursor, buildingSection, help + " " + context));
        cursor += 26;
    }

    private void toggle(String key, BooleanSupplier get, Consumer<Boolean> set, String help) {
        put(new SettingRow(12, 0, formWidth, Text.get(key), get, () -> {
            set.accept(!get.getAsBoolean());
            app.save();
        }), Text.get(help));
    }

    private SettingRow action(String key, Runnable action, String help) {
        var row = new SettingRow(12, 0, formWidth, Text.get(key), null, action);
        put(row, Text.get(help));
        return row;
    }

    /**
     * A row that steps through choices on each click and shows the current one.
     */
    private void choice(String key, Supplier<String> value, Runnable next, String help) {
        action(key, () -> {
            next.run();
            app.save();
            rebuildWidgets();
        }, help);
        controls.getLast().widget().setMessage(Component.literal(Text.get(key) + ": " + value.get()));
    }

    /**
     * A choice row over every value of an enum, named by {@code prefix} plus the value's lowercase name.
     */
    private <E extends Enum<E>> void choice(String key, String prefix, Supplier<E> get, Consumer<E> set, String help) {
        choice(key, () -> Text.get(prefix + name(get.get())), () -> set.accept(next(get.get())), help);
    }

    /**
     * The value after {@code value} in declaration order, wrapping to the first.
     */
    static <E extends Enum<E>> E next(E value) {
        var values = value.getDeclaringClass().getEnumConstants();
        return values[(value.ordinal() + 1) % values.length];
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private void finish(String key, Supplier<Settings.SurfaceFinish> get, Consumer<Settings.SurfaceFinish> set) {
        choice(key, "finish.darkmusic.", get, set, "finish.darkmusic.help");
    }

    private void rotation(String key, Supplier<Settings.Rotation> get, Consumer<Settings.Rotation> set, String help) {
        choice(key, "circle.darkmusic.rotation.", get, set, help);
    }

    private void slider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        var slider = new ValueSlider(12, 0, formWidth, initial, label, v -> {
            change.accept(v);
            app.configureVisualizer();
        }, app::save, steps);
        put(slider, Text.get(help));
    }

    private void waveformSlider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        var slider = new ValueSlider(12, 0, formWidth, initial, label, change, app::save, steps);
        put(slider, Text.get(help));
    }

    @Override
    protected void init() {
        backButton();
        if (shownSettings != null && shownSettings != app.settings) {
            previewScale = 1;
            scaleChanged = 0;
            scroll = 0;
            previewWheel = new HudScale();
            Arrays.fill(sectionScroll, 0);
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
            if (track != null) return track;
            var tracks = app.library.snapshot().tracks();
            return tracks.isEmpty() ? null : tracks.getFirst();
        });
        int searchY = wide ? 44 : 68;
        top = searchY + 26;
        bottom = height - 12;
        search = addRenderableWidget(new UiInput(font, formX, searchY, formWidth, 20, Text.component("settings.search")));
        search.setMaxLength(100);
        search.setHint(Text.component("settings.search_hint"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            position();
        });
        search.visible = !previewOnly;
        for (int i = 0; i < SECTION_KEYS.length; i++) {
            final int index = i;
            if (wide || i == selectedSection) {
                var button = addRenderableWidget(UiButton.builder(Text.component(SECTION_KEYS[i]), b -> {
                    query = "";
                    sectionScroll[selectedSection] = scroll;
                    selectedSection = wide ? index : (selectedSection + 1) % SECTION_KEYS.length;
                    scroll = sectionScroll[selectedSection];
                    previewOnly = false;
                    rebuildWidgets();
                }).bounds(12, wide ? 44 + i * 28 : 38, wide ? 112 : 140, 24).build());
                if (SECTION_KEYS[i].equals("lyrics.section")) UiButton.icon(button, null, false);
                UiButton.selected(button, () -> selectedSection == index && !searching());
            }
        }
        if (!wide)
            addRenderableWidget(UiButton.builder(Text.component(previewOnly ? "phase2.settings_form" : "phase2.preview"), b -> {
                previewOnly = !previewOnly;
                rebuildWidgets();
            }).bounds(width - 120, 38, 108, 24).build());
        if (previewVisible && (app.settings.hudEnabled || LyricsHud.enabled(app.settings))) {
            int layoutButtonWidth = Math.min(previewWidth, font.width(Text.get("text.darkmusic.edit_hud_layout")) + 32);
            UiButton.icon(addRenderableWidget(UiButton.builder(Text.component("text.darkmusic.edit_hud_layout"),
                    button -> show(new HudEditorScreen(app, this)))
                .bounds(previewX + (previewWidth - layoutButtonWidth) / 2, previewY + previewHeight + 4, layoutButtonWidth, 24).build()), UiIcon.EDIT, false);
        }
        var s = app.settings;
        generalSettings(s);
        audioSettings();
        lyricsSettings(s);
        appearanceSettings(s);
        hudSettings(s);
        spectrumSettings(s);
        section("reset.darkmusic.section");
        action("reset.darkmusic.button", () -> show(new ConfirmationScreen(this, Text.component("reset.darkmusic.title"),
            Text.component("reset.darkmusic.body"), Text.component("reset.darkmusic.confirm"), app::resetDefaults)), "reset.darkmusic.help").destructive();
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
        app.configureVisualizer();
    }

    private void generalSettings(Settings s) {
        section("text.darkmusic.general_settings");
        action("folders.title", () -> show(new MusicFoldersScreen(app, this)), "folders.scope");
        folderY = cursor;
        folderPath = app.library.root().toString();
        folderLines = font.split(Component.literal(folderPath), formWidth - 14);
        cursor += folderLines.size() * 10 + 4;
        toggle("text.darkmusic.autoplay_on_launch", () -> s.autoplay, v -> s.autoplay = v, "text.darkmusic.automatically_start_the_library_after_minecraft_launches_disabled_by_default");
        toggle("resume.darkmusic.enabled", () -> s.resumeLast, v -> s.resumeLast = v, "resume.darkmusic.enabled_help");
        toggle("ui.darkmusic.show_hover_info", () -> s.showHoverInfo, v -> s.showHoverInfo = v, "ui.darkmusic.show_hover_info_help");
        toggle("update.enabled", () -> s.updateNotifications, v -> s.updateNotifications = v, "update.enabled_help");
        action("update.check", () -> show(new UpdateScreen(app, this)), "update.check_help");
        put(new SettingRow(12, 0, formWidth, Text.get("discord.darkmusic.status"), () -> s.discordStatus, () -> {
            s.discordStatus = !s.discordStatus;
            app.save();
            rebuildWidgets();
        }), Text.get("discord.darkmusic.status_help") + (s.discordStatus ? " " + Text.get(app.discord.state()) : ""));
        if (s.discordStatus) {
            toggle("discord.darkmusic.show_title", () -> s.discordShowTitle, v -> {
                s.discordShowTitle = v;
                rebuildWidgets();
            }, "discord.darkmusic.show_title_help");
            if (s.discordShowTitle)
                toggle("discord.darkmusic.artwork", () -> s.discordArtwork, v -> s.discordArtwork = v, "discord.darkmusic.artwork_help");
        }
        mediaControlsRows(s);
        action("backup.title", () -> show(new BackupScreen(app, this)), "backup.scope");
        action("history.clear", () -> show(new ConfirmationScreen(this, Text.component("history.clear"), Text.component("history.clear_help"), Text.component("history.clear"), () -> {
            app.listeningHistory.replace(Map.of());
            app.listeningHistory.flush(true);
            return CompletableFuture.completedFuture(null);
        })), "history.clear_help").destructive();
    }

    private void audioSettings() {
        section("settings.audio_section");
        action("audio.darkmusic.title", () -> show(new AudioSettingsScreen(app, this)), "audio.darkmusic.options_help");
        action("environment.title", () -> show(new SoundEnvironmentScreen(app, this)), "environment.scope");
    }

    private void lyricsSettings(Settings s) {
        section("lyrics.section");
        toggle("lyrics.darkmusic.enabled", () -> s.lyricsEnabled, v -> {
            s.lyricsEnabled = v;
            rebuildWidgets();
        }, "lyrics.darkmusic.enabled_help");
        if (s.lyricsEnabled) {
            action("lyrics.settings", () -> show(new LyricsSettingsScreen(app, this)), "lyrics.settings.help");
        }
        if (s.lyricsEnabled) {
            toggle("lyrics.darkmusic.hud", () -> s.lyricsHud, v -> {
                s.lyricsHud = v;
                rebuildWidgets();
            }, "lyrics.darkmusic.hud_help");
        }
        if (LyricsHud.enabled(s)) {
            subsection("lyrics.hud.settings");
            toggle("lyrics.hud.next", () -> s.lyricsNextLine, v -> s.lyricsNextLine = v, "lyrics.hud.next.help");
            toggle("lyrics.hud.text_plate", () -> s.lyricsTextPlate, v -> s.lyricsTextPlate = v, "lyrics.hud.text_plate.help");
            waveformSlider((s.lyricsHudWidth - 120) / 480.0, v -> Text.get("lyrics.hud.width") + ": " + s.lyricsHudWidth,
                v -> s.lyricsHudWidth = 120 + (int) Math.round(v * 480), 480, "lyrics.hud.width.help");
            waveformSlider(s.lyricsHudOpacity, v -> Text.get("lyrics.hud.opacity") + ": " + Math.round(s.lyricsHudOpacity * 100) + "%",
                v -> s.lyricsHudOpacity = v, 100, "lyrics.hud.opacity.help");
            action("lyrics.hud.color", () -> show(new ColorPickerScreen(this, s.lyricsHudColor, 0xDDD9FF, color -> {
                s.lyricsHudColor = color;
                app.save();
            })), "lyrics.hud.color.help");
            action("lyrics.hud.next_color", () -> show(new ColorPickerScreen(this, s.lyricsHudNextColor, 0xAAA7BD, color -> {
                s.lyricsHudNextColor = color;
                app.save();
            })), "lyrics.hud.next_color.help");
        }
    }

    private void appearanceSettings(Settings s) {
        section("settings.appearance_section");
        action("settings.darkmusic.menu_background", () -> show(new ColorPickerScreen(this, s.menuBackgroundColor, 0x000000, color -> {
            s.menuBackgroundColor = color;
            app.save();
        })), "settings.darkmusic.menu_background_help");
        action("settings.darkmusic.menu_accent", () -> show(new ColorPickerScreen(this, s.menuAccentColor, 0xB8A8FA, color -> {
            s.menuAccentColor = color;
            app.save();
        })), "settings.darkmusic.menu_accent_help");
    }

    private void hudSettings(Settings s) {
        section("text.darkmusic.hud_settings");
        toggle("text.darkmusic.now_playing_hud", () -> s.hudEnabled, v -> {
            s.hudEnabled = v;
            rebuildWidgets();
        }, "hud.darkmusic.master_help");
        var style = HudKind.of(s);
        boolean classic = style.classic(), artwork = style.artwork(), spectrumOnly = style.spectrumOnly(), linear = style.linear();
        if (s.hudEnabled) {
            subsection("hud.darkmusic.group.appearance");
            choice("circle.darkmusic.style", "circle.darkmusic.style.", () -> s.hudStyle, v -> s.hudStyle = v, "circle.darkmusic.style_help");
            if (classic) {
                toggle("text.darkmusic.hud_artwork", () -> s.hudArtwork, v -> s.hudArtwork = v, "hud.darkmusic.artwork_help");
                toggle("hud.darkmusic.next_song", () -> s.hudNextSong, v -> s.hudNextSong = v, "hud.darkmusic.next_song_help");
            }

            if (!spectrumOnly) {
                choice("hud.image_source", "hud.image_source.", s::hudImage, s::hudImage, "hud.image_source.help");
            }

            toggle("hud.darkmusic.spectrum", () -> s.hudVisualizer, v -> s.hudVisualizer = v, "hud.darkmusic.spectrum_help");
            if (!linear) {
                waveformSlider(s.circularImageGap / 24.0, v -> Text.get("hud.circular_gap", s.circularImageGap),
                    v -> s.circularImageGap = (int) Math.round(v * 24), 24, "hud.circular_gap.help");
                toggle("circle.darkmusic.text", () -> s.circularText, v -> {
                    s.circularText = v;
                    rebuildWidgets();
                }, "circle.darkmusic.text_help");
                if (s.circularText)
                    toggle("hud.text_plate", () -> s.hudTextPlate, v -> s.hudTextPlate = v, "hud.text_plate.help");
                // Off, then following the level, then the beat.
                choice("circle.darkmusic.pulse", () -> Text.get("circle.darkmusic.pulse."
                    + (!s.circularIconPulse ? "off" : s.visualQuality.beatReactive ? "beat" : "level")), () -> {
                    if (!s.circularIconPulse) {
                        s.circularIconPulse = true;
                        s.visualQuality.beatReactive = false;
                    } else if (!s.visualQuality.beatReactive) s.visualQuality.beatReactive = true;
                    else s.circularIconPulse = false;
                }, "circle.darkmusic.pulse_help");
                rotation("circle.darkmusic.rotation", () -> s.circularRotation, v -> s.circularRotation = v, "circle.darkmusic.rotation_help");
                if (s.circularRotation != Settings.Rotation.OFF || s.hudProgress && s.circularProgressRotation != Settings.Rotation.OFF)
                    waveformSlider((s.circularRotationSeconds - 4) / 116.0, v -> Text.get("circle.darkmusic.rotation_speed", s.circularRotationSeconds),
                        v -> s.circularRotationSeconds = 4 + (int) Math.round(v * 116), 116, "circle.darkmusic.rotation_speed_help");
            }
            if (classic) {
                choice("style.darkmusic.shape", "style.darkmusic.shape.", () -> s.hudShape, v -> s.hudShape = v, "style.darkmusic.shape_help");
                toggle("style.darkmusic.raised", () -> s.hudRaised, v -> s.hudRaised = v, "style.darkmusic.raised_help");
                toggle("hud.text_plate", () -> s.hudTextPlate, v -> s.hudTextPlate = v, "hud.text_plate.help");
            }
            if (artwork) {
                toggle("circle.darkmusic.text", () -> s.artworkText, v -> s.artworkText = v, "hud.darkmusic.artwork_text_help");
                toggle("hud.darkmusic.artwork_reactive", () -> s.artworkReactive, v -> s.artworkReactive = v, "hud.darkmusic.artwork_reactive_help");
            }
            if (linear && !spectrumOnly) {
                slider(s.hudBackgroundTransparency, v -> Text.get("hud.darkmusic.transparency", Math.round(s.hudBackgroundTransparency * 100)), v -> s.hudBackgroundTransparency = v, 100, "hud.darkmusic.transparency_help");
                toggle("hud.darkmusic.background_gradient", () -> s.hudBackgroundGradient, v -> s.hudBackgroundGradient = v, "hud.darkmusic.background_gradient_help");
                action("hud.darkmusic.background_colors", () -> show(new ColorsScreen(app, this, s.hudBackgroundColors, "hud.darkmusic.background_colors")), "colors.darkmusic.help");
            }
        }
        subsection("hud.darkmusic.group.motion");
        toggle("hud.darkmusic.in_menus", () -> s.hudInMenus, v -> s.hudInMenus = v, "hud.darkmusic.in_menus_help");
        if (s.hudEnabled) {
            waveformSlider(s.hudIdleHide / (double) Settings.IDLE_HIDE_MAX, v -> Text.get("hud.darkmusic.idle_hide") + ": "
                    + (s.hudIdleHide == 0 ? Text.get("hud.darkmusic.idle_off") : Text.get("hud.darkmusic.seconds", s.hudIdleHide)),
                v -> s.hudIdleHide = (int) Math.round(v * Settings.IDLE_HIDE_MAX), Settings.IDLE_HIDE_MAX, "hud.darkmusic.idle_hide_help");
            if (s.hudIdleHide > 0)
                choice("hud.darkmusic.idle_animation", "hud.darkmusic.motion.", () -> s.hudIdleAnimation, v -> s.hudIdleAnimation = v,
                    "hud.darkmusic.idle_animation_help");
            toggle("hud.darkmusic.transitions", () -> s.hudTransitions, v -> s.hudTransitions = v, "hud.darkmusic.transitions_help");
        }
        subsection("hud.darkmusic.group.card");
        choice("hud.darkmusic.song_card", "hud.darkmusic.song_card.", () -> s.songCard, v -> s.songCard = v, "hud.darkmusic.song_card_help");
        if (s.songCard != Settings.SongCard.OFF) {
            choice("hud.darkmusic.card_animation", "hud.darkmusic.motion.", () -> s.songCardAnimation, v -> s.songCardAnimation = v,
                "hud.darkmusic.card_animation_help");
            action("hud.darkmusic.card_position", () -> show(new HudEditorScreen(app, this, HudEditorScreen.Element.CARD)), "hud.darkmusic.card_position_help");
        }
        subsection("progress.darkmusic.group");
        if (s.hudEnabled && !spectrumOnly)
            toggle("text.darkmusic.hud_progress", () -> s.hudProgress, v -> s.hudProgress = v,
                artwork ? "hud.darkmusic.artwork_progress_help" : classic ? "text.darkmusic.show_the_current_track_s_progress_below_the_title_and_artist" : "circle.darkmusic.progress_help");
        if (s.hudEnabled && classic) {
            toggle("hud.darkmusic.time", () -> s.hudTime, v -> {
                s.hudTime = v;
                rebuildWidgets();
            }, "hud.darkmusic.time_help");
            if (s.hudTime)
                choice("hud.darkmusic.time_position", "hud.darkmusic.time_position.", () -> s.hudTimePosition, v -> s.hudTimePosition = v,
                    "hud.darkmusic.time_position_help");
        }
        if (!linear && s.hudProgress)
            rotation("circle.darkmusic.progress_rotation", () -> s.circularProgressRotation, v -> s.circularProgressRotation = v, "circle.darkmusic.progress_rotation_help");
        toggle("progress.darkmusic.waveform", () -> s.waveformProgress, v -> {
            s.waveformProgress = v;
            rebuildWidgets();
        }, "progress.darkmusic.waveform_help");
        finish("finish.darkmusic.progress", () -> s.progressFinish, v -> s.progressFinish = v);
        action("colors.darkmusic.progress", () -> show(new ColorsScreen(app, this, false)), "colors.darkmusic.help");
        if (s.waveformProgress) {
            choice("waveform.style", "waveform.style.", () -> s.waveform.style, v -> s.waveform.style = v, "waveform.style.help");
            action("waveform.unplayed", () -> show(new ColorPickerScreen(this, s.waveform.unplayedColor, 0x555D78, color -> {
                s.waveform.unplayedColor = color;
                app.save();
            })), "waveform.unplayed.help");
            waveformSlider((s.waveform.height - 6) / 12.0, v -> Text.get("waveform.height", s.waveform.height),
                v -> s.waveform.height = 6 + (int) Math.round(v * 12), 12, "waveform.height.help");
            waveformSlider((s.waveform.detail - 48) / 464.0, v -> Text.get("waveform.detail", s.waveform.detail),
                v -> s.waveform.detail = 48 + (int) Math.round(v * 464), 464, "waveform.detail.help");
            if (s.waveform.style == WaveformSettings.Style.BARS)
                waveformSlider(s.waveform.spacing / 4.0, v -> Text.get("waveform.spacing", s.waveform.spacing),
                    v -> s.waveform.spacing = (int) Math.round(v * 4), 4, "waveform.spacing.help");
            waveformSlider(s.waveform.smoothing, v -> Text.get("waveform.smoothing", Math.round(s.waveform.smoothing * 100)),
                v -> s.waveform.smoothing = v, 100, "waveform.smoothing.help");
            waveformSlider((s.waveform.contrast - .5) / 2, v -> Text.get("waveform.contrast", Math.round(s.waveform.contrast * 100) / 100.0),
                v -> s.waveform.contrast = .5 + v * 2, 100, "waveform.contrast.help");
            waveformSlider((s.waveform.opacity - .15) / .85, v -> Text.get("waveform.opacity", Math.round(s.waveform.opacity * 100)),
                v -> s.waveform.opacity = .15 + v * .85, 85, "waveform.opacity.help");
            action("waveform.reset", () -> {
                s.waveform = new WaveformSettings();
                app.save();
                rebuildWidgets();
            }, "waveform.reset.help").destructive();
        }
    }

    private void spectrumSettings(Settings s) {
        var style = HudKind.of(s);
        boolean artwork = style.artwork(), spectrumOnly = style.spectrumOnly(), linear = style.linear();
        section("hud.darkmusic.section_spectrum");
        var chosenStyle = linear ? s.spectrumStyle : s.circularSpectrumStyle;
        choice("style.darkmusic.spectrum", () -> Text.get((linear ? "style.darkmusic.spectrum." : "circle.darkmusic.spectrum.") + name(chosenStyle)), () -> {
            if (linear)
                s.spectrumStyle = switch (s.spectrumStyle) {
                    case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                    case ROUNDED_BARS -> Settings.SpectrumStyle.SMOOTH_WAVE;
                    case FILLED_SPIKE -> Settings.SpectrumStyle.BARS;
                    default -> next(s.spectrumStyle);
                };
            else s.circularSpectrumStyle = switch (s.circularSpectrumStyle) {
                case BARS -> Settings.SpectrumStyle.ROUNDED_BARS;
                case ROUNDED_BARS -> Settings.SpectrumStyle.FILLED_WAVE;
                case FILLED_WAVE -> Settings.SpectrumStyle.FILLED_SPIKE;
                default -> Settings.SpectrumStyle.BARS;
            };
        }, linear ? "style.darkmusic.spectrum_help" : "circle.darkmusic.spectrum_help");
        if (s.hudEnabled && linear) {
            if (!spectrumOnly) {
                if (artwork)
                    choice("hud.darkmusic.position", "hud.darkmusic.position.", () -> s.artworkSpectrumPosition, v -> s.artworkSpectrumPosition = v,
                        "hud.darkmusic.artwork_position_help");
                else
                    choice("hud.darkmusic.position", "hud.darkmusic.position.", () -> s.spectrumPosition, v -> s.spectrumPosition = v,
                        "hud.darkmusic.position_help");
            }
            if (!artwork || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.ABOVE
                || s.artworkSpectrumPosition == Settings.ArtworkSpectrumPosition.BELOW) {
                choice("hud.darkmusic.direction", "hud.darkmusic.direction.", () -> s.spectrumDirection, v -> s.spectrumDirection = v,
                    "hud.darkmusic.direction_help");
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
        if (s.hudEnabled)
            slider(linear ? ((artwork ? s.artworkSpectrumHeight : s.classicSpectrumHeight) - 8) / 92.0 : (s.circularSpectrumHeight - 4) / 56.0,
                v -> Text.get("hud.darkmusic.spectrum_height", artwork ? s.artworkSpectrumHeight : linear ? s.classicSpectrumHeight : s.circularSpectrumHeight),
                v -> {
                    if (artwork) s.artworkSpectrumHeight = 8 + (int) Math.round(v * 92);
                    else if (linear) s.classicSpectrumHeight = 8 + (int) Math.round(v * 92);
                    else s.circularSpectrumHeight = 4 + (int) Math.round(v * 56);
                }, linear ? 92 : 56, "hud.darkmusic.spectrum_height_help");
    }

    private boolean searching() {
        return !query.isBlank();
    }

    /**
     * Every word of the search must appear in the row's label, help text, or section and group names.
     */
    static boolean matches(String query, String label, String details) {
        String haystack = (label + " " + details).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).strip().split("\\s+"))
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        return true;
    }

    private boolean matches(Placed p) {
        return matches(query, p.widget().getMessage().getString(), p.details());
    }

    /**
     * Matching rows from all sections in order, each section introduced by its heading.
     */
    private void positionSearch() {
        searchHeadings.clear();
        searchRows.clear();
        int y = 0, lastSection = -1;
        for (var p : controls) {
            if (!matches(p)) continue;
            if (p.section() != lastSection) {
                searchHeadings.add(new Section(sections.get(p.section()).title(), y));
                y += 24;
                lastSection = p.section();
            }
            searchRows.put(p.widget(), y);
            y += 26;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, y - (bottom - top)));
        if (scrollbar != null) {
            scrollbar.configure(formX + formWidth + 3, top, bottom - top, y, bottom - top);
            scrollbar.visible &= !previewOnly;
        }
        for (var p : controls) {
            Integer row = searchRows.get(p.widget());
            int at = top + (row == null ? 0 : row) - scroll;
            p.widget().setX(formX);
            p.widget().setY(at);
            p.widget().visible = !previewOnly && row != null && at >= top && at + 20 <= bottom;
            if (!p.widget().visible && getFocused() == p.widget()) setFocused(null);
        }
    }

    private void position() {
        if (searching()) {
            positionSearch();
            return;
        }
        int origin = sections.get(selectedSection).y();
        int end = selectedSection < sections.size() - 1 ? sections.get(selectedSection + 1).y() : cursor;
        scroll = Math.clamp(scroll, 0, Math.max(0, end - origin - (bottom - top)));
        sectionScroll[selectedSection] = scroll;
        if (scrollbar != null) {
            scrollbar.configure(formX + formWidth + 3, top, bottom - top, end - origin, bottom - top);
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
    public boolean keyPressed(KeyEvent event) {
        var rows = controls.stream().filter(p -> searching() ? searchRows.containsKey(p.widget()) : p.section() == selectedSection).toList();
        int current = IntStream.range(0, rows.size()).filter(i -> rows.get(i).widget() == getFocused()).findFirst().orElse(-1);
        if (!previewOnly && event.key() == 258 && current >= 0) {
            int next = FocusRows.next(rows.stream().map(p -> p.widget().active).toList(), current, event.hasShiftDown(), false);
            if (next >= 0) {
                var row = rows.get(next);
                int y = searching() ? searchRows.get(row.widget()) : row.y() - sections.get(selectedSection).y();
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
        if (previewVisible && (lyricsPreview() ? LyricsHud.enabled(app.settings) : app.settings.hudEnabled) && x >= previewX && x < previewX + previewWidth && y >= previewY + 20 && y < previewY + previewHeight - (progressPreview() ? 54 : 0) && dy != 0) {
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
        // The Media Controls row shows installation progress and the connection as they change.
        String media = mediaSignature();
        if (!media.equals(shownMedia)) rebuildWidgets();
    }

    private String shownMedia = "";

    private String mediaSignature() {
        var media = app.mediaPackage;
        return media.state() + ":" + (media.size() > 0 ? media.downloaded() * 20 / media.size() : 0) + ":" + app.mediaControls.connected();
    }

    /**
     * Windows x64 only: Install while missing, progress while installing, then an on/off switch with its status.
     */
    private void mediaControlsRows(Settings s) {
        var media = app.mediaPackage;
        shownMedia = mediaSignature();
        if (!media.available()) return;
        long megabytes = Math.max(1, Math.round(media.size() / 1_048_576.0));
        switch (media.state()) {
            case READY -> {
                put(new SettingRow(12, 0, formWidth, Text.get("media.darkmusic.enabled"), () -> s.mediaControls, () -> {
                    s.mediaControls = !s.mediaControls;
                    app.save();
                    rebuildWidgets();
                }), Text.get("media.darkmusic.enabled_help") + (s.mediaControls
                    ? " " + Text.get(app.mediaControls.connected() ? "media.darkmusic.state.connected" : "media.darkmusic.state.starting") : ""));
                action("media.darkmusic.remove", () -> show(new ConfirmationScreen(this, Text.component("media.darkmusic.remove"),
                    Text.component("media.darkmusic.remove_body"), Text.component("media.darkmusic.remove"), () -> {
                    // Stop the helper first so its files can be deleted.
                    s.mediaControls = false;
                    app.save();
                    app.mediaControls.update(null, null, System.nanoTime());
                    return media.remove();
                })), "media.darkmusic.remove_help").destructive();
            }
            case INSTALLING -> {
                var row = action("media.darkmusic.install", () -> {
                }, "media.darkmusic.install_help");
                row.active = false;
                row.setMessage(Component.literal(Text.get("media.darkmusic.installing",
                    media.size() > 0 ? Math.round(media.downloaded() * 100.0 / media.size()) : 0)));
            }
            default -> {
                var row = action("media.darkmusic.install", () -> show(new ConfirmationScreen(this, Text.component("media.darkmusic.install"),
                    Text.component("media.darkmusic.install_body", megabytes), Text.component("media.darkmusic.install_confirm"),
                    () -> media.install().thenRun(() -> Minecraft.getInstance().execute(() -> {
                        if (media.state() == MediaControlsPackage.State.READY) {
                            s.mediaControls = true;
                            app.save();
                        }
                    })))), "media.darkmusic.install_help");
                if (media.state() == MediaControlsPackage.State.FAILED)
                    Help.tip(row, Text.get("media.darkmusic.install_help") + " " + Text.get(media.failure()));
            }
        }
    }

    private void fitPreview() {
        var layout = NowPlayingHud.content(app);
        int w = lyricsPreview() ? app.settings.lyricsHudWidth : layout.width();
        int h = lyricsPreview() ? HudLyricsLayout.height(app.settings.lyricsNextLine) : layout.height();
        double fit = Math.min((previewWidth - 16.0) / w, (previewHeight - 30.0 - (progressPreview() ? 54 : 0)) / h);
        previewScale = Math.min(previewScale, Math.max(.1, Math.floor(fit * 10) / 10));
    }

    private boolean lyricsPreview() {
        return SECTION_KEYS[selectedSection].equals("lyrics.section");
    }

    @Override
    public boolean previewsProgress() {
        return previewVisible && (progressPreview() || app.settings.hudEnabled && !lyricsPreview());
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
            g.text(font, Text.get(app.settings.hudEnabled || lyricsPreview() ? "hud.darkmusic.live_preview" : "settings.player_preview"), previewX + 6, previewY + 6, UiTheme.ACCENT);
            g.enableScissor(previewX + 2, previewY + 20, previewX + previewWidth - 2, previewY + previewHeight - 2);
            if (app.settings.hudEnabled || lyricsPreview()) fitPreview();
            if (lyricsPreview())
                LyricsHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30, previewScale);
            else if (!app.settings.hudEnabled) {
                int available = previewHeight - 30 - (progressPreview() ? 54 : 0);
                int spectrumHeight = Math.max(1, Math.min(60, available));
                playerSpectrumPreview.bars(app, g, SpectrumRenderer.Bars.at(previewX + 12, previewY + 22 + (available - spectrumHeight) / 2,
                    previewWidth - 24, spectrumHeight, false, true));
            } else
                NowPlayingHud.renderPreview(app, g, previewX + 8, previewY + 22, previewWidth - 16, previewHeight - 30 - (progressPreview() ? 54 : 0), previewScale, longText);
            if (progressPreview()) {
                int divider = previewY + previewHeight - 54;
                g.fill(previewX + 6, divider, previewX + previewWidth - 6, divider + 1, UiTheme.BORDER);
                longText.draw(g, font, "player_progress_preview", Text.get("progress.darkmusic.player_preview"), previewX + 10, divider + 7, previewWidth - 20, UiTheme.ACCENT);
                var state = app.playback.state();
                boolean actual = state.status() == AudioBackend.Status.PLAYING || state.status() == AudioBackend.Status.PAUSED;
                playerProgressPreview.syncNow(actual && state.duration() > 0 ? state.position() / state.duration() : .4);
                playerProgressPreview.extractWidgetRenderState(g, Integer.MIN_VALUE, Integer.MIN_VALUE, delta);
            }
            g.disableScissor();
            if (System.nanoTime() - scaleChanged < 2_000_000_000L) {
                String scale = Text.get("hud.darkmusic.preview_scale", Math.round(previewScale * 100));
                g.text(font, scale, previewX + previewWidth - font.width(scale) - 8, previewY + 6, UiTheme.ACCENT);
            }
        }
        if (!previewOnly && searching()) {
            g.enableScissor(formX, top, formX + formWidth, bottom);
            for (var heading : searchHeadings) heading(g, heading.title(), top + heading.y() - scroll);
            if (searchHeadings.isEmpty())
                g.text(font, HorizontalText.ellipsize(font, Text.get("settings.search_none", query.strip()), formWidth - 8), formX + 4, top + 6, UiTheme.MUTED);
            g.disableScissor();
        } else if (!previewOnly) {
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

    /**
     * A row and, for search, its help text with its section and group names.
     */
    /**
     * Which rows the chosen HUD style offers. Every style but the circular one draws its spectrum in a line, and so
     * does a hidden HUD.
     */
    private record HudKind(boolean classic, boolean artwork, boolean spectrumOnly, boolean linear) {
        static HudKind of(Settings s) {
            boolean classic = s.hudStyle == Settings.HudStyle.CLASSIC;
            boolean artwork = s.hudStyle == Settings.HudStyle.ARTWORK;
            boolean spectrumOnly = s.hudStyle == Settings.HudStyle.SPECTRUM;
            return new HudKind(classic, artwork, spectrumOnly, !s.hudEnabled || classic || artwork || spectrumOnly);
        }
    }

    private record Placed(AbstractWidget widget, int y, int section, String details) {
    }

    private record Subsection(String title, int y, int section) {
    }

    private record Section(String title, int y) {
    }
}
