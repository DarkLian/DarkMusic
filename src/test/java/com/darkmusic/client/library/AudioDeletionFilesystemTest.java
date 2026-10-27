package com.darkmusic.client.library;

import com.darkmusic.client.tools.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Filesystem and orchestration checks use ordinary text files and an explicit test validator.
 * Actual audio recognition and native Trash remain separate real-collection integration tests.
 */
class AudioDeletionFilesystemTest {
    @TempDir
    Path directory;

    private Track file(String name) throws Exception {
        Path path = Files.writeString(directory.resolve(name), "Filesystem test document: " + name);
        return new Track("root", name, path, name, "", "", 0, Files.size(path), Files.getLastModifiedTime(path).toMillis(), "");
    }

    private AudioDeletion service(boolean supported, java.util.function.Predicate<AudioDeletion.Item> move) {
        return new AudioDeletion(new AudioDeletion.Trash() {
            public boolean supported() {
                return supported;
            }

            public boolean move(AudioDeletion.Item item) {
                return move.test(item);
            }
        }, path -> true);
    }

    @Test
    void permanentDeletionIsDeduplicatedAndPreservesUnselectedFiles() throws Exception {
        var selected = file("selected.txt");
        var untouched = file("untouched.txt");
        Path sidecar = Files.writeString(directory.resolve("selected.lrc"), "Sidecar document");
        var service = service(false, item -> fail("Permanent mode called Trash"));
        var plan = service.prepare(Map.of(directory, "root"), List.of(selected, selected));
        assertEquals(1, plan.items().size());
        var result = service.delete(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), progress -> assertEquals(1, progress.total()));
        assertEquals(List.of(selected), result.removed());
        assertTrue(result.failed().isEmpty());
        assertTrue(result.cancelled().isEmpty());
        assertFalse(Files.exists(selected.file()));
        assertTrue(Files.exists(untouched.file()));
        assertTrue(Files.exists(sidecar));
    }

    @Test
    void trashFailuresNeverBecomePermanentDeletions() throws Exception {
        for (boolean supported : List.of(false, true)) {
            var selected = file("trash-" + supported + ".txt");
            var moves = new AtomicInteger();
            var service = service(supported, item -> {
                moves.incrementAndGet();
                return false;
            });
            var result = service.delete(service.prepare(Map.of(directory, "root"), List.of(selected)), AudioDeletion.Mode.TRASH,
                new ProcessRunner.Token(), progress -> {
                });
            assertTrue(result.removed().isEmpty());
            assertTrue(result.cancelled().isEmpty());
            assertEquals(1, result.failed().size());
            assertEquals(supported ? 1 : 0, moves.get());
            assertTrue(Files.exists(selected.file()));
        }
    }

    @Test
    void changedAndMissingFilesFailIndependentlyOfSuccessfulItems() throws Exception {
        var changed = file("changed.txt");
        var missing = file("missing.txt");
        var good = file("good.txt");
        var service = service(false, item -> false);
        var plan = service.prepare(Map.of(directory, "root"), List.of(changed, missing, good));
        Files.setLastModifiedTime(changed.file(), FileTime.fromMillis(changed.modified() + 2000));
        Files.delete(missing.file());
        var result = service.delete(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), progress -> {
        });
        assertEquals(List.of(good), result.removed());
        assertEquals(List.of(changed, missing), result.failed().stream().map(AudioDeletion.Failure::track).toList());
        assertTrue(Files.exists(changed.file()));
        assertFalse(Files.exists(good.file()));
    }

    @Test
    void rootValidationAndAudioValidationRemainMandatory() throws Exception {
        var selected = file("document.txt");
        var service = service(false, item -> false);
        assertThrows(java.io.IOException.class, () -> service.prepare(Map.of(directory.resolve("other"), "root"), List.of(selected)));
        var checked = new AtomicInteger();
        var reject = new AudioDeletion(new AudioDeletion.Trash() {
            public boolean supported() {
                return false;
            }

            public boolean move(AudioDeletion.Item item) {
                return fail("Rejected file reached Trash");
            }
        }, path -> {
            checked.incrementAndGet();
            return false;
        });
        assertThrows(java.io.IOException.class, () -> reject.prepare(Map.of(directory, "root"), List.of(selected)));
        assertEquals(1, checked.get());
        assertTrue(Files.exists(selected.file()));
        // The production constructor must reject this ordinary document too.
        assertThrows(java.io.IOException.class, () -> new AudioDeletion().prepare(Map.of(directory, "root"), List.of(selected)));
    }

    @Test
    void cancellationBeforeStartingMarksAllEntriesCancelledWithoutFailures() throws Exception {
        var files = List.of(file("first.txt"), file("second.txt"));
        var service = service(true, item -> fail("Canceled item reached Trash"));
        var plan = service.prepare(Map.of(directory, "root"), files);
        var token = new ProcessRunner.Token();
        token.cancel();
        var result = service.delete(plan, AudioDeletion.Mode.TRASH, token, progress -> {
        });
        assertEquals(files, result.cancelled());
        assertTrue(result.removed().isEmpty());
        assertTrue(result.failed().isEmpty());
        for (var track : files) assertTrue(Files.exists(track.file()));
    }

    @Test
    void cancellationBetweenPermanentDeletionsRetainsCompletedWork() throws Exception {
        var files = List.of(file("first.txt"), file("second.txt"), file("third.txt"));
        var service = service(false, item -> false);
        var token = new ProcessRunner.Token();
        var result = service.delete(service.prepare(Map.of(directory, "root"), files), AudioDeletion.Mode.PERMANENT, token,
            progress -> {
                if (progress.completed() == 1) token.cancel();
            });
        assertEquals(List.of(files.getFirst()), result.removed());
        assertEquals(files.subList(1, 3), result.cancelled());
        assertTrue(result.failed().isEmpty());
        assertFalse(Files.exists(files.getFirst().file()));
        assertTrue(Files.exists(files.get(1).file()));
        assertTrue(Files.exists(files.get(2).file()));
    }

    @Test
    void cancellationLetsAnIssuedTrashOperationSettleAndSkipsFollowingFiles() throws Exception {
        var files = List.of(file("active.txt"), file("pending.txt"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var token = new ProcessRunner.Token();
        var service = service(true, item -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                Files.move(item.file(), directory.resolve("moved.txt"));
                return true;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        var plan = service.prepare(Map.of(directory, "root"), files);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(() -> service.delete(plan, AudioDeletion.Mode.TRASH, token, progress -> {
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                token.cancel();
                assertFalse(future.isDone(), "Native operation outcome must settle before reporting");
            } finally {
                release.countDown();
            }
            var result = future.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(files.getFirst()), result.removed());
            assertEquals(List.of(files.getLast()), result.cancelled());
            assertTrue(result.failed().isEmpty());
            assertTrue(Files.exists(files.getLast().file()));
        }
    }
}
