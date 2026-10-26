package com.darkmusic.client.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WaveformRetriesTest {
    @Test
    void retriesAreBoundedAndPermanentLimitsStaySuppressed() {
        var r = new WaveformRetries();
        long m = TimeUnit.MINUTES.toNanos(1);
        r.failed("long", 0, true);
        assertFalse(r.ready("long", m * 10000));
        assertTrue(r.ready("changed identity", 0));
        r.failed("temporary", 0, false);
        assertFalse(r.ready("temporary", m - 1));
        assertTrue(r.ready("temporary", m));
        r.failed("temporary", m, false);
        assertFalse(r.ready("temporary", 3 * m - 1));
        assertTrue(r.ready("temporary", 3 * m));
        r.succeeded("temporary");
        assertTrue(r.ready("temporary", 0));
    }
}