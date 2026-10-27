package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.SpectrumAppearance;
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
    private static final int TITLE = 0xFFF0EEFF, ARTIST = 0xFFC4C1D6;
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private final float[] levels = new float[64];
    private final float[] peaks = new float[64];
    private final TitleMarquee title = new TitleMarquee();

    /**
     * Whole-pixel scale and position keep the pixel font's strokes even; the plate carries it over bright scenery.
     */
    private void text(GuiGraphicsExtractor g, Font font, String titleText, String artistText, int width, int lineWidth, int titleY, int artistY, boolean plated) {
        int available = Math.max(1, lineWidth - 8);
        String artist = ellipsis(artistText, available, font::width);
        int titleWidth = Math.min(font.width(titleText), available), artistWidth = font.width(artist);
        int plate = Math.max(titleWidth, artistWidth) + 10, left = (width - plate) / 2;
        if (plated) HudText.plate(g, left, titleY - 3, left + plate, artistY + 11, HudText.PLATE);
        if (font.width(titleText) <= available) g.text(font, titleText, (width - titleWidth) / 2, titleY, TITLE);
        else title.draw(g, font, titleText, (width - available) / 2, titleY, available, TITLE);
        g.text(font, artist, (width - artistWidth) / 2, artistY, ARTIST);
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

    private static Matrix3x2f rotated(Matrix3x2f pose, CircularHudLayout layout, double turn) {
        return turn == 0 ? pose : new Matrix3x2f(pose).rotateAbout((float) (turn * Math.PI * 2), layout.centerX(), layout.centerY());
    }

    public void render(DarkMusicClient app, GuiGraphicsExtractor g, Track track, AudioBackend.State audio, boolean preview, boolean settingsPreview) {
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
        double turn = CircularRotation.advance(settings.circularRotationSeconds,
            preview || audio.status() == AudioBackend.Status.PLAYING);
        // Rotation stays inside the unrotated bounds: every layer is a circle around the same center.
        double spin = settings.circularRotation.sign() * turn, progressSpin = settings.circularProgressRotation.sign() * turn;
        var spinning = rotated(pose, layout, spin);
        var progressPose = rotated(pose, layout, progressSpin);
        boolean analyzed = (settings.hudVisualizer || settings.circularIconPulse) && spectrum.copyLevels(app, levels, settingsPreview);
        float zoom = analyzed && settings.circularIconPulse ? CircularHudLayout.iconZoom(levels, settings.visualizerBars) : 1;
        if (analyzed && settings.circularIconPulse && settings.visualQuality.beatReactive)
            zoom = 1 + app.playback.spectrum().beat() * .045f;
        var cover = HudImages.cover(app, track);
        var texture = Minecraft.getInstance().getTextureManager().getTexture(cover == null ? HudImages.ICON : cover.id());
        g.guiRenderState.addGuiElement(new CircularHudMesh.Icon(spinning, clip, bounds,
            TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()), layout.centerX(), layout.centerY(), zoom, HudImages.circularSpan(cover)));
        if (analyzed && settings.hudVisualizer && (settings.visualQuality.trails || settings.visualQuality.peakHold)) {
            app.playback.spectrum().copyPeaks(peaks);
            if (settings.visualQuality.trails)
                g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(spinning, clip, bounds, layout.centerX(), layout.centerY(), -1, peaks.clone(), settings.visualizerBars, settings.circularSpectrumStyle, 0, 0, SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, settings.visualQuality.detail, false, settings.circularSpectrumHeight, baseRadius));
            if (settings.visualQuality.peakHold)
                g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(spinning, clip, bounds, layout.centerX(), layout.centerY(), -2, peaks.clone(), settings.visualizerBars, settings.circularSpectrumStyle, 0, 0, SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, settings.visualQuality.detail, false, settings.circularSpectrumHeight, baseRadius, progressInner, levels.clone()));
        }
        // Progress and spectrum are separate meshes so each can turn its own way.
        if (settings.hudProgress && waveform == null)
            g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(pose, clip, bounds, layout.centerX(), layout.centerY(),
                CircularHudLayout.progress(audio.position(), audio.duration(), preview), null, settings.visualizerBars, settings.circularSpectrumStyle,
                settings.progressColors.first(), settings.progressColors.last(), 0, 0, settings.spectrumFinish, settings.progressFinish, settings.visualQuality.detail, false, settings.circularSpectrumHeight, baseRadius, progressInner).rotated(progressPose, progressSpin));
        if (analyzed && settings.hudVisualizer)
            g.guiRenderState.addGuiElement(new CircularHudMesh.Rings(pose, clip, bounds, layout.centerX(), layout.centerY(),
                -3, levels.clone(), settings.visualizerBars, settings.circularSpectrumStyle,
                settings.progressColors.first(), settings.progressColors.last(), SpectrumAppearance.opacity(settings.spectrumColors.first() | 0xff000000, settings.spectrumOpacity), SpectrumAppearance.opacity(settings.spectrumColors.last() | 0xff000000, settings.spectrumOpacity), settings.spectrumFinish, settings.progressFinish, settings.visualQuality.detail, settings.visualQuality.glow, settings.circularSpectrumHeight, baseRadius, progressInner).rotated(spinning, spin));
        if (waveform != null)
            g.guiRenderState.addGuiElement(new CircularHudMesh.ProgressWaveform(progressPose, clip, bounds, layout.centerX(), layout.centerY(),
                CircularHudLayout.progress(audio.position(), audio.duration(), preview), waveform,
                settings.progressColors.first(), settings.progressColors.last(), settings.progressFinish, settings.waveform.look(), progressInner));
        if (!settings.circularText) return;
        var font = Minecraft.getInstance().font;
        int lineWidth = Math.round(CircularHudLayout.outerRadius(settings) * 2) + 8;
        text(g, font, track == null ? Text.get("text.darkmusic.song_title") : track.title(),
            track == null ? Text.get("text.darkmusic.artist") : track.artist(), layout.width(), lineWidth, layout.titleY(), layout.artistY(), settings.hudTextPlate);
    }
}
