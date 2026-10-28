package com.darkmusic.client.pip;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Developer-only stage timings; never stores pixels, audio, window names or URLs.
 */
public final class PipTrace {
    private static final PipTrace INSTANCE = create();
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(8192);
    private volatile boolean closed;
    private final AtomicLong dropped = new AtomicLong();

    private static PipTrace create() {
        String directory = System.getProperty("darkmusic.pip.trace");
        if (directory == null || directory.isBlank()) return null;
        try {
            return new PipTrace(Path.of(directory));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private PipTrace(Path directory) {
        var worker = Thread.ofPlatform().daemon().name("darkmusic-pip-timings").start(() -> {
            try {
                Files.createDirectories(directory);
                try (var out = Files.newBufferedWriter(directory.resolve("java-" + ProcessHandle.current().pid() + ".csv"))) {
                    out.write("stage,sequence,nanos,capturedMicros,sentMicros,durationNanos,unixMicros\n");
                    while (!closed || !pending.isEmpty()) {
                        var line = pending.poll(100, TimeUnit.MILLISECONDS);
                        if (line != null) {
                            out.write(line);
                            out.newLine();
                        }
                        if (pending.isEmpty()) out.flush();
                    }
                    out.write("dropped," + dropped + ",0,0,0,0,0\n");
                }
            } catch (Exception ignored) { /* Diagnostics cannot fail playback/capture. */ }
        });
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            closed = true;
            try {
                worker.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    public static void event(String stage, PipFrame frame, long nanos, long durationNanos) {
        var trace = INSTANCE;
        if (trace == null || trace.closed) return;
        if (!trace.pending.offer(stage + "," + frame.sequence() + "," + nanos + "," + frame.capturedMicros() + "," + frame.sentMicros() + "," + durationNanos + "," + System.currentTimeMillis() * 1000))
            trace.dropped.incrementAndGet();
    }
}
