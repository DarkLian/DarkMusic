package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.library.*;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class OutputRoutingTest {
    @TempDir
    Path directory;

    private static class Line {
        final AtomicLong frames = new AtomicLong();
        volatile boolean running, closed;
        volatile byte[] first;
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
                    if (first == null)
                        first = Arrays.copyOfRange((byte[]) args[0], (int) args[1], (int) args[1] + n);
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
        var input = new InputStream() {
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
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() > .2);
            Line original = line.get();
            long before = original.frames.get();
            assertFalse(backend.output(f -> {
                throw new LineUnavailableException("Unplugged output");
            }).get(5, TimeUnit.SECONDS));
            assertFalse(original.closed);
            Await.of(() -> original.frames.get() > before + 512);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double position = backend.state().position();
            var target = new AtomicReference<Line>();
            assertTrue(backend.output(f -> {
                var opened = new Line(f);
                target.set(opened);
                return opened.value;
            }).get(5, TimeUnit.SECONDS));
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            assertTrue(original.closed);
            assertFalse(target.get().running);
            assertEquals(position, backend.state().position(), .03);
            backend.resume();
            Await.of(() -> target.get().frames.get() > 1024);
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
            Await.of(() -> history.snapshot().containsKey(track.id()) && history.snapshot().get(track.id()).seconds() > .2);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double heard = history.snapshot().get(track.id()).seconds();
            Thread.sleep(40);
            assertEquals(heard, history.snapshot().get(track.id()).seconds());
            backend.seek(2);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED && backend.state().position() > 1.9);
            assertEquals(heard, history.snapshot().get(track.id()).seconds(), .01);
            backend.volume(0);
            backend.resume();
            long frames = line.get().frames.get();
            Await.of(() -> line.get().frames.get() > frames + 4096);
            assertEquals(heard, history.snapshot().get(track.id()).seconds(), .01);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            backend.volume(1);
            backend.speed(2);
            backend.resume();
            Await.of(() -> history.snapshot().get(track.id()).seconds() > heard + .2);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
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
            Await.of(() -> history.snapshot().containsKey(track.id()) && history.snapshot().get(track.id()).plays() == 1);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double position = backend.state().position(), before = history.snapshot().get(track.id()).seconds();
            backend.stop();
            assertTrue(backend.awaitIdle(3));
            backend.resumeFrom(track, position, false, () -> {
            }, errors::set);
            Await.of(() -> history.snapshot().get(track.id()).seconds() > before + 1.1);
            assertEquals(1, history.snapshot().get(track.id()).plays());
            backend.play(track, () -> {
            }, errors::set);
            Await.of(() -> history.snapshot().get(track.id()).plays() == 2);
            assertNull(errors.get());
        }
    }

    private void cacheReading(JavaSoundBackend backend, Track track, double lufs) throws Exception {
        var cache = backend.loudness();
        cache.store().readings().put(track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified() + ":TRACK",
            new LoudnessCache.Reading(lufs, -6, LoudnessCache.Source.ANALYSIS));
    }

    private Track realTrack() throws Exception {
        var file = TestMusic.featured();
        return new Track("real", file.getFileName().toString(), file, "", "", "", 300, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
    }

    @Test
    @Timeout(25)
    void normalizationIsReadyAtFirstPcmAndSurvivesSeekOutputAndMetadataContinuation() throws Exception {
        var clip = TestMusic.clip();
        var track = realTrack();
        String key = track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified() + ":TRACK";
        Files.writeString(directory.resolve("loudness-cache.json"), ConfigStore.JSON.toJson(Map.of(key, Map.of("lufs", -8, "peakDb", -2, "source", "ANALYSIS"))));
        ExternalAudio.configure(() -> new ExternalAudio.Paths(directory, "", ""));
        var line = new AtomicReference<Line>();
        var errors = new AtomicReference<String>();
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        try (var backend = new JavaSoundBackend(Runnable::run, p -> loop(clip), f -> {
            var opened = new Line(f);
            line.set(opened);
            return opened.value;
        })) {
            backend.volume(1);
            backend.configure(settings, new SoundEnvironmentSettings());
            backend.play(track, () -> {
            }, errors::set);
            Await.of(() -> line.get() != null && line.get().first != null);
            assertEquals(-10, backend.normalization().appliedDb(), .0001, "No startup normalization ramp");
            byte[] normalized = line.get().first;
            assertEquals(16, clip.format().getSampleSizeInBits());
            for (int i = 0; i + 1 < normalized.length; i += 2) {
                short input = (short) ((clip.bytes()[i] & 255) | clip.bytes()[i + 1] << 8);
                short output = (short) ((normalized[i] & 255) | normalized[i + 1] << 8);
                assertEquals(Math.round(input * Math.pow(10, -.5)), output, 2,
                    "The very first submitted PCM block must already contain the cached attenuation");
            }
            cacheReading(backend, track, -20);
            backend.seek(2);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() >= 2);
            assertEquals(-10, backend.normalization().appliedDb(), .0001);
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            assertTrue(backend.output(f -> new Line(f).value).get(5, TimeUnit.SECONDS));
            backend.resume();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING);
            assertEquals(-10, backend.normalization().appliedDb(), .0001);
            backend.resumeFrom(track, 3, false, () -> {
            }, errors::set);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() >= 3);
            assertEquals(-10, backend.normalization().appliedDb(), .0001);
            // Replaying explicitly is a new session and can use the updated cached reading.
            backend.play(track, () -> {
            }, errors::set);
            Await.of(() -> backend.normalization().appliedDb() > 1.9);
            assertEquals(2, backend.normalization().appliedDb(), .0001);
            assertNull(errors.get());
            assertTrue(normalized.length > 0);
        } finally {
            ExternalAudio.configure(null);
        }
    }

    @Test
    @Timeout(25)
    void lateNormalizationIsDeferredAndRapidRouteChangesKeepSessionGainAndPauseState() throws Exception {
        var clip = TestMusic.clip();
        var track = realTrack();
        ExternalAudio.configure(null);
        var errors = new AtomicReference<String>();
        var settings = new AudioSettings();
        settings.neutral = false;
        settings.normalization = true;
        try (var backend = new JavaSoundBackend(Runnable::run, p -> loop(clip), f -> new Line(f).value)) {
            backend.configure(settings, new SoundEnvironmentSettings());
            backend.play(track, () -> {
            }, errors::set);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() > .1);
            cacheReading(backend, track, -8);
            var changes = new ArrayList<CompletableFuture<Boolean>>();
            for (int i = 0; i < 24; i++) {
                boolean reject = i % 3 == 0;
                changes.add(backend.output(f -> {
                    if (reject) throw new LineUnavailableException("Injected unavailable route");
                    return new Line(f).value;
                }));
            }
            for (var change : changes) change.get(5, TimeUnit.SECONDS);
            backend.seek(1);
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING && backend.state().position() >= 1);
            assertEquals(0, backend.normalization().appliedDb(), 1e-9);
            assertEquals(LoudnessCache.State.NEXT_SESSION, backend.normalization().state());
            backend.pause();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PAUSED);
            double position = backend.state().position();
            for (int i = 0; i < 5; i++) assertTrue(backend.output(f -> new Line(f).value).get(5, TimeUnit.SECONDS));
            assertEquals(AudioBackend.Status.PAUSED, backend.state().status());
            assertEquals(position, backend.state().position(), .03);
            backend.resume();
            Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING);
            assertEquals(0, backend.normalization().appliedDb(), 1e-9);
            assertNull(errors.get());
        }
    }

    @Test
    @Timeout(25)
    void gaplessAndCrossfadeKeepTheIncomingDecisionAcrossQueuedOutputChanges() throws Exception {
        var clip = TestMusic.clip();
        for (double fade : new double[]{0, .5}) {
            Path data = directory.resolve("fade-" + fade);
            Files.createDirectories(data);
            ExternalAudio.configure(() -> new ExternalAudio.Paths(data, "", ""));
            var first = new Track("real", "first", data.resolve("first"), "", "", "", 4, 10, 20, "");
            var second = new Track("real", "second", data.resolve("second"), "", "", "", 4, 10, 20, "");
            String aKey = first.file().toAbsolutePath() + ":10:20:TRACK", bKey = second.file().toAbsolutePath() + ":10:20:TRACK";
            Files.writeString(data.resolve("loudness-cache.json"), ConfigStore.JSON.toJson(
                Map.of(aKey, Map.of("lufs", -8, "peakDb", -6), bKey, Map.of("lufs", -6, "peakDb", -6))));
            var settings = new AudioSettings();
            settings.neutral = false;
            settings.normalization = true;
            settings.crossfade = fade;
            var started = new AtomicBoolean();
            var errors = new AtomicReference<String>();
            try (var backend = new JavaSoundBackend(Runnable::run, p -> clip.stream(), f -> new Line(f).value)) {
                backend.configure(settings, new SoundEnvironmentSettings());
                backend.play(first, () -> {
                }, errors::set);
                Await.of(() -> backend.state().status() == AudioBackend.Status.PLAYING);
                backend.nextTrack(second, () -> started.set(true), () -> {
                }, errors::set);
                if (fade > 0) {
                    Await.of(() -> backend.overlap() > 0);
                    cacheReading(backend, second, -20);
                } else {
                    Await.of(started::get);
                }
                assertTrue(backend.output(f -> new Line(f).value).get(5, TimeUnit.SECONDS));
                Await.of(() -> backend.state().track().id().equals(second.id()) && backend.state().status() == AudioBackend.Status.PLAYING);
                assertEquals(-12, backend.normalization().appliedDb(), .0001);
                assertTrue(started.get());
                assertNull(errors.get());
            } finally {
                ExternalAudio.configure(null);
            }
        }
    }
}
