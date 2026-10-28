package com.darkmusic.client.gui;

import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * One bounded drag model for list gutters and scrollable forms; never a row action.
 */
public final class ScrollBar extends AbstractWidget {
    @Override
    public void setTooltip(Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private final IntSupplier offset;
    private final IntConsumer change;
    private int content, viewport;
    private boolean dragging;
    private double grab;

    public ScrollBar(IntSupplier offset, IntConsumer change) {
        super(0, 0, 8, 1, Text.component("ui.darkmusic.scroll"));
        this.offset = offset;
        this.change = change;
        visible = false;
    }

    public void configure(int x, int y, int height, int content, int viewport) {
        if (x != getX() || y != getY() || height != getHeight() || content != this.content || viewport != this.viewport)
            cancel();
        setX(x);
        setY(y);
        setHeight(Math.max(1, height));
        this.content = Math.max(0, content);
        this.viewport = Math.max(0, viewport);
        visible = active = content > viewport && viewport > 0 && height > 0;
    }

    public static int thumbLength(int height, int content, int viewport) {
        return Math.clamp(content <= 0 ? height : (int) ((long) height * viewport / content), Math.min(12, height), height);
    }

    private int maximum() {
        return Math.max(0, content - viewport);
    }

    private int length() {
        return thumbLength(height, content, viewport);
    }

    private int thumb() {
        return getY() + (int) Math.round((height - length()) * (double) offset.getAsInt() / Math.max(1, maximum()));
    }

    public boolean dragging() {
        return dragging;
    }

    public void cancel() {
        dragging = false;
    }

    public boolean hit(double x, double y) {
        return visible && isMouseOver(x, y);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        int thumb = thumb();
        if (event.y() >= thumb && event.y() < thumb + length()) {
            dragging = true;
            grab = event.y() - thumb;
        } else change.accept(Math.clamp(offset.getAsInt() + (event.y() < thumb ? -viewport : viewport), 0, maximum()));
    }

    public void drag(double y) {
        if (dragging && height > length())
            change.accept(Math.clamp((int) Math.round((y - getY() - grab) * maximum() / (height - length())), 0, maximum()));
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        drag(event.y());
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        cancel();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) cancel();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!hit(x, y) || HorizontalText.horizontalIntent(dx, dy)) return false;
        change.accept(Math.clamp(offset.getAsInt() - (int) Math.round(dy * 24), 0, maximum()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isFocused()) return false;
        int next = switch (event.key()) {
            case 265 -> offset.getAsInt() - 24;
            case 264 -> offset.getAsInt() + 24;
            case 266 -> offset.getAsInt() - viewport;
            case 267 -> offset.getAsInt() + viewport;
            case 268 -> 0;
            case 269 -> maximum();
            default -> -1;
        };
        if (next == -1 && event.key() != 265 && event.key() != 266) return false;
        change.accept(Math.clamp(next, 0, maximum()));
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(getX() + 3, getY(), getRight() - 1, getBottom(), UiTheme.BACKGROUND);
        g.fill(getX() + 3, thumb(), getRight() - 1, thumb() + length(), dragging || isHoveredOrFocused() ? UiTheme.ACCENT : UiTheme.MUTED);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
