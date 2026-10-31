package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.gui.MusicScreen;
import com.darkmusic.client.gui.SettingsScreen;
import com.darkmusic.client.hud.HudSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Show HUD in Menus inside a disposable world copy: the HUD draws above Minecraft's menus only when the option is on,
 * never above DarkMusic's own screens (player, Settings preview), and never while F1 hides the HUD. Each screen stays open for a
 * few frames so the menu drawing path runs, not only the decision.
 */
final class HudMenusSmoke {
    private static final int SETTLE = 10;
    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private int stage, waited;

    boolean tick(Minecraft mc, DarkMusicClient app) throws Exception {
        if (System.nanoTime() > deadline) throw new IllegalStateException("HUD in menus smoke timed out at " + stage);
        if (mc.level == null || mc.player == null) return false;
        var status = app.services().playback().state().status();
        if (status == AudioBackend.Status.ERROR)
            throw new IllegalStateException("Playback failed: " + app.services().playback().state().error());
        if (stage == 0 && status != AudioBackend.Status.PLAYING) return false;
        if (++waited < (stage == 0 ? 100 : SETTLE)) return false;
        waited = 0;
        var settings = app.services().settings();
        switch (stage) {
            case 0 -> {
                settings.hudEnabled = true;
                settings.hudInMenus = false;
                if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
                mc.gui.setScreen(new PauseScreen(true));
            }
            case 1 -> {
                require(mc, app, false, "HUD drew over the pause menu with the option off");
                settings.hudInMenus = true;
            }
            case 2 -> {
                require(mc, app, true, "HUD missing over the pause menu with the option on");
                // The player opens only from gameplay, not over the pause menu.
                mc.gui.setScreen(null);
                app.open();
                if (!(mc.gui.screen() instanceof MusicScreen))
                    throw new IllegalStateException("DarkMusic player did not open");
            }
            case 3 -> {
                require(mc, app, false, "HUD drew over the DarkMusic player");
                mc.gui.setScreen(new SettingsScreen(app.sources(), mc.gui.screen()));
            }
            case 4 -> {
                require(mc, app, false, "HUD drew over DarkMusic Settings");
                mc.gui.setScreen(new PauseScreen(true));
                mc.gui.hud.toggle();
            }
            case 5 -> {
                require(mc, app, false, "HUD drew over a menu while F1 hid it");
                mc.gui.hud.toggle();
                settings.hudInMenus = false;
                mc.gui.setScreen(null);
                System.out.println("DARKMUSIC_HUD_MENUS_PASS");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected stage " + stage);
        }
        stage++;
        return false;
    }

    private static void require(Minecraft mc, DarkMusicClient app, boolean expected, String message) throws Exception {
        Screen screen = mc.gui.screen();
        if (screen == null) throw new IllegalStateException(message + ": no screen open");
        var inMenus = Class.forName("com.darkmusic.client.hud.HudLayer").getDeclaredMethod("inMenus", HudSources.class, Screen.class);
        inMenus.setAccessible(true);
        if ((boolean) inMenus.invoke(null, app.services().hud(), screen) != expected)
            throw new IllegalStateException(message + " (" + screen.getClass().getSimpleName() + ")");
    }
}
