package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shortcut feedback is independent of the optional now-playing HUD.
 */
public final class VolumeFeedback {
    private final FeedbackTimer timer = new FeedbackTimer();
    private double volume;

    public static void register(DarkMusicClient app) {
        HudLayer.add(app, "volume_feedback", g -> app.volumeFeedback.render(g));
    }

    public void show(double value) {
        show(value, System.nanoTime());
    }

    public void clear() {
        timer.clear();
    }

    public void show(double value, long now) {
        volume = Settings.clampVolume(value);
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
        g.fill(x, y, x + width, y + 24, HudText.themed(UiTheme.BACKGROUND, (int) (opacity * 210)));
        g.text(font, HorizontalText.ellipsize(font, label, width - 12), x + 12, y + 4, HudText.themed(UiTheme.TEXT, alpha));
        g.fill(x + 8, y + 17, x + width - 8, y + 20, HudText.themed(UiTheme.BORDER, alpha));
        g.fill(x + 8, y + 17, x + 8 + (int) ((width - 16) * volume), y + 20, HudText.themed(UiTheme.ACCENT, alpha));
    }
}
