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
 * Native-resolution covers up to 1024px; at most 32 entries and 32 MiB of GPU image data.
 */
public final class ArtworkCache implements AutoCloseable {
    public static final int MAX_SIZE = 1024;
    private final LinkedHashMap<String, Cover> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> pending = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-artwork").factory());
    private int cachedBytes;
    private volatile boolean closed;
    private int serial;

    public static java.awt.image.BufferedImage fitArtwork(java.awt.image.BufferedImage original) {
        int size = Math.min(MAX_SIZE, Math.max(original.getWidth(), original.getHeight()));
        var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        double ratio = Math.min((double) size / original.getWidth(), (double) size / original.getHeight());
        int w = Math.max(1, (int) Math.round(original.getWidth() * ratio)), h = Math.max(1, (int) Math.round(original.getHeight() * ratio));
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(original, (size - w) / 2, (size - h) / 2, w, h, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g, Cover cover, int x, int y, int size) {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, cover.id(), x, y, 0, 0, size, size, cover.size(), cover.size(), cover.size(), cover.size());
    }

    public Cover get(Track track) {
        if (track == null || closed) return null;
        String key = track.id() + ":" + track.size() + ":" + track.modified();
        if (cache.containsKey(key)) return cache.get(key);
        if (pending.size() >= 8) return null;
        if (pending.add(key)) worker.execute(() -> {
            int[] pixels = null;
            int dimension = 0;
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
                                dimension = image.getWidth();
                                pixels = image.getRGB(0, 0, dimension, dimension, null, 0, dimension);
                            }
                        } finally {
                            reader.dispose();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            int size = dimension;
            NativeImage prepared = pixels == null ? null : new NativeImage(size, size, false);
            if (prepared != null) for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) prepared.setPixel(x, y, pixels[y * size + x]);
            if (closed) {
                if (prepared != null) prepared.close();
                return;
            }
            Minecraft.getInstance().execute(() -> {
                pending.remove(key);
                if (closed) {
                    if (prepared != null) prepared.close();
                    return;
                }
                Identifier id = null;
                if (prepared != null) {
                    id = Identifier.fromNamespaceAndPath("darkmusic", "artwork/" + serial++);
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "DarkMusic artwork", prepared));
                }
                cache.put(key, id == null ? null : new Cover(id, size));
                if (id != null) cachedBytes += size * size * 4;
                while (cache.size() > 32 || cachedBytes > 32 * 1024 * 1024) {
                    var entry = cache.entrySet().iterator().next();
                    if (entry.getValue() != null) {
                        cachedBytes -= entry.getValue().size() * entry.getValue().size() * 4;
                        Minecraft.getInstance().getTextureManager().release(entry.getValue().id());
                    }
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
        cache.values().stream().filter(Objects::nonNull).map(Cover::id).forEach(Minecraft.getInstance().getTextureManager()::release);
        cachedBytes = 0;
        cache.clear();
        pending.clear();
    }

    public record Cover(Identifier id, int size) {
    }
}
