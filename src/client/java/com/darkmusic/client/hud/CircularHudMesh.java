package com.darkmusic.client.hud;

import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.WaveformSettings;
import com.darkmusic.client.render.Bevel;
import com.darkmusic.client.render.SpectrumAppearance;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * Immutable snapshots for Minecraft's deferred GUI renderer. Subpixel fringes soften curves.
 */
final class CircularHudMesh {
    private static final int SEGMENTS = 192;
    private static final double TURN = Math.PI * 2;

    private CircularHudMesh() {
    }

    private static float x(float center, double turn, float radius) {
        return center + (float) Math.sin(turn * TURN) * radius;
    }

    private static float y(float center, double turn, float radius) {
        return center - (float) Math.cos(turn * TURN) * radius;
    }

    private static int mix(int first, int last, double fraction) {
        double t = Math.clamp(fraction, 0, 1);
        int r = (int) Math.round((first >> 16 & 255) * (1 - t) + (last >> 16 & 255) * t);
        int g = (int) Math.round((first >> 8 & 255) * (1 - t) + (last >> 8 & 255) * t);
        int b = (int) Math.round((first & 255) * (1 - t) + (last & 255) * t);
        int alpha = (first >>> 24) == 0 && (last >>> 24) == 0 ? 255 : (int) Math.round((first >>> 24) * (1 - t) + (last >>> 24) * t);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private static void point(VertexConsumer v, Matrix3x2fc pose, float cx, float cy, float radius, double turn, int color) {
        vertex(v, pose, x(cx, turn, radius), y(cy, turn, radius)).setColor(color);
    }

    private static VertexConsumer vertex(VertexConsumer v, Matrix3x2fc pose, float x, float y) {
        return v.addVertex(pose.m00() * x + pose.m10() * y + pose.m20(), pose.m01() * x + pose.m11() * y + pose.m21(), 0);
    }

    private static void strip(VertexConsumer v, Matrix3x2fc pose, float cx, float cy, float inner, float outer, double start, double end, int innerStart, int outerStart, int innerEnd, int outerEnd) {
        point(v, pose, cx, cy, outer, start, outerStart);
        point(v, pose, cx, cy, inner, start, innerStart);
        point(v, pose, cx, cy, inner, end, innerEnd);
        point(v, pose, cx, cy, outer, end, outerEnd);
    }

    private static void arc(VertexConsumer v, Matrix3x2fc pose, float cx, float cy, float inner, float outer, double start, double end, int first, int last) {
        strip(v, pose, cx, cy, inner, outer, start, end, first, first, last, last);
        float feather = CircularHudLayout.FEATHER;
        strip(v, pose, cx, cy, inner - feather, inner, start, end, first & 0xFFFFFF, first, last & 0xFFFFFF, last);
        strip(v, pose, cx, cy, outer, outer + feather, start, end, first, first & 0xFFFFFF, last, last & 0xFFFFFF);
    }

    /**
     * {@code light} is the pose's rotation in turns: geometry turns with the pose, the light stays fixed on screen.
     */
    private static void surfaceArc(VertexConsumer v, Matrix3x2fc pose, float cx, float cy, float inner, float outer, double start, double end, int first, int last, Settings.SurfaceFinish finish, boolean recessed, double light) {
        if (finish == Settings.SurfaceFinish.FLAT) {
            arc(v, pose, cx, cy, inner, outer, start, end, first, last);
            return;
        }
        double sign = recessed ? -1 : 1;
        double light0 = Bevel.circularLight(start + light) * .34 * sign, light1 = Bevel.circularLight(end + light) * .34 * sign;
        int inner0 = Bevel.shade(first, -light0), inner1 = Bevel.shade(last, -light1);
        int outer0 = Bevel.shade(first, light0), outer1 = Bevel.shade(last, light1);
        int face0 = Bevel.shade(first, .06), face1 = Bevel.shade(last, .06);
        float middle = (inner + outer) / 2, feather = CircularHudLayout.FEATHER;
        strip(v, pose, cx, cy, inner, middle, start, end, inner0, face0, inner1, face1);
        strip(v, pose, cx, cy, middle, outer, start, end, face0, outer0, face1, outer1);
        strip(v, pose, cx, cy, inner - feather, inner, start, end, inner0 & 0xFFFFFF, inner0, inner1 & 0xFFFFFF, inner1);
        strip(v, pose, cx, cy, outer, outer + feather, start, end, outer0, outer0 & 0xFFFFFF, outer1, outer1 & 0xFFFFFF);
    }

    record Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
                 double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
                 int progressEnd, int spectrumStart,
                 int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
                 Settings.SurfaceFinish progressFinish, int detail, boolean glow,
                 float spectrumHeight, float baseRadius, float progressInner,
                 float[] liveLevels, double rotation) implements GuiElementRenderState {
        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
              double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
              int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
              Settings.SurfaceFinish progressFinish, int detail, boolean glow,
              float spectrumHeight, float baseRadius, float progressInner, float[] liveLevels) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, spectrumHeight, baseRadius, progressInner, liveLevels, 0);
        }

        /**
         * The same rings drawn on a pose rotated by {@code turns}; lighting compensates so it stays fixed on screen.
         */
        Rings rotated(Matrix3x2fc rotatedPose, double turns) {
            return new Rings(rotatedPose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, spectrumHeight, baseRadius, progressInner, liveLevels, turns);
        }

        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
              double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
              int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
              Settings.SurfaceFinish progressFinish, int detail, boolean glow,
              float spectrumHeight, float baseRadius, float progressInner) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, spectrumHeight, baseRadius, progressInner, null);
        }

        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
              double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
              int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
              Settings.SurfaceFinish progressFinish, int detail, boolean glow, float spectrumHeight, float baseRadius) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, spectrumHeight, baseRadius, CircularHudLayout.RING_INNER);
        }

        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
              double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
              int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
              Settings.SurfaceFinish progressFinish, int detail, boolean glow, float spectrumHeight) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, spectrumHeight, CircularHudLayout.RING_OUTER);
        }

        private boolean peakOnly() {
            return progress == -2;
        }

        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
              double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart,
              int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish,
              Settings.SurfaceFinish progressFinish, int detail, boolean glow) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd,
                spectrumStart, spectrumEnd, spectrumFinish, progressFinish, detail, glow, CircularHudLayout.SPECTRUM_HEIGHT);
        }

        Rings(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy, double progress, float[] levels, int bands, Settings.SpectrumStyle style, int progressStart, int progressEnd, int spectrumStart, int spectrumEnd, Settings.SurfaceFinish spectrumFinish, Settings.SurfaceFinish progressFinish) {
            this(pose, scissorArea, bounds, cx, cy, progress, levels, bands, style, progressStart, progressEnd, spectrumStart, spectrumEnd, spectrumFinish, progressFinish, 192, false, CircularHudLayout.SPECTRUM_HEIGHT);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public void buildVertices(VertexConsumer v) {
            for (int i = 0; progress >= 0 && i < detail; i++) {
                double start = i / (double) detail, end = (i + 1) / (double) detail;
                surfaceArc(v, pose, cx, cy, progressInner, progressInner + CircularHudLayout.RING_OUTER - CircularHudLayout.RING_INNER, start, end, 0xFF49485F, 0xFF49485F, progressFinish, true, rotation);
                if (start < progress) {
                    double filled = Math.min(end, progress);
                    surfaceArc(v, pose, cx, cy, progressInner, progressInner + CircularHudLayout.RING_OUTER - CircularHudLayout.RING_INNER, start, filled,
                        mix(progressStart, progressEnd, start), mix(progressStart, progressEnd, filled), progressFinish, false, rotation);
                }
            }
            if (levels == null) return;
            if (!style.bars()) {
                int segments = style == Settings.SpectrumStyle.FILLED_SPIKE ? bands * 8 : detail;
                for (int i = 0; i < segments; i++) {
                    double a = i / (double) segments, b = (i + 1) / (double) segments;
                    float h0 = CircularHudLayout.waveHeight(levels, bands, a, style, spectrumHeight), h1 = CircularHudLayout.waveHeight(levels, bands, b, style, spectrumHeight);
                    if (Math.max(h0, h1) < .01f) continue;
                    int first = mix(spectrumStart, spectrumEnd, 1 - Math.abs(2 * a - 1));
                    int last = mix(spectrumStart, spectrumEnd, 1 - Math.abs(2 * b - 1));
                    float base = baseRadius, feather = CircularHudLayout.FEATHER;
                    boolean beveled = spectrumFinish == Settings.SurfaceFinish.BEVELED;
                    boolean trail = progress == -1;
                    int outer0 = trail ? SpectrumAppearance.alpha(first, 0x10) : beveled ? Bevel.shade(first, Bevel.circularLight(a + rotation) * .34) : first;
                    int outer1 = trail ? SpectrumAppearance.alpha(last, 0x10) : beveled ? Bevel.shade(last, Bevel.circularLight(b + rotation) * .34) : last;
                    point(v, pose, cx, cy, base + h0, a, outer0);
                    point(v, pose, cx, cy, peakOnly() ? base + Math.max(0, h0 - .75f) : base, a, trail ? SpectrumAppearance.alpha(first, 0x58) : beveled ? Bevel.shade(first, -.22) : first);
                    point(v, pose, cx, cy, peakOnly() ? base + Math.max(0, h1 - .75f) : base, b, trail ? SpectrumAppearance.alpha(last, 0x58) : beveled ? Bevel.shade(last, -.22) : last);
                    point(v, pose, cx, cy, base + h1, b, outer1);
                    if (beveled) {
                        point(v, pose, cx, cy, base + h0, a, outer0);
                        point(v, pose, cx, cy, base + h0 - Math.min(1, h0 * .3f), a, Bevel.shade(first, .04));
                        point(v, pose, cx, cy, base + h1 - Math.min(1, h1 * .3f), b, Bevel.shade(last, .04));
                        point(v, pose, cx, cy, base + h1, b, outer1);
                    }
                    point(v, pose, cx, cy, base + h0 + feather, a, outer0 & 0xFFFFFF);
                    point(v, pose, cx, cy, base + h0, a, outer0);
                    point(v, pose, cx, cy, base + h1, b, outer1);
                    point(v, pose, cx, cy, base + h1 + feather, b, outer1 & 0xFFFFFF);
                    if (glow) {
                        // Follow both endpoints of the true contour: a constant maximum radius detaches from slopes.
                        point(v, pose, cx, cy, base + h0 + 1.6f, a, first & 0xffffff);
                        point(v, pose, cx, cy, base + h0, a, SpectrumAppearance.alpha(first, 0x33));
                        point(v, pose, cx, cy, base + h1, b, SpectrumAppearance.alpha(last, 0x33));
                        point(v, pose, cx, cy, base + h1 + 1.6f, b, last & 0xffffff);
                    }
                }
                return;
            }
            for (int i = 0; i < bands; i++) {
                double turn = i / (double) bands, half = .28 / bands;
                float height = CircularHudLayout.magnitude(levels, bands, turn) * spectrumHeight;
                if (height < .15f) continue;
                // The innermost radius is always the ring's outer edge, never the icon.
                int color = mix(spectrumStart, spectrumEnd, 1 - Math.abs(2 * turn - 1));
                if (style == Settings.SpectrumStyle.ROUNDED_BARS) {
                    if (peakOnly() && liveLevels != null && !SpectrumAppearance.detachedPeak(
                        CircularHudLayout.magnitude(levels, bands, turn), CircularHudLayout.magnitude(liveLevels, bands, turn), spectrumHeight))
                        continue;
                    roundedBar(v, turn, half, height, color);
                    continue;
                }
                int slices = Math.max(1, (int) Math.ceil(SEGMENTS * half * 2));
                for (int slice = 0; slice < slices; slice++)
                    if (progress == -1)
                        strip(v, pose, cx, cy, baseRadius, baseRadius + height,
                            turn - half + 2 * half * slice / slices, turn - half + 2 * half * (slice + 1) / slices,
                            SpectrumAppearance.alpha(color, 0x58), SpectrumAppearance.alpha(color, 0x10),
                            SpectrumAppearance.alpha(color, 0x58), SpectrumAppearance.alpha(color, 0x10));
                    else
                        surfaceArc(v, pose, cx, cy, peakOnly() ? baseRadius + Math.max(0, height - .75f) : baseRadius, baseRadius + height,
                            turn - half + 2 * half * slice / slices, turn - half + 2 * half * (slice + 1) / slices, color, color, spectrumFinish, false, rotation);
                if (glow)
                    strip(v, pose, cx, cy, baseRadius + height, baseRadius + height + 1.6f, turn - half, turn + half, SpectrumAppearance.alpha(color, 0x33), color & 0xffffff, SpectrumAppearance.alpha(color, 0x33), color & 0xffffff);
            }
        }

        private void roundedBar(VertexConsumer v, double turn, double half, float height, int color) {
            float markerBase = peakOnly() ? baseRadius + Math.max(0, height - 2) : baseRadius;
            float markerHeight = peakOnly() ? Math.min(2, height) : height;
            float radius = Math.min(markerHeight / 2, (float) (markerBase * half * TURN));
            double light = Bevel.circularLight(turn + rotation);
            for (int i = 0; i < 6; i++) {
                double t0 = -1 + i / 3.0, t1 = -1 + (i + 1) / 3.0;
                double a = turn + half * t0, b = turn + half * t1;
                float cut0 = radius * (1 - (float) Math.sqrt(Math.max(0, 1 - t0 * t0)));
                float cut1 = radius * (1 - (float) Math.sqrt(Math.max(0, 1 - t1 * t1)));
                float outer0 = baseRadius + height - cut0, outer1 = baseRadius + height - cut1;
                float inner0 = markerBase + cut0;
                float inner1 = markerBase + cut1;
                int low = progress == -1 ? SpectrumAppearance.alpha(color, 0x58) : spectrumFinish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(color, -.16 + light * .10) : color;
                int high = progress == -1 ? SpectrumAppearance.alpha(color, 0x10) : spectrumFinish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(color, .22 + light * .12) : color;
                point(v, pose, cx, cy, outer0, a, high);
                point(v, pose, cx, cy, inner0, a, low);
                point(v, pose, cx, cy, inner1, b, low);
                point(v, pose, cx, cy, outer1, b, high);
                point(v, pose, cx, cy, outer0 + CircularHudLayout.FEATHER, a, high & 0xffffff);
                point(v, pose, cx, cy, outer0, a, high);
                point(v, pose, cx, cy, outer1, b, high);
                point(v, pose, cx, cy, outer1 + CircularHudLayout.FEATHER, b, high & 0xffffff);
                point(v, pose, cx, cy, inner0, a, low);
                point(v, pose, cx, cy, inner0 - CircularHudLayout.FEATHER, a, low & 0xffffff);
                point(v, pose, cx, cy, inner1 - CircularHudLayout.FEATHER, b, low & 0xffffff);
                point(v, pose, cx, cy, inner1, b, low);
                if (glow) {
                    point(v, pose, cx, cy, outer0 + 1.6f, a, color & 0xffffff);
                    point(v, pose, cx, cy, outer0, a, SpectrumAppearance.alpha(color, 0x33));
                    point(v, pose, cx, cy, outer1, b, SpectrumAppearance.alpha(color, 0x33));
                    point(v, pose, cx, cy, outer1 + 1.6f, b, color & 0xffffff);
                }
            }
        }
    }

    record ProgressWaveform(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
                            double progress, TrackWaveform waveform, int first, int last,
                            Settings.SurfaceFinish finish,
                            WaveformSettings.Look look,
                            float innerRadius) implements GuiElementRenderState {
        ProgressWaveform(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float cx, float cy,
                         double progress, TrackWaveform waveform, int first, int last,
                         Settings.SurfaceFinish finish, WaveformSettings.Look look) {
            this(pose, scissorArea, bounds, cx, cy, progress, waveform, first, last, finish, look, CircularHudLayout.ICON_RADIUS);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public void buildVertices(VertexConsumer v) {
            int count = Math.min(384, look.detail());
            int alpha = (int) Math.round(look.opacity() * 255) << 24;
            boolean bars = look.style() == WaveformSettings.Style.BARS;
            double gap = bars ? Math.min(.6, look.spacing() / (Math.PI * 2 * CircularHudLayout.RING_OUTER / count)) : 0;
            for (int i = 0; i < count; i++) {
                double start = i / (double) count, end = (i + 1 - gap) / count;
                float inner = innerRadius;
                float h0 = Math.max(.5f, look.height() * waveform.amplitude(start, 1.0 / count, look.smoothing(), look.contrast()));
                float h1 = bars ? h0 : Math.max(.5f, look.height() * waveform.amplitude(end, 1.0 / count, look.smoothing(), look.contrast()));
                if (start < progress && end > progress) {
                    float split = h0 + (h1 - h0) * (float) ((progress - start) / (end - start));
                    waveformSegment(v, start, progress, inner, h0, split, alpha | mix(first, last, start) & 0xffffff, alpha | mix(first, last, progress) & 0xffffff);
                    waveformSegment(v, progress, end, inner, split, h1, alpha | look.unplayedColor(), alpha | look.unplayedColor());
                } else {
                    boolean played = end <= progress;
                    waveformSegment(v, start, end, inner, h0, h1,
                        alpha | (played ? mix(first, last, start) & 0xffffff : look.unplayedColor()),
                        alpha | (played ? mix(first, last, end) & 0xffffff : look.unplayedColor()));
                }
            }
        }

        private void waveformSegment(VertexConsumer v, double start, double end, float inner, float h0, float h1, int a, int b) {
            int shadeA = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(a, .18) : a;
            int shadeB = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(b, .18) : b;
            point(v, pose, cx, cy, inner + h0, start, shadeA);
            point(v, pose, cx, cy, inner, start, a);
            point(v, pose, cx, cy, inner, end, b);
            point(v, pose, cx, cy, inner + h1, end, shadeB);
            point(v, pose, cx, cy, inner + h0 + CircularHudLayout.FEATHER, start, shadeA & 0xffffff);
            point(v, pose, cx, cy, inner + h0, start, shadeA);
            point(v, pose, cx, cy, inner + h1, end, shadeB);
            point(v, pose, cx, cy, inner + h1 + CircularHudLayout.FEATHER, end, shadeB & 0xffffff);
            strip(v, pose, cx, cy, inner - CircularHudLayout.FEATHER, inner, start, end,
                a & 0xffffff, a, b & 0xffffff, b);
        }
    }

    record Icon(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, TextureSetup textureSetup,
                float cx, float cy, float zoom, float uvSpan) implements GuiElementRenderState {
        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }

        private void vertex(VertexConsumer v, double turn, float radius, int color) {
            float dx = (float) Math.sin(turn * TURN) * radius, dy = -(float) Math.cos(turn * TURN) * radius;
            CircularHudMesh.vertex(v, pose, cx + dx, cy + dy)
                .setUv(.5f + dx * uvSpan / (CircularHudLayout.ICON_RADIUS * 2 * zoom), .5f + dy * uvSpan / (CircularHudLayout.ICON_RADIUS * 2 * zoom)).setColor(color);
        }

        @Override
        public void buildVertices(VertexConsumer v) {
            for (int i = 0; i < SEGMENTS; i++) {
                double a = i / (double) SEGMENTS, b = (i + 1) / (double) SEGMENTS;
                float radius = CircularHudLayout.ICON_RADIUS;
                vertex(v, a, radius, 0xFFFFFFFF);
                vertex(v, a, 0, 0xFFFFFFFF);
                vertex(v, b, radius, 0xFFFFFFFF);
                vertex(v, a, radius, 0xFFFFFFFF);
                vertex(v, a, radius + CircularHudLayout.FEATHER, 0x00FFFFFF);
                vertex(v, a, radius, 0xFFFFFFFF);
                vertex(v, b, radius, 0xFFFFFFFF);
                vertex(v, b, radius + CircularHudLayout.FEATHER, 0x00FFFFFF);
            }
        }
    }
}
