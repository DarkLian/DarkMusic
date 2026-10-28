package com.darkmusic.client.pip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Render-thread-only texture ownership; no pipe reads, decoding, or tick-rate throttle.
 */
public final class PipTexture implements AutoCloseable {
    private final Identifier id;
    private DynamicTexture texture;
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
            texture.getPixels().getPixelBytes().clear().put(lease.pixels());
            texture.upload();
            long uploadEnd = System.nanoTime();
            PipTrace.event("upload_submit", frame, uploadEnd, uploadEnd - uploadStart);
            current = frame;
            uploaded++;
        }
    }

    private static final class VideoTexture extends DynamicTexture {
        private VideoTexture(int width, int height) {
            super("DarkMusic PiP", width, height, false);
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
