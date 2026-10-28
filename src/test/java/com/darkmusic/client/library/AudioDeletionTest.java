package com.darkmusic.client.library;

import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.testing.Await;
import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class AudioDeletionTest {
    @TempDir
    Path temp;

    private Path source() throws Exception {
        Path root = Path.of(System.getProperty("darkmusic.test.music", "run/config/darkmusic/music"));
        if (Boolean.getBoolean("darkmusic.test.requireMusic"))
            assertTrue(Files.isDirectory(root), "Private real music required");
        Assumptions.assumeTrue(Files.isDirectory(root), "Private real music unavailable");
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile).filter(AudioFormats::candidate).toList();
        }
        if (Boolean.getBoolean("darkmusic.test.requireMusic"))
            assertFalse(files.isEmpty(), "Private real music required");
        Assumptions.assumeFalse(files.isEmpty(), "Private real music unavailable");
        return files.getFirst();
    }

    private Track copy(String name) throws Exception {
        Path file = Files.copy(source(), temp.resolve(name));
        return new Track("root", name, file, name, "", "", 1, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
    }

    private AudioDeletion service(boolean supported, Predicate<Path> move) {
        return new AudioDeletion(new AudioDeletion.Trash() {
            public boolean supported() {
                return supported;
            }

            public boolean move(AudioDeletion.Item item) {
                return move.test(item.file());
            }
        });
    }

    @Test
    void permanentDeletionDeduplicatesAndLeavesOtherFilesUntouched() throws Exception {
        var track = copy("selected.audio");
        var untouched = copy("untouched.audio");
        Path lyrics = Files.writeString(temp.resolve("selected.lrc"), "[00:01.00]Sidecar retained");
        byte[] original = Files.readAllBytes(source());
        var service = service(false, p -> fail("Permanent deletion must not call Trash"));
        var plan = service.prepare(Map.of(temp, "root"), List.of(track, track));
        assertEquals(1, plan.items().size());
        var result = service.delete(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> assertEquals(1, p.total()));
        assertEquals(List.of(track), result.removed());
        assertTrue(result.failed().isEmpty());
        assertFalse(Files.exists(track.file()));
        assertTrue(Files.exists(lyrics));
        assertTrue(Files.exists(untouched.file()));
        assertArrayEquals(original, Files.readAllBytes(source()), "Original private audio must remain untouched");
    }

    @Test
    void failedOrUnsupportedTrashNeverFallsBackToPermanentDeletion() throws Exception {
        for (boolean supported : new boolean[]{false, true}) {
            var track = copy("kept-" + supported + ".audio");
            var service = service(supported, p -> false);
            var result = service.delete(service.prepare(Map.of(temp, "root"), List.of(track)), AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
            });
            assertTrue(result.removed().isEmpty());
            assertEquals(1, result.failed().size());
            assertTrue(Files.exists(track.file()));
        }
    }

    @Test
    void changedFilesAreKeptWhileOtherSelectedFilesContinue() throws Exception {
        var changed = copy("changed.audio");
        var good = copy("good.audio");
        var service = service(false, p -> false);
        var plan = service.prepare(Map.of(temp, "root"), List.of(changed, good));
        Files.setLastModifiedTime(changed.file(), FileTime.fromMillis(changed.modified() + 2000));
        var result = service.delete(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> {
        });
        assertEquals(List.of(good), result.removed());
        assertEquals(changed, result.failed().getFirst().track());
        assertTrue(Files.exists(changed.file()));
        assertFalse(Files.exists(good.file()));
    }

    @Test
    void pathsOutsideRegisteredRootAndNonAudioFilesAreRejected() throws Exception {
        var track = copy("outside.audio");
        var service = service(false, p -> false);
        assertThrows(IOException.class, () -> service.prepare(Map.of(temp.resolve("other"), "root"), List.of(track)));
        Path text = Files.writeString(temp.resolve("notes.txt"), "Unrelated notes");
        var fake = new Track("root", "notes.txt", text, "", "", "", 0, Files.size(text), Files.getLastModifiedTime(text).toMillis(), "");
        assertThrows(IOException.class, () -> service.prepare(Map.of(temp, "root"), List.of(fake)));
        assertTrue(Files.exists(text));
        assertTrue(Files.exists(track.file()));
    }

    @Test
    void cancellationKeepsFilesAndSuccessfulTrashUsesOnlyTheTrashRoute() throws Exception {
        var track = copy("cancelled.audio");
        var service = service(true, p -> {
            try {
                Files.move(p, temp.resolve("trashed.audio"));
                return true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        var plan = service.prepare(Map.of(temp, "root"), List.of(track));
        var token = new ProcessRunner.Token();
        token.cancel();
        assertTrue(service.delete(plan, AudioDeletion.Mode.TRASH, token, p -> {
        }).removed().isEmpty());
        assertTrue(Files.exists(track.file()));
        assertEquals(List.of(track), service.delete(plan, AudioDeletion.Mode.TRASH, new ProcessRunner.Token(), p -> {
        }).removed());
        assertTrue(Files.exists(temp.resolve("trashed.audio")));
    }

    @Test
    void browseTargetsRespectCollectionAndNestedFolderWithSaveFolderFallback() {
        Path saved = temp.resolve("saved"), other = temp.resolve("other");
        var roots = Map.of(saved, "save", other, "external");
        assertEquals(other.resolve("nested/deeper"), MusicFolders.browseDirectory(roots, saved, "All", "@external/nested/deeper"));
        assertEquals(other, MusicFolders.browseDirectory(roots, saved, "All", "@external"));
        for (String mode : List.of("All", "Playlists", "Artists", "Albums", "History"))
            assertEquals(saved, MusicFolders.browseDirectory(roots, saved, mode, mode.equals("All") ? "" : "@external"));
        assertEquals(saved, MusicFolders.browseDirectory(roots, saved, "All", "@external/../../outside"));
        assertEquals(saved, MusicFolders.browseDirectory(roots, saved, "All", "@unknown"));
    }

    @Test
    void temporaryWindowsReadHandleReleasesWithoutLosingTheConfirmedSelection() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows sharing semantics");
        var track = copy("temporarily-locked.audio");
        var service = service(false, p -> false);
        var plan = service.prepare(Map.of(temp, "root"), List.of(track));
        var worker = new AtomicReference<Thread>();
        try (var handle = Files.newByteChannel(track.file(), Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE));
             var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                worker.set(Thread.currentThread());
                return service.delete(plan, AudioDeletion.Mode.PERMANENT, new ProcessRunner.Token(), p -> {
                });
            });
            Await.until(Duration.ofSeconds(2),
                () -> worker.get() != null && worker.get().getState() == Thread.State.TIMED_WAITING, "Sharing-violation retry");
            assertTrue(Files.exists(track.file()));
            handle.close();
            assertEquals(List.of(track), future.get(5, TimeUnit.SECONDS).removed());
            assertFalse(Files.exists(track.file()));
        }
    }
}
