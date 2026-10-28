package com.darkmusic.client.gui;

import com.darkmusic.client.render.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The native editor retains caret, selection, clipboard, scrolling, and IME handling.
 */
public final class UiInput extends EditBox {
    @Override
    public void setTooltip(Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private boolean invalid;
    private int selectionEnd;

    @Override
    public void setHighlightPos(int position) {
        super.setHighlightPos(position);
        selectionEnd = Math.clamp(position, 0, getValue().length());
    }

    int selectionEnd() {
        return selectionEnd;
    }

    public UiInput(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        // Minecraft owns the native editor's dark input surface, including caret and selection.
        setTextColor(0xFFF0EFF9);
        setTextColorUneditable(0xFF868DA7);
    }

    public void invalid(boolean value) {
        invalid = value;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (!isVisible()) return;
        super.extractWidgetRenderState(g, x, y, delta);
        g.outline(getX(), getY(), getWidth(), getHeight(), invalid ? UiTheme.ERROR : isFocused() ? UiTheme.ACCENT : UiTheme.BORDER);
    }
}
