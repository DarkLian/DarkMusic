package com.darkmusic.client.gui;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudSurface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumMeshTest {
    @Test
    void batchingPreservesEveryClippedPixelAndCoalescesInteriorSpans() throws Exception {
        for (var shape : Settings.HudShape.values()) {
            var mesh = new SpectrumMesh();
            mesh.begin(220, 82, shape);
            mesh.fill(0, 50, 220, 82, 0xFF123456);
            var countField = SpectrumMesh.class.getDeclaredField("count");
            countField.setAccessible(true);
            var dataField = SpectrumMesh.class.getDeclaredField("rectangles");
            dataField.setAccessible(true);
            int count = (int) countField.get(mesh);
            int[] data = (int[]) dataField.get(mesh);
            assertTrue(count / 5 <= 7, "Interior spans must not turn into per-pixel submissions");
            for (int y = 50; y < 82; y++)
                for (int x = 0; x < 220; x++) {
                    int coverage = 0;
                    for (int i = 0; i < count; i += 5) {
                        assertEquals(0xFF123456, data[i + 4]);
                        if (x >= data[i] && x < data[i + 2] && y >= data[i + 1] && y < data[i + 3]) coverage++;
                    }
                    int inset = HudSurface.inset(shape, 220, 82, y);
                    assertEquals(x >= inset && x < 220 - inset ? 1 : 0, coverage, "Clipping seam at " + x + "," + y + " " + shape);
                }
            mesh.begin(220, 82, shape);
            assertEquals(0, countField.get(mesh));
        }
    }
}