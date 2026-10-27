package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Speed and volume notices occupy separate rows when shortcuts are used together.
 */
public final class SpeedFeedback {
    private final FeedbackTimer timer = new FeedbackTimer();
    private double speed = 1;

    public static void register(DarkMusicClient app) {
        HudLayer.add(app, "speed_feedback", g -> app.speedFeedback.render(g));
    }

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

    public void render(GuiGraphicsExtractor g) {
        double opacity = opacity(System.nanoTime());
        int alpha = (int) Math.round(opacity * 255);
        if (alpha < 4) return;
        var font = Minecraft.getInstance().font;
        String label = Text.get("format.darkmusic.speed", String.format(java.util.Locale.ROOT, "%.1f", speed));
        int width = Math.min(g.guiWidth() - 16, font.width(label) + 24), x = (g.guiWidth() - width) / 2, y = Math.max(8, g.guiHeight() - 94);
        g.fill(x, y, x + width, y + 22, HudText.themed(UiTheme.BACKGROUND, (int) (opacity * 210)));
        g.text(font, HorizontalText.ellipsize(font, label, width - 12), x + 12, y + 7, HudText.themed(UiTheme.TEXT, alpha));
    }
}
