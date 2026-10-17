package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.SpectrumRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class SpectrumHud {
    private static final SpectrumRenderer RENDERER = new SpectrumRenderer();

    private SpectrumHud() {
    }

    public static HudLayout.Bounds bounds(Settings settings, int width, int height) {
        int w = Math.max(8, Math.min(settings.visualizerWidth, width - 16));
        int h = Math.max(4, Math.min(settings.visualizerHeight, height - 16));
        return new HudLayout.Bounds(8 + (int) Math.round(Math.max(0, width - 16 - w) * settings.visualizerX),
            8 + (int) Math.round(Math.max(0, height - 16 - h) * settings.visualizerY), w, h, w, 1);
    }

    public static void register(DarkMusicClient app) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", "spectrum"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (app.settings != null && app.settings.hudVisualizer && app.playback != null && mc.level != null && !mc.gui.hud.isHidden() && mc.gui.screen() == null)
                render(app, g);
        });
    }

    public static void render(DarkMusicClient app, GuiGraphicsExtractor g) {
        var b = bounds(app.settings, g.guiWidth(), g.guiHeight());
        RENDERER.render(app, g, b.x(), b.y(), b.width(), b.height());
    }
}
