package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Shortcut feedback is independent of the optional now-playing HUD.
 */
public final class VolumeFeedback {
    private final FeedbackTimer timer = new FeedbackTimer();
    private double volume;

    public static void register(DarkMusicClient app) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", "volume_feedback"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.gui.screen() == null && !mc.gui.hud.isHidden()) app.volumeFeedback.render(g);
        });
    }

    public void show(double value) {
        show(value, System.nanoTime());
    }

    public void clear() {
        timer.clear();
    }

    public void show(double value, long now) {
        volume = com.darkmusic.client.config.Settings.clampVolume(value);
        timer.show(now);
    }

    public double opacity(long now) {
        return timer.opacity(now);
    }

    public void render(GuiGraphicsExtractor g) {
        double opacity = opacity(System.nanoTime());
        int alpha = (int) Math.round(opacity * 255);
        if (alpha < 4) return;
        var font = Minecraft.getInstance().font;
        String label = volume == 0 ? Text.get("hud.darkmusic.muted") : Text.get("format.darkmusic.volume", Math.round(volume * 100));
        int width = Math.min(g.guiWidth() - 16, font.width(label) + 24), x = (g.guiWidth() - width) / 2, y = Math.max(8, g.guiHeight() - 66);
        g.fill(x, y, x + width, y + 24, ((int) (opacity * 210) << 24) | 0x111420);
        g.text(font, com.darkmusic.client.gui.HorizontalText.ellipsize(font, label, width - 12), x + 12, y + 4, (alpha << 24) | 0xE0DDEE);
        g.fill(x + 8, y + 17, x + width - 8, y + 20, (alpha << 24) | 0x34394F);
        g.fill(x + 8, y + 17, x + 8 + (int) ((width - 16) * volume), y + 20, (alpha << 24) | 0xB5B0FF);
    }
}
