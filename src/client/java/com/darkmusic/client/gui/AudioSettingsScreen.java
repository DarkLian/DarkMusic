package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.DownloadEncoding;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Audio controls use the existing setting rows, sliders, scrolling and automatic save behavior.
 */
public final class AudioSettingsScreen extends LocalScreen {
    private final DarkMusicClient app;
    private ScrollBar scrollbar;
    private final List<AbstractWidget> rows = new ArrayList<>();
    private final List<Integer> positions = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<Detail> details = new ArrayList<>();
    private int scroll, cursor;
    private int formX, formWidth;
    private int normalizationY = -1;
    private String notice = "";
    private AbstractWidget analysisButton;

    private void place(AbstractWidget widget) {
        rows.add(addRenderableWidget(widget));
        positions.add(cursor);
        cursor += 26;
    }

    private void section(String key) {
        sections.add(new Section(Text.get(key), cursor));
        cursor += 24;
    }

    private void info(java.util.function.Supplier<String> text) {
        details.add(new Detail(text, cursor));
        cursor += 22;
    }

    public AudioSettingsScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("audio.darkmusic.title"), parent);
        this.app = app;
    }

    private void row(String key, BooleanSupplier state, Runnable click, String help) {
        var widget = new SettingRow(formX, 0, formWidth, Text.get(key), state, () -> {
            click.run();
            app.save();
            rebuildWidgets();
        });
        if (!help.isBlank()) Help.tip(widget, Text.get(help));
        place(widget);
    }

    private void toggle(String key, BooleanSupplier get, Consumer<Boolean> set, String help) {
        row(key, get, () -> {
            set.accept(!get.getAsBoolean());
            app.settings.audio.profile = "CUSTOM";
        }, help);
    }

    private <E extends Enum<E>> void choice(String key, E current, E[] values, Consumer<E> change, String help) {
        row(key, null, () -> {
            change.accept(values[(current.ordinal() + 1) % values.length]);
            if (!key.equals("audio.darkmusic.download")) app.settings.audio.profile = "CUSTOM";
        }, help);
        rows.getLast().setMessage(Component.literal(Text.get(key) + ": " + Text.get("audio.darkmusic.value." + current.name().toLowerCase(Locale.ROOT))));
    }

    private void slider(double value, String key, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        double[] current = {value};
        var slider = new ValueSlider(formX, 0, formWidth, value, v -> Text.get(key) + ": " + label.apply(v), v -> {
            current[0] = v;
            change.accept(v);
            app.settings.audio.profile = "CUSTOM";
            app.configureVisualizer();
        }, app::save, steps);
        slider.plain(true);
        var parameter = switch (key) {
            case "audio.darkmusic.target" -> new AudioParameter(-24, -9, false, " LUFS");
            case "audio.darkmusic.band" -> new AudioParameter(-12, 12, false, " dB");
            case "audio.darkmusic.bass" -> new AudioParameter(0, 6, false, " dB");
            case "audio.darkmusic.width" -> new AudioParameter(0, 150, false, "%");
            case "audio.darkmusic.crossfade" -> new AudioParameter(0, 10, false, " s");
            case "audio.darkmusic.duck_attack" -> new AudioParameter(.01, .2, false, " s");
            case "audio.darkmusic.duck_hold", "audio.darkmusic.duck_release" -> new AudioParameter(.1, 2, false, " s");
            default -> new AudioParameter(0, 100, false, "%");
        };
        slider.exactEditor(() -> show(TextPromptScreen.validated(this, Text.get("audio.parameter.title", Text.get(key), parameter.low(), parameter.high(), parameter.unit().strip()),
            Double.toString(parameter.value(current[0])), 64, input -> {
                change.accept(parameter.position(parameter.parse(input)));
                app.settings.audio.profile = "CUSTOM";
                app.save();
                return true;
            })));
        place(Help.tip(slider, Text.get(help) + " " + Text.get("audio.parameter.help")));
    }

    @Override
    protected void init() {
        formWidth = Math.min(720, width - 24);
        formX = (width - formWidth) / 2;
        rows.clear();
        positions.clear();
        sections.clear();
        details.clear();
        cursor = 0;
        normalizationY = -1;
        analysisButton = null;
        var a = app.settings.audio;
        section("audio.darkmusic.section_playback");
        row("device.title", null, () -> show(new OutputDeviceScreen(app, this)), "device.help");
        row("analyzer.title", null, () -> show(new AudioAnalyzerScreen(app, this, com.darkmusic.client.audio.AudioComparison.Mode.AUDIO_OPTIONS)), "analyzer.help");
        row("dsp.advanced", null, () -> show(new AudioAdvancedScreen(app, this)), "dsp.advanced.help");
        toggle("audio.darkmusic.neutral", () -> a.neutral, v -> a.neutral = v, "audio.darkmusic.neutral_help");
        info(() -> Text.get(a.neutral ? "dsp.neutral_scope" : "audio.basic_hint"));
        choice("audio.darkmusic.download", app.settings.downloadFormat, DownloadEncoding.Format.values(), v -> app.settings.downloadFormat = v, "audio.darkmusic.download_help");
        String[] profiles = {"DEFAULT", "MUSIC", "GAMING", "VOCAL", "NIGHT"};
        row("audio.darkmusic.profile", null, () -> a.selectProfile(profiles[(Arrays.asList(profiles).indexOf(a.profile) + 1) % profiles.length]), "audio.darkmusic.profile_help");
        rows.getLast().setMessage(Component.literal(Text.get("audio.darkmusic.profile") + ": " + Text.get("audio.darkmusic.value." + a.profile.toLowerCase(Locale.ROOT))));
        info(() -> Text.get("audio.profile_description." + a.profile.toLowerCase(Locale.ROOT)));
        choice("audio.darkmusic.listening", a.listening, AudioSettings.Listening.values(), v -> a.listening = v, "audio.darkmusic.listening_help");
        int effectsStart = rows.size();
        section("audio.darkmusic.section_loudness");
        toggle("audio.darkmusic.normalization", () -> a.normalization, v -> a.normalization = v, "audio.darkmusic.normalization_help");
        if (a.normalization) {
            choice("audio.darkmusic.gain_mode", a.gainMode, AudioSettings.Normalization.values(), v -> a.gainMode = v, "audio.darkmusic.gain_mode_help");
            toggle("audio.darkmusic.analyze", () -> a.analyzeLoudness, v -> a.analyzeLoudness = v, "audio.darkmusic.analyze_help");
            if (a.analyzeLoudness) {
                toggle("audio.darkmusic.parallel_analysis", () -> a.parallelLoudnessAnalysis, v -> a.parallelLoudnessAnalysis = v, "audio.darkmusic.parallel_analysis_help");
                if (a.parallelLoudnessAnalysis) info(() -> Text.get("audio.darkmusic.parallel_analysis_warning"));
                row("audio.darkmusic.analyze_library", null, app::analyzeLibraryLoudness, "audio.darkmusic.analyze_library_help");
                analysisButton = rows.getLast();
                info(() -> {
                    var progress = app.loudnessAnalysis();
                    return progress.total() == 0 ? "" : Text.get(!progress.running() && progress.processed() < progress.total() ? "audio.darkmusic.analysis_stopped" : "audio.darkmusic.analysis_progress", progress.processed(), progress.total(), progress.available(), progress.processed() - progress.available());
                });
            }
            slider((a.targetLufs + 24) / 15, "audio.darkmusic.target", v -> String.format(Locale.ROOT, "%.0f LUFS", a.targetLufs), v -> a.targetLufs = -24 + v * 15, 15, "audio.darkmusic.target_help");
            normalizationY = cursor;
            cursor += 20;
        }
        section("audio.darkmusic.section_equalizer");
        toggle("audio.darkmusic.eq", () -> a.equalizer, v -> a.equalizer = v, "audio.darkmusic.eq_help");
        if (a.equalizer) {
            var preset = AudioSettings.Equalizer.CUSTOM;
            try {
                preset = AudioSettings.Equalizer.valueOf(a.eqPreset);
            } catch (IllegalArgumentException ignored) {
            }
            choice("audio.darkmusic.eq_preset", preset, AudioSettings.Equalizer.values(), a::equalizer, "audio.darkmusic.eq_preset_help");
            for (int i = 0; i < 10 && a.enhancements.eqMode == com.darkmusic.client.config.AudioEnhancements.EqMode.GRAPHIC; i++) {
                int b = i;
                double hz = com.darkmusic.client.audio.AudioProcessor.FREQUENCIES[i];
                slider((a.bands[i] + 12) / 24, "audio.darkmusic.band", v -> String.format(Locale.ROOT, "%.0f Hz: %+.1f dB", hz, a.bands[b]), v -> {
                    a.bands[b] = -12 + v * 24;
                    a.eqPreset = "CUSTOM";
                }, 48, "audio.darkmusic.band_help");
            }
        }
        section("audio.darkmusic.section_effects");
        slider(a.bass / 6, "audio.darkmusic.bass", v -> String.format(Locale.ROOT, "%.1f dB", a.bass), v -> a.bass = v * 6, 12, "audio.darkmusic.bass_help");
        slider(a.width / 1.5, "audio.darkmusic.width", v -> Math.round(a.width * 100) + "%", v -> a.width = v * 1.5, 30, "audio.darkmusic.width_help");
        toggle("audio.darkmusic.night", () -> a.compressor, v -> a.compressor = v, "audio.darkmusic.night_help");
        choice("audio.darkmusic.reverb", a.reverb, AudioSettings.Reverb.values(), v -> a.reverb = v, "audio.darkmusic.reverb_help");
        toggle("audio.darkmusic.limiter", () -> a.limiter, v -> a.limiter = v, "audio.darkmusic.limiter_help");
        for (int i = effectsStart; i < rows.size(); i++) rows.get(i).active = !a.neutral;
        section("audio.darkmusic.section_transitions");
        toggle("audio.darkmusic.gapless", () -> a.gapless, v -> a.gapless = v, "audio.darkmusic.transition_help");
        slider(a.crossfade / 10, "audio.darkmusic.crossfade", v -> String.format(Locale.ROOT, "%.1f s", a.crossfade), v -> a.crossfade = v * 10, 20, "audio.darkmusic.crossfade_help");
        section("audio.darkmusic.section_game_audio");
        if (a.listening == AudioSettings.Listening.AMBIENT)
            slider(a.ambientGain, "audio.darkmusic.ambient", v -> Math.round(a.ambientGain * 100) + "%", v -> a.ambientGain = v, 100, "audio.darkmusic.ambient_help");
        if (a.listening == AudioSettings.Listening.FOCUS)
            slider(a.focusGain, "audio.darkmusic.focus", v -> Math.round(a.focusGain * 100) + "%", v -> a.focusGain = v, 100, "audio.darkmusic.focus_help");
        toggle("audio.darkmusic.ducking", () -> a.ducking, v -> a.ducking = v, "audio.darkmusic.ducking_help");
        if (a.ducking) {
            slider(a.duckGain, "audio.darkmusic.duck_level", v -> Math.round(a.duckGain * 100) + "%", v -> a.duckGain = v, 100, "audio.darkmusic.duck_level_help");
            slider((a.duckAttack - .01) / .19, "audio.darkmusic.duck_attack", v -> Math.round(a.duckAttack * 1000) + " ms", v -> a.duckAttack = .01 + v * .19, 19, "audio.darkmusic.duck_attack_help");
            slider((a.duckHold - .1) / 1.9, "audio.darkmusic.duck_hold", v -> String.format(Locale.ROOT, "%.1f s", a.duckHold), v -> a.duckHold = .1 + v * 1.9, 19, "audio.darkmusic.duck_hold_help");
            slider((a.duckRelease - .1) / 1.9, "audio.darkmusic.duck_release", v -> String.format(Locale.ROOT, "%.1f s", a.duckRelease), v -> a.duckRelease = .1 + v * 1.9, 19, "audio.darkmusic.duck_release_help");
        }
        section("audio.saved_profiles");
        info(() -> app.warning.equals(Text.get("error.save_settings")) ? app.warning : notice);
        row("audio.darkmusic.save_profile", null, () -> show(PresetDialogs.save(this, "audio.darkmusic.save_profile", a.savedProfiles, Integer.MAX_VALUE, 100, (name, replace) -> {
            var copy = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(a), AudioSettings.class);
            copy.savedProfiles.clear();
            com.darkmusic.client.config.PresetNames.save(a.savedProfiles, name, ConfigStore.JSON.toJson(copy), Integer.MAX_VALUE, replace, 100);
            notice = Text.get("preset.saved");
            app.save();
        })), "audio.darkmusic.save_profile_help");
        for (String name : List.copyOf(a.savedProfiles.keySet())) {
            row("audio.darkmusic.load_profile", null, () -> {
                try {
                    var loaded = ConfigStore.JSON.fromJson(a.savedProfiles.get(name), AudioSettings.class);
                    loaded.validate();
                    loaded.enhancements.preservePitch = a.enhancements.preservePitch;
                    loaded.savedProfiles = a.savedProfiles;
                    app.settings.audio = loaded;
                } catch (RuntimeException e) {
                    app.warn(Text.get("audio.darkmusic.invalid_profile"));
                }
            }, "audio.darkmusic.load_profile_help");
            rows.getLast().setMessage(Component.literal(Text.get("audio.darkmusic.load_profile") + ": " + name));
            row("preset.rename", null, () -> show(TextPromptScreen.validated(this, Text.get("preset.rename"), name, 256, renamed -> {
                com.darkmusic.client.config.PresetNames.rename(a.savedProfiles, name, renamed, 100);
                notice = Text.get("preset.renamed");
                app.save();
                return true;
            })), "preset.rename_help");
            rows.getLast().setWidth(formWidth - 28);
            var delete = UiButton.icon(UiButton.builder(Text.component("audio.darkmusic.delete_profile", name), b ->
                show(new ConfirmationScreen(this, Text.component("audio.darkmusic.delete_profile_title"),
                    Text.component("audio.darkmusic.delete_profile_body", name), Text.component("text.darkmusic.delete"), () -> {
                    app.settings.audio.savedProfiles.remove(name);
                    notice = Text.get("preset.deleted");
                    app.save();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }))).bounds(formX + formWidth - 24, 0, 24, 20).build(), UiIcon.DELETE, true);
            UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
            rows.add(addRenderableWidget(delete));
            positions.add(cursor - 26);
        }
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
    }

    private void position() {
        scroll = Math.clamp(scroll, 0, Math.max(0, cursor - (height - 48)));
        if (scrollbar != null) scrollbar.configure(formX + formWidth + 3, 36, height - 48, cursor, height - 48);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int y = 36 + positions.get(i) - scroll;
            row.setY(y);
            row.visible = y >= 36 && y + 20 <= height - 12;
            if (!row.visible && getFocused() == row) setFocused(null);
        }
    }

    @Override
    public void tick() {
        if (analysisButton != null) {
            analysisButton.setMessage(Text.component(app.loudnessAnalysis().running() ? "audio.darkmusic.cancel_analysis" : "audio.darkmusic.analyze_library"));
            analysisButton.active = !app.settings.audio.neutral && (app.loudnessAnalysis().running() || !app.library.snapshot().scanning() && !app.library.snapshot().tracks().isEmpty());
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 258 && (getFocused() == null || rows.contains(getFocused()))) {
            int index = FocusRows.next(rows.stream().map(w -> w.active).toList(), rows.indexOf(getFocused()), event.hasShiftDown(), true);
            if (index >= 0) {
                int y = positions.get(index);
                if (y < scroll || y + 24 > scroll + height - 50) scroll = Math.max(0, y - (height - 74) / 2);
                position();
                setFocused(rows.get(index));
                return true;
            }
        }
        int page = Math.max(24, height - 72);
        switch (event.key()) {
            case 266 -> scroll -= page;
            case 267 -> scroll += page;
            case 268 -> scroll = 0;
            case 269 -> scroll = cursor;
            default -> {
                return super.keyPressed(event);
            }
        }
        position();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy)) return super.mouseScrolled(x, y, dx, dy);
        if (x < formX || x > formX + formWidth || y < 36 || y > height - 12) return super.mouseScrolled(x, y, dx, dy);
        scroll -= (int) Math.round(dy * 24);
        position();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.VOLUME);
        g.enableScissor(formX - 2, 36, formX + formWidth + 2, height - 12);
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            int sectionY = 36 + section.y - scroll;
            int end = i + 1 < sections.size() ? sections.get(i + 1).y : cursor;
            UiTheme.panel(g, formX - 2, sectionY, formWidth + 4, end - section.y - 4);
            g.text(font, section.title, formX + 2, sectionY + 3, UiTheme.ACCENT);
        }
        longText.begin();
        for (var detail : details) {
            int detailY = 40 + detail.y - scroll;
            if (detailY >= 28 && detailY < height - 12)
                longText.draw(g, font, "detail" + detail.y, detail.text.get(), formX + 4, detailY, formWidth - 8, UiTheme.SECONDARY);
        }
        if (normalizationY >= 0) {
            String label = LoudnessStatusText.describe(app.playback.normalization());
            longText.draw(g, font, "normalization", label, formX + 4, 40 + normalizationY - scroll, formWidth - 8, UiTheme.SECONDARY);
        }
        g.disableScissor();
        super.extractRenderState(g, x, y, delta);
    }

    private record Section(String title, int y) {
    }

    private record Detail(java.util.function.Supplier<String> text, int y) {
    }
}
