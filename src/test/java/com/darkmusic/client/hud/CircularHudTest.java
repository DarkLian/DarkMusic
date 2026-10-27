package com.darkmusic.client.hud;

import com.darkmusic.client.audio.TrackWaveform;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.WaveformSettings;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularHudTest {
    @Test
    void roundedPeaksUseTheSameSmoothedMagnitudeAsTheLiveBarBeforeTestingSeparation() {
        float[] levels = new float[64];
        Arrays.fill(levels, .5f);
        for (float peak : new float[]{.51f, .8f}) {
            float[] peaks = new float[64];
            Arrays.fill(peaks, peak);
            int[] vertices = {0};
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                if (method.getName().equals("addVertex")) vertices[0]++;
                return proxy;
            });
            new CircularHudMesh.Rings(new Matrix3x2f(), null, null, 0, 0, -2,
                peaks, 36, Settings.SpectrumStyle.ROUNDED_BARS, 0, 0, 0xff123456, 0xff123456,
                Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, 96, false, 50, 59, 59, levels).buildVertices(consumer);
            assertEquals(peak > .6f, vertices[0] > 0);
        }
    }

    @Test
    void roundedPeakIsAThinPillAndOpacityIncludesTrailsAndGlow() {
        float[] levels = new float[64];
        Arrays.fill(levels, 1);
        for (var style : new Settings.SpectrumStyle[]{Settings.SpectrumStyle.ROUNDED_BARS, Settings.SpectrumStyle.BARS, Settings.SpectrumStyle.FILLED_WAVE})
            for (double mode : new double[]{-1, -2, -3}) {
                var radii = new java.util.ArrayList<Double>();
                VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addVertex")) radii.add(Math.hypot((float) args[0], (float) args[1]));
                    if (method.getName().equals("setColor"))
                        assertTrue(((int) args[0] >>> 24) <= 128, "Trails/glow must not override configured opacity");
                    return proxy;
                });
                new CircularHudMesh.Rings(new Matrix3x2f(), null, null, 0, 0, mode,
                    levels, 36, style, 0, 0, 0x80123456, 0x80123456,
                    Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, 96, true, 50, 59).buildVertices(consumer);
                assertTrue(!radii.isEmpty());
                if (mode == -2 && style == Settings.SpectrumStyle.ROUNDED_BARS)
                    assertTrue(radii.stream().mapToDouble(Double::doubleValue).min().orElseThrow() >= 107 - CircularHudLayout.FEATHER - .01,
                        "Peak thickness must not grow with bar width or curve around the whole cap");
            }
    }

    @Test
    void disabledProgressHasNoRailAndSpectrumStartsAtTheIconEdge() {
        float[] levels = new float[64];
        var points = new ArrayList<Point>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) points.add(new Point((float) args[0], (float) args[1]));
            return proxy;
        });
        for (var style : new Settings.SpectrumStyle[]{Settings.SpectrumStyle.BARS, Settings.SpectrumStyle.ROUNDED_BARS, Settings.SpectrumStyle.FILLED_WAVE, Settings.SpectrumStyle.FILLED_SPIKE}) {
            points.clear();
            Arrays.fill(levels, 0);
            var mesh = new CircularHudMesh.Rings(new Matrix3x2f(), null, null, 100, 100, -3,
                levels, 36, style, 0, 0, 0xff123456, 0xffabcdef, Settings.SurfaceFinish.BEVELED,
                Settings.SurfaceFinish.BEVELED, 384, true, 18, CircularHudLayout.ICON_RADIUS);
            mesh.buildVertices(consumer);
            assertTrue(points.isEmpty(), "No progress rail or inactive spectrum geometry");
            Arrays.fill(levels, 1);
            mesh.buildVertices(consumer);
            assertTrue(!points.isEmpty());
            double minimum = points.stream().mapToDouble(p -> Math.hypot(p.x() - 100, p.y() - 100)).min().orElseThrow();
            assertTrue(minimum >= CircularHudLayout.ICON_RADIUS - CircularHudLayout.FEATHER - .001);
            assertTrue(minimum <= CircularHudLayout.ICON_RADIUS + .001, "Spectrum must connect directly to the icon");
        }
    }

    @Test
    void glowTracksBothSlopeEndpointsAndPeakHoldIsOnlyAnOuterCap() {
        var layout = CircularHudLayout.of(true);
        float[] levels = new float[64];
        Arrays.fill(levels, 1);
        var points = new ArrayList<Point>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) points.add(new Point((float) args[0], (float) args[1]));
            return proxy;
        });
        new CircularHudMesh.Rings(new Matrix3x2f(), null, null, layout.centerX(), layout.centerY(), -1,
            levels, 8, Settings.SpectrumStyle.FILLED_SPIKE, 0, 0, 0xff123456, 0xffabcdef,
            Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, 96, true).buildVertices(consumer);
        java.util.function.ToDoubleFunction<Point> radius = p -> Math.hypot(p.x() - layout.centerX(), p.y() - layout.centerY());
        for (int i = 0; i < points.size(); i += 12) {
            assertEquals(radius.applyAsDouble(points.get(i)), radius.applyAsDouble(points.get(i + 9)), .0001);
            assertEquals(radius.applyAsDouble(points.get(i + 3)), radius.applyAsDouble(points.get(i + 10)), .0001);
            assertEquals(radius.applyAsDouble(points.get(i)) + 1.6, radius.applyAsDouble(points.get(i + 8)), .0001);
        }
        points.clear();
        new CircularHudMesh.Rings(new Matrix3x2f(), null, null, layout.centerX(), layout.centerY(), -2,
            levels, 36, Settings.SpectrumStyle.FILLED_WAVE, 0, 0, 0xff123456, 0xffabcdef,
            Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, 96, false).buildVertices(consumer);
        assertTrue(points.stream().allMatch(p -> radius.applyAsDouble(p) >= CircularHudLayout.RING_OUTER + CircularHudLayout.SPECTRUM_HEIGHT - .751));
    }

    @Test
    void beveledCircularFacesStayInsideFlatBoundsAndUseSeveralLightingTones() {
        var layout = CircularHudLayout.of(true);
        float[] levels = new float[64];
        Arrays.fill(levels, 1);
        for (var style : new Settings.SpectrumStyle[]{Settings.SpectrumStyle.BARS, Settings.SpectrumStyle.FILLED_WAVE, Settings.SpectrumStyle.FILLED_SPIKE}) {
            var colors = new java.util.HashSet<Integer>();
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                if (method.getName().equals("addVertex")) {
                    float x = (float) args[0], y = (float) args[1];
                    assertTrue(x >= 0 && x <= layout.width() && y >= 0 && y < layout.titleY() - 8);
                    double radius = Math.hypot(x - layout.centerX(), y - layout.centerY());
                    assertTrue(radius >= CircularHudLayout.RING_INNER - CircularHudLayout.FEATHER - .001 && radius <= CircularHudLayout.RING_OUTER + CircularHudLayout.SPECTRUM_HEIGHT + CircularHudLayout.FEATHER + .001);
                }
                if (method.getName().equals("setColor")) colors.add((int) args[0]);
                return proxy;
            });
            new CircularHudMesh.Rings(new Matrix3x2f(), null, new ScreenRectangle(0, 0, layout.width(), layout.height()), layout.centerX(), layout.centerY(), .375,
                levels, 36, style, 0xB5B0FF, 0xB5B0FF, 0xB5B0FF, 0xB5B0FF, Settings.SurfaceFinish.BEVELED, Settings.SurfaceFinish.BEVELED).buildVertices(consumer);
            assertTrue(colors.size() > 20, "Circular bevel should shade faces rather than draw one flat color");
        }
    }

    private List<Point> vertices(double progress, float[] levels, int bars) {
        return vertices(progress, levels, bars, Settings.SpectrumStyle.BARS);
    }

    private List<Point> vertices(double progress, float[] levels, int bars, Settings.SpectrumStyle style) {
        var points = new ArrayList<Point>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) points.add(new Point((float) args[0], (float) args[1]));
            return proxy;
        });
        var layout = CircularHudLayout.of(true);
        new CircularHudMesh.Rings(new Matrix3x2f(), null, new ScreenRectangle(0, 0, layout.width(), layout.height()),
            layout.centerX(), layout.centerY(), progress, levels, bars, style, 0xB5B0FF, 0xB5B0FF, 0xB5B0FF, 0xB5B0FF, Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT).buildVertices(consumer);
        return points;
    }

    @Test
    void progressStartsAtTwelveAndFillsClockwiseWithoutRoundingToSegments() {
        int base = vertices(0, null, 36).size();
        for (double fraction : new double[]{.001, .125, .25, .5, .75, 1}) {
            var points = vertices(fraction, null, 36);
            assertTrue(points.size() > base);
            // Each progress arc follows its base arc; the last quad is its outer feather.
            int segment = (int) Math.ceil(fraction * 192) - 1;
            int lastProgressVertex = (segment + 1) * 24 - 1;
            var endpoint = points.get(lastProgressVertex);
            double angle = Math.atan2(endpoint.x() - 88, 82 - endpoint.y());
            if (angle < 0) angle += Math.PI * 2;
            double actual = angle / (Math.PI * 2);
            assertTrue(Math.abs(actual - fraction) < .00001 || fraction == 1 && actual < .00001);
        }
        assertEquals(.25, CircularHudLayout.progress(10, 40, false));
        assertEquals(1, CircularHudLayout.progress(50, 40, false));
        assertEquals(0, CircularHudLayout.progress(10, 0, false));
        assertEquals(.4, CircularHudLayout.progress(Double.NaN, 0, true));
    }

    @Test
    void everyRadialVertexStaysOutsideIconAndAboveTextEvenAtFullMagnitude() {
        var layout = CircularHudLayout.of(true);
        var levels = new float[64];
        Arrays.fill(levels, 1);
        int ringVertices = vertices(0, null, 36).size();
        for (int bands = 8; bands <= 64; bands++) {
            var points = vertices(0, levels, bands);
            assertTrue(points.size() > ringVertices);
            for (var p : points.subList(ringVertices, points.size())) {
                double radius = Math.hypot(p.x() - layout.centerX(), p.y() - layout.centerY());
                assertTrue(radius >= CircularHudLayout.RING_OUTER - CircularHudLayout.FEATHER - .001);
                assertTrue(radius <= CircularHudLayout.RING_OUTER + CircularHudLayout.SPECTRUM_HEIGHT + CircularHudLayout.FEATHER + .001);
                assertTrue(p.x() >= 0 && p.x() < layout.width() && p.y() >= 0 && p.y() < layout.titleY() - 8);
            }
        }
    }

    @Test
    void radialSpectrumHasNoSeamOrSyntheticMotionAndHandlesInvalidSamples() {
        float[] levels = {.2f, .8f, .4f, 1, 0, .3f, .1f, .6f};
        assertEquals(CircularHudLayout.magnitude(levels, 8, 0), CircularHudLayout.magnitude(levels, 8, 1));
        for (int i = 0; i < 100; i++) {
            double turn = i / 100.0;
            float value = CircularHudLayout.magnitude(levels, 8, turn);
            assertTrue(value >= 0 && value <= 1);
            assertEquals(value, CircularHudLayout.magnitude(levels, 8, 1 - turn), .00001);
        }
        assertEquals(vertices(.5, levels, 8), vertices(.5, levels, 8));
        Arrays.fill(levels, Float.NaN);
        assertEquals(vertices(0, null, 8), vertices(0, levels, 8));
    }

    @Test
    void filledCircularStylesProduceDifferentBoundedGeometry() {
        float[] levels = new float[64];
        Arrays.fill(levels, 1);
        var bars = vertices(0, levels, 36, Settings.SpectrumStyle.BARS);
        var waves = vertices(0, levels, 36, Settings.SpectrumStyle.FILLED_WAVE);
        var spikes = vertices(0, levels, 36, Settings.SpectrumStyle.FILLED_SPIKE);
        assertTrue(!bars.equals(waves) && !waves.equals(spikes) && !bars.equals(spikes));
        int base = vertices(0, null, 36).size();
        for (var style : new Settings.SpectrumStyle[]{Settings.SpectrumStyle.FILLED_WAVE, Settings.SpectrumStyle.FILLED_SPIKE})
            for (var point : vertices(0, levels, 36, style).subList(base, vertices(0, levels, 36, style).size())) {
                double radius = Math.hypot(point.x() - 88, point.y() - 82);
                assertTrue(radius >= CircularHudLayout.RING_OUTER - .001 && radius <= CircularHudLayout.RING_OUTER + CircularHudLayout.SPECTRUM_HEIGHT + CircularHudLayout.FEATHER + .001);
            }
        assertEquals(0, CircularHudLayout.waveHeight(levels, 36, 0, Settings.SpectrumStyle.FILLED_SPIKE));
        assertEquals(18, CircularHudLayout.waveHeight(levels, 36, .5 / 36, Settings.SpectrumStyle.FILLED_SPIKE), .001);
    }

    @Test
    void iconMeetsRingAndPulseIsStableBoundedAndIndependentOfText() {
        assertEquals(CircularHudLayout.RING_INNER, CircularHudLayout.ICON_RADIUS);
        float[] levels = new float[64];
        assertEquals(1, CircularHudLayout.iconZoom(levels, 36));
        Arrays.fill(levels, 1);
        assertEquals(1.045, CircularHudLayout.iconZoom(levels, 36), .00001);
        assertEquals(CircularHudLayout.iconZoom(levels, 36), CircularHudLayout.iconZoom(levels, 36));
        Arrays.fill(levels, Float.NaN);
        assertEquals(1, CircularHudLayout.iconZoom(levels, 36));
        for (boolean spectrum : new boolean[]{false, true}) {
            var withText = CircularHudLayout.of(spectrum, true);
            var withoutText = CircularHudLayout.of(spectrum, false);
            assertEquals(withText.centerY(), withoutText.centerY());
            assertTrue(withoutText.height() < withText.height());
            assertEquals(withoutText.centerY() * 2, withoutText.height());
        }
        var settings = new Settings();
        settings.circularText = false;
        settings.circularIconPulse = false;
        settings.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_SPIKE;
        settings.spectrumStyle = Settings.SpectrumStyle.SMOOTH_WAVE;
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertTrue(!loaded.circularText && !loaded.circularIconPulse);
        assertEquals(Settings.SpectrumStyle.FILLED_SPIKE, loaded.circularSpectrumStyle);
        assertEquals(Settings.SpectrumStyle.SMOOTH_WAVE, loaded.spectrumStyle);
        loaded.circularSpectrumStyle = Settings.SpectrumStyle.SMOOTH_WAVE;
        loaded.validate();
        assertEquals(Settings.SpectrumStyle.BARS, loaded.circularSpectrumStyle);
    }

    @Test
    void circularModePreservesClassicPreferencesAndFitsTheWholeComponent() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertEquals(Settings.HudStyle.CLASSIC, settings.hudStyle);
        settings.hudStyle = Settings.HudStyle.CIRCULAR;
        settings.hudArtwork = true;
        settings.hudShape = Settings.HudShape.CHAMFERED;
        settings.hudX = .8;
        settings.hudY = .7;
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        restored.validate();
        assertEquals(Settings.HudStyle.CIRCULAR, restored.hudStyle);
        assertTrue(restored.hudArtwork);
        assertEquals(settings.hudShape, restored.hudShape);
        for (boolean spectrum : new boolean[]{false, true}) {
            settings.hudVisualizer = spectrum;
            for (int width : new int[]{320, 426, 854, 1280})
                for (int height : new int[]{180, 240, 480, 720})
                    for (double scale : new double[]{.5, 1, 3}) {
                        settings.hudScale = scale;
                        var bounds = HudLayout.bounds(settings, width, height);
                        assertTrue(bounds.x() >= 8 && bounds.y() >= 8 && bounds.x() + bounds.width() <= width - 8 && bounds.y() + bounds.height() <= height - 8);
                        assertEquals(176, bounds.contentWidth());
                    }
            var artLayout = HudLayout.content(settings, Integer.MAX_VALUE);
            settings.hudArtwork = !settings.hudArtwork;
            assertEquals(artLayout, HudLayout.content(settings, Integer.MAX_VALUE));
        }
        assertEquals(Settings.HudStyle.CLASSIC, Settings.defaults(restored).hudStyle);
    }

    @Test
    void centeredTextEllipsisKeepsUnicodeIntactAndWithinMeasuredWidth() {
        String value = "歌曲🎵音樂𝄞Artist";
        for (int width = 0; width <= 20; width++) {
            String fitted = CircularHudRenderer.ellipsis(value, width, s -> s.codePointCount(0, s.length()));
            assertTrue(fitted.codePointCount(0, fitted.length()) <= width);
            assertEquals(fitted, new String(fitted.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(value, CircularHudRenderer.ellipsis(value, 100, String::length));
    }

    @Test
    void waveformRingStaysWithinMeasuredBoundsAndFillsOnlyThePlayedPart() {
        float[] envelope = new float[TrackWaveform.BINS];
        for (int i = 0; i < envelope.length; i++) envelope[i] = i / (float) envelope.length;
        var waveform = new TrackWaveform(envelope, 200);
        var settings = new Settings();
        settings.hudVisualizer = false;
        settings.waveformProgress = true;
        var layout = CircularHudLayout.of(settings);
        var colors = new java.util.HashSet<Integer>();
        var points = new ArrayList<Point>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) {
                var p = new Point((float) args[0], (float) args[1]);
                points.add(p);
                assertTrue(Float.isFinite(p.x()) && Float.isFinite(p.y()));
                assertTrue(p.x() >= 0 && p.x() <= layout.width() && p.y() >= 0 && p.y() < layout.titleY() - 8);
                double radius = Math.hypot(p.x() - layout.centerX(), p.y() - layout.centerY());
                assertTrue(radius >= CircularHudLayout.ICON_RADIUS - CircularHudLayout.FEATHER - .001);
                assertTrue(radius <= CircularHudLayout.ICON_RADIUS + 14 + CircularHudLayout.FEATHER + .001);
            }
            if (method.getName().equals("setColor")) colors.add((int) args[0]);
            return proxy;
        });
        new CircularHudMesh.ProgressWaveform(new Matrix3x2f(), null, null, layout.centerX(), layout.centerY(),
            0, waveform, 0xff12ab34, 0xff12ab34, Settings.SurfaceFinish.FLAT, new WaveformSettings().look()).buildVertices(consumer);
        assertTrue(!points.isEmpty());
        assertTrue(!colors.contains(0xff12ab34), "Nothing is highlighted at the start");
        points.clear();
        colors.clear();
        new CircularHudMesh.ProgressWaveform(new Matrix3x2f(), null, null, layout.centerX(), layout.centerY(),
            .5, waveform, 0xff12ab34, 0xff12ab34, Settings.SurfaceFinish.FLAT, new WaveformSettings().look()).buildVertices(consumer);
        assertTrue(colors.contains(0xff12ab34));
        assertTrue(colors.contains(0xff555d78), "Unplayed audio remains visible");
    }

    @Test
    void progressRailUsesConfiguredImageGapWithoutChangingItsThickness() {
        for (int gap : new int[]{0, 12, 24}) {
            float inner = CircularHudLayout.ICON_RADIUS + gap;
            var radii = new ArrayList<Double>();
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                if (method.getName().equals("addVertex"))
                    radii.add(Math.hypot((float) args[0] - 100, (float) args[1] - 100));
                return proxy;
            });
            new CircularHudMesh.Rings(new Matrix3x2f(), null, null, 100, 100, .4, null,
                36, Settings.SpectrumStyle.BARS, 0xff123456, 0xff123456, 0, 0,
                Settings.SurfaceFinish.FLAT, Settings.SurfaceFinish.FLAT, 192, false, 18, inner + 3, inner).buildVertices(consumer);
            assertEquals(inner - CircularHudLayout.FEATHER, radii.stream().mapToDouble(Double::doubleValue).min().orElseThrow(), .001);
            assertEquals(inner + 3 + CircularHudLayout.FEATHER, radii.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), .001);
        }
    }

    private record Point(float x, float y) {
    }
}
