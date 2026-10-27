package com.darkmusic.client.hud;

import java.util.function.LongSupplier;

/**
 * One angle shared by every rotating circular layer, so layers turning in opposite directions stay in step.
 * It advances only while music plays or a preview is shown, and only on the render thread.
 */
final class CircularRotation {
    static final CircularRotation SHARED = new CircularRotation(System::nanoTime);

    private final LongSupplier clock;
    private long last;
    private double turn;

    CircularRotation(LongSupplier clock) {
        this.clock = clock;
    }

    double advance(int secondsPerTurn, boolean moving) {
        long now = clock.getAsLong();
        if (last != 0 && moving) turn = (turn + (now - last) / 1e9 / secondsPerTurn) % 1;
        last = now;
        return turn;
    }
}
