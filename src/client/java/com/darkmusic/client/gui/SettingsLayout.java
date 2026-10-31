package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Builds the rows of the Settings form for one init: each row is placed below the last, in the section and group
 * being built, and remembers enough of its context for search. The screen owns the row lists and positions the rows.
 */
final class SettingsLayout {
    private final SettingsScreen screen;
    private final SettingsScreen.Sources app;
    private final UnaryOperator<AbstractWidget> add;
    private final Runnable rebuild;
    private final List<Placed> controls;
    private final List<Section> sections;
    private final List<Subsection> subsections;
    final int formWidth;
    int cursor;
    private int buildingSection = -1;
    private String subsectionTitle = "";

    SettingsLayout(SettingsScreen screen, SettingsScreen.Sources app, UnaryOperator<AbstractWidget> add, Runnable rebuild,
                   List<Placed> controls, List<Section> sections, List<Subsection> subsections, int formWidth) {
        this.screen = screen;
        this.app = app;
        this.add = add;
        this.rebuild = rebuild;
        this.controls = controls;
        this.sections = sections;
        this.subsections = subsections;
        this.formWidth = formWidth;
    }

    /**
     * The Settings screen, as the parent of the pages and pickers its rows open.
     */
    SettingsScreen screen() {
        return screen;
    }

    void rebuild() {
        rebuild.run();
    }

    void section(String key) {
        subsectionTitle = "";
        sections.add(new Section(Text.get(key), cursor));
        buildingSection++;
        cursor += 24;
    }

    void subsection(String key) {
        subsectionTitle = Text.get(key);
        subsections.add(new Subsection(subsectionTitle, cursor, buildingSection));
        cursor += 24;
    }

    void put(AbstractWidget widget, String help) {
        if (!help.isEmpty()) Help.tip(widget, help);
        String context = (sections.isEmpty() ? "" : sections.getLast().title()) + " " + subsectionTitle;
        controls.add(new Placed(add.apply(widget), cursor, buildingSection, help + " " + context));
        cursor += 26;
    }

    void toggle(String key, BooleanSupplier get, Consumer<Boolean> set, String help) {
        put(new SettingRow(12, 0, formWidth, Text.get(key), get, () -> {
            set.accept(!get.getAsBoolean());
            app.save();
        }), Text.get(help));
    }

    SettingRow action(String key, Runnable action, String help) {
        var row = new SettingRow(12, 0, formWidth, Text.get(key), null, action);
        put(row, Text.get(help));
        return row;
    }

    /**
     * A row that steps through choices on each click and shows the current one.
     */
    void choice(String key, Supplier<String> value, Runnable next, String help) {
        action(key, () -> {
            next.run();
            app.save();
            rebuild.run();
        }, help);
        controls.getLast().widget().setMessage(Component.literal(Text.get(key) + ": " + value.get()));
    }

    /**
     * A choice row over every value of an enum, named by {@code prefix} plus the value's lowercase name.
     */
    <E extends Enum<E>> void choice(String key, String prefix, Supplier<E> get, Consumer<E> set, String help) {
        choice(key, () -> Text.get(prefix + name(get.get())), () -> set.accept(SettingsScreen.next(get.get())), help);
    }

    static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    void finish(String key, Supplier<Settings.SurfaceFinish> get, Consumer<Settings.SurfaceFinish> set) {
        choice(key, "finish.darkmusic.", get, set, "finish.darkmusic.help");
    }

    void rotation(String key, Supplier<Settings.Rotation> get, Consumer<Settings.Rotation> set, String help) {
        choice(key, "circle.darkmusic.rotation.", get, set, help);
    }

    void slider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        var slider = new ValueSlider(12, 0, formWidth, initial, label, v -> {
            change.accept(v);
            app.applySettings();
        }, app::save, steps);
        put(slider, Text.get(help));
    }

    void waveformSlider(double initial, DoubleFunction<String> label, DoubleConsumer change, int steps, String help) {
        var slider = new ValueSlider(12, 0, formWidth, initial, label, change, app::save, steps);
        put(slider, Text.get(help));
    }

    /**
     * A placed row: its widget, its y within the whole form, its section, and the text search matches besides its label.
     */
    record Placed(AbstractWidget widget, int y, int section, String details) {
    }

    record Subsection(String title, int y, int section) {
    }

    record Section(String title, int y) {
    }
}
