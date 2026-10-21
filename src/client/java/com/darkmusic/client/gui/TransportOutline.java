package com.darkmusic.client.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/**
 * A continuous rounded stroke with subpixel alpha fringes, submitted as one mesh.
 */
final class TransportOutline {
    private static final int POINTS = 36;
    private static final float[] COS = new float[POINTS], SIN = new float[POINTS];

    static {
        for (int i = 0; i < POINTS; i++) {
            double angle = Math.PI + (i / 9) * Math.PI / 2 + (i % 9) * Math.PI / 16;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private TransportOutline() {
    }

    static void draw(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        if (width < 4 || height < 4) return;
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(x - 1, y - 1, width + 2, height + 2).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds != null) g.guiRenderState.addGuiElement(new Stroke(pose, clip, bounds, x, y, width, height, color));
    }

    private record Stroke(Matrix3x2f pose, ScreenRectangle scissorArea, ScreenRectangle bounds,
                          float x, float y, float width, float height, int color) implements GuiElementRenderState {
        @Override
        public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        private void point(VertexConsumer vertices, int i, float inset, int tint) {
            float radius = Math.min(5, Math.min(width, height) / 2);
            int corner = i / 9;
            float cx = corner == 0 || corner == 3 ? x + radius : x + width - radius;
            float cy = corner < 2 ? y + radius : y + height - radius;
            vertices.addVertexWith2DPose(pose, cx + COS[i] * (radius - inset), cy + SIN[i] * (radius - inset)).setColor(tint);
        }

        private void strip(VertexConsumer vertices, int a, int b, float outer, float inner, int outside, int inside) {
            point(vertices, a, outer, outside);
            point(vertices, a, inner, inside);
            point(vertices, b, inner, inside);
            point(vertices, b, outer, outside);
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            int clear = color & 0xFFFFFF;
            for (int i = 0; i < POINTS; i++) {
                int next = (i + 1) % POINTS;
                strip(vertices, i, next, -.5f, 0, clear, color);
                strip(vertices, i, next, 0, .85f, color, color);
                strip(vertices, i, next, .85f, 1.35f, color, clear);
            }
        }
    }
}
