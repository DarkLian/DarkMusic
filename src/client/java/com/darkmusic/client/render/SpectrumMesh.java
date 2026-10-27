package com.darkmusic.client.render;

import com.darkmusic.client.config.Settings;
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
public final class SpectrumMesh {
    // Sized for the densest default HUD; grows once if a shaped, rounded, trailed spectrum needs more.
    private static final int MAX_RECTANGLES = 26_214;
    private int[] rectangles = new int[16384];
    private int[] bottomColors = new int[3277];
    private boolean hasGradient;
    private double opacity = 1;
    private int count, width, hudHeight;
    private Settings.HudShape shape;

    public void begin(int width, int hudHeight, Settings.HudShape shape) {
        begin(width, hudHeight, shape, 1);
    }

    public void begin(int width, int hudHeight, Settings.HudShape shape, double opacity) {
        this.opacity = opacity;
        count = 0;
        hasGradient = false;
        this.width = width;
        this.hudHeight = hudHeight;
        this.shape = shape;
    }

    public void fill(int left, int top, int right, int bottom, int color) {
        gradient(left, top, right, bottom, color, color);
    }

    /**
     * Rounded caps reuse the bounded batch and the existing HUD shape clipping.
     */
    void rounded(int left, int top, int right, int bottom, int color, Settings.SurfaceFinish finish) {
        rounded(left, top, right, bottom, color, finish, false);
    }

    /**
     * Beveled bodies match square Bars: a slightly darker face, with lit and shaded side columns when wide enough.
     */
    void rounded(int left, int top, int right, int bottom, int color, Settings.SurfaceFinish finish, boolean sides) {
        if (right <= left || bottom <= top) return;
        float radius = Math.min(3, Math.min(right - left, bottom - top) / 2f);
        int rows = (int) Math.ceil(radius);
        for (int row = 0; row < rows; row++) {
            double dy = Math.max(0, radius - row - .5);
            double inset = radius - Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int trim = (int) Math.ceil(inset);
            float edge = (float) (trim - inset);
            int upper = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(color, .24 * (1 - row / Math.max(1f, radius))) : color;
            int lower = finish == Settings.SurfaceFinish.BEVELED ? Bevel.shade(color, -.20 * (1 - row / Math.max(1f, radius))) : color;
            roundedRow(left, top + row, right, trim, edge, upper);
            if (bottom - row - 1 != top + row) roundedRow(left, bottom - row - 1, right, trim, edge, lower);
        }
        if (finish != Settings.SurfaceFinish.BEVELED) {
            gradient(left, top + rows, right, bottom - rows, color, color);
            return;
        }
        int face = Bevel.shade(color, -.08);
        if (sides && right - left >= 3) {
            fill(left, top + rows, left + 1, bottom - rows, Bevel.shade(color, .16));
            fill(left + 1, top + rows, right - 1, bottom - rows, face);
            fill(right - 1, top + rows, right, bottom - rows, Bevel.shade(color, -.25));
        } else fill(left, top + rows, right, bottom - rows, face);
    }

    /**
     * Waves and spikes have no side edges, so light follows the curve's slope (rise per pixel) instead: rising
     * edges face the upper-left light that Bars use, falling edges turn away from it. Lines keep no dark bottom
     * row, which would cancel their highlight at two or three pixels thick.
     */
    void contour(int left, int top, int right, int bottom, int color, double slope, boolean filled, Settings.SurfaceFinish finish) {
        if (finish != Settings.SurfaceFinish.BEVELED || bottom - top < 2) {
            fill(left, top, right, bottom, color);
            return;
        }
        double tilt = slope / Math.sqrt(1 + slope * slope);
        fill(left, top, right, top + 1, Bevel.shade(color, .30 + .16 * tilt));
        if (filled) {
            gradient(left, top + 1, right, bottom - 1, Bevel.shade(color, .12 + .12 * tilt), Bevel.shade(color, -.24 + .06 * tilt));
            fill(left, bottom - 1, right, bottom, Bevel.shade(color, -.30));
        } else
            gradient(left, top + 1, right, bottom, Bevel.shade(color, -.02 + .28 * tilt), Bevel.shade(color, -.16 + .28 * tilt));
    }

    private void roundedRow(int left, int y, int right, int trim, float coverage, int color) {
        fill(left + trim, y, right - trim, y + 1, color);
        if (trim > 0 && coverage > 0) {
            int edge = color & 0xffffff | Math.round((color >>> 24) * coverage) << 24;
            fill(left + trim - 1, y, left + trim, y + 1, edge);
            if (right - trim != left + trim - 1) fill(right - trim, y, right - trim + 1, y + 1, edge);
        }
    }

    public void gradient(int left, int top, int right, int bottom, int topColor, int bottomColor) {
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
        topColor = SpectrumAppearance.opacity(topColor, opacity);
        bottomColor = SpectrumAppearance.opacity(bottomColor, opacity);
        if (count + 5 > rectangles.length) {
            // Drop excess detail rather than throw from HUD rendering, which would end the game session.
            if (rectangles.length / 5 >= MAX_RECTANGLES) return;
            rectangles = java.util.Arrays.copyOf(rectangles, Math.min(rectangles.length * 2, MAX_RECTANGLES * 5));
            bottomColors = java.util.Arrays.copyOf(bottomColors, rectangles.length / 5 + 1);
        }
        bottomColors[count / 5] = bottomColor;
        hasGradient |= topColor != bottomColor;
        rectangles[count++] = left;
        rectangles[count++] = top;
        rectangles[count++] = right;
        rectangles[count++] = bottom;
        rectangles[count++] = topColor;
    }

    public void submit(GuiGraphicsExtractor g) {
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
