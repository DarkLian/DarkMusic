package com.darkmusic.client.download;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.tools.ToolPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.Entry;
import static org.junit.jupiter.api.Assertions.*;

class AuditionPlayerTest {
    @TempDir
    Path temporary;

    private AuditionClip clip(String name) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve(name));
        Path original = TestMusic.featured(), file = directory.resolve("preview.mp3");
        Files.copy(original, file);
        return new AuditionClip(directory, new Track("audition", "preview.mp3", file, name, "", "", 30, Files.size(file), 0, ""));
    }

    private Entry entry(String id) {
        return new Entry(1, id, "https://example.com/" + id, "Generic", id, true);
    }

    @Test
    void playingIsPublishedOnlyAfterTheBackendHasAcceptedItsCallbacks() throws Exception {
        var excerpt = clip("initialization");
        var entered = new CountDownLatch(1);
        var accepted = new CountDownLatch(1);
        var backend = new Audio() {
            @Override
            public void play(Track track, Runnable ended, Consumer<String> failed) {
                entered.countDown();
                try {
                    assertTrue(accepted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                super.play(track, ended, failed);
            }
        };
        try (var player = new AuditionPlayer((entry, tools, token) -> excerpt, () -> backend, Runnable::run, () -> {
        })) {
            player.play(entry("1"), new ToolPaths("", "", "", ""), .2);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(AuditionPlayer.Status.LOADING, player.snapshot().status());
            } finally {
                accepted.countDown();
            }
            DownloadTest.await(() -> player.snapshot().status() == AuditionPlayer.Status.PLAYING);
            assertNotNull(backend.ended);
        }
    }

    @Test
    void replacementIgnoresStaleCallbacksAndReleasesAudioBeforeDeletingTemporaryFiles() throws Exception {
        var first = clip("first");
        var second = clip("second");
        var audio = new CopyOnWriteArrayList<Audio>();
        var finished = new AtomicInteger();
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (var player = new AuditionPlayer((e, t, k) -> e.mediaId().equals("1") ? first : second, () -> {
            var a = new Audio();
            audio.add(a);
            return a;
        }, callbacks::add, finished::incrementAndGet)) {
            var tools = new ToolPaths("", "", "", "");
            player.play(entry("1"), tools, .2);
            DownloadTest.await(() -> player.snapshot().status() == AuditionPlayer.Status.PLAYING);
            player.stop();
            player.play(entry("2"), tools, .2);
            DownloadTest.await(() -> player.snapshot().status() == AuditionPlayer.Status.PLAYING && player.snapshot().title().equals("2"));
            while (!callbacks.isEmpty()) callbacks.remove().run();
            assertEquals(0, finished.get(), "A stopped request must not resume music over a replacement");
            assertTrue(audio.getFirst().closed);
            assertFalse(Files.exists(first.directory()));
            audio.getFirst().ended.run();
            assertEquals(AuditionPlayer.Status.PLAYING, player.snapshot().status());
            audio.getLast().ended.run();
            DownloadTest.await(() -> !Files.exists(second.directory()));
            assertTrue(audio.getLast().closed);
            while (!callbacks.isEmpty()) callbacks.remove().run();
            assertEquals(1, finished.get());
        }
    }

    @Test
    void cancellationAndErrorsDoNotStartAudioOrLeaveTemporaryExcerpts() throws Exception {
        var pending = clip("cancelled");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var factories = new AtomicInteger();
        try (var player = new AuditionPlayer((e, t, k) -> {
            started.countDown();
            release.await(3, TimeUnit.SECONDS);
            return pending;
        }, () -> {
            factories.incrementAndGet();
            return new Audio();
        }, Runnable::run, () -> {
        })) {
            player.play(entry("1"), new ToolPaths("", "", "", ""), .2);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            player.stop();
            release.countDown();
            DownloadTest.await(() -> !Files.exists(pending.directory()));
            assertEquals(0, factories.get());
            assertEquals(AuditionPlayer.Status.STOPPED, player.snapshot().status());
        }
        try (var player = new AuditionPlayer((e, t, k) -> {
            throw new IOException("HTTP Error 403");
        }, Audio::new, Runnable::run, () -> {
        })) {
            player.play(entry("bad"), new ToolPaths("", "", "", ""), .2);
            DownloadTest.await(() -> player.snapshot().status() == AuditionPlayer.Status.ERROR);
            assertTrue(player.snapshot().error().contains("403"));
        }
    }

    @Test
    void pauseResumeRetainsExcerptAndNeverResumesLibraryUntilStopped() throws Exception {
        var excerpt = clip("pause-resume");
        var fetches = new AtomicInteger();
        var finished = new AtomicInteger();
        var backend = new Audio();
        try (var player = new AuditionPlayer((e, t, k) -> {
            fetches.incrementAndGet();
            return excerpt;
        },
            () -> backend, Runnable::run, finished::incrementAndGet)) {
            player.play(entry("1"), new ToolPaths("", "", "", ""), .5);
            DownloadTest.await(() -> player.snapshot().status() == AuditionPlayer.Status.PLAYING);
            player.pause();
            assertEquals(AuditionPlayer.Status.PAUSED, player.snapshot().status());
            assertEquals(AudioBackend.Status.PAUSED, backend.state().status());
            assertEquals(0, finished.get());
            assertTrue(Files.exists(excerpt.track().file()));
            assertFalse(backend.closed);
            player.resume();
            assertEquals(AuditionPlayer.Status.PLAYING, player.snapshot().status());
            assertEquals(AudioBackend.Status.PLAYING, backend.state().status());
            assertEquals(1, fetches.get());
            player.pause();
            player.stop();
            DownloadTest.await(() -> !Files.exists(excerpt.directory()));
            assertEquals(1, finished.get());
            assertTrue(backend.closed);
            player.resume();
            assertEquals(AuditionPlayer.Status.STOPPED, player.snapshot().status());
        }
    }

    private static class Audio implements AudioBackend {
        volatile State state = new State(null, Status.STOPPED, 0, 0, "");
        volatile boolean closed;
        Runnable ended;

        public State state() {
            return state;
        }

        public void play(Track t, Runnable ended, Consumer<String> failed) {
            this.ended = ended;
            state = new State(t, Status.PLAYING, 0, 30, "");
        }

        public void pause() {
            state = new State(state.track(), Status.PAUSED, state.position(), state.duration(), "");
        }

        public void resume() {
            state = new State(state.track(), Status.PLAYING, state.position(), state.duration(), "");
        }

        public void seek(double seconds) {
        }

        public void volume(double value) {
        }

        public void speed(double value) {
        }

        public void stop() {
            state = new State(state.track(), Status.STOPPED, 0, 0, "");
        }

        public void close() {
            closed = true;
        }
    }
}
