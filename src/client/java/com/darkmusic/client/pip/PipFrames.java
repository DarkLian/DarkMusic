package com.darkmusic.client.pip;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Three reusable buffers. Neither the capture reader nor the renderer waits for the other.
 */
public final class PipFrames implements AutoCloseable {
    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    private Slot pending;
    private boolean closed;
    private long dropped, published;

    public PipFrames() {
        for (int i = 0; i < 3; i++) free.add(new Slot());
    }

    private static final class Slot {
        ByteBuffer pixels = ByteBuffer.allocate(0);
        PipFrame frame;
    }

    public final class Lease implements AutoCloseable {
        private final Slot slot;
        private final boolean writer;
        private boolean released;

        private Lease(Slot slot, boolean writer) {
            this.slot = slot;
            this.writer = writer;
        }

        public ByteBuffer pixels() {
            if (released) throw new IllegalStateException("Released PiP frame");
            return slot.pixels;
        }

        public PipFrame frame() {
            if (released) throw new IllegalStateException("Released PiP frame");
            return slot.frame;
        }

        public void publish() {
            synchronized (PipFrames.this) {
                if (released || !writer) throw new IllegalStateException("Invalid PiP publication");
                released = true;
                if (closed) {
                    slot.pixels = ByteBuffer.allocate(0);
                    slot.frame = null;
                    return;
                }
                if (pending != null) {
                    free.add(pending);
                    dropped++;
                }
                pending = slot;
                published++;
            }
        }

        @Override
        public void close() {
            synchronized (PipFrames.this) {
                if (!released) {
                    released = true;
                    if (!closed) free.add(slot);
                    else {
                        slot.pixels = ByteBuffer.allocate(0);
                        slot.frame = null;
                    }
                }
            }
        }
    }

    /**
     * The single receiver calls this only after validating the header.
     */
    public Lease writing(PipFrame frame) {
        Slot slot;
        synchronized (this) {
            if (closed) return null;
            slot = free.pollFirst();
            if (slot == null && pending != null) {
                slot = pending;
                pending = null;
                dropped++;
            }
            if (slot == null) return null;
        }
        // Allocation/copy preparation is outside the render-thread lock.
        try {
            if (slot.pixels.capacity() < frame.bytes()) slot.pixels = ByteBuffer.allocateDirect(frame.bytes());
            slot.pixels.clear().limit(frame.bytes());
            slot.frame = frame;
            return new Lease(slot, true);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                free.add(slot);
            }
            throw failure;
        }
    }

    /**
     * Ownership lasts until close; a producer can never overwrite an acquired frame.
     */
    public synchronized Lease latest() {
        if (closed || pending == null) return null;
        Slot slot = pending;
        pending = null;
        slot.pixels.flip();
        return new Lease(slot, false);
    }

    public synchronized long dropped() {
        return dropped;
    }

    public synchronized long published() {
        return published;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pending != null) {
            pending = null;
        }
        free.clear();
    }
}
