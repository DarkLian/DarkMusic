package com.darkmusic.client.gui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class Help {
    private Help() {
    }

    public static <T extends AbstractWidget> T tip(T widget, String explanation) {
        widget.setTooltip(Tooltip.create(Component.literal(explanation)));
        return widget;
    }
}
