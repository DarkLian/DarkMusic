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
 * Batches the existing pixel-exact spectrum rectangles without changing their geometry or layering.
 */
final class SpectrumMesh {
    private final int[] rectangles = new int[16384];
    private final int[] bottomColors = new int[3277];
    private boolean hasGradient;
    private int count, width, hudHeight;
    private Settings.HudShape shape;

    void begin(int width, int hudHeight, Settings.HudShape shape) {
        count = 0;
        hasGradient = false;
        this.width = width;
        this.hudHeight = hudHeight;
        this.shape = shape;
    }

    void fill(int left, int top, int right, int bottom, int color) {
        gradient(left, top, right, bottom, color, color);
    }

    void gradient(int left, int top, int right, int bottom, int topColor, int bottomColor) {
        if (right <= left || bottom <= top) return;
        if (hudHeight <= 0 || shape == Settings.HudShape.RECTANGLE) {
            rectangle(left, top, right, bottom, topColor, bottomColor);
            return;
        }
        for (int row = Math.max(0, top), limit = Math.min(bottom, hudHeight); row < limit; ) {
            int inset = HudSurface.inset(shape, width, hudHeight, row);
            int next = HudSurface.spanBottom(shape, width, hudHeight, left, right, row, limit);
            rectangle(Math.max(left, inset), row, Math.min(right, width - inset), next,
                interpolate(topColor, bottomColor, (row - top) / (float) (bottom - top)),
                interpolate(topColor, bottomColor, (next - top) / (float) (bottom - top)));
            row = next;
        }
    }

    private static int interpolate(int first, int last, float fraction) {
        if (first == last) return first;
        int alpha = Math.round((first >>> 24) + ((last >>> 24) - (first >>> 24)) * fraction);
        return (UiTheme.mix(first, last, fraction) & 0xffffff) | alpha << 24;
    }

    private void rectangle(int left, int top, int right, int bottom, int topColor, int bottomColor) {
        if (right <= left || bottom <= top) return;
        if (count + 5 > rectangles.length)
            throw new IllegalStateException("Spectrum geometry exceeded its bounded detail budget");
        bottomColors[count / 5] = bottomColor;
        hasGradient |= topColor != bottomColor;
        rectangles[count++] = left;
        rectangles[count++] = top;
        rectangles[count++] = right;
        rectangles[count++] = bottom;
        rectangles[count++] = topColor;
    }

    void submit(GuiGraphicsExtractor g) {
        if (count == 0) return;
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (int i = 0; i < count; i += 5) {
            left = Math.min(left, rectangles[i]);
            top = Math.min(top, rectangles[i + 1]);
            right = Math.max(right, rectangles[i + 2]);
            bottom = Math.max(bottom, rectangles[i + 3]);
        }
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds != null)
            g.guiRenderState.addGuiElement(new Snapshot(pose, clip, bounds, java.util.Arrays.copyOf(rectangles, count),
                hasGradient ? java.util.Arrays.copyOf(bottomColors, count / 5) : null));
    }

    record Snapshot(Matrix3x2f pose, ScreenRectangle scissorArea, ScreenRectangle bounds,
                    int[] rectangles, int[] bottomColors) implements GuiElementRenderState {
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
            for (int i = 0; i < rectangles.length; i += 5) {
                int left = rectangles[i], top = rectangles[i + 1], right = rectangles[i + 2], bottom = rectangles[i + 3];
                int bottomColor = bottomColors == null ? rectangles[i + 4] : bottomColors[i / 5];
                v.addVertexWith2DPose(pose, left, top).setColor(rectangles[i + 4]);
                v.addVertexWith2DPose(pose, left, bottom).setColor(bottomColor);
                v.addVertexWith2DPose(pose, right, bottom).setColor(bottomColor);
                v.addVertexWith2DPose(pose, right, top).setColor(rectangles[i + 4]);
            }
        }
    }
}
