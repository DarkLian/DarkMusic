package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
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
    public static int BACKGROUND = 0xFF111420, SURFACE = 0xFF191D2C, RAISED = 0xFF252A3D;
    public static int HOVER = 0xFF303750, SELECTED = 0xFF48446D, BORDER = 0xFF3A4059;
    public static int ACCENT = 0xFFB8A8FA, TEXT = 0xFFF0EFF9, SECONDARY = 0xFFB0B5CE;
    public static int MUTED = 0xFF868DA7;
    public static int SUCCESS = 0xFF66DDB8, WARNING = 0xFFEBC37C, ERROR = 0xFFEF8798;

    public static void apply(Settings settings) {
        int base = settings.menuBackgroundColor | 0xFF000000;
        ACCENT = settings.menuAccentColor | 0xFF000000;
        BACKGROUND = base;
        if (settings.menuBackgroundColor == 0x111420) {
            SURFACE = 0xFF191D2C;
            RAISED = 0xFF252A3D;
            HOVER = 0xFF303750;
            BORDER = 0xFF3A4059;
            TEXT = 0xFFF0EFF9;
            SECONDARY = 0xFFB0B5CE;
            MUTED = 0xFF868DA7;
        } else {
            boolean light = contrast(base, 0xFF000000) >= contrast(base, 0xFFFFFFFF);
            int ink = light ? 0xFF000000 : 0xFFFFFFFF;
            SURFACE = mix(base, ink, .05f);
            RAISED = mix(base, ink, .10f);
            HOVER = mix(base, ACCENT, .22f);
            int opposite = light ? 0xFFFFFFFF : 0xFF000000;
            SURFACE = readableAccent(SURFACE, ink, opposite);
            RAISED = readableAccent(RAISED, ink, opposite);
            HOVER = readableAccent(HOVER, ink, opposite);
            BORDER = mix(base, ink, .24f);
            TEXT = ink;
            SECONDARY = mix(base, ink, .75f);
            int worst = SURFACE;
            if (light && luminance(RAISED) < luminance(SURFACE) || !light && luminance(RAISED) > luminance(SURFACE))
                worst = RAISED;
            SECONDARY = readableAccent(SECONDARY, worst, ink);
            MUTED = mix(base, ink, .55f);
        }
        SELECTED = settings.menuBackgroundColor == 0x111420 && settings.menuAccentColor == 0xB8A8FA
            ? 0xFF48446D : mix(SURFACE, ACCENT, .30f);
        ACCENT = readableAccent(ACCENT, SURFACE, TEXT);
        UiStatus.refresh();
        SUCCESS = UiStatus.color(UiStatus.Tone.SUCCESS);
        WARNING = UiStatus.color(UiStatus.Tone.WARNING);
        ERROR = UiStatus.color(UiStatus.Tone.ERROR);
    }

    private UiTheme() {
    }

    public static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    private static double luminance(int color) {
        double value = 0;
        double[] weights = {.2126, .7152, .0722};
        for (int i = 0; i < 3; i++) {
            double channel = (color >> (16 - 8 * i) & 255) / 255d;
            value += weights[i] * (channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4));
        }
        return value;
    }

    public static int readableAccent(int accent, int background, int text) {
        if (contrast(accent, background) >= 4.5) return accent;
        for (int step = 1; step <= 16; step++) {
            int candidate = mix(accent, text, step / 16f);
            if (contrast(candidate, background) >= 4.5) return candidate;
        }
        return text;
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
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        int right = screen == null ? 1000 : screen.width - 12;
        if (screen != null) for (var child : screen.children())
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible
                && widget.getY() < 28 && widget.getX() >= 30) right = Math.min(right, widget.getX() - 6);
        g.text(font, HorizontalText.ellipsize(font, title.getString(), Math.max(0, right - 30)), 30, 12, TEXT);
    }

    /**
     * Red-tinted fill for destructive controls; derived from the theme so custom light menus keep contrast.
     */
    public static int destructiveFill(boolean highlighted) {
        if (BACKGROUND == 0xFF111420) return highlighted ? 0xFF483040 : 0xFF30232F;
        return mix(highlighted ? HOVER : RAISED, ERROR, highlighted ? .22f : .12f);
    }

    /**
     * The same box and check glyph as setting rows, for list selection columns.
     */
    public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, boolean checked, boolean active) {
        g.outline(x, y, size, size, active ? ACCENT : MUTED);
        if (checked) UiIcon.CHECK.draw(g, x + 1, y + 1, size - 2, active ? TEXT : MUTED);
    }

    /**
     * Signals rows clipped by a scrolling viewport without hiding the scrollbar.
     */
    public static void edgeFades(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean above, boolean below, int surface) {
        int size = Math.min(12, height / 4), clear = surface & 0x00FFFFFF;
        if (above) verticalGradient(g, x, y, width, size, surface, clear);
        if (below) verticalGradient(g, x, y + height - size, width, size, clear, surface);
    }

    /**
     * One quad, not one fill per pixel or gradient sample.
     */
    public static void gradient(GuiGraphicsExtractor g, int x, int y, int width, int height, int first, int last) {
        gradient(g, x, y, width, height, first, last, false);
    }

    public static void verticalGradient(GuiGraphicsExtractor g, int x, int y, int width, int height, int top, int bottom) {
        gradient(g, x, y, width, height, top, bottom, true);
    }

    private static void gradient(GuiGraphicsExtractor g, int x, int y, int width, int height, int first, int last, boolean vertical) {
        if (width <= 0 || height <= 0) return;
        var pose = new Matrix3x2f(g.pose());
        var clip = g.scissorStack.peek();
        var bounds = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
        if (clip != null) bounds = clip.intersection(bounds);
        if (bounds == null) return;
        if (vertical)
            g.guiRenderState.addGuiElement(new VerticalGradient(pose, clip, bounds, x, y, width, height, first, last));
        else g.guiRenderState.addGuiElement(new Gradient(pose, clip, bounds, x, y, width, height, first, last));
    }

    private record VerticalGradient(Matrix3x2f pose, ScreenRectangle scissorArea, ScreenRectangle bounds,
                                    int x, int y, int width, int height, int top,
                                    int bottom) implements GuiElementRenderState {
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
            vertices.addVertexWith2DPose(pose, x, y).setColor(top);
            vertices.addVertexWith2DPose(pose, x, y + height).setColor(bottom);
            vertices.addVertexWith2DPose(pose, x + width, y + height).setColor(bottom);
            vertices.addVertexWith2DPose(pose, x + width, y).setColor(top);
        }
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
