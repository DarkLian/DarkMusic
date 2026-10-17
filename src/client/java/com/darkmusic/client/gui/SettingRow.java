package com.darkmusic.client.gui;

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
    private final Runnable action;
    private final BooleanSupplier checked;

    public SettingRow(int x, int y, int width, String label, BooleanSupplier checked, Runnable action) {
        super(x, y, width, 20, Component.literal(label));
        this.action = action;
        this.checked = checked;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isFocused() && active && (event.key() == 32 || event.isConfirmation())) {
            action.run();
            return true;
        }
        return false;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? 0xFF393650 : 0xFF252A3B);
        if (isFocused()) g.outline(getX(), getY(), width, height, 0xFFB5B0FF);
        var font = Minecraft.getInstance().font;
        g.text(font, font.plainSubstrByWidth(getMessage().getString(), width - 36), getX() + 7, getY() + 6, 0xFFEEEEFF);
        int right = getRight() - 19, top = getY() + 4;
        if (checked != null) {
            g.outline(right, top, 12, 12, 0xFFB5B0FF);
            if (checked.getAsBoolean()) {
                for (int i = 0; i < 3; i++) g.fill(right + 2 + i, top + 5 + i, right + 4 + i, top + 7 + i, 0xFFDDD9FF);
                for (int i = 0; i < 5; i++) g.fill(right + 5 + i, top + 7 - i, right + 7 + i, top + 9 - i, 0xFFDDD9FF);
            }
        } else g.text(font, ">", right + 3, getY() + 6, 0xFFB5B0FF);
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(Component.literal(getMessage().getString() + (checked == null ? "" : checked.getAsBoolean() ? ": On" : ": Off")));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
