package com.darkmusic.client.library;

import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ArtworkCacheTest {
    @Test
    void decodedRealArtworkKeepsBoundsAndUnavailableArtworkRemainsEmpty() throws Exception {
        var file = TestMusic.featured();
        var track = new MetadataReader().read("real", file.getFileName().toString(), file,
            Files.size(file), Files.getLastModifiedTime(file).toMillis());
        for (int limit : new int[]{128, ArtworkCache.MAX_SIZE}) {
            var image = ArtworkCache.readArtwork(track, limit);
            assertNotNull(image.values());
            assertTrue(image.dimension() <= limit);
            assertEquals(image.dimension() * image.dimension(), image.values().length);
            assertTrue(image.width() <= image.dimension());
            assertTrue(image.height() <= image.dimension());
        }
        var absent = new Track("r", "absent", file.resolveSibling("absent-artwork-fixture"), "", "", "", 1, 1, 1, "");
        assertNull(ArtworkCache.readArtwork(absent, 128).values());
    }

    @Test
    void realEmbeddedArtworkIsLetterboxedWithoutStretching() throws Exception {
        byte[] bytes = new MetadataReader().artwork(TestMusic.featured());
        var original = ImageIO.read(new ByteArrayInputStream(bytes));
        var image = ArtworkCache.fitArtwork(original);
        assertEquals(Math.min(ArtworkCache.MAX_SIZE, Math.max(original.getWidth(), original.getHeight())), image.getWidth());
        assertEquals(image.getWidth(), image.getHeight());
        assertTrue((image.getRGB(image.getWidth() / 2, image.getHeight() / 2) >>> 24) > 0);
        if (original.getWidth() != original.getHeight()) assertEquals(0, image.getRGB(0, 0));
    }
}
