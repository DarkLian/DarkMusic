package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.*;

/**
 * Shared scrollable rows for the additive audio pages.
 */
abstract class AudioPage extends LocalScreen {
    protected final DarkMusicClient app;
    protected int formX, formWidth, cursor, scroll;
    private final List<AbstractWidget> rows = new ArrayList<>();
    private final List<Integer> positions = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private final List<Detail> details = new ArrayList<>();
    private ScrollBar scrollbar;

    AudioPage(DarkMusicClient app, Screen parent, String title) {
        super(Text.component(title), parent);
        this.app = app;
    }

    protected abstract void populate();

    protected int sectionColor() {
        return UiTheme.ACCENT;
    }

    protected UiIcon pageIcon() {
        return UiIcon.VOLUME;
    }

    protected void changed() {
        app.save();
        rebuildWidgets();
    }

    protected void adjusted() {
    }

    protected final void place(AbstractWidget widget) {
        rows.add(addRenderableWidget(widget));
        positions.add(cursor);
        cursor += 26;
    }

    protected final void section(String key) {
        headings.add(new Heading(key, cursor));
        cursor += 24;
    }

    protected final void info(Supplier<String> text) {
        details.add(new Detail(text, cursor));
        cursor += 22;
    }

    protected final void info(String key) {
        info(() -> Text.get(key));
    }

    protected final void action(String key, Runnable run) {
        place(new SettingRow(formX, 0, formWidth, Text.get(key), null, run));
    }

    protected final SettingRow labeledAction(String label, String help, Runnable run) {
        var row = new SettingRow(formX, 0, formWidth, label, null, run);
        place(row);
        Help.tip(row, help);
        return row;
    }

    protected final void revealRow(int index) {
        scroll = Math.max(0, positions.get(index) - Math.max(0, height - 72) / 2);
    }

    protected final int rowCount() {
        return rows.size();
    }

    protected final void enableSince(int start, boolean enabled) {
        for (int i = start; i < rows.size(); i++) rows.get(i).active = enabled;
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

    protected final void numberedAction(String key, int number, Runnable run) {
        place(new SettingRow(formX, 0, formWidth, Text.get(key) + " " + number, null, run));
    }

    protected final void toggle(String key, BooleanSupplier read, Consumer<Boolean> write) {
        var row = new SettingRow(formX, 0, formWidth, Text.get(key), read, () -> {
            write.accept(!read.getAsBoolean());
            changed();
        });
        place(row);
        Help.tip(row, Text.get(key + ".help"));
    }

    protected final <E extends Enum<E>> void choice(String key, E current, E[] values, Consumer<E> write) {
        int index = Arrays.asList(values).indexOf(current);
        var row = new SettingRow(formX, 0, formWidth, Text.get(key) + ": " + Text.get("dsp.value." + current.name().toLowerCase(Locale.ROOT)), null, () -> {
            write.accept(values[(index + 1) % values.length]);
            changed();
        });
        place(row);
        Help.tip(row, Text.get(key + ".help"));
    }

    protected final void slider(String key, double value, double low, double high, int steps, DoubleConsumer write, String unit) {
        var parameter = new AudioParameter(low, high, unit.equals(" Hz") && low >= 20, unit);
        double[] current = {value};
        var slider = new ValueSlider(formX, 0, formWidth, parameter.position(value), v -> Text.get(key) + ": " + parameter.format(parameter.value(v)),
            v -> {
                current[0] = parameter.value(v);
                write.accept(current[0]);
                adjusted();
                app.configureVisualizer();
            }, () -> {
            app.save();
            rebuildWidgets();
        }, parameter.logarithmic() ? 2048 : steps);
        slider.exactEditor(() -> show(TextPromptScreen.validated(this,
            Text.get("audio.parameter.title", Text.get(key), low, high, unit.strip()), Double.toString(current[0]), 64, input -> {
                write.accept(parameter.parse(input));
                adjusted();
                app.save();
                return true;
            })));
        slider.plain(true);
        place(slider);
        Help.tip(slider, Text.get(key + ".help") + " " + Text.get("audio.parameter.help"));
    }

    @Override
    protected final void init() {
        formWidth = Math.min(720, width - 28);
        formX = (width - formWidth) / 2;
        cursor = 0;
        rows.clear();
        positions.clear();
        headings.clear();
        details.clear();
        populate();
        scrollbar = addRenderableWidget(new ScrollBar(() -> scroll, value -> {
            scroll = value;
            position();
        }));
        position();
    }

    private void position() {
        scroll = Math.clamp(scroll, 0, Math.max(0, cursor - (height - 50)));
        scrollbar.configure(formX + formWidth + 3, 36, height - 48, cursor, height - 48);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int y = 36 + positions.get(i) - scroll;
            row.setY(y);
            row.visible = y >= 36 && y + 20 <= height - 12;
            if (!row.visible && getFocused() == row) setFocused(null);
        }
    }

    protected int contentY(int position) {
        return 36 + position - scroll;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (HorizontalText.horizontalIntent(dx, dy) || x < formX || x > formX + formWidth)
            return super.mouseScrolled(x, y, dx, dy);
        scroll -= (int) Math.round(dy * 24);
        position();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, pageIcon());
        longText.begin();
        g.enableScissor(formX - 2, 36, formX + formWidth + 2, height - 12);
        for (int i = 0; i < headings.size(); i++) {
            var h = headings.get(i);
            int end = i + 1 < headings.size() ? headings.get(i + 1).y : cursor;
            UiTheme.panel(g, formX - 2, contentY(h.y), formWidth + 4, end - h.y - 4);
            g.text(font, Text.get(h.key), formX + 4, contentY(h.y) + 4, sectionColor());
        }
        for (var d : details)
            if (contentY(d.y) > 20 && contentY(d.y) < height)
                longText.draw(g, font, "detail" + d.y, d.text.get(), formX + 4, contentY(d.y) + 5, formWidth - 8, UiTheme.SECONDARY);
        renderContent(g, x, y, delta);
        g.disableScissor();
        super.extractRenderState(g, x, y, delta);
    }

    protected void renderContent(GuiGraphicsExtractor g, int x, int y, float delta) {
    }

    private record Heading(String key, int y) {
    }

    private record Detail(Supplier<String> text, int y) {
    }
}
