package com.darkmusic.client.render;

import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.config.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * A static musical envelope with antialiased edges and an exact playback boundary.
 */
public final class WaveformProgress {
    private WaveformProgress() {
    }

    public static boolean draw(GuiGraphicsExtractor g, TrackWaveform data, VisualColors colors, WaveformSettings options,
                               int x, int y, int width, int height, double progress, Settings.SurfaceFinish finish) {
        if (data == null || width < 3 || height < 3) return false;
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(x - 1, y - 1, width + 2, height + 2).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds == null) return true;
        int count = Math.max(1, Math.min(options.detail,
            options.style == WaveformSettings.Style.BARS ? width / (options.spacing + 2) : width));
        float[] heights = new float[count + 1];
        for (int i = 0; i <= count; i++)
            heights[i] = Math.max(.5f,
                data.amplitude(i / (double) count, 1.0 / count, options.smoothing, options.contrast) * height / 2f);
        g.guiRenderState.addGuiElement(new Mesh(pose, clip, bounds, x, y + height / 2f, width,
            Math.clamp(progress, 0, 1), heights, colors.first(), colors.last(), options.look(), finish));
        return true;
    }

    record Mesh(Matrix3x2fc pose, ScreenRectangle scissorArea, ScreenRectangle bounds, float x, float cy, float width,
                double progress, float[] heights, int first, int last, WaveformSettings.Look look,
                Settings.SurfaceFinish finish) implements GuiElementRenderState {
        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        private void vertex(VertexConsumer v, float px, float py, int color) {
            v.addVertex(pose.m00() * px + pose.m10() * py + pose.m20(), pose.m01() * px + pose.m11() * py + pose.m21(), 0).setColor(color);
        }

        private int color(double t, boolean played) {
            int rgb = played ? UiTheme.mix(first, last, (float) t) : look.unplayedColor();
            return (int) Math.round(look.opacity() * 255) << 24 | rgb & 0xffffff;
        }

        private void band(VertexConsumer v, float left, float right, float y0, float y1, float z0, float z1, int c0, int c1, int d0, int d1) {
            vertex(v, left, y0, c0);
            vertex(v, left, z0, d0);
            vertex(v, right, z1, d1);
            vertex(v, right, y1, c1);
        }

        private void contour(VertexConsumer v, double start, double end, float h0, float h1, boolean played) {
            float left = x + (float) (start * width), right = x + (float) (end * width);
            int a = color(start, played), b = color(end, played);
            int topA = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(a, .18) : a;
            int topB = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(b, .18) : b;
            int bottomA = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(a, -.18) : a;
            int bottomB = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(b, -.18) : b;
            band(v, left, right, cy - h0, cy - h1, cy, cy, topA, topB, a, b);
            band(v, left, right, cy, cy, cy + h0, cy + h1, a, b, bottomA, bottomB);
            band(v, left, right, cy - h0 - .65f, cy - h1 - .65f, cy - h0, cy - h1,
                topA & 0xffffff, topB & 0xffffff, topA, topB);
            band(v, left, right, cy + h0, cy + h1, cy + h0 + .65f, cy + h1 + .65f,
                bottomA, bottomB, bottomA & 0xffffff, bottomB & 0xffffff);
        }

        private void cap(VertexConsumer v, float centerX, float centerY, float radius, boolean top, int color) {
            int slices = Math.clamp((int) Math.ceil(radius * 2), 3, 8);
            for (int j = 0; j < slices; j++) {
                double a = Math.PI * j / slices, b = Math.PI * (j + 1) / slices;
                float sign = top ? -1 : 1;
                float ax = (float) Math.cos(a), ay = sign * (float) Math.sin(a);
                float bx = (float) Math.cos(b), by = sign * (float) Math.sin(b);
                vertex(v, centerX + ax * radius, centerY + ay * radius, color);
                vertex(v, centerX, centerY, color);
                vertex(v, centerX, centerY, color);
                vertex(v, centerX + bx * radius, centerY + by * radius, color);
                vertex(v, centerX + ax * (radius + .5f), centerY + ay * (radius + .5f), color & 0xffffff);
                vertex(v, centerX + ax * radius, centerY + ay * radius, color);
                vertex(v, centerX + bx * radius, centerY + by * radius, color);
                vertex(v, centerX + bx * (radius + .5f), centerY + by * (radius + .5f), color & 0xffffff);
            }
        }

        @Override
        public void buildVertices(VertexConsumer v) {
            int count = heights.length - 1;
            for (int i = 0; i < count; i++) {
                double start = i / (double) count, end = (i + 1.0) / count;
                if (look.style() == WaveformSettings.Style.BARS) {
                    float gap = Math.min(look.spacing(), Math.max(0, width / count - 1));
                    float left = x + (float) (start * width) + gap / 2;
                    float right = x + (float) (end * width) - gap / 2;
                    float half = (heights[i] + heights[i + 1]) / 2;
                    float radius = Math.min((right - left) / 2, half);
                    int c = color((start + end) / 2, (start + end) / 2 <= progress);
                    int topColor = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(c, .18) : c;
                    int bottomColor = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(c, -.18) : c;
                    band(v, left, right, cy - half + radius, cy - half + radius, cy, cy, topColor, topColor, c, c);
                    band(v, left, right, cy, cy, cy + half - radius, cy + half - radius, c, c, bottomColor, bottomColor);
                    cap(v, (left + right) / 2, cy - half + radius, radius, true, topColor);
                    cap(v, (left + right) / 2, cy + half - radius, radius, false, bottomColor);
                    band(v, left - .5f, left, cy - half + radius, cy - half + radius, cy + half - radius, cy + half - radius, c & 0xffffff, c, c & 0xffffff, c);
                    band(v, right, right + .5f, cy - half + radius, cy - half + radius, cy + half - radius, cy + half - radius, c, c & 0xffffff, c, c & 0xffffff);
                } else if (progress > start && progress < end) {
                    float split = (float) ((progress - start) / (end - start));
                    float h = heights[i] + (heights[i + 1] - heights[i]) * split;
                    contour(v, start, progress, heights[i], h, true);
                    contour(v, progress, end, h, heights[i + 1], false);
                } else contour(v, start, end, heights[i], heights[i + 1], end <= progress);
            }
        }
    }
}
