package com.darkmusic.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Focusable help text, deliberately not an action button.
 */
public final class InfoHint extends AbstractWidget {
    private final boolean marker;

    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    public InfoHint(int x, int y, Component help) {
        this(x, y, 20, help, true);
    }

    /**
     * Hover and keyboard help over an already-rendered section label.
     */
    public InfoHint(int x, int y, int width, Component help) {
        this(x, y, width, help, false);
    }

    private InfoHint(int x, int y, int width, Component help, boolean marker) {
        super(x, y, width, 20, help);
        this.marker = marker;
        setTooltip(Tooltip.create(help));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (marker) g.centeredText(Minecraft.getInstance().font, "?", getX() + 10, getY() + 6, UiTheme.ACCENT);
        if (isFocused()) g.outline(getX(), getY(), width, height, UiTheme.ACCENT);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
