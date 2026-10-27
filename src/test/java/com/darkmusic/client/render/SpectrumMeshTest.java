package com.darkmusic.client.render;

import com.darkmusic.client.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumMeshTest {
    @Test
    void meshOpacityScalesRoundedFacesAndGradientsWithoutChangingRgb() throws Exception {
        var mesh = new SpectrumMesh();
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE, .4);
        mesh.rounded(0, 10, 10, 80, 0xff123456, Settings.SurfaceFinish.FLAT);
        mesh.gradient(20, 10, 30, 80, 0x58123456, 0x10123456);
        var countField = SpectrumMesh.class.getDeclaredField("count");
        countField.setAccessible(true);
        var dataField = SpectrumMesh.class.getDeclaredField("rectangles");
        dataField.setAccessible(true);
        var bottomField = SpectrumMesh.class.getDeclaredField("bottomColors");
        bottomField.setAccessible(true);
        int[] data = (int[]) dataField.get(mesh), bottom = (int[]) bottomField.get(mesh);
        int count = (int) countField.get(mesh);
        for (int i = 0; i < count; i += 5) {
            assertEquals(0x123456, data[i + 4] & 0xffffff);
            assertTrue((data[i + 4] >>> 24) <= 102);
            assertTrue((bottom[i / 5] >>> 24) <= 102);
        }
        assertEquals(35, data[count - 1] >>> 24);
        assertEquals(6, bottom[count / 5 - 1] >>> 24);
    }

    @Test
    void roundedBarsSoftenCornersAndStayWithinTheExistingBatchBudget() throws Exception {
        var countField = SpectrumMesh.class.getDeclaredField("count");
        countField.setAccessible(true);
        var dataField = SpectrumMesh.class.getDeclaredField("rectangles");
        dataField.setAccessible(true);
        for (int width : new int[]{220, 960})
            for (int bars : new int[]{8, 64}) {
                var mesh = new SpectrumMesh();
                mesh.begin(width, 0, Settings.HudShape.RECTANGLE);
                for (int i = 0; i < bars; i++) {
                    int left = i * width / bars, right = (i + 1) * width / bars - 1;
                    mesh.rounded(left, 0, right, 100, 0x22123456, Settings.SurfaceFinish.FLAT);
                    mesh.rounded(left, 25, right, 100, 0xff123456, Settings.SurfaceFinish.BEVELED);
                    mesh.fill(left, 0, right, 1, 0xff123456);
                }
                int count = (int) countField.get(mesh);
                assertTrue(count > 0 && count <= ((int[]) dataField.get(mesh)).length);
                int[] data = (int[]) dataField.get(mesh);
                for (int i = 0; i < count; i += 5) {
                    assertTrue(data[i] >= 0 && data[i + 2] <= width);
                    assertTrue(data[i + 1] >= 0 && data[i + 3] <= 100);
                }
            }
        var corner = new SpectrumMesh();
        corner.begin(32, 0, Settings.HudShape.RECTANGLE);
        corner.rounded(10, 0, 22, 30, 0xff123456, Settings.SurfaceFinish.FLAT);
        int[] data = (int[]) dataField.get(corner);
        int count = (int) countField.get(corner);
        boolean softened = false;
        for (int i = 0; i < count; i += 5) {
            if (data[i + 1] == 0 && data[i] > 10) softened = true;
        }
        assertTrue(softened, "The cap must not remain a square rectangle");
    }

    @Test
    void clippedTrailGradientsKeepTheirAlphaAndBoundedGeometry() throws Exception {
        for (var shape : Settings.HudShape.values()) {
            var mesh = new SpectrumMesh();
            mesh.begin(220, 82, shape);
            mesh.gradient(0, 50, 220, 82, 0x10B8A8FA, 0x58B8A8FA);
            var countField = SpectrumMesh.class.getDeclaredField("count");
            countField.setAccessible(true);
            var dataField = SpectrumMesh.class.getDeclaredField("rectangles");
            dataField.setAccessible(true);
            var bottomField = SpectrumMesh.class.getDeclaredField("bottomColors");
            bottomField.setAccessible(true);
            int count = (int) countField.get(mesh);
            int[] data = (int[]) dataField.get(mesh);
            int[] bottoms = (int[]) bottomField.get(mesh);
            assertTrue(count / 5 <= 7);
            for (int i = 0; i < count; i += 5) {
                assertEquals(0xB8A8FA, data[i + 4] & 0xffffff);
                assertEquals(0xB8A8FA, bottoms[i / 5] & 0xffffff);
                int a = data[i + 4] >>> 24, b = bottoms[i / 5] >>> 24;
                assertTrue(a >= 0x10 && b <= 0x58 && b >= a, "Gradient must not become opaque during clipping");
            }
        }
    }

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
