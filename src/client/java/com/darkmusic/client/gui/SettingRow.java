package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * A full-width keyboard-accessible setting, with state distinct from its label.
 */
public final class SettingRow extends AbstractWidget {
    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        Help.extract(this, g, x, y);
    }

    private final HorizontalText horizontal = new HorizontalText();
    private final Runnable action;
    private final BooleanSupplier checked;
    private final UiIcon icon;
    private String cachedMessage = "", optionLabel = "", optionValue = "";

    public SettingRow(int x, int y, int width, String label, BooleanSupplier checked, Runnable action) {
        super(x, y, width, 20, Component.literal(label));
        this.action = action;
        this.checked = checked;
        icon = UiIcon.forLabel(label);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isFocused() && (event.key() == 262 || event.key() == 263))
            return horizontal.pan("label", event.key() == 262 ? 24 : -24) | horizontal.pan("value", event.key() == 262 ? 24 : -24);
        if (isFocused() && active && (event.key() == 32 || event.isConfirmation())) {
            action.run();
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(getX(), getY(), getRight(), getBottom(), active && isHoveredOrFocused() ? UiTheme.HOVER : active ? UiTheme.RAISED : UiTheme.SURFACE);
        if (isFocused()) g.outline(getX(), getY(), width, height, active ? UiTheme.ACCENT : UiTheme.MUTED);
        var font = Minecraft.getInstance().font;
        horizontal.begin();
        if (icon != null) icon.draw(g, getX() + 6, getY() + 4, 12, active ? UiTheme.ACCENT : UiTheme.MUTED);
        String message = getMessage().getString();
        if (!cachedMessage.equals(message)) {
            cachedMessage = message;
            int separator = message.indexOf(':');
            if (separator < 0) separator = message.indexOf('\uFF1A');
            boolean choice = checked == null && separator > 0;
            optionLabel = choice ? message.substring(0, separator + 1) : message;
            optionValue = choice ? message.substring(separator + 1).strip() : "";
        }
        int left = getX() + (icon == null ? 7 : 24);
        if (!optionValue.isEmpty()) {
            int valueWidth = Math.min(font.width(optionValue), Math.max(1, width / 2 - 10));
            int valueX = getRight() - 8 - valueWidth;
            horizontal.draw(g, font, "label", optionLabel, left, getY() + 6, Math.max(1, valueX - left - 12), active ? UiTheme.TEXT : UiTheme.MUTED);
            horizontal.draw(g, font, "value", optionValue, valueX, getY() + 6, valueWidth, active ? UiTheme.ACCENT : UiTheme.MUTED);
        } else
            horizontal.draw(g, font, "label", message, left, getY() + 6, width - (icon == null ? 36 : 53), active ? UiTheme.TEXT : UiTheme.MUTED);
        int right = getRight() - 19, top = getY() + 4;
        if (checked != null) {
            g.outline(right, top, 12, 12, active ? UiTheme.ACCENT : UiTheme.MUTED);
            if (checked.getAsBoolean())
                UiIcon.CHECK.draw(g, right + 1, top + 1, 10, active ? UiTheme.TEXT : UiTheme.MUTED);
        } else if (optionValue.isEmpty())
            g.text(font, ">", right + 3, getY() + 6, active ? UiTheme.ACCENT : UiTheme.MUTED);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        return isMouseOver(x, y) && horizontal.scroll(x, y, dx, dy, HorizontalText.shiftDown());
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(checked == null ? getMessage() : Text.component(checked.getAsBoolean() ? "format.darkmusic.setting_on" : "format.darkmusic.setting_off", getMessage()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
