package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PipFramesTest {
    private static byte[] header(int width, int height, long sequence) {
        return ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PipFrame.MAGIC).putInt(1).putInt(width).putInt(height).putInt(width * 4).putInt(width * height * 4)
            .putLong(sequence).putLong(100).putLong(101).putInt(1).putInt(0).putLong(0).array();
    }

    private static PipFrame frame(long sequence) throws IOException {
        return PipFrame.decode(ByteBuffer.wrap(header(2, 2, sequence)), System.nanoTime());
    }

    private static void publish(PipFrames frames, long sequence) throws IOException {
        try (var lease = frames.writing(frame(sequence))) {
            assertNotNull(lease);
            while (lease.pixels().hasRemaining()) lease.pixels().put((byte) sequence);
            lease.publish();
        }
    }

    @Test
    void invalidHeadersCannotAllocateUnboundedFrames() throws IOException {
        assertEquals(16, frame(1).bytes());
        for (int offset : new int[]{0, 4, 8, 12, 16, 20, 48, 52, 56}) {
            byte[] data = header(2, 2, 1);
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> PipFrame.decode(ByteBuffer.wrap(data), 0), "offset " + offset);
        }
        assertThrows(IOException.class, () -> PipFrame.decode(ByteBuffer.wrap(header(3841, 2160, 1)), 0));
        assertThrows(IOException.class, () -> PipFrame.decode(ByteBuffer.allocate(63), 0));
    }

    @Test
    void slowRendererGetsNewestFrameWithoutCorruptingItsHeldFrame() throws Exception {
        try (var frames = new PipFrames()) {
            publish(frames, 1);
            try (var held = frames.latest()) {
                assertNotNull(held);
                for (int i = 2; i <= 1000; i++) publish(frames, i);
                assertEquals(1, held.frame().sequence());
                while (held.pixels().hasRemaining()) assertEquals((byte) 1, held.pixels().get());
                try (var latest = frames.latest()) {
                    assertEquals(1000, latest.frame().sequence());
                    assertEquals((byte) 1000, latest.pixels().get());
                }
                assertEquals(998, frames.dropped());
            }
        }
    }

    @Test
    void buffersAreReusedAndLatePublicationCannotReviveClosedCapture() throws Exception {
        var frames = new PipFrames();
        var buffers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ByteBuffer, Boolean>());
        for (int i = 1; i <= 100; i++) {
            try (var writer = frames.writing(frame(i))) {
                buffers.add(writer.pixels());
                writer.pixels().position(16);
                writer.publish();
            }
            try (var latest = frames.latest()) {
                assertNotNull(latest);
            }
        }
        assertTrue(buffers.size() <= 3);
        var late = frames.writing(frame(101));
        frames.close();
        late.publish();
        late.close();
        assertNull(frames.latest());
        assertNull(frames.writing(frame(102)));
        assertThrows(IllegalStateException.class, late::pixels);
    }

    @Test
    void streamReadsFragmentedFramesAndDiscardsOldSequences() throws Exception {
        var bytes = new ByteArrayOutputStream();
        for (int sequence : new int[]{1, 3, 2}) {
            bytes.write(header(2, 2, sequence));
            bytes.write(new byte[16]);
        }
        var fragmented = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override
            public int read(byte[] data, int offset, int length) throws IOException {
                return super.read(data, offset, Math.min(3, length));
            }
        };
        try (var frames = new PipFrames(); var reader = new PipFrameReader(Channels.newChannel(fragmented), frames)) {
            reader.read();
            try (var latest = frames.latest()) {
                assertEquals(3, latest.frame().sequence());
            }
            assertEquals(2, frames.published());
        }
    }

    @Test
    void truncatedAndStalledStreamsFailInsteadOfPublishingPartialPixels() throws Exception {
        byte[] truncated = java.util.Arrays.copyOf(header(2, 2, 1), 70);
        try (var frames = new PipFrames(); var reader = new PipFrameReader(Channels.newChannel(new ByteArrayInputStream(truncated)), frames)) {
            assertThrows(EOFException.class, reader::read);
            assertNull(frames.latest());
        }
        try (var frames = new PipFrames(); var reader = new PipFrameReader(new ReadableByteChannel() {
            public int read(ByteBuffer b) {
                return 0;
            }

            public boolean isOpen() {
                return true;
            }

            public void close() {
            }
        }, frames, 1_000_000)) {
            assertThrows(IOException.class, reader::read);
        }
    }

    @Test
    void producerAndRendererCanRunConcurrentlyWithoutTearing() throws Exception {
        try (var frames = new PipFrames(); var executor = Executors.newSingleThreadExecutor()) {
            var failure = new AtomicReference<Throwable>();
            var start = new CountDownLatch(1);
            var producer = executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 1; i <= 10_000; i++) publish(frames, i);
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            start.countDown();
            long last = 0;
            while (!producer.isDone()) {
                try (var lease = frames.latest()) {
                    if (lease == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    assertTrue(lease.frame().sequence() > last);
                    last = lease.frame().sequence();
                    while (lease.pixels().hasRemaining()) assertEquals((byte) last, lease.pixels().get());
                }
            }
            producer.get(5, TimeUnit.SECONDS);
            assertNull(failure.get());
        }
    }
}
