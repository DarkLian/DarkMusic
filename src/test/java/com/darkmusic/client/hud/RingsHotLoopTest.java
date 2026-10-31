package com.darkmusic.client.hud;

import com.darkmusic.client.config.Settings;
import com.darkmusic.client.testing.HotCost;
import com.darkmusic.client.testing.HotGolden;
import com.darkmusic.client.testing.Vertices;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RingsHotLoopTest {
    private static CircularHudMesh.Rings rings(Settings.SpectrumStyle style, double progress, boolean beveled, boolean glow, double rotation) {
        float[] levels = new float[64], live = new float[64];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = i % 9 / 8f;
            live[i] = levels[i] * .6f;
        }
        var finish = beveled ? Settings.SurfaceFinish.BEVELED : Settings.SurfaceFinish.FLAT;
        return new CircularHudMesh.Rings(new Matrix3x2f().translate(3, 7).rotate((float) (rotation * Math.PI * 2)),
            null, null, 100, 100, progress, levels, 36, style, 0xff123456, 0xffabcdef, 0x80123456,
            0xffeeddcc, finish, finish, 192, glow, 50, 59, 51, live, rotation);
    }

    @Test
    void everyRingStyleModeAndFinishKeepsEveryVertexBit() throws Exception {
        var results = new LinkedHashMap<String, String>();
        var consumer = new Vertices(true);
        for (int repeat = 0; repeat < 3; repeat++)
            for (var style : Settings.SpectrumStyle.values())
                for (double progress : new double[]{-3, -2, -1, 0, .375, 1})
                    for (boolean bevel : new boolean[]{false, true})
                        for (boolean glow : new boolean[]{false, true})
                            for (double rotation : new double[]{0, .125}) {
                                consumer.reset();
                                rings(style, progress, bevel, glow, rotation).buildVertices(consumer);
                                String key = style + "." + progress + "." + bevel + "." + glow + "." + rotation;
                                String hash = HotGolden.hash(consumer.values());
                                String previous = results.putIfAbsent(key, hash);
                                if (previous != null) assertEquals(previous, hash, key);
                            }
        HotGolden.check("hot-rings-golden", results);
    }

    @Test
    @EnabledIfSystemProperty(named = "darkmusic.test.perf", matches = "true")
    void ringsBuildVerticesCost() throws Exception {
        var mesh = rings(Settings.SpectrumStyle.FILLED_WAVE, .375, true, true, .125);
        var consumer = new Vertices(false);
        HotCost.measure("CircularHudMesh.Rings.buildVertices/192-beveled-glowing-wave", () -> {
            consumer.reset();
            mesh.buildVertices(consumer);
            return consumer.checksum();
        });
    }
}
