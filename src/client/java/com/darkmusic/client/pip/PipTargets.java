package com.darkmusic.client.pip;

/**
 * Aggregates every draw in an extraction frame and bounds continuous resizing to ten requests per second.
 */
public final class PipTargets {
    private Object extraction;
    private PipTarget current, sent;
    private long lastSent;

    public PipTarget request(Object frame, PipTarget target, long now) {
        PipTarget ready = null;
        if (extraction != frame) {
            if (current != null && !current.equals(sent) && (sent == null || now - lastSent >= 100_000_000L)) {
                ready = current;
                sent = ready;
                lastSent = now;
            }
            extraction = frame;
            current = target;
        } else current = current.union(target);
        return ready;
    }
}
