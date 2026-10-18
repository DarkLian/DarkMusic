package com.darkmusic.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Retains Button's click, sound, keyboard, focus, tooltip and narration behavior.
 */
public final class UiButton extends Button {
    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
        automaticTooltip = false;
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    public enum Style {SECONDARY, PRIMARY, SUBTLE, DESTRUCTIVE}

    private Style style = Style.SECONDARY;
    private BooleanSupplier selected = () -> false;
    private IntSupplier first, last;
    private UiIcon icon;
    private boolean iconOnly;
    private boolean automaticTooltip;
    private long transitionAt = System.nanoTime();
    private float emphasis;

    public static Button icon(Button button, UiIcon icon, boolean iconOnly) {
        if (button instanceof UiButton themed) {
            themed.icon = icon;
            themed.iconOnly = iconOnly;
            if (iconOnly && !Help.hasInfo(themed)) themed.actionTooltip();
        }
        return button;
    }

    @Override
    public void setMessage(Component message) {
        if (message.equals(getMessage())) return;
        super.setMessage(message);
        if (automaticTooltip) actionTooltip();
    }

    private void actionTooltip() {
        setTooltip(Tooltip.create(getMessage()));
        automaticTooltip = true;
    }

    private UiButton(int x, int y, int width, int height, Component label, OnPress press, CreateNarration narration) {
        super(x, y, width, height, label, press, narration);
        icon = UiIcon.forLabel(label.getString());
    }

    public static Button style(Button button, Style style) {
        if (button instanceof UiButton themed) themed.style = style;
        return button;
    }

    public static Button selected(Button button, BooleanSupplier selected) {
        if (button instanceof UiButton themed) themed.selected = selected;
        return button;
    }

    public void swatch(IntSupplier first, IntSupplier last) {
        this.first = first;
        this.last = last;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int x, int y, float delta) {
        boolean chosen = selected.getAsBoolean(), highlighted = active && isHoveredOrFocused();
        int fill = !active ? UiTheme.SURFACE : chosen || style == Style.PRIMARY ? UiTheme.SELECTED
            : style == Style.DESTRUCTIVE ? highlighted ? 0xFF483040 : 0xFF30232F
            : highlighted ? UiTheme.HOVER : style == Style.SUBTLE ? UiTheme.SURFACE : UiTheme.RAISED;
        long now = System.nanoTime();
        float step = Math.min(1, (now - transitionAt) / 120_000_000f);
        transitionAt = now;
        emphasis += Math.clamp((highlighted ? 1 : 0) - emphasis, -step, step);
        if (active && style == Style.SECONDARY && !chosen) fill = UiTheme.mix(UiTheme.RAISED, UiTheme.HOVER, emphasis);
        g.fill(getX(), getY(), getRight(), getBottom(), fill);
        int border = !active ? UiTheme.RAISED : isFocused() ? UiTheme.ACCENT
            : style == Style.DESTRUCTIVE ? UiTheme.ERROR : highlighted || chosen ? UiTheme.ACCENT : UiTheme.BORDER;
        g.outline(getX(), getY(), width, height, border);
        if (style == Style.PRIMARY || chosen)
            g.fill(getX() + 1, getY() + 1, getX() + 3, getBottom() - 1, active ? UiTheme.ACCENT : UiTheme.BORDER);
        if (first != null)
            UiTheme.gradient(g, getX() + 5, getBottom() - (height > 20 ? 6 : 3), width - 10, height > 20 ? 3 : 2, first.getAsInt() | 0xFF000000, last.getAsInt() | 0xFF000000);
        // Native scrolling labels preserve full translated labels, including narrow controls.
        if (icon != null)
            icon.draw(g, iconOnly ? getX() + (width - 12) / 2 : getX() + 6, getY() + (height - 12) / 2, 12, active ? UiTheme.ACCENT : UiTheme.MUTED);
        if (!iconOnly) {
            if (icon == null)
                extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
            else {
                g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE).acceptScrollingWithDefaultCenter(
                    getMessage(), getX() + 23, getRight() - 6, getY(), getBottom());
            }
        }
        if (!active) g.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, 0x55111420);
    }

    public static Builder builder(Component message, OnPress press) {
        return new Builder(message, press);
    }

    public static final class Builder extends Button.Builder {
        private final Component label;
        private final OnPress press;
        private int x, y, width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT;
        private Tooltip tooltip;
        private CreateNarration narration = DEFAULT_NARRATION;

        private Builder(Component label, OnPress press) {
            super(label, press);
            this.label = label;
            this.press = press;
        }

        @Override
        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        @Override
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        @Override
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        @Override
        public Builder bounds(int x, int y, int width, int height) {
            return pos(x, y).size(width, height);
        }

        @Override
        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        @Override
        public Builder createNarration(CreateNarration narration) {
            this.narration = narration;
            return this;
        }

        @Override
        public UiButton build() {
            var button = new UiButton(x, y, width, height, label, press, narration);
            button.setTooltip(tooltip);
            return button;
        }
    }
}
