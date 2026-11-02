package com.darkmusic.client.pip;

import com.darkmusic.client.config.PipSettings;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Render-thread-only texture ownership; no pipe reads, decoding, or tick-rate throttle.
 */
public final class PipTexture implements AutoCloseable {
    private final Identifier id;
    private AbstractTexture texture;
    private PipFrame current;
    private long uploaded;

    public PipTexture(Identifier id) {
        this.id = id;
    }

    public void update(PipFrames frames) {
        try (var lease = frames.latest()) {
            if (lease == null) return;
            var frame = lease.frame();
            boolean resized = texture == null || current == null || current.width() != frame.width() || current.height() != frame.height();
            if (resized) {
                texture = new VideoTexture(frame.width(), frame.height());
                Minecraft.getInstance().getTextureManager().register(id, texture);
            }
            long uploadStart = System.nanoTime();
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), lease.pixels(),
                0, 0, 0, 0, frame.width(), frame.height());
            long uploadEnd = System.nanoTime();
            PipTrace.event("upload_submit", frame, uploadEnd, uploadEnd - uploadStart);
            current = frame;
            uploaded++;
        }
    }

    public void draw(GuiGraphicsExtractor g, PipSettings.Bounds b, PipMapping m, int color, boolean exact) {
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(exact ? FilterMode.NEAREST : FilterMode.LINEAR);
        int x0 = b.x() + (int) Math.round(b.width() * m.left()), y0 = b.y() + (int) Math.round(b.height() * m.top());
        int x1 = b.x() + (int) Math.round(b.width() * m.right()), y1 = b.y() + (int) Math.round(b.height() * m.bottom());
        g.guiRenderState.addGuiElement(new BlitRenderState(RenderPipelines.GUI_TEXTURED,
            TextureSetup.singleTexture(texture.getTextureView(), sampler), new Matrix3x2f(g.pose()),
            x0, y0, x1, y1, m.u0(), m.u1(), m.v0(), m.v1(), color, g.scissorStack.peek()));
    }

    private static final class VideoTexture extends AbstractTexture {
        private VideoTexture(int width, int height) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture("DarkMusic PiP", 5, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            textureView = device.createTextureView(texture);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    public Identifier id() {
        return id;
    }

    public PipFrame current() {
        return current;
    }

    public long uploaded() {
        return uploaded;
    }

    @Override
    public void close() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(id);
        texture = null;
        current = null;
    }
}
