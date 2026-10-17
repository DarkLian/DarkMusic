package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing only: the audio worker owns analysis, timing, and smoothing.
 */
public final class SpectrumRenderer {
    private final float[] levels = new float[64];

    private long previewTime;

    public static int barHeight(float magnitude, int height) {
        return Float.isFinite(magnitude) ? Math.round(Math.clamp(magnitude, 0, 1) * height) : 0;
    }

    public void render(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, 0xBB111420);
        bars(app, g, x + 2, y + 2, width - 4, height - 4, false, false);
    }

    /**
     * No background and no audio mutations. Synthetic data is limited to the settings preview.
     */
    public void bars(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, boolean downward, boolean settingsPreview) {
        if (width < 8 || height < 1 || app.playback.spectrum() == null) return;
        var status = app.playback.state().status();
        boolean actual = status == AudioBackend.Status.PLAYING || status == AudioBackend.Status.PAUSED;
        if (actual) app.playback.spectrum().copyTo(levels);
        else if (!settingsPreview) return;
        int bands = app.settings.visualizerBars;
        if (!actual) {
            long now = System.nanoTime();
            double dt = previewTime == 0 ? .016 : Math.min(.1, (now - previewTime) / 1e9);
            previewTime = now;
            double factor = 1 - Math.exp(-dt / (.025 + app.settings.visualizerSmoothing * .65));
            for (int i = 0; i < bands; i++) {
                double target = Math.clamp((.45 + .3 * Math.sin(now / 5e8 + i * .7)) * app.settings.visualizerSensitivity, 0, 1);
                levels[i] += (float) ((target - levels[i]) * factor);
            }
        } else previewTime = 0;
        int bars = Math.min(bands, width / 2);
        for (int i = 0; i < bars; i++) {
            float value = 0;
            for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++) value = Math.max(value, levels[j]);
            int h = barHeight(value, height);
            if (h == 0) continue;
            int left = x + i * width / bars, right = x + (i + 1) * width / bars - 1;
            int top = downward ? y : y + height - h;
            g.fill(left, top, Math.max(left + 1, right), top + h, 0xFFB5B0FF);
        }
    }
}
