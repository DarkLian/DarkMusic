package com.darkmusic.client.gui;

import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.config.WaveformSettings;
import com.darkmusic.client.render.ColorStrip;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.render.WaveformProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

public class ValueSlider extends AbstractSliderButton {
    @Override
    public void setTooltip(Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private final DoubleConsumer changed;
    private final Runnable committed;
    private final DoubleFunction<String> label;
    private final int steps;
    private long adjusted;
    private boolean dragging;
    private boolean trackOnly, simpleProgress;
    private Runnable exactEditor;

    public void exactEditor(Runnable editor) {
        exactEditor = editor;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (active && visible && exactEditor != null && event.button() == 1 && isMouseOver(event.x(), event.y())) {
            exactEditor.run();
            return true;
        }
        return super.mouseClicked(event, twice);
    }

    public void simpleProgress(boolean value) {
        simpleProgress = value;
    }

    public void trackOnly(boolean value) {
        trackOnly = value;
    }

    private Supplier<TrackWaveform> waveform;

    private Supplier<WaveformSettings> waveformOptions;

    public void waveform(Supplier<TrackWaveform> source,
                         Supplier<WaveformSettings> options) {
        waveform = source;
        waveformOptions = options;
    }

    private Supplier<VisualColors> colors;
    private Supplier<Settings.SurfaceFinish> finish = () -> Settings.SurfaceFinish.FLAT;

    public ValueSlider(int x, int y, int width, double value, DoubleFunction<String> label, DoubleConsumer changed, Runnable committed) {
        this(x, y, width, value, label, changed, committed, 0);
    }

    public ValueSlider(int x, int y, int width, double value, DoubleFunction<String> label, DoubleConsumer changed, Runnable committed, int steps) {
        super(x, y, width, 20, Component.empty(), value);
        this.label = label;
        this.changed = changed;
        this.committed = committed;
        this.steps = steps;
        updateMessage();
    }

    public void colors(Supplier<VisualColors> value) {
        colors = value;
    }

    public void finish(Supplier<Settings.SurfaceFinish> value) {
        finish = value;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (!trackOnly)
            g.fill(getX(), getY(), getRight(), getBottom(), active ? isHoveredOrFocused() ? UiTheme.HOVER : UiTheme.RAISED : UiTheme.SURFACE);
        if (!trackOnly || isFocused())
            g.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? UiTheme.ACCENT : UiTheme.BORDER);
        if (simpleProgress) {
            // Drawn like the HUD's progress bar: its rail, colors and finish, on the panel rather than a dark slot.
            if (waveform != null) {
                var options = waveformOptions.get();
                if (WaveformProgress.draw(g, waveform.get(), colors.get(), options, getX(), getY() + (20 - options.height) / 2,
                    getWidth(), options.height, value, finish.get())) return;
            }
            ColorStrip.track(g, getX(), getY() + 8, getWidth(), 4, finish.get());
            ColorStrip.draw(g, colors.get(), getX(), getY() + 8, getWidth(), 4, (int) Math.round(getWidth() * value), finish.get());
            return;
        }
        int handle = getX() + (int) Math.round(value * (getWidth() - 8));
        if (!trackOnly) {
            // Text above, track below: the handle never crosses the label.
            int trackY = getBottom() - 5;
            if (colors == null) {
                g.fill(getX() + 4, trackY, getRight() - 4, trackY + 2, UiTheme.BORDER);
                g.fill(getX() + 4, trackY, Math.max(getX() + 4, handle + 4), trackY + 2, active ? UiTheme.ACCENT : UiTheme.MUTED);
            }
            g.fill(handle + 2, trackY - 2, handle + 6, trackY + 4, active && isHoveredOrFocused() ? UiTheme.TEXT : active ? UiTheme.ACCENT : UiTheme.BORDER);
            var font = Minecraft.getInstance().font;
            String[] parts = labelParts(getMessage().getString());
            int valueWidth = font.width(parts[1]);
            int labelWidth = Math.max(1, getWidth() - 12 - (valueWidth > 0 ? valueWidth + 8 : 0));
            g.text(font, HorizontalText.ellipsize(font, parts[0], labelWidth), getX() + 6, getY() + 4, active ? UiTheme.TEXT : UiTheme.MUTED);
            if (valueWidth > 0)
                // Ends at the right edge of the checkbox column in setting rows.
                g.text(font, parts[1], getRight() - SettingRow.ICON_INSET + 12 - valueWidth, getY() + 4, active ? UiTheme.ACCENT : UiTheme.MUTED);
        } else
            g.fill(handle + 2, getBottom() - 7, handle + 6, getBottom() - 2, active ? UiTheme.ACCENT : UiTheme.BORDER);
        handleCursor(g);
        if (!active) g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0x55111420);
        if (colors != null) {
            int width = Math.max(1, getWidth() - 4);
            if (trackOnly) g.fill(getX() + 2, getBottom() - 4, getRight() - 2, getBottom() - 2, UiTheme.BORDER);
            if (finish.get() == Settings.SurfaceFinish.BEVELED)
                ColorStrip.track(g, getX() + 2, getBottom() - 4, width, 2, finish.get());
            ColorStrip.draw(g, colors.get(), getX() + 2, getBottom() - 4, width, 2, (int) Math.round(width * value), finish.get());
        }
    }

    /**
     * "Label: value" splits at the last ": ", so "EQ Band: 31 Hz: -2.0 dB" keeps its band in the label
     * and ratios such as "4.0:1" stay whole.
     */
    static String[] labelParts(String message) {
        int separator = Math.max(message.lastIndexOf(": "), message.lastIndexOf('：'));
        if (separator <= 0) return new String[]{message, ""};
        String value = message.substring(separator + 1).strip();
        return value.isEmpty() ? new String[]{message, ""} : new String[]{message.substring(0, separator + 1), value};
    }

    public void sync(double next) {
        if (!dragging && System.nanoTime() - adjusted > 500_000_000L) {
            value = Math.clamp(next, 0, 1);
            updateMessage();
        }
    }

    public void syncNow(double next) {
        adjusted = 0;
        value = Math.clamp(next, 0, 1);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        if (label != null) setMessage(Component.literal(label.apply(value)));
    }

    @Override
    protected void applyValue() {
        if (steps > 0) value = Math.round(value * steps) / (double) steps;
        adjusted = System.nanoTime();
        changed.accept(value);
        updateMessage();
    }

    public boolean editing() {
        return dragging;
    }

    public double fraction() {
        return value;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        startDrag();
        super.onClick(event, twice);
    }

    protected void startDrag() {
        dragging = true;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        dragging = false;
        committed.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && exactEditor != null && event.isConfirmation()) {
            exactEditor.run();
            return true;
        }
        if (steps > 0 && (event.isLeft() || event.isRight())) {
            value = Math.clamp(value + (event.isLeft() ? -1 : 1) / (double) steps, 0, 1);
            applyValue();
            committed.run();
            return true;
        }
        boolean handled = super.keyPressed(event);
        if (handled) committed.run();
        return handled;
    }
}
