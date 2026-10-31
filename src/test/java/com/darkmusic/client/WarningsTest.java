package com.darkmusic.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarningsTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final ArrayList<String> spoken = new ArrayList<>();
    private final Warnings warnings = new Warnings(now::get, spoken::add);

    @Test
    void aRepeatedWarningIsReadAloudOnceAndExtendsItsTime() {
        warnings.warn("Disk full");
        now.addAndGet(TimeUnit.SECONDS.toNanos(9));
        warnings.warn("Disk full");
        assertEquals(1, spoken.size());
        now.addAndGet(TimeUnit.SECONDS.toNanos(9));
        warnings.tick();
        assertEquals("Disk full", warnings.current(), "the repeat restarted the ten seconds");
        warnings.warn("Other");
        warnings.warn("Disk full");
        assertEquals(List.of("Disk full", "Other", "Disk full"), spoken);
    }

    @Test
    void aWarningExpiresAfterTenSeconds() {
        warnings.warn("Gone soon");
        now.addAndGet(TimeUnit.SECONDS.toNanos(10) - 1);
        warnings.tick();
        assertEquals("Gone soon", warnings.current());
        now.incrementAndGet();
        warnings.tick();
        assertEquals("", warnings.current());
    }

    @Test
    void clearRemovesItAtOnce() {
        warnings.warn("Reset");
        warnings.clear();
        assertEquals("", warnings.current());
        warnings.tick();
        assertEquals("", warnings.current());
    }
}
