package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.*;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.junit.jupiter.api.Assertions.*;

class NeutralTempoPlaybackTest {
    @Test
    @Timeout(20)
    void liveTempoSwitchDoesNotReopenDecoderAndPauseSeekResumeRemainUsable() throws Exception {
        var clip = TestMusic.clip();
        var format = clip.format();
        var frames = new AtomicLong();
        var opens = new AtomicInteger();
        var error = new AtomicReference<String>();
        SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (p, m, a) -> switch (m.getName()) {
            case "getFormat" -> format;
            case "available", "getBufferSize" -> 1024;
            case "isRunning", "isOpen" -> true;
            case "getLongFramePosition" -> frames.get();
            case "start", "stop", "flush", "drain", "close" -> null;
            case "write" -> {
                int n = (int) a[2];
                frames.addAndGet(n / format.getFrameSize());
                Thread.sleep(1);
                yield n;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        try (var backend = new JavaSoundBackend(Runnable::run, path -> {
            opens.incrementAndGet();
            byte[] music = clip.bytes();
            var loop = new InputStream() {
                int at;

                public int read() {
                    return music[at++ % music.length] & 255;
                }

                public int read(byte[] b, int off, int len) {
                    for (int i = 0; i < len; i++) b[off + i] = (byte) read();
                    return len;
                }
            };
            return new AudioInputStream(loop, format, AudioSystem.NOT_SPECIFIED);
        }, f -> line)) {
            var a = new AudioSettings();
            backend.configure(a);
            backend.speed(.8);
            backend.play(new Track("t", "source", Path.of("source"), "", "", "", 300, 1, 0, ""), () -> {
            }, error::set);
            Await.of(() -> frames.get() > 4096);
            int initial = opens.get();
            for (boolean preserve : new boolean[]{true, false, true}) {
                long before = frames.get();
                a.enhancements.preservePitch = preserve;
                backend.configure(a);
                Await.of(() -> frames.get() > before + 4096);
                assertEquals(initial, opens.get());
            }
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            long frozen = frames.get();
            Thread.sleep(30);
            assertEquals(frozen, frames.get());
            backend.seek(1);
            Await.of(() -> opens.get() > initial && backend.state().status() == AudioBackend.Status.PAUSED);
            backend.speed(1.2);
            backend.resume();
            Await.of(() -> frames.get() > frozen + 4096);
            assertNull(error.get());
            backend.stop();
            assertTrue(backend.awaitIdle(3));
        }
    }

    @Test
    @Timeout(45)
    void actualBackendUsesTheSelectedTempoModeWithAndWithoutNeutral() throws Exception {
        var clip = TestMusic.clip();
        var format = clip.format();
        for (boolean neutral : new boolean[]{true, false})
            for (boolean preserve : new boolean[]{false, true})
                for (double speed : new double[]{.8, 1.2}) {
                    var bytes = new ByteArrayOutputStream();
                    var frames = new AtomicLong();
                    var ended = new AtomicBoolean();
                    var error = new AtomicReference<String>();
                    SourceDataLine line = (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (p, m, a) -> switch (m.getName()) {
                        case "getFormat" -> format;
                        case "available", "getBufferSize" -> 4096;
                        case "isRunning", "isOpen" -> true;
                        case "getLongFramePosition" -> frames.get();
                        case "start", "stop", "flush", "drain", "close" -> null;
                        case "write" -> {
                            int n = Math.min((int) a[2], 512) / format.getFrameSize() * format.getFrameSize();
                            bytes.write((byte[]) a[0], (int) a[1], n);
                            frames.addAndGet(n / format.getFrameSize());
                            yield n;
                        }
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
                    try (var backend = new JavaSoundBackend(Runnable::run, path -> clip.stream(), f -> line)) {
                        var options = new AudioSettings();
                        options.neutral = neutral;
                        options.enhancements.preservePitch = preserve;
                        backend.configure(options);
                        backend.volume(1);
                        backend.speed(speed);
                        var t = new Track("test", "source", Path.of("source"), "", "", "", clip.bytes().length / (double) format.getFrameSize() / format.getSampleRate(), 1, 0, "");
                        backend.play(t, () -> ended.set(true), error::set);
                        Await.of(() -> ended.get() || error.get() != null);
                        assertNull(error.get());
                        assertTrue(ended.get());
                    }
                    var expected = new ByteArrayOutputStream();
                    try (var stream = clip.stream()) {
                        var converter = new PcmRateConverter(stream);
                        float[] pcm = new float[2048];
                        byte[] encoded = new byte[4096];
                        for (int n; (n = converter.read(pcm, pcm.length, speed, format.getSampleRate(), preserve)) >= 0; ) {
                            AudioProcessor.quantize(pcm, encoded, n);
                            expected.write(encoded, 0, n * 2);
                        }
                    }
                    // Resampled output is off the 16-bit grid, so playback dither may move each sample by one step.
                    byte[] want = expected.toByteArray(), got = bytes.toByteArray();
                    String label = "neutral=" + neutral + ", preserve=" + preserve + ", speed=" + speed;
                    assertEquals(want.length, got.length, label);
                    for (int i = 0; i + 1 < want.length; i += 2)
                        assertTrue(Math.abs((short) ((want[i] & 255) | want[i + 1] << 8) - (short) ((got[i] & 255) | got[i + 1] << 8)) <= 1, label);
                }
    }
}
