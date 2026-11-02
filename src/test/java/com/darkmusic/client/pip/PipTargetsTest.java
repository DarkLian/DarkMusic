package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipTargetsTest {
    @Test
    void requestsUseSourceGeometryAndNeverUpscale() {
        var f = new PipFrame(100, 50, 20000, 1, 0, 0, 0, 1000, 500, 0, 0, 1000, 500);
        assertEquals(new PipTarget(100, 50, 400, 200, 200, 100), PipTarget.of(f, .1, .1, .5, .5, 200, 100));
        assertEquals(new PipTarget(0, 0, 1000, 500, 1000, 500), PipTarget.of(f, 0, 0, 1, 1, 2000, 1000));
    }

    @Test
    void multipleDrawsUnionTheirCoverageAndUseTheHighestDensity() {
        var a = new PipTarget(100, 50, 400, 200, 200, 100);
        var b = new PipTarget(0, 0, 1000, 500, 800, 400);
        assertEquals(b, a.union(b));
    }

    @Test
    void draggingIsBoundedAndTheFinalSizeIsSentAfterSettling() {
        var state = new PipTargets();
        var first = new PipTarget(0, 0, 1000, 500, 200, 100);
        var last = new PipTarget(0, 0, 1000, 500, 600, 300);
        assertNull(state.request(new Object(), first, 0));
        assertEquals(first, state.request(new Object(), first, 1));
        assertNull(state.request(new Object(), last, 2));
        assertNull(state.request(new Object(), last, 50_000_000));
        assertEquals(last, state.request(new Object(), last, 100_000_001));
        assertNull(state.request(new Object(), last, 200_000_001));
    }

    @Test
    void editorAndOverlayAreAggregatedBeforeTheNextExtraction() {
        var state = new PipTargets();
        var frame = new Object();
        var small = new PipTarget(100, 50, 400, 200, 200, 100);
        var full = new PipTarget(0, 0, 1000, 500, 800, 400);
        state.request(frame, small, 0);
        state.request(frame, full, 0);
        assertEquals(full, state.request(new Object(), small, 1));
    }
}
