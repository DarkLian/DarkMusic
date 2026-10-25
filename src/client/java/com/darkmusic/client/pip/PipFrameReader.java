package com.darkmusic.client.pip;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.locks.LockSupport;

/**
 * Blocking pipe reads belong on one background thread, never a Minecraft tick/render callback.
 */
public final class PipFrameReader implements AutoCloseable {
    private final ReadableByteChannel input;
    private final PipFrames frames;
    private final long emptyReadTimeout;
    private volatile boolean closed;

    public PipFrameReader(ReadableByteChannel input, PipFrames frames) {
        this(input, frames, 5_000_000_000L);
    }

    PipFrameReader(ReadableByteChannel input, PipFrames frames, long emptyReadTimeout) {
        this.input = input;
        this.frames = frames;
        this.emptyReadTimeout = emptyReadTimeout;
    }

    public void read() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(PipFrame.HEADER_BYTES);
        long last = 0;
        try {
            while (!closed) {
                header.clear();
                if (!readFully(header, true)) return;
                header.flip();
                PipFrame frame = PipFrame.decode(header, System.nanoTime());
                PipTrace.event("header", frame, frame.receivedNanos(), 0);
                try (var lease = frames.writing(frame)) {
                    if (lease == null) return;
                    readFully(lease.pixels(), false);
                    long completed = System.nanoTime();
                    PipTrace.event("receive", frame, completed, completed - frame.receivedNanos());
                    if (!closed && frame.sequence() > last) {
                        last = frame.sequence();
                        lease.publish();
                    }
                }
            }
        } catch (IOException failure) {
            if (!closed) throw failure;
        }
    }

    private boolean readFully(ByteBuffer target, boolean cleanEof) throws IOException {
        int initial = target.remaining();
        long progress = System.nanoTime();
        while (target.hasRemaining() && !closed) {
            int count = input.read(target);
            if (count < 0) {
                if (cleanEof && target.remaining() == initial) return false;
                throw new EOFException("Truncated PiP frame");
            }
            if (count > 0) progress = System.nanoTime();
            else {
                if (System.nanoTime() - progress > emptyReadTimeout)
                    throw new IOException("PiP stream made no progress");
                LockSupport.parkNanos(1_000_000);
            }
        }
        return !closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try {
            input.close();
        } finally {
            frames.close();
        }
    }
}
