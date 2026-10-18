package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.Bevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BevelTest {
    @Test
    void lightingPreservesAlphaAndProducesBoundedHighlightsAndShadows() {
        for (int color : new int[]{0x00B5B0FF, 0x80FF526E, 0xFF55F2C0, 0xFF000000, 0xFFFFFFFF})
            for (double light : new double[]{-3, -.35, 0, .35, 3, Double.NaN}) {
                int shaded = Bevel.shade(color, light);
                assertEquals(color >>> 24, shaded >>> 24);
                for (int shift : new int[]{0, 8, 16}) {
                    int before = color >> shift & 255, after = shaded >> shift & 255;
                    if (light > 0) assertTrue(after >= before);
                    else if (light < 0) assertTrue(after <= before);
                    else assertEquals(before, after);
                }
            }
        assertTrue(Bevel.circularLight(.875) > .99);
        assertTrue(Bevel.circularLight(.375) < -.99);
        assertEquals(Bevel.circularLight(0), Bevel.circularLight(1), .000001);
    }

    @Test
    void finishDefaultsAndPersistencePreserveIndependentChoicesAndColors() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertEquals(Settings.SurfaceFinish.FLAT, settings.spectrumFinish);
        assertEquals(Settings.SurfaceFinish.FLAT, settings.progressFinish);
        settings.spectrumFinish = Settings.SurfaceFinish.BEVELED;
        int color = settings.spectrumColors.color(.5);
        var loaded = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        loaded.validate();
        assertEquals(Settings.SurfaceFinish.BEVELED, loaded.spectrumFinish);
        assertEquals(Settings.SurfaceFinish.FLAT, loaded.progressFinish);
        assertEquals(color, loaded.spectrumColors.color(.5));
        assertEquals(Settings.SurfaceFinish.FLAT, Settings.defaults(loaded).spectrumFinish);
        loaded.progressFinish = loaded.spectrumFinish = null;
        loaded.validate();
        assertEquals(Settings.SurfaceFinish.FLAT, loaded.progressFinish);
        assertEquals(Settings.SurfaceFinish.FLAT, loaded.spectrumFinish);
    }
}
