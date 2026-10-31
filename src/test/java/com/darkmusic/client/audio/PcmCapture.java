package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.TestMusic;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * In-memory output only: every sample derives from the private collection, with no audio files written.
 */
final class PcmCapture implements AutoCloseable {
    enum Scenario {TRACK_FADE, GAPLESS, ALBUM_HANDOFF, SEEK_FADE, SPEED_PITCH, PAUSE_FADE, OUTPUT, MONO_24_STEREO}

    record Event(long frame, Runnable action) {
    }

    record Result(String sha256, long frames, int events) {
    }

    private final ExecutorService client = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("pcm-client").factory());
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<String> error = new AtomicReference<>();
    private final CountDownLatch ended = new CountDownLatch(1);
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final TestMusic.Clip clip;
    private final Scenario scenario;
    private final AudioSettings settings = new AudioSettings();
    private final Track first = track("first"), second = track("second");
    private final LoudnessCache cache = new LoudnessCache();
    private final JavaSoundBackend backend;
    private int fired;

    private static Track track(String name) {
        return new Track("pcm", name, Path.of(name), name, "", "album", 4, 10, 20, "");
    }

    PcmCapture(TestMusic.Clip clip, Scenario scenario) throws Exception {
        this.clip = clip;
        this.scenario = scenario;
        settings.neutral = false;
        settings.normalization = scenario == Scenario.TRACK_FADE || scenario == Scenario.ALBUM_HANDOFF;
        settings.gainMode = scenario == Scenario.ALBUM_HANDOFF ? AudioSettings.Normalization.ALBUM : AudioSettings.Normalization.TRACK;
        settings.crossfade = scenario == Scenario.GAPLESS || scenario == Scenario.ALBUM_HANDOFF ? 0 : 3;
        cache.executor().submit(() -> {
        }).get(5, TimeUnit.SECONDS);
        reading(first, -8);
        reading(second, -20);
        backend = new JavaSoundBackend(client, path -> source(path.equals(first.file())), this::line,
            Duration.ofSeconds(5), Duration.ofSeconds(5), () -> 1 + frames.get() * 1_000_000_000L / (long) clip.format().getFrameRate(), 0x4D55534943L);
        backend.loudness(cache);
        backend.configure(settings);
        schedule();
    }

    private void reading(Track track, double lufs) {
        var mode = settings.gainMode;
        var source = mode == AudioSettings.Normalization.ALBUM ? LoudnessCache.Source.ALBUM_TAGS : LoudnessCache.Source.TRACK_TAGS;
        cache.remember(track, track.file().toAbsolutePath() + ":10:20:" + mode,
            new LoudnessCache.Reading(mode == AudioSettings.Normalization.ALBUM ? -14 : lufs, -2, source));
    }

    private TestMusic.Clip sourceClip(boolean first) {
        byte[] data = clip.bytes().clone();
        if (!first) JavaSoundBackend.scale(data, data.length, .25);
        if (first && scenario == Scenario.MONO_24_STEREO) {
            int channels = clip.format().getChannels(), count = data.length / clip.format().getFrameSize();
            byte[] mono = new byte[count * 3];
            for (int f = 0; f < count; f++) {
                // A lossless 16-to-24-bit representation of the original left channel.
                mono[f * 3 + 1] = data[f * channels * 2];
                mono[f * 3 + 2] = data[f * channels * 2 + 1];
            }
            return new TestMusic.Clip(mono, new AudioFormat(clip.format().getSampleRate(), 24, 1, true, false));
        }
        return new TestMusic.Clip(data, clip.format());
    }

    private AudioInputStream source(boolean first) {
        return sourceClip(first).stream();
    }

    private void event(double seconds, Runnable action) {
        events.add(new Event(Math.round(seconds * clip.format().getFrameRate()), action));
    }

    private void schedule() {
        switch (scenario) {
            case SEEK_FADE -> event(2, () -> {
                backend.seek(.5);
                next();
            });
            case SPEED_PITCH -> {
                settings.enhancements.preservePitch = true;
                backend.configure(settings);
                event(.5, () -> backend.speed(1.25));
                event(1.5, () -> {
                    settings.enhancements.preservePitch = false;
                    backend.configure(settings);
                });
            }
            case PAUSE_FADE -> event(2, () -> {
                backend.pause();
                client.submit(() -> {
                    await(() -> backend.state().status() == AudioBackend.Status.PAUSED);
                    backend.resume();
                });
            });
            case OUTPUT -> {
                event(.5, () -> backend.output(this::line).thenAccept(ok -> {
                    if (!ok) error.set("First output switch failed");
                    try {
                        client.submit(this::next).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
                event(2, () -> backend.output(this::line).thenAccept(ok -> {
                    if (!ok) error.set("Crossfade output switch failed");
                }));
            }
            default -> {
            }
        }
    }

    private void next() {
        backend.nextTrack(second, () -> {
        }, ended::countDown, this::failed);
    }

    private void failed(String message) {
        error.set(message);
        ended.countDown();
    }

    Result capture() throws Exception {
        client.submit(() -> {
            backend.play(first, () -> failed("Ended before handoff"), this::failed);
            next();
            started.set(true);
        }).get(5, TimeUnit.SECONDS);
        if (!ended.await(10, TimeUnit.SECONDS))
            throw new AssertionError("PCM capture timed out: " + scenario + " at " + frames + ", " + backend.state());
        if (error.get() != null) throw new AssertionError(error.get());
        if (!events.isEmpty()) throw new AssertionError("Unreached PCM events: " + events);
        return new Result(hash(bytes.toByteArray()), frames.get(), fired);
    }

    static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private int available(AudioFormat format) {
        await(started::get);
        var next = backend.upcoming();
        if (next != null) await(() -> next.peek() != null);
        long remaining = events.isEmpty() ? Long.MAX_VALUE : events.getFirst().frame - frames.get();
        return (int) Math.min(4096 / format.getFrameSize(), remaining) * format.getFrameSize();
    }

    private SourceDataLine line(AudioFormat requested) {
        var format = scenario == Scenario.MONO_24_STEREO
            ? new AudioFormat(requested.getSampleRate(), 24, 2, true, false) : requested;
        var position = new AtomicLong();
        var running = new AtomicBoolean();
        var closed = new AtomicBoolean();
        return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getFormat" -> format;
            case "getBufferSize" -> 4096 / format.getFrameSize() * format.getFrameSize();
            case "available" -> available(format);
            case "isRunning" -> running.get();
            case "isOpen" -> !closed.get();
            case "getLongFramePosition" -> position.get();
            case "start" -> {
                running.set(true);
                yield null;
            }
            case "stop" -> {
                running.set(false);
                yield null;
            }
            case "close" -> {
                running.set(false);
                closed.set(true);
                yield null;
            }
            case "flush", "drain" -> null;
            case "write" -> {
                int count = (int) args[2];
                bytes.write((byte[]) args[0], (int) args[1], count);
                position.addAndGet(count / format.getFrameSize());
                frames.addAndGet(count / format.getFrameSize());
                if (!events.isEmpty() && frames.get() == events.getFirst().frame) {
                    var event = events.removeFirst();
                    client.submit(event.action).get(5, TimeUnit.SECONDS);
                    fired++;
                }
                yield count;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static void await(BooleanSupplier ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("PCM resource fence timed out");
            LockSupport.parkNanos(100_000);
        }
    }

    @Override
    public void close() throws InterruptedException {
        backend.close();
        if (!backend.awaitStopped(5)) throw new AssertionError("PCM worker did not stop");
        client.shutdownNow();
        cache.close();
    }
}
