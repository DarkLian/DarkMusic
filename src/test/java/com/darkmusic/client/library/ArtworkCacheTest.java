package com.darkmusic.client.library;

import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArtworkCacheTest {
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
