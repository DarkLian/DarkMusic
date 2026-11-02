package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.PipSettings;
import com.darkmusic.client.pip.PipPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Smoke-only physical-pixel bounds, independent of GUI-unit rounding.
 */
final class PipFidelityScreen extends Screen implements PipPreview {
    PipFidelityScreen() {
        super(Component.literal("PiP fidelity"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        var mc = Minecraft.getInstance();
        var pip = DarkMusicClient.INSTANCE.services().pip();
        pip.update();
        var frame = pip.frame();
        if (frame == null) return;
        int width = Integer.getInteger("darkmusic.pipExactWidth", frame.sourceWidth());
        int height = Integer.getInteger("darkmusic.pipExactHeight", frame.sourceHeight());
        if (width < 1 || height < 1 || width > mc.getWindow().getWidth() || height > mc.getWindow().getHeight())
            throw new IllegalStateException("Exact PiP bounds must fit the physical framebuffer");
        float scale = (float) mc.getWindow().getGuiScale();
        g.pose().pushMatrix();
        try {
            g.pose().scale(1 / scale, 1 / scale);
            pip.draw(g, new PipSettings.Bounds(0, 0, width, height), true);
        } finally {
            g.pose().popMatrix();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
