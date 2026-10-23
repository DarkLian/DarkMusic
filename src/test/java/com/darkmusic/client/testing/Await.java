package com.darkmusic.client.testing;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class Await {
    private Await() {
    }

    public static void until(Duration timeout, BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("Interrupted: " + description);
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out: " + description);
            LockSupport.parkNanos(5_000_000);
        }
    }
}
