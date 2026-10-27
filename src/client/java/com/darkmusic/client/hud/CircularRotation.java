package com.darkmusic.client.hud;

/**
 * One angle shared by every rotating circular layer, so layers turning in opposite directions stay in step.
 * It advances only while music plays or a preview is shown, and only on the render thread.
 */
final class CircularRotation {
    private static long last;
    private static double turn;

    private CircularRotation() {
    }

    static double advance(int secondsPerTurn, boolean moving) {
        long now = System.nanoTime();
        if (last != 0 && moving) turn = (turn + (now - last) / 1e9 / secondsPerTurn) % 1;
        last = now;
        return turn;
    }
}
