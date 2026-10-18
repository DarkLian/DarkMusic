package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing only: the audio worker owns analysis, timing, and smoothing.
 */
public final class SpectrumRenderer {
    private final float[] levels = new float[64];
    private final float[] peaks = new float[64];

    private long previewTime;

    public static int barHeight(float magnitude, int height) {
        return Float.isFinite(magnitude) ? Math.round(Math.clamp(magnitude, 0, 1) * height) : 0;
    }

    private static void fill(DarkMusicClient app, GuiGraphicsExtractor g, int left, int top, int right, int bottom, int color, int width, int hudHeight) {
        if (app.settings.visualQuality.glow)
            rawFill(app, g, left - 1, top - 1, right + 1, bottom + 1, (color & 0xffffff) | 0x22000000, width, hudHeight);
        if (app.settings.spectrumFinish != com.darkmusic.client.config.Settings.SurfaceFinish.BEVELED || bottom - top < 2) {
            rawFill(app, g, left, top, right, bottom, color, width, hudHeight);
            return;
        }
        rawFill(app, g, left, top, right, bottom, Bevel.shade(color, -.08), width, hudHeight);
        rawFill(app, g, left, top, right, top + 1, Bevel.shade(color, .32), width, hudHeight);
        rawFill(app, g, left, bottom - 1, right, bottom, Bevel.shade(color, -.28), width, hudHeight);
        if (app.settings.spectrumStyle == com.darkmusic.client.config.Settings.SpectrumStyle.BARS && right - left >= 3 && bottom - top >= 3) {
            rawFill(app, g, left, top + 1, left + 1, bottom - 1, Bevel.shade(color, .16), width, hudHeight);
            rawFill(app, g, right - 1, top + 1, right, bottom - 1, Bevel.shade(color, -.25), width, hudHeight);
        }
    }

    private static void rawFill(DarkMusicClient app, GuiGraphicsExtractor g, int left, int top, int right, int bottom, int color, int width, int hudHeight) {
        if (hudHeight > 0)
            com.darkmusic.client.hud.HudSurface.fill(g, app.settings.hudShape, width, hudHeight, left, top, right, bottom, color);
        else g.fill(left, top, right, bottom, color);
    }

    private boolean update(DarkMusicClient app, boolean settingsPreview) {
        if (app.playback.spectrum() == null) return false;
        var status = app.playback.state().status();
        boolean actual = status == AudioBackend.Status.PLAYING || status == AudioBackend.Status.PAUSED;
        if (actual) app.playback.spectrum().copyTo(levels);
        else if (!settingsPreview) return false;
        int bands = app.settings.visualizerBars;
        if (actual) app.playback.spectrum().copyPeaks(peaks);
        else System.arraycopy(levels, 0, peaks, 0, levels.length);
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
        return true;
    }

    /**
     * Shared audio snapshot for alternative HUD layouts, with preview synthesis confined here.
     */
    public boolean copyLevels(DarkMusicClient app, float[] target, boolean settingsPreview) {
        if (!update(app, settingsPreview)) return false;
        System.arraycopy(levels, 0, target, 0, Math.min(target.length, levels.length));
        return true;
    }

    public void render(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, 0xBB111420);
        bars(app, g, x + 2, y + 2, width - 4, height - 4, false, false);
    }

    /**
     * No background and no audio mutations. Synthetic data is limited to the settings preview.
     */
    public void bars(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, boolean downward, boolean settingsPreview) {
        bars(app, g, x, y, width, height, downward, settingsPreview, 0);
    }

    public void bars(DarkMusicClient app, GuiGraphicsExtractor g, int x, int y, int width, int height, boolean downward, boolean settingsPreview, int hudHeight) {
        if (width < 8 || height < 1 || app.playback.spectrum() == null) return;
        if (!update(app, settingsPreview)) return;
        int bands = app.settings.visualizerBars;
        if (app.settings.spectrumStyle != com.darkmusic.client.config.Settings.SpectrumStyle.BARS) {
            // At most 384 columns, even on large windows. Reuse the analysis snapshot above.
            int columns = Math.min(width, app.settings.visualQuality.detail), previous = 0;
            for (int i = 0; i < columns; i++) {
                double position = columns == 1 ? 0 : i / (double) (columns - 1);
                boolean spike = app.settings.spectrumStyle == com.darkmusic.client.config.Settings.SpectrumStyle.SPIKE || app.settings.spectrumStyle == com.darkmusic.client.config.Settings.SpectrumStyle.FILLED_SPIKE;
                int h = barHeight(spike ? SpectrumCurve.spike(levels, bands, position) : SpectrumCurve.sample(levels, bands, position), height);
                if (h == 0) {
                    previous = 0;
                    continue;
                }
                if (i == 0) previous = h;
                boolean filled = app.settings.spectrumStyle == com.darkmusic.client.config.Settings.SpectrumStyle.FILLED_WAVE || app.settings.spectrumStyle == com.darkmusic.client.config.Settings.SpectrumStyle.FILLED_SPIKE;
                int low = filled ? 0 : Math.max(0, Math.min(h, previous) - 2);
                int high = filled ? h : Math.max(h, previous);
                int left = x + i * width / columns, right = x + (i + 1) * width / columns;
                int top = downward ? y + low : y + height - high;
                int bottom = downward ? y + high : y + height - low;
                if (app.settings.visualQuality.trails) {
                    int tail = barHeight(SpectrumCurve.sample(peaks, bands, position), height);
                    rawFill(app, g, left, downward ? y : y + height - tail, right, downward ? y + tail : y + height, (app.settings.spectrumColors.color(position) & 0xffffff) | 0x33000000, width, hudHeight);
                }
                fill(app, g, left, top, right, bottom, app.settings.spectrumColors.color(i / (double) (columns - 1)), width, hudHeight);
                previous = h;
            }
            return;
        }
        int bars = Math.min(bands, width / 2);
        for (int i = 0; i < bars; i++) {
            float value = 0;
            for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++) value = Math.max(value, levels[j]);
            int h = barHeight(value, height);
            if (h == 0) continue;
            int left = x + i * width / bars, right = x + (i + 1) * width / bars - 1;
            int top = downward ? y : y + height - h;
            if (app.settings.visualQuality.trails || app.settings.visualQuality.peakHold) {
                int peak = 0;
                for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++)
                    peak = Math.max(peak, barHeight(peaks[j], height));
                int color = (app.settings.spectrumColors.color(i / (double) Math.max(1, bars - 1)) & 0xffffff) | 0x55000000;
                if (app.settings.visualQuality.trails)
                    rawFill(app, g, left, downward ? y : y + height - peak, Math.max(left + 1, right), downward ? y + peak : y + height, color, width, hudHeight);
                if (app.settings.visualQuality.peakHold)
                    rawFill(app, g, left, downward ? y + Math.max(0, peak - 1) : y + height - peak, Math.max(left + 1, right), downward ? y + peak : y + height - peak + 1, color | 0xff000000, width, hudHeight);
            }
            fill(app, g, left, top, Math.max(left + 1, right), top + h, app.settings.spectrumColors.color(bars <= 1 ? 0 : i / (double) (bars - 1)), width, hudHeight);
        }
    }
}
