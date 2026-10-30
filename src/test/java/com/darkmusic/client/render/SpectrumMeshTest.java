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
    void geometryBeyondTheInitialBudgetGrowsOnceThenDropsInsteadOfThrowing() throws Exception {
        var countField = SpectrumMesh.class.getDeclaredField("count");
        countField.setAccessible(true);
        var mesh = new SpectrumMesh();
        mesh.begin(400, 0, Settings.HudShape.RECTANGLE);
        // 40,000 one-pixel rectangles: past the initial 3,276 and past the hard cap.
        for (int i = 0; i < 40_000; i++) mesh.fill(i % 400, i / 400, i % 400 + 1, i / 400 + 1, 0xff123456);
        int count = (int) countField.get(mesh);
        assertTrue(count / 5 > 3276, "The batch grows past its initial size");
        assertTrue(count / 5 <= 26_214, "The batch stays bounded");
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
            if (data[i + 1] == 0 && data[i] > 10) {
                softened = true;
                break;
            }
        }
        assertTrue(softened, "The cap must not remain a square rectangle");
    }

    /**
     * {left, top, right, bottom, topColor, bottomColor} per batched rectangle.
     */
    private static int[][] rectangles(SpectrumMesh mesh) throws Exception {
        var countField = SpectrumMesh.class.getDeclaredField("count");
        countField.setAccessible(true);
        var dataField = SpectrumMesh.class.getDeclaredField("rectangles");
        dataField.setAccessible(true);
        var bottomField = SpectrumMesh.class.getDeclaredField("bottomColors");
        bottomField.setAccessible(true);
        int count = (int) countField.get(mesh);
        int[] data = (int[]) dataField.get(mesh), bottoms = (int[]) bottomField.get(mesh);
        var result = new int[count / 5][];
        for (int i = 0; i < count; i += 5)
            result[i / 5] = new int[]{data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4], bottoms[i / 5]};
        return result;
    }

    private static int brightness(int color) {
        return (color >> 16 & 255) + (color >> 8 & 255) + (color & 255);
    }

    // Mean brightness over every covered pixel, averaging each gradient's two ends.
    private static double meanBrightness(int[][] rectangles) {
        double sum = 0, area = 0;
        for (int[] r : rectangles) {
            int pixels = (r[2] - r[0]) * (r[3] - r[1]);
            sum += pixels * (brightness(r[4]) + brightness(r[5])) / 2.0;
            area += pixels;
        }
        return sum / area;
    }

    @Test
    void beveledWaveLinesFollowTheSlopeInsteadOfCancellingOut() throws Exception {
        int color = 0xff6070c0;
        var mesh = new SpectrumMesh();
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.contour(10, 40, 11, 43, color, 0, false, Settings.SurfaceFinish.FLAT);
        var flat = rectangles(mesh);
        assertEquals(1, flat.length);
        assertEquals(color, flat[0][4]);
        double[] means = new double[3];
        double[] slopes = {-2, 0, 2};
        for (int i = 0; i < slopes.length; i++) {
            mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
            mesh.contour(10, 40, 11, 43, color, slopes[i], false, Settings.SurfaceFinish.BEVELED);
            var lit = rectangles(mesh);
            assertTrue(brightness(lit[0][4]) > brightness(color), "The top row carries a highlight");
            means[i] = meanBrightness(lit);
        }
        assertTrue(means[0] < means[1] && means[1] < means[2], "Rising edges are lit, falling edges shaded");
        assertTrue(means[2] - means[0] > 120, "Slope lighting must be clearly visible: " + means[0] + " to " + means[2]);
        // A level stretch of line is two pixels thick; its highlight must not average back to the flat color.
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.contour(10, 40, 11, 42, color, 0, false, Settings.SurfaceFinish.BEVELED);
        assertTrue(meanBrightness(rectangles(mesh)) - brightness(color) > 25);
    }

    @Test
    void beveledFilledWavesShadeTheirBodyTowardTheBase() throws Exception {
        int color = 0xff6070c0;
        var mesh = new SpectrumMesh();
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.contour(10, 20, 11, 80, color, 0, true, Settings.SurfaceFinish.BEVELED);
        var lit = rectangles(mesh);
        int[] body = null;
        for (int[] r : lit) if (r[3] - r[1] > 1) body = r;
        assertTrue(body != null && body[1] == 21 && body[3] == 79);
        assertTrue(brightness(body[4]) > brightness(color) && brightness(body[5]) < brightness(color),
            "The body fades from lit near the edge to shaded at the base");
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.contour(10, 20, 11, 80, color, 3, true, Settings.SurfaceFinish.BEVELED);
        double rising = meanBrightness(rectangles(mesh));
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.contour(10, 20, 11, 80, color, -3, true, Settings.SurfaceFinish.BEVELED);
        assertTrue(rising > meanBrightness(rectangles(mesh)));
    }

    @Test
    void beveledRoundedBarsGetLitAndShadedSidesLikeSquareBars() throws Exception {
        int color = 0xff6070c0;
        var mesh = new SpectrumMesh();
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.rounded(10, 10, 16, 80, color, Settings.SurfaceFinish.BEVELED, true);
        int left = 0, right = 0;
        for (int[] r : rectangles(mesh)) {
            if (r[3] - r[1] < 10) continue;
            if (r[0] == 10 && r[2] == 11) left = r[4];
            if (r[0] == 15 && r[2] == 16) right = r[4];
        }
        assertTrue(brightness(left) > brightness(color), "Left side is lit");
        assertTrue(right != 0 && brightness(right) < brightness(color), "Right side is shaded");
        mesh.begin(100, 0, Settings.HudShape.RECTANGLE);
        mesh.rounded(10, 10, 16, 80, color, Settings.SurfaceFinish.FLAT, true);
        for (int[] r : rectangles(mesh))
            if (r[3] - r[1] >= 10) assertEquals(color, r[4], "Flat bodies stay one color");
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
