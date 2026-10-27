package com.darkmusic.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * Fades everything DarkMusic draws inside {@link #run}: rectangles, images, text and spectrum meshes. Minecraft's GUI
 * has no global transparency, so a mixin on its render state passes each element added meanwhile through here.
 * Render thread only.
 */
public final class HudFade {
    private static float alpha = 1;

    private HudFade() {
    }

    /**
     * Draws with every color's alpha scaled by {@code amount}; nothing is drawn below one percent.
     */
    public static void run(double amount, Runnable draw) {
        float value = (float) Math.clamp(amount, 0, 1);
        if (value < .01f) return;
        if (value >= 1) {
            draw.run();
            return;
        }
        float previous = alpha;
        alpha = previous * value;
        try {
            draw.run();
        } finally {
            alpha = previous;
        }
    }

    public static boolean active() {
        return alpha < 1;
    }

    public static int color(int argb) {
        if (alpha >= 1) return argb;
        // Text treats a zero alpha as opaque, so the faintest visible step stays above it.
        return (argb & 0xFFFFFF) | Math.max(4, Math.round((argb >>> 24) * alpha)) << 24;
    }

    public static GuiElementRenderState element(GuiElementRenderState element) {
        return alpha >= 1 || element instanceof Faded ? element : new Faded(element, alpha);
    }

    public static BlitRenderState blit(BlitRenderState blit) {
        if (alpha >= 1) return blit;
        return new BlitRenderState(blit.pipeline(), blit.textureSetup(), blit.pose(), blit.x0(), blit.y0(), blit.x1(), blit.y1(),
            blit.u0(), blit.u1(), blit.v0(), blit.v1(), color(blit.color()), blit.scissorArea(), blit.bounds());
    }

    private record Faded(GuiElementRenderState element, float alpha) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer vertices) {
            element.buildVertices(new Scaled(vertices, alpha));
        }

        @Override
        public RenderPipeline pipeline() {
            return element.pipeline();
        }

        @Override
        public TextureSetup textureSetup() {
            return element.textureSetup();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return element.scissorArea();
        }

        @Override
        public ScreenRectangle bounds() {
            return element.bounds();
        }
    }

    /**
     * Passes vertices through, scaling the alpha of each color.
     */
    record Scaled(VertexConsumer target, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            target.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int a) {
            target.setColor(red, green, blue, Math.round(a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            target.setColor((argb & 0xFFFFFF) | Math.round((argb >>> 24) * alpha) << 24);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            target.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            target.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            target.setLineWidth(width);
            return this;
        }
    }
}
