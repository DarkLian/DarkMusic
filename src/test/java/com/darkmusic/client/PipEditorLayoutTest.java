package com.darkmusic.client;

import com.darkmusic.client.gui.PipEditorLayout;
import com.darkmusic.client.pip.PipSettings.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipEditorLayoutTest {
    @Test
    void buttonsMoveAwayFromImageAndSpectrumAtTheTop() {
        for (int[] size : new int[][]{{320, 240}, {426, 240}, {960, 540}}) {
            var image = new Bounds(0, 0, size[0] / 2, size[1] / 2);
            assertEquals(size[1] - 24, PipEditorLayout.buttonY(size[1], image, null, 12, 80));
            assertEquals(4, PipEditorLayout.buttonY(size[1], image, null, size[0] - 80, 68));
            var lowerImage = new Bounds(0, 60, size[0] / 2, size[1] / 2);
            var spectrum = new Bounds(0, 0, size[0] / 2, 60);
            assertEquals(size[1] - 24, PipEditorLayout.buttonY(size[1], lowerImage, spectrum, 12, 80));
        }
    }

    @Test
    void bottomPlacementLeavesControlsAtTopAndFullScreenFallbackIsStable() {
        var image = new Bounds(0, 240, 480, 300);
        assertEquals(4, PipEditorLayout.buttonY(540, image, null, 12, 80));
        assertEquals(4, PipEditorLayout.buttonY(540, new Bounds(0, 0, 960, 540), null, 12, 80));
        assertEquals(1600, PipEditorLayout.overlap(new Bounds(12, 4, 80, 20), new Bounds(0, 0, 960, 540), null));
    }
}
