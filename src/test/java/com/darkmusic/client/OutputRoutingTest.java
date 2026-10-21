package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.library.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class OutputRoutingTest {
    @TempDir
    Path directory;

    private static class Line {
        final AtomicLong frames = new AtomicLong();
        volatile boolean running, closed;
        final SourceDataLine value;

        Line(AudioFormat format) {
            value = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getFormat" -> format;
                case "available", "getBufferSize" -> 1024;
                case "isRunning" -> running;
                case "isOpen" -> !closed;
                case "getLongFramePosition" -> frames.get();
                case "start" -> {
                    running = true;
                    yield null;
                }
                case "stop" -> {
                    running = false;
                    yield null;
                }
                case "close" -> {
                    closed = true;
                    running = false;
                    yield null;
                }
                case "flush", "drain" -> null;
                case "write" -> {
                    int n = (int) args[2];
                    frames.addAndGet(n / format.getFrameSize());
                    Thread.sleep(2);
                    yield n;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    private AudioInputStream loop(TestMusic.Clip clip) {
        byte[] bytes = clip.bytes();
        var input = new java.io.InputStream() {
            int at;

            public int read() {
                int value = bytes[at] & 255;
                at = (at + 1) % bytes.length;
                return value;
            }

            public int read(byte[] b, int off, int len) {
                for (int i = 0; i < len; i++) b[off + i] = (byte) read();
                return len;
            }
        };
        return new AudioInputStream(input, clip.format(), AudioSystem.NOT_SPECIFIED);
    }

    @Test
    @Timeout(20)
    void failedDeviceSwitchKeepsPlayingAndPausedSwitchPreservesPosition() throws Exception {
        var clip = TestMusic.clip();
        var line = new AtomicReference<Line>();
        var errors = new AtomicReference<String>();
        try (var backend = new JavaSoundBackend(Runnable::run, p -> loop(clip), format -> {
            var opened = new Line(format);
            line.set(opened);
            return opened.value;
        })) {
            var song = new Track("real", "collection", Path.of("collection"), "", "", "", 300, 1, 1, "");
            backend.play(song, () -> {
            }, errors::set);
            backend.nextTrack(new Track("real", "next", Path.of("next"), "", "", "", 300, 1, 1, ""), () -> {
            }, () -> {
            }, errors::set);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() > .2);
            Line original = line.get();
            long before = original.frames.get();
            assertFalse(backend.output(f -> {
                throw new LineUnavailableException("Unplugged output");
            }).get(5, TimeUnit.SECONDS));
            assertFalse(original.closed);
            StorageTest.await(() -> original.frames.get() > before + 512);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double position = backend.state().position();
            var target = new AtomicReference<Line>();
            assertTrue(backend.output(f -> {
                var opened = new Line(f);
                target.set(opened);
                return opened.value;
            }).get(5, TimeUnit.SECONDS));
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            assertTrue(original.closed);
            assertFalse(target.get().running);
            assertEquals(position, backend.state().position(), .03);
            backend.resume();
            StorageTest.await(() -> target.get().frames.get() > 1024);
            assertNull(errors.get());
            backend.stop();
            assertTrue(backend.awaitIdle(3));
            assertTrue(backend.output(f -> new Line(f).value).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(20)
    void deviceClockHistoryExcludesMutedPausedAndSeekedAudioWithoutNewPlayCounts() throws Exception {
        var clip = TestMusic.clip();
        var line = new AtomicReference<Line>();
        var history = new ListeningHistory(directory, Runnable::run, s -> fail(s));
        history.load();
        var errors = new AtomicReference<String>();
        try (var backend = new JavaSoundBackend(Runnable::run, p -> loop(clip), format -> {
            var opened = new Line(format);
            line.set(opened);
            return opened.value;
        })) {
            backend.listening(history::heard);
            var track = new Track("real", "collection", Path.of("collection"), "", "", "", 300, 1, 1, "");
            backend.play(track, () -> {
            }, errors::set);
            StorageTest.await(() -> history.snapshot().containsKey(track.id()) && history.snapshot().get(track.id()).seconds() > .2);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double heard = history.snapshot().get(track.id()).seconds();
            Thread.sleep(40);
            assertEquals(heard, history.snapshot().get(track.id()).seconds());
            backend.seek(2);
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED && backend.state().position() > 1.9);
            assertEquals(heard, history.snapshot().get(track.id()).seconds(), .01);
            backend.volume(0);
            backend.resume();
            long frames = line.get().frames.get();
            StorageTest.await(() -> line.get().frames.get() > frames + 4096);
            assertEquals(heard, history.snapshot().get(track.id()).seconds(), .01);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            backend.volume(1);
            backend.speed(2);
            backend.resume();
            StorageTest.await(() -> history.snapshot().get(track.id()).seconds() > heard + .2);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            assertTrue(history.snapshot().get(track.id()).seconds() < heard + 2);
            assertEquals(0, history.snapshot().get(track.id()).plays());
            assertNull(errors.get());
        }
    }

    @Test
    @Timeout(20)
    void resumingAfterMetadataEditsPreservesTheListeningSession() throws Exception {
        var clip = TestMusic.clip();
        var history = new ListeningHistory(directory, Runnable::run, s -> fail(s));
        history.load();
        var errors = new AtomicReference<String>();
        try (var backend = new JavaSoundBackend(Runnable::run, p -> loop(clip), format -> new Line(format).value)) {
            backend.listening(history::heard);
            var track = new Track("real", "collection", Path.of("collection"), "", "", "", 2, 1, 1, "");
            backend.play(track, () -> {
            }, errors::set);
            StorageTest.await(() -> history.snapshot().containsKey(track.id()) && history.snapshot().get(track.id()).plays() == 1);
            backend.pause();
            StorageTest.await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double position = backend.state().position(), before = history.snapshot().get(track.id()).seconds();
            backend.stop();
            assertTrue(backend.awaitIdle(3));
            backend.resumeFrom(track, position, false, () -> {
            }, errors::set);
            StorageTest.await(() -> history.snapshot().get(track.id()).seconds() > before + 1.1);
            assertEquals(1, history.snapshot().get(track.id()).plays());
            backend.play(track, () -> {
            }, errors::set);
            StorageTest.await(() -> history.snapshot().get(track.id()).plays() == 2);
            assertNull(errors.get());
        }
    }
}
