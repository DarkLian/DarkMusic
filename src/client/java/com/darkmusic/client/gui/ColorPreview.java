package com.darkmusic.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the sample a colour screen edits against. Navigation names it, so it lives beside Navigation.
 */
@FunctionalInterface
public interface ColorPreview {
    void render(GuiGraphicsExtractor g, int x, int y, int width, int height);
}
