package com.darkmusic.client.library;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native-resolution covers up to 1024px; viewport-aware entry count and at most 32 MiB of GPU image data.
 */
public final class ArtworkCache implements AutoCloseable {
    public static final int MAX_SIZE = 1024;
    private final LinkedHashMap<String, Cover> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<String> pending = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-artwork").factory());
    private int cachedBytes;
    private volatile boolean closed;
    private int serial;
    private int entryLimit = 32;

    public void visibleRows(int rows) {
        entryLimit = Math.max(32, rows + 4);
    }

    /**
     * Omit only symmetric, uniformly black edge bands embedded in the source cover.
     */
    public static BufferedImage trimLetterbox(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int top = 0, bottom = 0, left = 0, right = 0;
        while (top < height / 4 && blackLine(image, top, true)) top++;
        while (bottom < height / 4 && blackLine(image, height - bottom - 1, true)) bottom++;
        if (top == 0 || bottom == 0 || Math.abs(top - bottom) > 2 || top == height / 4 || bottom == height / 4)
            top = bottom = 0;
        while (left < width / 4 && blackLine(image, left, false)) left++;
        while (right < width / 4 && blackLine(image, width - right - 1, false)) right++;
        if (left == 0 || right == 0 || Math.abs(left - right) > 2 || left == width / 4 || right == width / 4)
            left = right = 0;
        return top + bottom + left + right == 0 ? image : image.getSubimage(left, top, width - left - right, height - top - bottom);
    }

    private static boolean blackLine(BufferedImage image, int line, boolean horizontal) {
        int count = horizontal ? image.getWidth() : image.getHeight();
        for (int index = 0; index < count; index++) {
            int pixel = image.getRGB(horizontal ? index : line, horizontal ? line : index);
            if ((pixel >>> 24) != 255 || (pixel >> 16 & 255) > 12 || (pixel >> 8 & 255) > 12 || (pixel & 255) > 12)
                return false;
        }
        return true;
    }

    public static BufferedImage fitArtwork(BufferedImage original) {
        var fit = fit(original.getWidth(), original.getHeight());
        int size = fit.size();
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(original, (size - fit.width()) / 2, (size - fit.height()) / 2, fit.width(), fit.height(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static Fit fit(int width, int height) {
        int size = Math.min(MAX_SIZE, Math.max(width, height));
        double ratio = Math.min((double) size / width, (double) size / height);
        return new Fit(size, Math.max(1, (int) Math.round(width * ratio)), Math.max(1, (int) Math.round(height * ratio)));
    }

    public record Fit(int size, int width, int height) {
    }

    public static void draw(GuiGraphicsExtractor g, Cover cover, int x, int y, int size) {
        g.blit(RenderPipelines.GUI_TEXTURED, cover.id(), x, y, 0, 0, size, size, cover.size(), cover.size(), cover.size(), cover.size());
    }

    /**
     * Only equal, uniform pillarboxes around a square cover are omitted in menus.
     */
    public static int menuInset(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        if (width <= height || (width - height) % 2 != 0) return 0;
        int inset = (width - height) / 2;
        int matte = image.getRGB(0, 0);
        if ((matte >>> 24) != 255) return 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < inset; x++)
                if (!sameMatte(matte, image.getRGB(x, y), x >= inset - 4 ? 48 : 12) || !sameMatte(matte, image.getRGB(width - 1 - x, y), x >= inset - 4 ? 48 : 12))
                    return 0;
        return inset;
    }

    private static boolean sameMatte(int first, int next, int tolerance) {
        return (next >>> 24) == 255 && Math.abs((first >> 16 & 255) - (next >> 16 & 255)) <= tolerance
            && Math.abs((first >> 8 & 255) - (next >> 8 & 255)) <= tolerance && Math.abs((first & 255) - (next & 255)) <= tolerance;
    }

    public static void drawMenu(GuiGraphicsExtractor g, Cover cover, int x, int y, int size) {
        drawMenu(g, cover, x, y, size, size);
    }

    public static void drawMenu(GuiGraphicsExtractor g, Cover cover, int x, int y, int maxWidth, int maxHeight) {
        int sourceWidth = cover.contentWidth() - 2 * cover.menuInset();
        double ratio = Math.min((double) maxWidth / sourceWidth, (double) maxHeight / cover.contentHeight());
        int width = Math.max(1, (int) Math.round(sourceWidth * ratio));
        int height = Math.max(1, (int) Math.round(cover.contentHeight() * ratio));
        g.blit(RenderPipelines.GUI_TEXTURED, cover.id(), x + (maxWidth - width) / 2, y + (maxHeight - height) / 2,
            (cover.size() - cover.contentWidth()) / 2 + cover.menuInset(), (cover.size() - cover.contentHeight()) / 2,
            width, height, sourceWidth, cover.contentHeight(), cover.size(), cover.size());
    }

    public Cover get(Track track) {
        return get(track, MAX_SIZE);
    }

    /**
     * Row-sized images share the same worker, eviction policy and total memory cap.
     */
    public Cover thumbnail(Track track) {
        return get(track, 128);
    }

    private Cover get(Track track, int limit) {
        if (track == null || closed) return null;
        String key = track.id() + ":" + track.size() + ":" + track.modified() + ":" + limit;
        if (cache.containsKey(key)) return cache.get(key);
        if (pending.size() >= 8) return null;
        if (pending.add(key)) worker.execute(() -> {
            NativeImage owned = null;
            try {
                int[] pixels = null;
                int dimension = 0, contentWidth = 0, contentHeight = 0, inset = 0;
                try {
                    byte[] bytes = new MetadataReader().artwork(track.file());
                    if (bytes != null)
                        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                            var readers = ImageIO.getImageReaders(input);
                            if (readers.hasNext()) {
                                var reader = readers.next();
                                try {
                                    reader.setInput(input);
                                    int w = reader.getWidth(0), h = reader.getHeight(0);
                                    if (w > 0 && h > 0 && w <= 32_768 && h <= 32_768 && (long) w * h <= 268_435_456) {
                                        var parameters = reader.getDefaultReadParam();
                                        int step = Math.max(1, (Math.max(w, h) + limit - 1) / limit);
                                        parameters.setSourceSubsampling(step, step, 0, 0);
                                        var original = trimLetterbox(reader.read(0, parameters));
                                        var fit = fit(original.getWidth(), original.getHeight());
                                        contentWidth = fit.width();
                                        contentHeight = fit.height();
                                        inset = (int) Math.round(menuInset(original) * (double) fit.width() / original.getWidth());
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
                int imageWidth = contentWidth, imageHeight = contentHeight, menuInset = inset;
                NativeImage prepared = pixels == null ? null : new NativeImage(size, size, false);
                owned = prepared;
                if (prepared != null) for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++) prepared.setPixel(x, y, pixels[y * size + x]);
                if (closed) {
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
                        try {
                            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "DarkMusic artwork", prepared));
                        } catch (RuntimeException failure) {
                            prepared.close();
                            LoggerFactory.getLogger("darkmusic").warn("Artwork texture registration failed", failure);
                            cache.put(key, null);
                            return;
                        }
                    }
                    cache.put(key, id == null ? null : new Cover(id, size, imageWidth, imageHeight, menuInset));
                    if (id != null) cachedBytes += size * size * 4;
                    while (cache.size() > entryLimit || cachedBytes > 32 * 1024 * 1024) {
                        var entry = cache.entrySet().iterator().next();
                        if (entry.getValue() != null) {
                            cachedBytes -= entry.getValue().size() * entry.getValue().size() * 4;
                            Minecraft.getInstance().getTextureManager().release(entry.getValue().id());
                        }
                        cache.remove(entry.getKey());
                    }
                });
                owned = null; // The render-thread callback now owns the native image.
            } finally {
                if (owned != null) owned.close();
                Minecraft.getInstance().execute(() -> pending.remove(key));
            }
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

    public record Cover(Identifier id, int size, int contentWidth, int contentHeight, int menuInset) {
        public Cover(Identifier id, int size, int contentWidth, int contentHeight) {
            this(id, size, contentWidth, contentHeight, 0);
        }

        public Cover(Identifier id, int size) {
            this(id, size, size, size);
        }
    }
}
