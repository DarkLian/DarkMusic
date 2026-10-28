package com.darkmusic.client.gui;

import com.darkmusic.client.render.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * The player's speed and volume sliders: a thin track with a marker, and the value as text beside it. Narrower than
 * 72 pixels, the value sits above the track instead.
 */
final class MediaSlider extends ValueSlider {
    private static final int COMPACT = 72;
    private final DoubleFunction<String> valueLabel;
    private final boolean labelLeft;
    private String text = "";

    MediaSlider(int x, int y, int width, double value, DoubleFunction<String> label, DoubleConsumer changed,
                Runnable committed, int steps, DoubleFunction<String> valueLabel, boolean labelLeft) {
        super(x, y, width, value, label, changed, committed, steps);
        this.valueLabel = valueLabel;
        this.labelLeft = labelLeft;
        updateMessage();
    }

    private boolean compact() {
        return getWidth() < COMPACT;
    }

    /**
     * Widest value text, so the track keeps its length as the value changes.
     */
    private int labelWidth() {
        return Minecraft.getInstance().font.width(labelLeft ? "3.0×" : "100%");
    }

    private int trackStart() {
        return getX() + (labelLeft && !compact() ? labelWidth() + 10 : 4);
    }

    private int trackWidth() {
        return Math.max(1, getWidth() - (compact() ? 8 : labelWidth() + 14));
    }

    private void moveTo(double mouseX) {
        value = Math.clamp((mouseX - trackStart()) / trackWidth(), 0, 1);
        applyValue();
    }

    @Override
    protected void updateMessage() {
        super.updateMessage();
        if (valueLabel != null) text = valueLabel.apply(value);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        var font = Minecraft.getInstance().font;
        boolean compact = compact();
        int start = trackStart(), length = trackWidth();
        int centerY = getY() + (compact ? 16 : 10);
        int marker = start + (int) Math.round(value * length);
        int color = active ? UiTheme.ACCENT : UiTheme.MUTED;
        g.fill(start, centerY - 1, start + length, centerY + 1, UiTheme.BORDER);
        g.fill(start, centerY - 1, marker, centerY + 1, color);
        int halfHeight = compact ? 4 : 6;
        g.fill(marker - 2, centerY - halfHeight, marker + 2, centerY + halfHeight, active && isHoveredOrFocused() ? UiTheme.TEXT : color);
        int textX = compact ? getX() + (getWidth() - font.width(text)) / 2 : labelLeft ? getX() : getRight() - font.width(text);
        g.text(font, text, textX, getY() + (compact ? 1 : 6), active ? UiTheme.SECONDARY : UiTheme.MUTED);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        // Clicks on the value text do nothing; only the track moves the value.
        if (!compact() && (event.x() < trackStart() - 3 || event.x() > trackStart() + trackWidth() + 3)) return;
        startDrag();
        moveTo(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (editing()) moveTo(event.x());
    }
}
