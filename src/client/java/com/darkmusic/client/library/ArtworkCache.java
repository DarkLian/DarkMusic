package com.darkmusic.client.library;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * At most 32 256px textures (8 MiB), including cached missing-art results.
 */
public final class ArtworkCache implements AutoCloseable {
    private final LinkedHashMap<String, Identifier> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> pending = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-artwork").factory());
    private volatile boolean closed;
    private int serial;

    public static java.awt.image.BufferedImage fitArtwork(java.awt.image.BufferedImage original) {
        var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        double ratio = Math.min(256.0 / original.getWidth(), 256.0 / original.getHeight());
        int w = Math.max(1, (int) Math.round(original.getWidth() * ratio)), h = Math.max(1, (int) Math.round(original.getHeight() * ratio));
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, (256 - w) / 2, (256 - h) / 2, w, h, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public Identifier get(Track track) {
        if (track == null || closed) return null;
        String key = track.id() + ":" + track.size() + ":" + track.modified();
        if (cache.containsKey(key)) return cache.get(key);
        if (pending.size() >= 8) return null;
        if (pending.add(key)) worker.execute(() -> {
            int[] pixels = null;
            try {
                byte[] bytes = new MetadataReader().artwork(track.file());
                if (bytes != null) try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (readers.hasNext()) {
                        var reader = readers.next();
                        try {
                            reader.setInput(input);
                            int w = reader.getWidth(0), h = reader.getHeight(0);
                            if (w > 0 && h > 0 && (long) w * h <= 4_000_000) {
                                var original = reader.read(0);
                                var image = fitArtwork(original);
                                pixels = image.getRGB(0, 0, 256, 256, null, 0, 256);
                            }
                        } finally {
                            reader.dispose();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            int[] result = pixels;
            Minecraft.getInstance().execute(() -> {
                pending.remove(key);
                if (closed) return;
                Identifier id = null;
                if (result != null) {
                    var image = new NativeImage(256, 256, false);
                    for (int y = 0; y < 256; y++)
                        for (int x = 0; x < 256; x++) image.setPixel(x, y, result[y * 256 + x]);
                    id = Identifier.fromNamespaceAndPath("darkmusic", "artwork/" + serial++);
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "DarkMusic artwork", image));
                }
                cache.put(key, id);
                while (cache.size() > 32) {
                    var entry = cache.entrySet().iterator().next();
                    if (entry.getValue() != null) Minecraft.getInstance().getTextureManager().release(entry.getValue());
                    cache.remove(entry.getKey());
                }
            });
        });
        return null;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        cache.values().stream().filter(Objects::nonNull).forEach(Minecraft.getInstance().getTextureManager()::release);
        cache.clear();
        pending.clear();
    }
}
