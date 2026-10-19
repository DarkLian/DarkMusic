package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudSurface;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/**
 * A single deferred, bounded halo mesh; adjacent columns never expand over one another.
 */
final class SpectrumHalo {
    private SpectrumHalo() {
    }

    static void draw(GuiGraphicsExtractor g, Settings settings, int x, int y, int width, int height,
                     float[] levels, int bands, boolean downward, int hudHeight) {
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(x, Math.max(0, y - 2), width, height + 4).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds == null) return;
        int columns = settings.spectrumStyle == Settings.SpectrumStyle.BARS ? Math.min(bands, width / 2) : Math.min(width, settings.visualQuality.detail);
        float[] tops = new float[columns], bottoms = new float[columns];
        boolean bars = settings.spectrumStyle == Settings.SpectrumStyle.BARS;
        boolean filled = bars || settings.spectrumStyle == Settings.SpectrumStyle.FILLED_SPIKE || settings.spectrumStyle == Settings.SpectrumStyle.FILLED_WAVE;
        int previous = 0;
        for (int i = 0; i < columns; i++) {
            double t = columns == 1 ? 0 : i / (double) (columns - 1);
            float value = 0;
            if (bars) {
                for (int j = i * bands / columns; j < (i + 1) * bands / columns; j++)
                    value = Math.max(value, levels[j]);
            } else
                value = settings.spectrumStyle == Settings.SpectrumStyle.SPIKE || settings.spectrumStyle == Settings.SpectrumStyle.FILLED_SPIKE ? SpectrumCurve.spike(levels, bands, t) : SpectrumCurve.sample(levels, bands, t);
            int h = SpectrumRenderer.barHeight(value, height);
            if (i == 0) previous = h;
            int low = filled ? 0 : Math.max(0, Math.min(previous, h) - 2), high = filled ? h : Math.max(previous, h);
            tops[i] = h == 0 ? Float.NaN : downward ? y + low : y + height - high;
            bottoms[i] = downward ? y + high : y + height - low;
            previous = h;
        }
        g.guiRenderState.addGuiElement(new Halo(pose, clip, bounds, x, width, tops, bottoms, bars, settings.hudShape, hudHeight, settings.spectrumColors.first(), settings.spectrumColors.last()));
    }

    record Halo(Matrix3x2f pose, ScreenRectangle scissorArea, ScreenRectangle bounds, int x, int width,
                float[] tops, float[] bottoms, boolean bars, Settings.HudShape shape, int hudHeight, int first,
                int last) implements GuiElementRenderState {
        @Override
        public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public void buildVertices(VertexConsumer v) {
            for (int i = 0; i < tops.length; i++) {
                if (!Float.isFinite(tops[i])) continue;
                int left = x + i * width / tops.length, right = x + (i + 1) * width / tops.length - (bars ? 1 : 0);
                int color = UiTheme.mix(first, last, tops.length == 1 ? 0 : i / (float) (tops.length - 1)) & 0xffffff;
                band(v, left, right, (int) tops[i] - 2, (int) tops[i], color, false);
                band(v, left, right, (int) bottoms[i], (int) bottoms[i] + 2, color, true);
            }
        }

        private void band(VertexConsumer v, int left, int right, int top, int bottom, int color, boolean fading) {
            // Coalesce equal clipped spans. Only corner boundaries can split the two-unit halo.
            int limit = hudHeight > 0 ? Math.min(bottom, hudHeight) : bottom;
            for (int row = hudHeight > 0 ? Math.max(0, top) : top; row < limit; ) {
                int inset = hudHeight > 0 ? HudSurface.inset(shape, width, hudHeight, row) : 0;
                int l = hudHeight > 0 ? Math.max(left, inset) : left;
                int r = hudHeight > 0 ? Math.min(right, width - inset) : right;
                int endInset = hudHeight > 0 ? HudSurface.inset(shape, width, hudHeight, limit - 1) : 0;
                int next = inset == endInset ? limit : row + 1;
                if (r <= l) {
                    row = next;
                    continue;
                }
                int a = Math.round(38 * (fading ? (bottom - row) / 2f : (row - top) / 2f));
                int b = Math.round(38 * (fading ? (bottom - next) / 2f : (next - top) / 2f));
                v.addVertexWith2DPose(pose, l, row).setColor(color | a << 24);
                v.addVertexWith2DPose(pose, l, next).setColor(color | b << 24);
                v.addVertexWith2DPose(pose, r, next).setColor(color | b << 24);
                v.addVertexWith2DPose(pose, r, row).setColor(color | a << 24);
                row = next;
            }
        }
    }
}
