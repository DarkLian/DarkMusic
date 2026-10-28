package com.darkmusic.client.render;

import com.darkmusic.client.config.Settings;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumHaloTest {
    @Test
    void oneBoundedMeshClipsHaloToHudCornersWithoutExpandedColumnOverlap() {
        for (var shape : Settings.HudShape.values())
            for (int columns : new int[]{8, 36, 192, 384}) {
                var count = new AtomicInteger();
                var vertices = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (p, m, args) -> {
                    if (m.getName().equals("addVertexWith2DPose")) {
                        count.incrementAndGet();
                        float x = ((Number) args[1]).floatValue(), y = ((Number) args[2]).floatValue();
                        assertTrue(y >= 0 && y <= 80);
                        assertTrue(x >= 0 && x <= 220);
                        // Pixel-exclusive bottom edges use the previous row's silhouette.
                        assertTrue(x >= HudSurface.inset(shape, 220, 80, Math.min(79, Math.max(0, (int) y - 1))) - 6);
                    }
                    return m.getReturnType() == VertexConsumer.class ? p : null;
                });
                float[] top = new float[columns], bottom = new float[columns];
                Arrays.fill(top, 1);
                Arrays.fill(bottom, 79);
                new SpectrumHalo.Halo(new Matrix3x2f(), null, null, 0, 220, top, bottom, false, shape, 80, 0x123456, 0xabcdef).buildVertices(vertices);
                assertTrue(count.get() <= columns * 16, "Halo geometry must remain bounded by spectrum detail");
            }
    }
}