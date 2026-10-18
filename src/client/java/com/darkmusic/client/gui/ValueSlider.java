package com.darkmusic.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class ValueSlider extends AbstractSliderButton {
    private final DoubleConsumer changed;
    private final Runnable committed;
    private final DoubleFunction<String> label;
    private final int steps;
    private long adjusted;
    private boolean dragging;
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
        super.extractWidgetRenderState(g, mx, my, delta);
        if (colors != null) {
            int width = Math.max(1, getWidth() - 4);
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
