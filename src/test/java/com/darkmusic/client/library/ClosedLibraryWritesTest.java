package com.darkmusic.client.library;

import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reset publishes a new library even when the old one's folder walk is stuck on a slow drive. The walk only reads;
 * once it finishes, the closed library must neither save its index nor publish a snapshot.
 */
class ClosedLibraryWritesTest {
    @TempDir
    Path directory;

    @Test
    void aStuckFolderWalkFinishingAfterCloseWritesNothing() throws Exception {
        Path slow = Files.createDirectories(directory.resolve("slow"));
        Path data = directory.resolve("config");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var library = new MusicLibrary(data, path -> {
            if (!path.equals(slow)) return;
            entered.countDown();
            // A filesystem call that does not answer to interruption.
            while (true) try {
                release.await();
                return;
            } catch (InterruptedException ignored) {
            }
        }, 700);
        try {
            var roots = new LinkedHashMap<Path, String>();
            roots.put(slow, "slow");
            library.setRoots(roots, slow);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "the scan gives up on the stuck folder");
            var published = library.snapshot();
            Files.deleteIfExists(data.resolve("library-cache.json"));
            library.close();
            assertFalse(library.awaitStopped(1), "the walk is still stuck");
            release.countDown();
            assertTrue(library.awaitStopped(5));
            assertFalse(Files.exists(data.resolve("library-cache.json")), "the closed library saved its index");
            assertEquals(published, library.snapshot());
        } finally {
            release.countDown();
            library.close();
        }
    }
}
