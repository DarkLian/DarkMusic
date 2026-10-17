package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Speed and volume notices occupy separate rows when shortcuts are used together.
 */
public final class SpeedFeedback {
    private final FeedbackTimer timer = new FeedbackTimer();
    private double speed = 1;

    public void clear() {
        timer.clear();
    }

    public void show(double value) {
        speed = value;
        timer.show(System.nanoTime());
    }

    public double opacity(long now) {
        return timer.opacity(now);
    }

    public static void register(DarkMusicClient app) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("darkmusic", "speed_feedback"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.level != null && mc.gui.screen() == null && !mc.gui.hud.isHidden()) app.speedFeedback.render(g);
        });
    }

    public void render(GuiGraphicsExtractor g) {
        double opacity = opacity(System.nanoTime());
        int alpha = (int) Math.round(opacity * 255);
        if (alpha < 4) return;
        var font = Minecraft.getInstance().font;
        String label = Text.get("format.darkmusic.speed", String.format(java.util.Locale.ROOT, "%.1f", speed));
        int width = Math.min(g.guiWidth() - 16, font.width(label) + 24), x = (g.guiWidth() - width) / 2, y = Math.max(8, g.guiHeight() - 94);
        g.fill(x, y, x + width, y + 22, ((int) (opacity * 210) << 24) | 0x111420);
        g.text(font, label, x + 12, y + 7, (alpha << 24) | 0xE0DDEE);
    }
}
