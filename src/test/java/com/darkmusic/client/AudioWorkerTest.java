package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AudioWorkerTest {
    @Test
    void repeatedEmptyDecoderReadsFailButLegitimatePrimingRecovers() throws Exception {
        var clip = TestMusic.clip();
        var track = StorageTest.track("real", "empty-read", TestMusic.featured());
        for (boolean recover : List.of(false, true)) {
            var reads = new AtomicInteger();
            var failure = new AtomicReference<String>();
            var resources = new CopyOnWriteArrayList<AtomicBoolean>();
            try (var backend = new JavaSoundBackend(Runnable::run, path -> new AudioInputStream(clip.stream(), clip.format(), clip.bytes().length / clip.format().getFrameSize()) {
                @Override
                public int read(byte[] bytes, int off, int len) throws IOException {
                    int n = reads.incrementAndGet();
                    return !recover || n <= 4 ? 0 : super.read(bytes, off, len);
                }
            }, f -> output(resources), Duration.ofSeconds(2), Duration.ofMillis(100))) {
                backend.play(track, () -> {
                }, failure::set);
                if (recover) {
                    Await.until(Duration.ofSeconds(5), () -> backend.state().position() > .01 || failure.get() != null, "Priming ends");
                    assertNull(failure.get());
                    assertTrue(reads.get() > 4);
                } else {
                    Await.until(Duration.ofSeconds(5), () -> failure.get() != null, "Empty decoder is bounded");
                    assertEquals(AudioBackend.Status.ERROR, backend.state().status());
                    assertTrue(reads.get() > 1);
                    assertTrue(backend.awaitIdle(3));
                }
            }
        }
    }

    @Test
    void openingAtSavedPositionIsAtomicAndCanRemainPaused() throws Exception {
        var lines = new CopyOnWriteArrayList<AtomicBoolean>();
        var failure = new AtomicReference<String>();
        Path file = TestMusic.featured();
        var track = new MetadataReader().read("real", file.getFileName().toString(), file, Files.size(file), 0);
        try (var backend = new JavaSoundBackend(Runnable::run, path -> PcmDecoder.open(path), f -> output(lines))) {
            for (int i = 0; i < 8; i++) {
                backend.playFrom(track, 30 + i * .1, true, () -> fail("Unexpected completion"), failure::set);
                StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
                assertEquals(30 + i * .1, backend.state().position(), .02);
                backend.stop();
                assertTrue(backend.awaitIdle(3));
            }
            assertNull(failure.get());
        }
    }


    private SourceDataLine output(List<AtomicBoolean> resources) throws Exception {
        var format = TestMusic.clip().format();
        AtomicBoolean closed = new AtomicBoolean(), running = new AtomicBoolean();
        AtomicLong frames = new AtomicLong();
        resources.add(closed);
        return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getFormat" -> format;
            case "available", "getBufferSize" -> 512;
            case "getLongFramePosition" -> frames.get();
            case "getFramePosition" -> (int) frames.get();
            case "isRunning", "isActive" -> running.get();
            case "isOpen" -> !closed.get();
            case "start" -> {
                running.set(true);
                yield null;
            }
            case "stop" -> {
                running.set(false);
                yield null;
            }
            case "close" -> {
                closed.set(true);
                yield null;
            }
            case "write" -> {
                int count = (int) args[2];
                frames.addAndGet(count / format.getFrameSize());
                Thread.sleep(2);
                yield count;
            }
            case "flush", "drain", "open", "addLineListener", "removeLineListener" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void pauseSeekLatestCommandStopAndCloseReleaseAllResources() throws Exception {
        Path source = TestMusic.featured();
        List<AtomicBoolean> lines = new CopyOnWriteArrayList<>(), streams = new CopyOnWriteArrayList<>();
        var backend = new JavaSoundBackend(Runnable::run, path -> {
            AtomicBoolean closed = new AtomicBoolean();
            streams.add(closed);
            var decoded = PcmDecoder.open(path);
            return new AudioInputStream(decoded, decoded.getFormat(), decoded.getFrameLength()) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
        }, f -> output(lines));
        try {
            var track = new MetadataReader().read("real", source.getFileName().toString(), source, Files.size(source), 0);
            AtomicReference<String> failure = new AtomicReference<>();
            backend.play(track, () -> {
            }, failure::set);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PLAYING);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            backend.speed(2);
            double paused = backend.state().position();
            Thread.sleep(40);
            assertEquals(paused, backend.state().position());
            backend.resume();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PLAYING);
            for (int i = 0; i < 50; i++) backend.seek(20 + i * .1);
            backend.seek(.75);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() >= .74);
            assertTrue(backend.state().position() < 1.5, "An obsolete seek must not replace the latest seek");
            backend.stop();
            assertTrue(backend.awaitIdle(3), "Stopped playback must release file handles for metadata editing");
            StorageTest.await(() -> lines.stream().allMatch(AtomicBoolean::get) && streams.stream().allMatch(AtomicBoolean::get));
            assertEquals(AudioBackend.Status.STOPPED, backend.state().status());
            assertNull(failure.get());
        } finally {
            backend.close();
        }
        assertTrue(lines.stream().allMatch(AtomicBoolean::get));
        assertTrue(streams.stream().allMatch(AtomicBoolean::get));
    }

    @Test
    void realOutputSamplesDriveSpectrumAndPausedSeekClearsIt() throws Exception {
        var clip = TestMusic.clip();
        List<AtomicBoolean> lines = new CopyOnWriteArrayList<>();
        float[] values = new float[64];
        try (var backend = new JavaSoundBackend(Runnable::run, path -> clip.stream(), f -> output(lines))) {
            backend.spectrum().configure(true, 24, 1, 0);
            backend.play(StorageTest.track("real", TestMusic.featured().getFileName().toString(), TestMusic.featured()), () -> {
            }, message -> fail(message));
            StorageTest.await(() -> {
                backend.spectrum().copyTo(values);
                for (float value : values) if (value > .2) return true;
                return false;
            });
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            backend.spectrum().copyTo(values);
            float[] frozen = values.clone();
            Thread.sleep(40);
            backend.spectrum().copyTo(values);
            assertArrayEquals(frozen, values);
            backend.seek(.2);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED && backend.state().position() >= .19);
            backend.spectrum().copyTo(values);
            assertArrayEquals(new float[64], values);
            backend.resume();
            StorageTest.await(() -> {
                backend.spectrum().copyTo(values);
                for (float value : values) if (value > .2) return true;
                return false;
            });
            backend.volume(0);
            StorageTest.await(() -> {
                backend.spectrum().copyTo(values);
                for (float value : values) if (value > .005) return false;
                return true;
            });
            backend.stop();
            StorageTest.await(() -> lines.stream().allMatch(AtomicBoolean::get));
            backend.spectrum().copyTo(values);
            assertArrayEquals(new float[64], values);
        }
    }

    @Test
    void outputFailureClosesDecoderAndReportsError() throws Exception {
        var clip = TestMusic.clip();
        AtomicBoolean streamClosed = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        try (var backend = new JavaSoundBackend(Runnable::run, path -> new AudioInputStream(clip.stream(), clip.format(), clip.bytes().length / clip.format().getFrameSize()) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        }, f -> {
            throw new LineUnavailableException("No output device");
        })) {
            backend.play(StorageTest.track("real", TestMusic.featured().getFileName().toString(), TestMusic.featured()), () -> fail("Unexpected completion"), failure::set);
            StorageTest.await(() -> failure.get() != null);
            assertTrue(streamClosed.get());
            assertEquals(AudioBackend.Status.ERROR, backend.state().status());
            assertEquals(Text.get("audio.error.device"), failure.get());
            assertFalse(failure.get().contains("LineUnavailableException"));
        }
    }

    @Test
    void stalledAvailabilityAndZeroWritesFailAndReleaseResources() throws Exception {
        for (boolean noCapacity : new boolean[]{false, true}) {
            var resources = new CopyOnWriteArrayList<AtomicBoolean>();
            var failure = new AtomicReference<String>();
            var delegate = output(resources);
            var stalled = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> {
                if (method.getName().equals("available") && noCapacity) return 0;
                if (method.getName().equals("write")) return 0;
                return method.invoke(delegate, args);
            });
            try (var backend = new JavaSoundBackend(Runnable::run, path -> TestMusic.clip().stream(), f -> stalled, Duration.ofMillis(300))) {
                backend.play(StorageTest.track("real", "stalled", TestMusic.featured()), () -> fail("Unexpected completion"), failure::set);
                StorageTest.await(() -> failure.get() != null);
                assertTrue(failure.get().contains("Audio"));
                assertTrue(resources.stream().allMatch(AtomicBoolean::get));
            }
        }
    }

    @Test
    void slowDecoderDoesNotConsumeTheOutputStallTimeout() throws Exception {
        var clip = TestMusic.clip();
        var resources = new CopyOnWriteArrayList<AtomicBoolean>();
        var failure = new AtomicReference<String>();
        var completed = new AtomicBoolean();
        try (var backend = new JavaSoundBackend(Runnable::run, path -> new AudioInputStream(clip.stream(), clip.format(), clip.bytes().length / clip.format().getFrameSize()) {
            boolean delayed;

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                if (!delayed) {
                    delayed = true;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                return super.read(bytes, off, len);
            }
        }, f -> {
            var delegate = output(resources);
            // Keep the deliberately slow decoder test independent of host sleep granularity.
            // A tiny fake device buffer otherwise requires thousands of timed writes.
            return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> {
                if (method.getName().equals("available") || method.getName().equals("getBufferSize")) return 4096;
                return method.invoke(delegate, args);
            });
        }, Duration.ofMillis(100))) {
            backend.play(StorageTest.track("real", "slow-decoder", TestMusic.featured()), () -> completed.set(true), failure::set);
            StorageTest.await(() -> completed.get() || failure.get() != null);
            assertNull(failure.get());
            assertTrue(completed.get());
        }
    }

    @Test
    void unsupportedDeviceRateFallsBackAndActualPositionCanExceedEstimatedDuration() throws Exception {
        var resources = new CopyOnWriteArrayList<AtomicBoolean>();
        var delegate = output(resources);
        var target = new AudioFormat(48_000, 16, TestMusic.clip().format().getChannels(), true, false);
        var device = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> method.getName().equals("getFormat") ? target : method.invoke(delegate, args));
        var sourceFormat = new AudioFormat(192_000, 16, target.getChannels(), true, false);
        var failure = new AtomicReference<String>();
        var track = new Track("real", "high-rate", TestMusic.featured(), "Real music", "", "", .001, 0, 0, "");
        try (var backend = new JavaSoundBackend(Runnable::run, path -> new AudioInputStream(new ByteArrayInputStream(TestMusic.clip().bytes()), sourceFormat, TestMusic.clip().bytes().length / sourceFormat.getFrameSize()), f -> {
            if (f.getFrameRate() != 48_000) throw new LineUnavailableException("Unsupported rate");
            return device;
        })) {
            backend.play(track, () -> {
            }, failure::set);
            StorageTest.await(() -> backend.state().position() > .01 || failure.get() != null);
            assertNull(failure.get());
            var observed = backend.state();
            assertTrue(observed.position() > track.duration());
            assertTrue(observed.duration() >= observed.position());
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            var field = JavaSoundBackend.class.getDeclaredField("worker");
            field.setAccessible(true);
            Thread worker = (Thread) field.get(backend);
            StorageTest.await(() -> worker.getState() == Thread.State.WAITING);
            backend.stop();
            StorageTest.await(() -> resources.stream().allMatch(AtomicBoolean::get));
        }
    }

}
