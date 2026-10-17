package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing only: the audio worker owns analysis, timing, and smoothing.
 */
public final class SpectrumRenderer {
    private final float[] levels = new float[64];

    public void render(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height) {
        if (width < 8 || height < 4 || app.playback.spectrum() == null) return;
        g.fill(x, y, x + width, y + height, 0xBB111420);
        var status = app.playback.state().status();
        if (status != AudioBackend.Status.PLAYING && status != AudioBackend.Status.PAUSED) return;
        app.playback.spectrum().copyTo(levels);
        int bands = app.settings.visualizerBars;
        int bars = Math.min(bands, (width - 4) / 2);
        for (int i = 0; i < bars; i++) {
            float value = 0;
            for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++) value = Math.max(value, levels[j]);
            int h = Math.round(value * (height - 4));
            if (h == 0) continue;
            int left = x + 2 + i * (width - 4) / bars, right = x + 2 + (i + 1) * (width - 4) / bars - 1;
            g.fill(left, y + height - 2 - h, Math.max(left + 1, right), y + height - 2, 0xFFB5B0FF);
        }
    }
}
