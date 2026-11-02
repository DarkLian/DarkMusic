package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipMappingTest {
    private static PipFrame cropped() {
        return new PipFrame(200, 100, 80000, 1, 0, 0, 0, 1000, 500, 100, 50, 400, 200);
    }

    @Test
    void geometryUsesTheOriginalSourceAndKeepsFractionalTexels() {
        var m = PipMapping.of(cropped(), .15, .15, .45, .45);
        assertEquals(.125, m.u0(), 1e-7);
        assertEquals(.875, m.u1(), 1e-7);
        assertEquals(.125, m.v0(), 1e-7);
        assertEquals(.875, m.v1(), 1e-7);
        var fractional = PipMapping.of(new PipFrame(7, 3, 84, 1, 0, 0, 0), .1, 0, .9, 1);
        assertEquals(.1, fractional.u0(), 1e-7);
        assertEquals(.9, fractional.u1(), 1e-7);
    }

    @Test
    void staleCropDrawsOnlyAvailablePixelsInTheirCorrectDestination() {
        var m = PipMapping.of(cropped(), 0, 0, 1, 1);
        assertEquals(.1, m.left(), 1e-7);
        assertEquals(.5, m.right(), 1e-7);
        assertEquals(0, m.u0());
        assertEquals(1, m.u1());
        assertNull(PipMapping.of(cropped(), .8, .8, 1, 1));
    }
}
