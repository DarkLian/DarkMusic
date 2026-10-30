package com.darkmusic.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A queued write is not a durable commit: the acknowledgement completes only once the JSON, or a newer one that
 * replaced it, is on disk, and fails with the write. The storage executor is a queue the test runs by hand.
 */
class WriteAcknowledgementTest {
    @TempDir
    Path directory;
    private final ArrayDeque<Runnable> storage = new ArrayDeque<>();
    private final ArrayList<Exception> failures = new ArrayList<>();

    private void drain() {
        while (!storage.isEmpty()) storage.poll().run();
    }

    @Test
    void aWriteIsAcknowledgedOnlyOnceItIsOnDisk() throws Exception {
        var file = directory.resolve("config.json");
        var writer = new LatestWriter(storage::add, file, "config.invalid-", failures::add);
        var written = writer.write("{\"volume\":1}");
        assertFalse(written.isDone(), "queued is not written");
        drain();
        written.get();
        assertEquals("{\"volume\":1}", Files.readString(file));
    }

    @Test
    void aReplacedWriteIsAcknowledgedByTheNewerOne() throws Exception {
        var file = directory.resolve("config.json");
        var writer = new LatestWriter(storage::add, file, "config.invalid-", failures::add);
        var older = writer.write("{\"volume\":1}");
        var newer = writer.write("{\"volume\":0.5}");
        assertSame(older, newer, "both are covered by the one write that happens");
        drain();
        older.get();
        assertEquals("{\"volume\":0.5}", Files.readString(file));
        var later = writer.write("{\"volume\":0.25}");
        assertFalse(later.isDone(), "a write after the last one has its own acknowledgement");
        drain();
        later.get();
    }

    @Test
    void aFailedWriteFailsItsAcknowledgement() throws Exception {
        // The parent is a file, so the write cannot create its temporary file.
        var blocker = Files.writeString(directory.resolve("blocker"), "");
        var writer = new LatestWriter(storage::add, blocker.resolve("config.json"), "config.invalid-", failures::add);
        var written = writer.write("{}");
        drain();
        assertThrows(ExecutionException.class, written::get);
        assertEquals(1, failures.size());
    }

    @Test
    void aRefusedWriteFailsAndTheNextOneStillQueues() throws Exception {
        var file = directory.resolve("config.json");
        var refuse = new boolean[]{true};
        var writer = new LatestWriter(task -> {
            if (refuse[0]) throw new RejectedExecutionException("storage stopped");
            storage.add(task);
        }, file, "config.invalid-", failures::add);
        assertThrows(RejectedExecutionException.class, () -> writer.write("{}"));
        refuse[0] = false;
        var written = writer.write("{\"volume\":1}");
        drain();
        written.get();
        assertTrue(Files.exists(file));
    }
}
