package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TransitionAudioTest {
    @Test
    void gaplessAndCrossfadeReuseOneDeviceAcrossRatesAndChannelLayouts() throws Exception {
        var clip = TestMusic.clip();
        var initial = clip.format();
        for (double crossfade : new double[]{0, .5}) {
            var frames = new AtomicLong();
            var opens = new AtomicInteger();
            var closed = new AtomicBoolean();
            var ended = new AtomicBoolean();
            var secondStarted = new AtomicBoolean();
            var error = new AtomicReference<String>();
            var nextReady = new java.util.concurrent.CountDownLatch(1);
            SourceDataLine output = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (p, m, a) -> switch (m.getName()) {
                case "getFormat" -> initial;
                case "available", "getBufferSize" -> 1024;
                case "getLongFramePosition" -> frames.get();
                case "isRunning", "isOpen" -> true;
                case "start", "stop", "flush", "drain" -> null;
                case "write" -> {
                    int count = (int) a[2];
                    frames.addAndGet(count / initial.getFrameSize());
                    assertTrue(nextReady.await(5, java.util.concurrent.TimeUnit.SECONDS), "Next decoder did not prepare");
                    yield count;
                }
                case "close" -> {
                    closed.set(true);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
            var a = new Track("t", "a", Path.of("a"), "a", "artist", "album", 4, 1, 0, "");
            var b = new Track("t", "b", Path.of("b"), "b", "artist", "album", 4, 1, 0, "");
            try (var backend = new JavaSoundBackend(Runnable::run, path -> {
                if (path.equals(a.file())) return clip.stream();
                byte[] bytes = new byte[4096];
                var converted = new ByteArrayOutputStream();
                try (var in = clip.stream()) {
                    var converter = new PcmRateConverter(in);
                    int n;
                    while ((n = converter.read(bytes, bytes.length, initial.getSampleRate() / 48000)) >= 0)
                        if (n > 0) converted.write(bytes, 0, n);
                }
                bytes = converted.toByteArray();
                int count = bytes.length;
                // A real collection excerpt, converted to mono at a different sample rate.
                var full = new ByteArrayOutputStream();
                for (int at = 0; at < count; at += initial.getFrameSize()) {
                    full.write(bytes[at]);
                    full.write(bytes[at + 1]);
                }
                byte[] mono = full.toByteArray();
                nextReady.countDown();
                return new AudioInputStream(new ByteArrayInputStream(mono), new AudioFormat(48000, 16, 1, true, false), mono.length / 2);
            }, f -> {
                opens.incrementAndGet();
                return output;
            })) {
                var options = new AudioSettings();
                options.crossfade = crossfade;
                backend.configure(options);
                backend.play(a, () -> {
                }, error::set);
                backend.nextTrack(b, () -> secondStarted.set(true), () -> ended.set(true), error::set);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (!ended.get() && error.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
                assertNull(error.get());
                assertTrue(secondStarted.get(), "Prepared track must become audible");
                assertTrue(ended.get(), () -> "Transition did not finish: crossfade=" + crossfade + ", state=" + backend.state() + ", outputFrames=" + frames.get() + ", workers=" + Thread.getAllStackTraces().entrySet().stream().filter(e -> e.getKey().getName().startsWith("darkmusic-audio")).map(e -> e.getKey().getName() + java.util.Arrays.toString(e.getValue())).toList());
                assertEquals(1, opens.get(), "Queue transition must retain its output device");
            }
            assertTrue(closed.get());
        }
    }
}
