package com.darkmusic.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * Gameplay HUD elements draw in the HUD layer; with the menu option they also draw above an open screen.
 */
public final class HudLayer {
    private HudLayer() {
    }

    public static void add(HudSources sources, String id, Consumer<GuiGraphicsExtractor> draw) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", id), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.gui.screen() == null && !mc.gui.hud.isHidden()) draw.accept(g);
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> ScreenEvents.afterExtract(screen).register((s, g, x, y, delta) -> {
            if (inMenus(sources, s)) draw.accept(g);
        }));
    }

    /**
     * Only above Minecraft's own menus: DarkMusic screens show playback themselves, and its Settings preview the HUD.
     */
    static boolean inMenus(HudSources sources, Screen screen) {
        var mc = Minecraft.getInstance();
        return sources.settings() != null && sources.settings().hudInMenus && mc.level != null && !mc.gui.hud.isHidden()
            && !screen.getClass().getName().startsWith("com.darkmusic.");
    }
}
