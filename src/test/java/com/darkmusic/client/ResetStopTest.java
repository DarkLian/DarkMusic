package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.testing.Await;
import com.darkmusic.client.testing.TestMusic;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reset's storage task stopping the real services it replaces: every one is closed and waited for, and the reset
 * keeps the saved files unless all of them stopped. One desktop check holds an old audio line stuck while its
 * replacement plays.
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

    /**
     * An old player stuck inside its audio line's write, as with a driver that will not return, while reset publishes a
     * replacement on the same device. Plays real music on the real default device.
     */
    @Test
    @Timeout(60)
    void anOldAudioLineThatWillNotStopLeavesItsReplacementPlaying() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.desktop"), "Plays on the real audio device");
        Path file = TestMusic.featured();
        var track = new Track("real", file.getFileName().toString(), file, "", "", "", 300, Files.size(file),
            Files.getLastModifiedTime(file).toMillis(), "");
        var stall = new AtomicBoolean();
        var stuck = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldLineClosed = new AtomicBoolean();
        var old = new JavaSoundBackend(Runnable::run, PcmDecoder::openPrecise,
            format -> stalling(deviceLine(format), stall, stuck, release, oldLineClosed));
        JavaSoundBackend replacement = null;
        try {
            old.volume(.3);
            old.play(track, () -> {
            }, error -> {
            });
            Await.of(() -> old.state().status() == AudioBackend.Status.PLAYING && old.state().position() > .3);
            stall.set(true);
            assertTrue(stuck.await(5, TimeUnit.SECONDS), "the old player never wrote to its line again");

            var failure = assertThrows(IOException.class, () -> ResetOperation.stop(List.of(
                new ResetOperation.Stopping("playback", old, old::awaitStopped)), 1));
            assertEquals(Text.get("reset.darkmusic.waiting"), failure.getMessage());
            // awaitStopped(0) would join the stuck worker without a limit.
            assertFalse(old.awaitStopped(1), "the old player was meant to be stuck");
            assertFalse(oldLineClosed.get(), "the old line was meant to stay open");

            // The replacement opens its own line on the same device while the old one is still held.
            var playing = replacement = new JavaSoundBackend(Runnable::run);
            playing.volume(.3);
            playing.play(track, () -> {
            }, error -> {
            });
            Await.of(() -> playing.state().status() == AudioBackend.Status.PLAYING && playing.state().position() > .5);
            double before = playing.state().position();
            Await.of(() -> playing.state().position() > before + 1);

            release.countDown();
            assertTrue(old.awaitStopped(5), "the old player did not end once its line returned");
            assertTrue(oldLineClosed.get(), "the old player ended without closing its line");
            double after = playing.state().position();
            Await.of(() -> playing.state().status() == AudioBackend.Status.PLAYING
                && playing.state().position() > after + .5);
        } finally {
            release.countDown();
            if (replacement != null) replacement.close();
            old.close();
        }
    }

    private static SourceDataLine deviceLine(AudioFormat format) throws Exception {
        var line = AudioSystem.getSourceDataLine(format);
        line.open(format, Math.max(4096, ((int) (format.getFrameRate() / 5)) * format.getFrameSize()));
        return line;
    }

    /**
     * The device line, except that once stall is set its next write blocks until release.
     */
    private static SourceDataLine stalling(SourceDataLine line, AtomicBoolean stall, CountDownLatch stuck,
                                           CountDownLatch release, AtomicBoolean closed) {
        return (SourceDataLine) Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
            new Class<?>[]{SourceDataLine.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("write") && stall.get()) {
                    stuck.countDown();
                    while (release.getCount() > 0) try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // A driver stuck in write ignores interrupts too.
                    }
                }
                if (method.getName().equals("close")) closed.set(true);
                try {
                    return method.invoke(line, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
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
