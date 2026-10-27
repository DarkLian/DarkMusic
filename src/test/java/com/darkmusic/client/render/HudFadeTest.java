package com.darkmusic.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HudFadeTest {
    /**
     * Records the colors written to it.
     */
    private static final class Colors implements VertexConsumer {
        final List<Integer> written = new ArrayList<>();

        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            written.add(a << 24 | r << 16 | g << 8 | b);
            return this;
        }

        public VertexConsumer setColor(int argb) {
            written.add(argb);
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private record Rectangle(int color) implements GuiElementRenderState {
        public void buildVertices(VertexConsumer v) {
            v.addVertex(0, 0, 0).setColor(color);
            v.addVertex(1, 0, 0).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24);
        }

        public RenderPipeline pipeline() {
            return null;
        }

        public TextureSetup textureSetup() {
            return null;
        }

        public ScreenRectangle scissorArea() {
            return null;
        }

        public ScreenRectangle bounds() {
            return null;
        }
    }

    @Test
    void elementsAddedDuringAFadeKeepTheirRgbAndScaleAlphaWhileOthersAreUntouched() {
        var plain = new Rectangle(0xFF336699);
        assertSame(plain, HudFade.element(plain), "Outside a fade nothing is wrapped");
        assertEquals(0xFF336699, HudFade.color(0xFF336699));
        var faded = new GuiElementRenderState[1];
        HudFade.run(.5, () -> {
            assertTrue(HudFade.active());
            faded[0] = HudFade.element(plain);
            assertEquals(0x80336699, HudFade.color(0xFF336699) & 0xFFFFFFFF);
            // Nested fades multiply.
            HudFade.run(.5, () -> assertEquals(0x40336699, HudFade.color(0xFF336699) & 0xFFFFFFFF));
        });
        assertFalse(HudFade.active(), "The fade ends with the drawing");
        var colors = new Colors();
        faded[0].buildVertices(colors);
        assertEquals(List.of(0x80336699, 0x80336699), colors.written.stream().map(c -> c & 0xFFFFFFFF).toList());
    }

    @Test
    void visibleTextNeverReachesZeroAlphaAndNearlyInvisibleFadesDrawNothing() {
        HudFade.run(.02, () -> assertTrue((HudFade.color(0x40FFFFFF) >>> 24) >= 4, "Zero alpha text would draw opaque"));
        boolean[] drawn = {false};
        HudFade.run(.005, () -> drawn[0] = true);
        assertFalse(drawn[0]);
        HudFade.run(1, () -> drawn[0] = true);
        assertTrue(drawn[0]);
    }
}
