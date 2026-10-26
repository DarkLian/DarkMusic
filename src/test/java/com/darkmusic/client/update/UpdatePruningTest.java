package com.darkmusic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UpdatePruningTest {
    @TempDir
    Path temp;

    private Path job(String state, long time) throws Exception {
        Path job = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        Files.writeString(job.resolve("download.jar"), "Disposable staged bytes");
        if (state != null) {
            var p = new Properties();
            p.setProperty("result", state);
            try (var out = Files.newOutputStream(job.resolve("install.properties"))) {
                p.store(out, "");
            }
            Files.setLastModifiedTime(job.resolve("install.properties"), java.nio.file.attribute.FileTime.fromMillis(time));
        }
        Files.setLastModifiedTime(job, java.nio.file.attribute.FileTime.fromMillis(time));
        return job;
    }

    @Test
    void keepsNewestRollbackPendingFailedAndUnknownButPrunesOldOwnedStaging() throws Exception {
        long now = System.currentTimeMillis(), day = 86_400_000L;
        Path old = job("installed", now - 4 * day), latest = job("installed", now - 2 * day), pending = job("", now - 3 * day), failed = job("failed", now - 3 * day);
        Path orphan = job(null, now - 2 * day), young = job(null, now), unknown = job(null, now - 2 * day);
        Files.writeString(unknown.resolve("user-notes.txt"), "Keep unknown material");
        Files.setLastModifiedTime(unknown, java.nio.file.attribute.FileTime.fromMillis(now - 2 * day));
        UpdateRecovery.prune(temp, now);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(orphan));
        for (Path kept : List.of(latest, pending, failed, young, unknown))
            assertTrue(Files.exists(kept), kept.toString());
    }

    @Test
    void failedUnapprovedJobCanBeDiscardedWithoutTouchingSiblingJobs() throws Exception {
        Path bad = job(null, System.currentTimeMillis()), good = job("", System.currentTimeMillis());
        Path code = Files.createDirectories(bad.resolve("com/darkmusic/client/update")).resolve("UpdateInstaller.class");
        Files.writeString(code, "Disposable class placeholder");
        UpdateRecovery.discard(bad);
        assertFalse(Files.exists(bad));
        assertTrue(Files.exists(good));
    }
}
