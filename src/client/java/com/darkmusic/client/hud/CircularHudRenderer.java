package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.gui.SpectrumRenderer;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;

import java.util.function.ToIntFunction;

/**
 * A single HUD: original icon, clockwise progress, outward audio bands, and two text lines.
 */
public final class CircularHudRenderer {
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private final float[] levels = new float[64];
    private final float[] peaks = new float[64];
    private final TitleMarquee title = new TitleMarquee();

    private void title(GuiGraphicsExtractor g, Font font, String text, int width, int lineWidth, int y) {
        float scale = 1.1f;
        int available = Math.max(1, (int) ((lineWidth - 8) / scale));
        g.pose().pushMatrix();
        try {
            g.pose().translate(width / 2f, y);
            g.pose().scale(scale);
            if (font.width(text) <= available) g.text(font, text, -font.width(text) / 2, 0, 0xFFDDD9FF);
            else title.draw(g, font, text, -available / 2, 0, available, 0xFFDDD9FF);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static void centered(GuiGraphicsExtractor g, Font font, String text, int width, int lineWidth, int y, float scale, int color) {
        String fitted = ellipsis(text, (int) ((lineWidth - 8) / scale), font::width);
        g.pose().pushMatrix();
        try {
            g.pose().translate(width / 2f, y);
            g.pose().scale(scale);
            g.text(font, fitted, -font.width(fitted) / 2, 0, color);
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * Keep Unicode code points intact when truncating, including supplementary characters.
     */
    public static String ellipsis(String text, int width, ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= width) return text;
        String mark = "\u2026";
        if (measure.applyAsInt(mark) > width) return "";
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(0, text.offsetByCodePoints(0, mid)) + mark;
            if (measure.applyAsInt(candidate) <= width) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + mark;
    }

    public void render(DarkMusicClient app, GuiGraphicsExtractor g, Track track, com.darkmusic.client.audio.AudioBackend.State audio, boolean preview, boolean settingsPreview) {
        var settings = app.settings;
        boolean waveformEnabled = settings.waveformProgress && settings.hudProgress;
        var waveform = waveformEnabled && app.waveforms != null ? app.waveforms.get(track) : null;
        float baseRadius = CircularHudLayout.spectrumBase(settings);
        float progressInner = CircularHudLayout.progressInner(settings);
        var layout = CircularHudLayout.of(settings);
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(0, 0, layout.width(), layout.height()).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds == null) return;
        boolean analyzed = (settings.hudVisualizer || settings.circularIconPulse) && spectrum.copyLevels(app, levels, settingsPreview);
        float zoom = analyzed && settings.circularIconPulse ? CircularHudLayout.iconZoom(levels, settings.visualizerBars) : 1;
        if (analyzed && settings.circularIconPulse && settings.visualQuality.beatReactive)
            zoom = 1 + app.playback.spectrum().beat() * .045f;
        var cover = HudImages.cover(app, track);
        var texture = Minecraft.getInstance().getTextureManager().getTexture(cover == null ? HudImages.ICON : cover.id());
        g.guiRenderState.addGuiElement(new CircularHudMesh.Icon(pose, clip, bounds,
            TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()), layout.centerX(), layout.centerY(), zoom, HudImages.circularSpan(cover)));
        if (analyzed && settings.hudVisualizer && (settings.visualQuality.trails || settings.visualQuality.peakHold)) {
            app.playback.spectrum().copyPeaks(peaks);
            if (settings.visualQuality.trails)
                g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(pose, clip, bounds, layout.centerX(), layout.centerY(), -1, peaks.clone(), settings.visualizerBars, settings.circularSpectrumStyle, 0, 0, com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), com.darkmusic.client.config.Settings.SurfaceFinish.FLAT, com.darkmusic.client.config.Settings.SurfaceFinish.FLAT, settings.visualQuality.detail, false, settings.circularSpectrumHeight, baseRadius));
            if (settings.visualQuality.peakHold)
                g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(pose, clip, bounds, layout.centerX(), layout.centerY(), -2, peaks.clone(), settings.visualizerBars, settings.circularSpectrumStyle, 0, 0, com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), com.darkmusic.client.config.Settings.SurfaceFinish.FLAT, com.darkmusic.client.config.Settings.SurfaceFinish.FLAT, settings.visualQuality.detail, false, settings.circularSpectrumHeight, baseRadius, progressInner, levels.clone()));
        }
        g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(pose, clip, bounds, layout.centerX(), layout.centerY(),
            settings.hudProgress && waveform == null ? CircularHudLayout.progress(audio.position(), audio.duration(), preview) : -3,
            analyzed && settings.hudVisualizer ? levels.clone() : null, settings.visualizerBars, settings.circularSpectrumStyle,
            settings.progressColors.first(), settings.progressColors.last(), com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), com.darkmusic.client.gui.SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), settings.spectrumFinish, settings.progressFinish, settings.visualQuality.detail, settings.visualQuality.glow, settings.circularSpectrumHeight, baseRadius, progressInner));
        if (waveform != null)
            g.guiRenderState.addGuiElement(new CircularHudMesh.ProgressWaveform(pose, clip, bounds, layout.centerX(), layout.centerY(),
                CircularHudLayout.progress(audio.position(), audio.duration(), preview), waveform,
                settings.progressColors.first(), settings.progressColors.last(), settings.progressFinish, settings.waveform.look(), progressInner));
        if (!settings.circularText) return;
        var font = Minecraft.getInstance().font;
        int lineWidth = Math.round(CircularHudLayout.outerRadius(settings) * 2) + 8;
        title(g, font, track == null ? Text.get("text.darkmusic.song_title") : track.title(), layout.width(), lineWidth, layout.titleY());
        centered(g, font, track == null ? Text.get("text.darkmusic.artist") : track.artist(), layout.width(), lineWidth, layout.artistY(), .85f, 0xFFB0ADC2);
    }
}
