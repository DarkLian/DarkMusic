package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedPlaybackTest {
    @Test
    @Timeout(25)
    void liveControllerSnapshotsChangeThePcmActuallyAcceptedByPlayback() throws Exception {
        var clip = TestMusic.clip();
        var format = clip.format();
        byte[] music = clip.bytes();
        var phase = new AtomicInteger();
        var frames = new AtomicLong();
        var error = new java.util.concurrent.atomic.DoubleAdder[5];
        var counts = new LongAdder[5];
        for (int i = 0; i < 5; i++) {
            error[i] = new java.util.concurrent.atomic.DoubleAdder();
            counts[i] = new LongAdder();
        }
        var running = new AtomicBoolean();
        var failed = new AtomicReference<String>();
        SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getFormat" -> format;
            case "available", "getBufferSize" -> 2048;
            case "isRunning", "isActive" -> running.get();
            case "isOpen" -> true;
            case "getLongFramePosition" -> frames.get();
            case "getFramePosition" -> (int) frames.get();
            case "start" -> {
                running.set(true);
                yield null;
            }
            case "stop" -> {
                running.set(false);
                yield null;
            }
            case "flush", "drain", "close", "open", "addLineListener", "removeLineListener" -> null;
            case "write" -> {
                byte[] bytes = (byte[]) args[0];
                int offset = (int) args[1], length = Math.min((int) args[2], 512) / format.getFrameSize() * format.getFrameSize();
                long sample = frames.get() * format.getChannels();
                int p = phase.get();
                for (int i = 0; i < length; i += 2) {
                    int original = (int) ((sample + i / 2) * 2 % music.length);
                    short a = (short) ((bytes[offset + i] & 255) | (bytes[offset + i + 1] << 8));
                    short b = (short) ((music[original] & 255) | (music[original + 1] << 8));
                    error[p].add(Math.abs(a - b) / 32768.0);
                    counts[p].increment();
                }
                frames.addAndGet(length / format.getFrameSize());
                Thread.sleep(2);
                yield length;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        try (var backend = new JavaSoundBackend(Runnable::run, path -> {
            InputStream loop = new InputStream() {
                long at;

                @Override
                public int read() {
                    return music[(int) (at++ % music.length)] & 255;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    for (int i = 0; i < len; i++) b[off + i] = (byte) read();
                    return len;
                }
            };
            return new AudioInputStream(loop, format, AudioSystem.NOT_SPECIFIED);
        }, f -> line)) {
            var controller = new PlaybackController(backend, failed::set);
            var a = new AudioSettings();
            var e = new SoundEnvironmentSettings();
            controller.volume(1);
            controller.configure(a, e);
            backend.diagnostics().panels(AudioDiagnostics.ALL);
            var file = TestMusic.featured();
            var track = new com.darkmusic.client.library.MetadataReader().read("live", file.getFileName().toString(), file, java.nio.file.Files.size(file), 0);
            controller.play(java.util.List.of(track), track);
            StorageTest.await(() -> counts[0].sum() > 4000);
            assertEquals(0, error[0].sum() / counts[0].sum(), 1e-7);
            phase.set(1);
            a.neutral = false;
            a.equalizer = true;
            a.bands[5] = 12;
            controller.configure(a, e);
            StorageTest.await(() -> counts[1].sum() > 16000);
            assertTrue(error[1].sum() / counts[1].sum() > 1e-4);
            var eqSnapshot = backend.diagnostics().snapshot();
            assertNotNull(eqSnapshot);
            phase.set(2);
            e.select(SoundEnvironmentSettings.Preset.TELEPHONE);
            controller.configure(a, e);
            StorageTest.await(() -> counts[2].sum() > 16000);
            assertTrue(error[2].sum() / counts[2].sum() > 1e-4);
            phase.set(3);
            a.neutral = true;
            controller.configure(a, e);
            StorageTest.await(() -> counts[3].sum() > 16000);
            assertTrue(error[3].sum() / counts[3].sum() > 1e-4);
            phase.set(4);
            e.select(SoundEnvironmentSettings.Preset.OFF);
            controller.configure(a, e);
            // Wait for processed frames, not wall time: a debugger may deliberately slow the worker.
            StorageTest.await(() -> counts[4].sum() > format.getSampleRate() * format.getChannels() * .5);
            error[4].reset();
            counts[4].reset();
            StorageTest.await(() -> counts[4].sum() > 8000);
            assertEquals(0, error[4].sum() / counts[4].sum(), 1e-6);
            assertNull(failed.get());
            controller.stop();
            assertTrue(backend.awaitIdle(3));
        }
    }
}
