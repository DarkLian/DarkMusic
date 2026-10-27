package com.darkmusic.client.render;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.SpectrumAnalyzer;
import com.darkmusic.client.config.Settings;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing only: the audio worker owns analysis, timing, and smoothing.
 */
public final class SpectrumRenderer {
    private final float[] levels = new float[64];
    private final float[] peaks = new float[64];
    private final SpectrumMesh mesh = new SpectrumMesh();

    private long previewTime;

    public static int barHeight(float magnitude, int height) {
        return Float.isFinite(magnitude) ? Math.round(Math.clamp(magnitude, 0, 1) * height) : 0;
    }

    // Side edges need three pixels; decided once per row so bars rounded to 2 or 3 pixels look alike.
    private boolean sideBevel;

    private void fill(Settings settings, int left, int top, int right, int bottom, int color) {
        if (settings.spectrumStyle == Settings.SpectrumStyle.ROUNDED_BARS) {
            mesh.rounded(left, top, right, bottom, color, settings.spectrumFinish);
            return;
        }
        if (settings.spectrumFinish != Settings.SurfaceFinish.BEVELED || bottom - top < 2) {
            rawFill(left, top, right, bottom, color);
            return;
        }
        rawFill(left, top, right, bottom, Bevel.shade(color, -.08));
        rawFill(left, top, right, top + 1, Bevel.shade(color, .32));
        rawFill(left, bottom - 1, right, bottom, Bevel.shade(color, -.28));
        if (settings.spectrumStyle == Settings.SpectrumStyle.BARS && sideBevel && bottom - top >= 3) {
            rawFill(left, top + 1, left + 1, bottom - 1, Bevel.shade(color, .16));
            rawFill(right - 1, top + 1, right, bottom - 1, Bevel.shade(color, -.25));
        }
    }

    private void rawFill(int left, int top, int right, int bottom, int color) {
        mesh.fill(left, top, right, bottom, color);
    }

    private void trail(int left, int right, int y, int height, int low, int high, int color, boolean downward) {
        if (high <= low) return;
        int near = (color & 0xffffff) | 0x58000000, far = (color & 0xffffff) | 0x10000000;
        mesh.gradient(left, downward ? y + low : y + height - high, right,
            downward ? y + high : y + height - low, downward ? near : far, downward ? far : near);
    }

    private boolean update(DarkMusicClient app, boolean settingsPreview) {
        return update(app, settingsPreview, null, app.settings);
    }

    private boolean update(DarkMusicClient app, boolean settingsPreview, SpectrumAnalyzer external, Settings settings) {
        var analyzer = external == null ? app.playback.spectrum() : external;
        var status = app.playback.state().status();
        boolean actual = external != null || settings == app.settings && (status == AudioBackend.Status.PLAYING || status == AudioBackend.Status.PAUSED);
        if (actual && analyzer == null) return false;
        if (actual) analyzer.copyTo(levels);
        else if (!settingsPreview) return false;
        int bands = settings.visualizerBars;
        if (actual) analyzer.copyPeaks(peaks);
        else System.arraycopy(levels, 0, peaks, 0, levels.length);
        if (!actual) {
            long now = System.nanoTime();
            double dt = previewTime == 0 ? .016 : Math.min(.1, (now - previewTime) / 1e9);
            previewTime = now;
            double factor = 1 - Math.exp(-dt / (.025 + settings.visualizerSmoothing * .65));
            for (int i = 0; i < bands; i++) {
                double target = Math.clamp((.45 + .3 * Math.sin(now / 5e8 + i * .7)) * settings.visualizerSensitivity, 0, 1);
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
        bars(app, g, Bars.at(x + 2, y + 2, width - 4, height - 4, false, false));
    }

    /**
     * Where to draw and what to read. hudHeight is 0 outside the HUD; a null analyzer reads playback and null
     * settings use the app's.
     */
    public record Bars(int x, int y, int width, int height, boolean downward, boolean settingsPreview, int hudHeight,
                       SpectrumAnalyzer analyzer, Settings settings) {
        public static Bars at(int x, int y, int width, int height, boolean downward, boolean settingsPreview) {
            return new Bars(x, y, width, height, downward, settingsPreview, 0, null, null);
        }

        public Bars inHud(int hudHeight) {
            return new Bars(x, y, width, height, downward, settingsPreview, hudHeight, analyzer, settings);
        }

        public Bars from(SpectrumAnalyzer analyzer, Settings settings) {
            return new Bars(x, y, width, height, downward, settingsPreview, hudHeight, analyzer, settings);
        }
    }

    /**
     * No background and no audio mutations. Synthetic data is limited to the settings preview.
     */
    public void bars(DarkMusicClient app, GuiGraphicsExtractor g, Bars request) {
        int x = request.x(), y = request.y(), width = request.width(), height = request.height(), hudHeight = request.hudHeight();
        boolean downward = request.downward(), settingsPreview = request.settingsPreview();
        SpectrumAnalyzer external = request.analyzer();
        Settings settings = request.settings() == null ? app.settings : request.settings();
        if (width < 8 || height < 1) return;
        if (!update(app, settingsPreview, external, settings)) return;
        int bands = settings.visualizerBars;
        mesh.begin(width, hudHeight, settings.hudShape, settings.spectrumOpacity);
        if (settings.visualQuality.glow)
            SpectrumHalo.draw(g, settings, x, y, width, height, levels, bands, downward, hudHeight);
        if (!settings.spectrumStyle.bars()) {
            // At most 384 columns, even on large windows. Reuse the analysis snapshot above.
            int columns = Math.min(width, settings.visualQuality.detail), previous = 0, previousPeak = 0;
            for (int i = 0; i < columns; i++) {
                double position = columns == 1 ? 0 : i / (double) (columns - 1);
                boolean spike = settings.spectrumStyle == Settings.SpectrumStyle.SPIKE || settings.spectrumStyle == Settings.SpectrumStyle.FILLED_SPIKE;
                int h = barHeight(spike ? SpectrumCurve.spike(levels, bands, position) : SpectrumCurve.sample(levels, bands, position), height);
                if (i == 0) previous = h;
                boolean filled = settings.spectrumStyle == Settings.SpectrumStyle.FILLED_WAVE || settings.spectrumStyle == Settings.SpectrumStyle.FILLED_SPIKE;
                int low = filled ? 0 : Math.max(0, Math.min(h, previous) - 2);
                int high = filled ? h : Math.max(h, previous);
                int left = x + i * width / columns, right = x + (i + 1) * width / columns;
                int top = downward ? y + low : y + height - high;
                int bottom = downward ? y + high : y + height - low;
                int peak = settings.visualQuality.trails || settings.visualQuality.peakHold
                    ? barHeight(spike ? SpectrumCurve.spike(peaks, bands, position) : SpectrumCurve.sample(peaks, bands, position), height) : 0;
                if (i == 0) previousPeak = peak;
                if (settings.visualQuality.trails) {
                    int tailLow = filled ? h : Math.max(0, Math.min(peak, previousPeak) - 2);
                    int tailHigh = filled ? peak : Math.max(peak, previousPeak);
                    if (tailHigh > 0)
                        trail(left, right, y, height, tailLow, tailHigh, settings.spectrumColors.color(position), downward);
                }
                if (settings.visualQuality.peakHold) {
                    if (peak > 0)
                        rawFill(left, downward ? y + peak - 1 : y + height - peak, right, downward ? y + peak : y + height - peak + 1, settings.spectrumColors.color(position));
                }
                if (h > 0)
                    fill(settings, left, top, right, bottom, settings.spectrumColors.color(position));
                previous = h;
                previousPeak = peak;
            }
            mesh.submit(g);
            return;
        }
        int bars = Math.min(bands, width / 2);
        sideBevel = width / bars - 1 >= 3;
        for (int i = 0; i < bars; i++) {
            float value = 0;
            for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++) value = Math.max(value, levels[j]);
            int h = barHeight(value, height);
            int left = x + i * width / bars, right = x + (i + 1) * width / bars - 1;
            int top = downward ? y : y + height - h;
            if (settings.visualQuality.trails || settings.visualQuality.peakHold) {
                int peak = 0;
                for (int j = i * bands / bars; j < (i + 1) * bands / bars; j++)
                    peak = Math.max(peak, barHeight(peaks[j], height));
                int color = (settings.spectrumColors.color(i / (double) Math.max(1, bars - 1)) & 0xffffff) | 0x22000000;
                if (settings.visualQuality.trails) {
                    if (settings.spectrumStyle == Settings.SpectrumStyle.ROUNDED_BARS)
                        mesh.rounded(left, downward ? y : y + height - peak, Math.max(left + 1, right), downward ? y + peak : y + height,
                            color, Settings.SurfaceFinish.FLAT);
                    else trail(left, Math.max(left + 1, right), y, height, h, peak, color, downward);
                }
                if (settings.visualQuality.peakHold && peak > 0) {
                    if (settings.spectrumStyle == Settings.SpectrumStyle.ROUNDED_BARS) {
                        if (SpectrumAppearance.detachedPeak(peak, h, 1))
                            mesh.rounded(left, downward ? y + peak - 2 : y + height - peak, Math.max(left + 1, right),
                                downward ? y + peak : y + height - peak + 2, color | 0xff000000,
                                Settings.SurfaceFinish.FLAT);
                    } else
                        rawFill(left, downward ? y + Math.max(0, peak - 1) : y + height - peak, Math.max(left + 1, right), downward ? y + peak : y + height - peak + 1, color | 0xff000000);
                }
            }
            if (h > 0)
                fill(settings, left, top, Math.max(left + 1, right), top + h, settings.spectrumColors.color(bars <= 1 ? 0 : i / (double) (bars - 1)));
        }
        mesh.submit(g);
    }
}
