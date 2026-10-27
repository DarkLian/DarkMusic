package com.darkmusic.client.gui;

import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
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
    /**
     * Checkboxes, choice arrows and page chevrons share one 12-pixel column this far from the row's right edge.
     */
    static final int ICON_INSET = 19;
    private String cachedMessage = "", optionLabel = "", optionValue = "";
    private String description = "";
    private boolean accentedLabel;

    /**
     * NAVIGATE opens another page (chevron); ACTION and DESTRUCTIVE act on this page (button);
     * OPTION picks one entry from a list (check when chosen). AUTO keeps the older inference.
     */
    enum Kind {AUTO, NAVIGATE, ACTION, DESTRUCTIVE, OPTION}

    private Kind kind = Kind.AUTO;
    private boolean chosen;

    public SettingRow accentLabel() {
        accentedLabel = true;
        return this;
    }

    public SettingRow navigates() {
        kind = Kind.NAVIGATE;
        return this;
    }

    public SettingRow runs() {
        kind = Kind.ACTION;
        return this;
    }

    public SettingRow destructive() {
        kind = Kind.DESTRUCTIVE;
        return this;
    }

    public SettingRow option(boolean selected) {
        kind = Kind.OPTION;
        chosen = selected;
        return this;
    }

    public SettingRow description(String text) {
        description = text;
        return this;
    }

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
            return horizontal.pan("label", event.key() == 262 ? 24 : -24) | horizontal.pan("value", event.key() == 262 ? 24 : -24) | horizontal.pan("description", event.key() == 262 ? 24 : -24);
        if (isFocused() && active && (event.key() == 32 || event.isConfirmation())) {
            action.run();
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (kind == Kind.ACTION || kind == Kind.DESTRUCTIVE) {
            button(g);
            return;
        }
        boolean highlighted = kind == Kind.OPTION && chosen;
        g.fill(getX(), getY(), getRight(), getBottom(), active && isHoveredOrFocused() ? UiTheme.HOVER : highlighted ? UiTheme.SELECTED : active ? UiTheme.RAISED : UiTheme.SURFACE);
        if (highlighted) g.fill(getX() + 1, getY() + 2, getX() + 3, getBottom() - 2, UiTheme.ACCENT);
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
        int labelColor = accentedLabel ? UiTheme.ACCENT : active ? UiTheme.TEXT : UiTheme.MUTED;
        if (!description.isEmpty()) {
            int measured = optionValue.isEmpty() ? font.width(message) : font.width(optionLabel) + 4 + font.width(optionValue);
            int labelWidth = Math.min(measured, Math.max(1, width / 2 - 18));
            if (optionValue.isEmpty())
                horizontal.draw(g, font, "label", message, left, getY() + 6, labelWidth, active ? UiTheme.ACCENT : UiTheme.MUTED);
            else {
                int prefixWidth = Math.min(font.width(optionLabel), Math.max(1, labelWidth - 16));
                horizontal.draw(g, font, "label", optionLabel, left, getY() + 6, prefixWidth, labelColor);
                int valueX = left + prefixWidth + 4;
                horizontal.draw(g, font, "value", optionValue, valueX, getY() + 6, Math.max(1, labelWidth - prefixWidth - 4), active ? UiTheme.ACCENT : UiTheme.MUTED);
            }
            int detailX = left + labelWidth + 12;
            horizontal.draw(g, font, "description", description, detailX, getY() + 6, Math.max(1, getRight() - 27 - detailX), active ? UiTheme.SECONDARY : UiTheme.MUTED);
        } else if (!optionValue.isEmpty()) {
            // A cycling value carries a small up/down hint; a page link keeps room for its chevron. Both sit in the
            // same right-hand column as the checkboxes, with the value ending just before it.
            boolean cycles = kind == Kind.AUTO;
            int reserved = cycles || kind == Kind.NAVIGATE ? ICON_INSET + 4 : 8;
            int valueWidth = Math.min(font.width(optionValue), Math.max(1, width / 2 - 10));
            int valueX = getRight() - reserved - valueWidth;
            horizontal.draw(g, font, "label", optionLabel, left, getY() + 6, Math.max(1, valueX - left - 12), labelColor);
            horizontal.draw(g, font, "value", optionValue, valueX, getY() + 6, valueWidth, active ? UiTheme.ACCENT : UiTheme.MUTED);
            if (cycles) {
                int hint = active ? UiTheme.SECONDARY : UiTheme.MUTED;
                UiIcon.chevron(g, getRight() - ICON_INSET + 3, getY() + 3, 7, hint, 1);
                UiIcon.chevron(g, getRight() - ICON_INSET + 3, getY() + 10, 7, hint, 3);
            }
        } else
            horizontal.draw(g, font, "label", message, left, getY() + 6, width - (icon == null ? 36 : 53), highlighted ? UiTheme.ACCENT : labelColor);
        int right = getRight() - ICON_INSET, top = getY() + 4;
        if (checked != null) UiTheme.checkbox(g, right, top, 12, checked.getAsBoolean(), active);
        else if (kind == Kind.OPTION) {
            if (chosen) UiIcon.CHECK.draw(g, right, top, 12, active ? UiTheme.ACCENT : UiTheme.MUTED);
        } else if (kind == Kind.NAVIGATE || optionValue.isEmpty())
            UiIcon.chevron(g, right + 1, top + 1, 10, active ? UiTheme.ACCENT : UiTheme.MUTED, 2);
    }

    /**
     * Rows that act on the current page look like buttons, not like links to another page.
     */
    private void button(GuiGraphicsExtractor g) {
        boolean danger = kind == Kind.DESTRUCTIVE, highlighted = active && isHoveredOrFocused();
        int fill = !active ? UiTheme.SURFACE : danger ? UiTheme.destructiveFill(highlighted) : highlighted ? UiTheme.HOVER : UiTheme.RAISED;
        g.fill(getX(), getY(), getRight(), getBottom(), fill);
        int border = !active ? UiTheme.RAISED : isFocused() ? UiTheme.ACCENT : danger ? UiTheme.ERROR : highlighted ? UiTheme.ACCENT : UiTheme.BORDER;
        g.outline(getX(), getY(), width, height, border);
        var font = Minecraft.getInstance().font;
        horizontal.begin();
        String message = getMessage().getString();
        int iconWidth = icon == null ? 0 : 16;
        int textWidth = Math.min(font.width(message), Math.max(1, width - 16 - iconWidth));
        int start = getX() + (width - textWidth - iconWidth) / 2;
        int color = !active ? UiTheme.MUTED : danger ? UiTheme.ERROR : UiTheme.TEXT;
        if (icon != null)
            icon.draw(g, start, getY() + 4, 12, active ? danger ? UiTheme.ERROR : UiTheme.ACCENT : UiTheme.MUTED);
        horizontal.draw(g, font, "label", message, start + iconWidth, getY() + 6, textWidth, color);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        return isMouseOver(x, y) && horizontal.scroll(x, y, dx, dy, HorizontalText.shiftDown());
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        var label = kind == Kind.OPTION && chosen ? Text.component("ui.darkmusic.option_selected", getMessage()) : getMessage();
        return wrapDefaultNarrationMessage(checked == null ? description.isEmpty() ? label : label.copy().append(". " + description) : Text.component(checked.getAsBoolean() ? "format.darkmusic.setting_on" : "format.darkmusic.setting_off", getMessage()));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
