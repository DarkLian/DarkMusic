package com.darkmusic.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class ValueSlider extends AbstractSliderButton {
    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private final DoubleConsumer changed;
    private final Runnable committed;
    private final DoubleFunction<String> label;
    private final int steps;
    private long adjusted;
    private boolean dragging;
    private boolean trackOnly, plain, simpleProgress;
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

    public void plain(boolean value) {
        plain = value;
    }

    public void simpleProgress(boolean value) {
        simpleProgress = value;
    }

    public void trackOnly(boolean value) {
        trackOnly = value;
    }

    private java.util.function.Supplier<com.darkmusic.client.config.VisualColors> colors;
    private java.util.function.Supplier<com.darkmusic.client.config.Settings.SurfaceFinish> finish = () -> com.darkmusic.client.config.Settings.SurfaceFinish.FLAT;

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

    public void colors(java.util.function.Supplier<com.darkmusic.client.config.VisualColors> value) {
        colors = value;
    }

    public void finish(java.util.function.Supplier<com.darkmusic.client.config.Settings.SurfaceFinish> value) {
        finish = value;
    }

    @Override
    public void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (!trackOnly)
            g.fill(getX(), getY(), getRight(), getBottom(), active ? isHoveredOrFocused() ? UiTheme.HOVER : UiTheme.RAISED : UiTheme.SURFACE);
        if (!trackOnly || isFocused())
            g.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? UiTheme.ACCENT : UiTheme.BORDER);
        if (simpleProgress) {
            g.fill(getX(), getY(), getRight(), getBottom(), UiTheme.BACKGROUND);
            ColorStrip.draw(g, colors.get(), getX(), getY() + 8, getWidth(), 4,
                (int) Math.round(getWidth() * value), com.darkmusic.client.config.Settings.SurfaceFinish.FLAT);
            return;
        }
        if (plain) {
            int marker = getX() + 2 + (int) Math.round(value * (getWidth() - 6));
            g.fill(marker, getY() + 3, marker + 2, getBottom() - 3, active ? UiTheme.ACCENT : UiTheme.MUTED);
            extractScrollingStringOverContents(g.textRendererForWidget(this, net.minecraft.client.gui.GuiGraphicsExtractor.HoveredTextEffects.NONE), getMessage(), 2);
            return;
        }
        int handle = getX() + (int) Math.round(value * (getWidth() - 8));
        if (colors == null) {
            g.fill(getX() + 4, getBottom() - 4, getRight() - 4, getBottom() - 2, UiTheme.BORDER);
            g.fill(getX() + 4, getBottom() - 4, Math.max(getX() + 4, handle + 4), getBottom() - 2, active ? UiTheme.ACCENT : UiTheme.MUTED);
        }
        g.fill(handle + 2, trackOnly ? getBottom() - 7 : getY() + 2, handle + 6, getBottom() - 2, active ? UiTheme.ACCENT : UiTheme.BORDER);
        if (!trackOnly)
            extractScrollingStringOverContents(g.textRendererForWidget(this, net.minecraft.client.gui.GuiGraphicsExtractor.HoveredTextEffects.NONE), getMessage(), 2);
        handleCursor(g);
        if (!active) g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0x55111420);
        if (colors != null) {
            int width = Math.max(1, getWidth() - 4);
            if (trackOnly) g.fill(getX() + 2, getBottom() - 4, getRight() - 2, getBottom() - 2, UiTheme.BORDER);
            if (finish.get() == com.darkmusic.client.config.Settings.SurfaceFinish.BEVELED)
                ColorStrip.track(g, getX() + 2, getBottom() - 4, width, 2, finish.get());
            ColorStrip.draw(g, colors.get(), getX() + 2, getBottom() - 4, width, 2, (int) Math.round(width * value), finish.get());
        }
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
        dragging = true;
        super.onClick(event, twice);
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
