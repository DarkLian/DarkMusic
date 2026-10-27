package com.darkmusic.client.render;

import com.darkmusic.client.config.*;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WaveformMeshTest {
    @Test
    void smoothAndRoundedStylesKeepFiniteBoundsOpacityAndPlayedBoundary() {
        for (var style : WaveformSettings.Style.values()) {
            var options = new WaveformSettings();
            options.style = style;
            options.opacity = .5;
            float[] heights = new float[33];
            for (int i = 0; i < heights.length; i++) heights[i] = .5f + (i % 8);
            var count = new AtomicInteger();
            var played = new AtomicInteger();
            var unplayed = new AtomicInteger();
            float[] lastX = new float[1];
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                if (method.getName().equals("addVertex")) {
                    float x = (float) args[0], y = (float) args[1];
                    assertTrue(Float.isFinite(x) && Float.isFinite(y));
                    assertTrue(x >= -.66 && x <= 100.66);
                    assertTrue(y >= 1.84 && y <= 18.16);
                    lastX[0] = x;
                    count.incrementAndGet();
                }
                if (method.getName().equals("setColor")) {
                    int color = (int) args[0];
                    assertTrue((color >>> 24) <= 128, "Opacity applies to every face and antialiased edge");
                    if (color == 0x8011ee66) {
                        assertTrue(lastX[0] <= 37.5 + (style == WaveformSettings.Style.BARS ? 3.7 : .001));
                        played.incrementAndGet();
                    }
                    if (color == 0x80555d78) {
                        assertTrue(lastX[0] >= 37.5 - (style == WaveformSettings.Style.BARS ? 3.7 : .001));
                        unplayed.incrementAndGet();
                    }
                }
                return proxy;
            });
            new WaveformProgress.Mesh(new Matrix3x2f(), null, null, 0, 10, 100, .375,
                heights, 0x11ee66, 0x11ee66, options.look(), Settings.SurfaceFinish.FLAT).buildVertices(consumer);
            assertTrue(count.get() > 0 && played.get() > 0 && unplayed.get() > 0);
        }
    }

    @Test
    void largeSpacingStillFitsANarrowWaveform() {
        var settings = new WaveformSettings();
        settings.style = WaveformSettings.Style.BARS;
        settings.spacing = 4;
        var vertices = new AtomicInteger();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) {
                float x = (float) args[0], y = (float) args[1];
                assertTrue(Float.isFinite(x) && Float.isFinite(y));
                assertTrue(x >= -.51 && x <= 3.51);
                vertices.incrementAndGet();
            }
            return proxy;
        });
        new WaveformProgress.Mesh(new Matrix3x2f(), null, null, 0, 10, 3, .5,
            new float[]{2, 2}, 0x11ee66, 0x11ee66, settings.look(), Settings.SurfaceFinish.FLAT).buildVertices(consumer);
        assertTrue(vertices.get() > 0);
    }

    @Test
    void appearanceSnapshotsDoNotChangeWhenSettingsAreEdited() {
        var settings = new WaveformSettings();
        var look = settings.look();
        settings.height = 18;
        settings.opacity = .15;
        settings.style = WaveformSettings.Style.BARS;
        assertEquals(14, look.height());
        assertEquals(1, look.opacity());
        assertEquals(WaveformSettings.Style.FILLED, look.style());
    }
}
