package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real private-collection PCM through the actual decoder promotion path and a controlled device clock.
 */
class UpNextPreloadTest {
    static Object field(Object owner, String name) throws Exception {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @Test
    @Timeout(30)
    void gaplessAndCrossfadePromoteOnlyTheCurrentPlanWithoutReopeningTheDevice() throws Exception {
        var clip = TestMusic.clip();
        for (double fade : new double[]{0, .2}) {
            var callbacks = new ConcurrentLinkedQueue<Runnable>();
            var credit = new AtomicInteger();
            var frames = new AtomicLong();
            var devices = new AtomicInteger();
            var errors = new ArrayList<String>();
            var opens = new java.util.concurrent.ConcurrentHashMap<Path, AtomicInteger>();
            Track a = track("A"), b = track("B"), x = track("X"), y = track("Y");
            JavaSoundBackend.Output output = format -> {
                devices.incrementAndGet();
                return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getFormat" -> format;
                    case "available" -> Math.max(0, credit.get()) * format.getFrameSize();
                    case "getBufferSize" -> 4096;
                    case "getLongFramePosition" -> frames.get();
                    case "isOpen", "isRunning" -> true;
                    case "start", "stop", "flush", "drain", "close" -> null;
                    case "write" -> {
                        int n = Math.min((int) args[2], Math.max(0, credit.get()) * format.getFrameSize());
                        credit.addAndGet(-n / format.getFrameSize());
                        frames.addAndGet(n / format.getFrameSize());
                        yield n;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            };
            try (var backend = new JavaSoundBackend(callbacks::add, path -> {
                opens.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
                long count = Math.round(clip.format().getFrameRate() * .6);
                return new AudioInputStream(new ByteArrayInputStream(clip.bytes(), 0, Math.toIntExact(count * clip.format().getFrameSize())), clip.format(), count);
            }, output); var controller = new PlaybackController(backend, errors::add)) {
                var options = new AudioSettings();
                options.neutral = true;
                options.crossfade = fade;
                controller.configure(options);
                controller.play(List.of(a, b), a);
                Await.until(Duration.ofSeconds(5), () -> backend.state().status() == AudioBackend.Status.PLAYING && ready(backend), "Normal preload ready");
                Object obsolete = ((AtomicReference<?>) field(backend, "upcoming")).get();
                var obsoleteGate = (AudioBackend.Transition) field(obsolete, "transition");
                controller.addToQueue(x);
                controller.playNext(y);
                controller.removeQueued(controller.upNextSnapshot().manual().getFirst().id());
                Await.until(Duration.ofSeconds(5), () -> ready(backend), "Manual preload ready");
                // The worker has observed its preload; seeking can legitimately discard it.
                credit.set(512);
                Await.until(Duration.ofSeconds(5), () -> backend.state().position() > 0 && credit.get() == 0, "First submitted block");
                controller.seek(.1);
                Await.until(Duration.ofSeconds(5), () -> backend.state().status() == AudioBackend.Status.PLAYING && ready(backend), "Seek restores the prepared manual target");
                int preparedDevices = devices.get();
                int preparedOpens = opens.get(x.file()).get();
                var heard = new ArrayList<String>();
                Await.until(Duration.ofSeconds(8), () -> {
                    for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
                    credit.set(512);
                    var state = backend.state();
                    if (state.track() != null && state.status() == AudioBackend.Status.PLAYING && (heard.isEmpty() || !heard.getLast().equals(state.track().title())))
                        heard.add(state.track().title());
                    return state.track() != null && state.track().equals(b) && state.status() == AudioBackend.Status.PLAYING;
                }, "Actual promotion and source continuation");
                for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
                assertEquals(List.of("A", "X", "B"), heard);
                assertTrue(controller.upNextSnapshot().manual().isEmpty());
                assertFalse(obsoleteGate.claimed());
                assertEquals(preparedDevices, devices.get(), "Prepared handoffs retain the post-seek output device");
                assertEquals(preparedOpens, opens.get(x.file()).get(), "The final prepared decoder was promoted, not reopened; earlier canceled plans may have opened it");
                assertFalse(opens.containsKey(y.file()) && backend.state().track().equals(y));
                assertTrue(errors.isEmpty(), errors.toString());
            }
        }
    }

    private static boolean ready(JavaSoundBackend backend) {
        try {
            Object next = ((AtomicReference<?>) field(backend, "upcoming")).get();
            return next != null && field(next, "ready") != null;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Track track(String name) {
        return new Track("collection", name, Path.of(name), name, "", "", .6, 1, 1, "");
    }
}
