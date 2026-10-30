package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reset's storage task stopping the real services it replaces: every one is closed and waited for, and the reset
 * keeps the saved files unless all of them stopped.
 */
class ResetStopTest {
    @TempDir
    Path data;

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @Test
    void aThrowingCloseStillClosesTheOtherServices() throws Exception {
        var downloads = new DownloadService(data, () -> {
        });
        var library = new MusicLibrary(data);
        try {
            AutoCloseable broken = () -> {
                throw new IllegalStateException("audio device gone");
            };
            var failure = assertThrows(Exception.class, () -> ResetOperation.stop(List.of(
                new ResetOperation.Stopping("audition", broken, seconds -> true),
                new ResetOperation.Stopping("downloads", downloads, downloads::awaitStopped),
                new ResetOperation.Stopping("library", library, library::awaitStopped)), 5));
            // Their workers ended, so nothing of theirs runs beside the replacements.
            assertTrue(downloads.awaitStopped(1), "downloads were left running");
            assertTrue(library.awaitStopped(1), "the library was left running");
            assertInstanceOf(IOException.class, failure);
            assertEquals(Text.get("reset.darkmusic.waiting"), failure.getMessage());
            assertEquals("audio device gone", failure.getSuppressed()[0].getCause().getMessage());
        } finally {
            downloads.close();
            library.close();
        }
    }

    @Test
    void aServiceStillRunningKeepsTheFilesAndTheOthersAreStillStopped() throws Exception {
        var playback = new JavaSoundBackend(Runnable::run, path -> {
            throw new IllegalStateException("nothing plays in this test");
        }, format -> {
            throw new IllegalStateException("nothing plays in this test");
        });
        var cache = new LoudnessCache();
        var loudness = JavaSoundBackend.class.getDeclaredField("loudness");
        loudness.setAccessible(true);
        loudness.set(playback, cache);
        var release = new CountDownLatch(1);
        // The final cache write queues behind this, as behind a slow disk.
        ((ExecutorService) field(cache, "writer")).execute(() -> {
            while (true) try {
                release.await();
                return;
            } catch (InterruptedException ignored) {
            }
        });
        var downloads = new DownloadService(data, () -> {
        });
        try {
            var failure = assertThrows(IOException.class, () -> ResetOperation.stop(List.of(
                new ResetOperation.Stopping("playback", playback, playback::awaitStopped),
                new ResetOperation.Stopping("downloads", downloads, downloads::awaitStopped)), 1));
            assertEquals(Text.get("reset.darkmusic.waiting"), failure.getMessage());
            assertTrue(downloads.awaitStopped(0));
            assertFalse(playback.awaitStopped(0));
        } finally {
            release.countDown();
            downloads.close();
        }
        assertTrue(playback.awaitStopped(5));
    }

    @Test
    void servicesThatStopLetTheResetClearTheFiles() throws Exception {
        var downloads = new DownloadService(data, () -> {
        });
        var library = new MusicLibrary(data);
        ResetOperation.stop(List.of(new ResetOperation.Stopping("downloads", downloads, downloads::awaitStopped),
            new ResetOperation.Stopping("library", library, library::awaitStopped)), 5);
        assertTrue(downloads.awaitStopped(0) && library.awaitStopped(0));
    }
}
