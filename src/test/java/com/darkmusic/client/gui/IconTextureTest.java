package com.darkmusic.client.gui;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class IconTextureTest {
    private static final String ATLAS = "/assets/darkmusic/textures/gui/icons.png";

    @Test
    void iconAtlasUsesSmoothSamplingWithoutChangingOtherTextures() throws Exception {
        try (var input = getClass().getResourceAsStream(ATLAS + ".mcmeta")) {
            assertNotNull(input, "Icon downscaling must not fall back to nearest-neighbor sampling");
            var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var metadata = TextureMetadataSection.CODEC.parse(JsonOps.INSTANCE, root.get("texture")).getOrThrow();
            assertTrue(metadata.blur(), "Linear minification and magnification prevent asymmetric icon edges");
            assertTrue(metadata.clamp());
        }
    }

    @Test
    void atlasCellsHaveTransparentGuttersForFilteredSampling() throws Exception {
        try (var input = getClass().getResourceAsStream(ATLAS)) {
            assertNotNull(input);
            var image = ImageIO.read(input);
            assertEquals(UiIcon.ATLAS_WIDTH, image.getWidth());
            assertEquals(UiIcon.CELL, image.getHeight());
            for (var icon : UiIcon.values()) {
                int offset = icon.ordinal() * UiIcon.CELL;
                for (int p = 0; p < UiIcon.CELL; p++) {
                    assertEquals(0, image.getRGB(offset, p) >>> 24, icon + " left gutter");
                    assertEquals(0, image.getRGB(offset + UiIcon.CELL - 1, p) >>> 24, icon + " right gutter");
                    assertEquals(0, image.getRGB(offset + p, 0) >>> 24, icon + " top gutter");
                    assertEquals(0, image.getRGB(offset + p, UiIcon.CELL - 1) >>> 24, icon + " bottom gutter");
                }
            }
        }
    }
}
