package com.darkmusic.client.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/**
 * Menu surfaces only. HUD geometry and spectrum colors remain independent.
 */
public final class UiTheme {
    public static final int BACKGROUND = 0xFF111420, SURFACE = 0xFF191D2C, RAISED = 0xFF252A3D;
    public static final int HOVER = 0xFF303750, SELECTED = 0xFF48446D, BORDER = 0xFF3A4059;
    public static final int ACCENT = 0xFFB8A8FA, TEXT = 0xFFF0EFF9, SECONDARY = 0xFFB0B5CE;
    public static final int MUTED = 0xFF868DA7, SUCCESS = 0xFF66DDB8, WARNING = 0xFFEBC37C, ERROR = 0xFFEF8798;

    private UiTheme() {
    }

    public static int mix(int from, int to, float fraction) {
        float t = Math.clamp(fraction, 0, 1);
        int r = Math.round((from >> 16 & 255) * (1 - t) + (to >> 16 & 255) * t);
        int g = Math.round((from >> 8 & 255) * (1 - t) + (to >> 8 & 255) * t);
        int b = Math.round((from & 255) * (1 - t) + (to & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public static void background(GuiGraphicsExtractor g, int width, int height) {
        g.fill(0, 0, width, height, BACKGROUND);
        g.fill(0, 0, width, 28, SURFACE);
        g.fill(12, 27, width - 12, 28, BORDER);
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        // Fixed chamfer geometry: never subdivide surfaces into pixel rows.
        g.fill(x + 3, y, x + width - 3, y + height, SURFACE);
        g.fill(x, y + 3, x + width, y + height - 3, SURFACE);
        g.fill(x + 3, y, x + width - 3, y + 1, BORDER);
        g.fill(x + 3, y + height - 1, x + width - 3, y + height, BORDER);
        g.fill(x, y + 3, x + 1, y + height - 3, BORDER);
        g.fill(x + width - 1, y + 3, x + width, y + height - 3, BORDER);
        g.fill(x + 1, y + 1, x + 3, y + 3, BORDER);
        g.fill(x + width - 3, y + 1, x + width - 1, y + 3, BORDER);
        g.fill(x + 1, y + height - 3, x + 3, y + height - 1, BORDER);
        g.fill(x + width - 3, y + height - 3, x + width - 1, y + height - 1, BORDER);
    }

    public static void section(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, int x, int y, int width) {
        g.text(font, text, x + 2, y + 3, ACCENT);
        g.fill(x, y + 17, x + width, y + 18, BORDER);
    }

    public static void header(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component title, UiIcon icon) {
        icon.draw(g, 12, 10, 12, ACCENT);
        g.text(font, title, 30, 12, TEXT);
    }

    /**
     * One quad, not one fill per pixel or gradient sample.
     */
    public static void gradient(GuiGraphicsExtractor g, int x, int y, int width, int height, int first, int last) {
        if (width <= 0 || height <= 0) return;
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds != null)
            g.guiRenderState.addGuiElement(new Gradient(pose, clip, bounds, x, y, width, height, first, last));
    }

    private record Gradient(Matrix3x2f pose, ScreenRectangle scissorArea, ScreenRectangle bounds,
                            int x, int y, int width, int height, int first, int last) implements GuiElementRenderState {
        @Override
        public com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            vertices.addVertexWith2DPose(pose, x, y).setColor(first);
            vertices.addVertexWith2DPose(pose, x, y + height).setColor(first);
            vertices.addVertexWith2DPose(pose, x + width, y + height).setColor(last);
            vertices.addVertexWith2DPose(pose, x + width, y).setColor(last);
        }
    }
}
