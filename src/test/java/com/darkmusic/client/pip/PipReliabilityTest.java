package com.darkmusic.client.pip;

import com.darkmusic.client.config.PipSettings;
import com.darkmusic.client.tools.ProcessRunner;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PipReliabilityTest {
    @TempDir
    Path temp;

    @Test
    void failedProbeIsDistinctFromUnsupportedHardware() throws Exception {
        assertEquals(0, PipRuntime.requirementsResult(0, ""));
        assertEquals(26300, PipRuntime.requirementsResult(0, "supported 26300"));
        assertThrows(java.io.IOException.class, () -> PipRuntime.requirementsResult(1, ""));
        assertThrows(java.io.IOException.class, () -> PipRuntime.requirementsResult(0, "policy failure"));
    }

    @Test
    void revocationAndReceiverErrorsDoNotLeaveAccessOrLiveStatus() {
        var s = new PipSettings();
        s.enabled = true;
        s.audioAccess = true;
        s.audioSpectrum = true;
        s.revokeAccess();
        assertFalse(s.enabled);
        assertFalse(s.audioAccess);
        assertFalse(s.audioSpectrum);
        var failed = new AtomicBoolean();
        PipSession.receiveSafely(() -> {
            throw new OutOfMemoryError("injected without real allocation");
        }, () -> failed.set(true));
        assertTrue(failed.get());
    }

    @Test
    void legacyRuntimeDoesNotBlockOldZipPruningAndLockedFilesAreKept() throws Exception {
        temp = temp.toRealPath();
        long now = System.currentTimeMillis() + 2L * 86_400_000;
        var token = new ProcessRunner.Token();
        Path current = Files.createDirectory(temp.resolve("a".repeat(64)));
        Path old = Files.createDirectory(temp.resolve("b".repeat(64)));
        Path legacy = Files.createDirectory(temp.resolve("c".repeat(64)));
        Path young = Files.createDirectory(temp.resolve("d".repeat(64)));
        Path busyFile = Files.writeString(legacy.resolve("DarkMusic.Pip.exe"), "Disposable runtime fixture");
        Path sibling = Files.writeString(legacy.resolve("runtime.dll"), "Keep this too while any file is busy");
        Files.setLastModifiedTime(young, java.nio.file.attribute.FileTime.fromMillis(now));
        Path currentZip = Files.writeString(temp.resolve("dependencies-" + "a".repeat(64) + ".zip"), "current");
        Path oldZip = Files.writeString(temp.resolve("dependencies-" + "b".repeat(64) + ".zip"), "referenced while busy");
        Path orphanZip = Files.writeString(temp.resolve("dependencies-" + "c".repeat(64) + ".zip"), "legacy ZIP");
        Path youngZip = Files.writeString(temp.resolve("dependencies-" + "d".repeat(64) + ".zip"), "new ZIP");
        Files.setLastModifiedTime(youngZip, java.nio.file.attribute.FileTime.fromMillis(now));
        try (var setup = PipStorage.setup(temp, token); var live = PipStorage.use(current, currentZip.getFileName().toString(), token)) {
            var oldLease = PipStorage.use(old, oldZip.getFileName().toString(), token);
            try (var channel = java.nio.channels.FileChannel.open(busyFile, StandardOpenOption.WRITE); var lock = channel.lock()) {
                PipStorage.prune(temp, current, currentZip, now);
                assertTrue(Files.exists(old));
                assertTrue(Files.exists(legacy));
                assertEquals("Keep this too while any file is busy", Files.readString(sibling));
                assertFalse(Files.exists(orphanZip), "A legacy directory cannot retain unrelated old ZIPs");
                assertTrue(Files.exists(oldZip));
                assertTrue(Files.exists(young));
                assertTrue(Files.exists(youngZip));
            }
            oldLease.close();
            PipStorage.prune(temp, current, currentZip, now);
            assertFalse(Files.exists(old));
            assertFalse(Files.exists(legacy));
            assertFalse(Files.exists(oldZip));
            assertTrue(Files.exists(current));
            assertTrue(Files.exists(currentZip));
            assertTrue(Files.exists(youngZip));
        }
    }

    @Test
    void windowsSharingViolationKeepsTheWholeLegacyRuntime() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PipNativeTools.supported(), "Windows file sharing check");
        temp = temp.toRealPath();
        Path current = Files.createDirectory(temp.resolve("a".repeat(64)));
        Path legacy = Files.createDirectory(temp.resolve("b".repeat(64)));
        Path exe = Files.writeString(legacy.resolve("DarkMusic.Pip.exe"), "Disposable executable placeholder");
        Path dll = Files.writeString(legacy.resolve("runtime.dll"), "Preserve siblings");
        Path archive = Files.writeString(temp.resolve("dependencies-" + "a".repeat(64) + ".zip"), "current");
        try (var handle = Files.newByteChannel(exe, java.util.Set.of(StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE))) {
            PipStorage.prune(temp, current, archive, System.currentTimeMillis() + 2L * 86_400_000);
            assertTrue(Files.exists(exe));
            assertEquals("Preserve siblings", Files.readString(dll));
        }
        PipStorage.prune(temp, current, archive, System.currentTimeMillis() + 2L * 86_400_000);
        assertFalse(Files.exists(legacy));
    }

    @Test
    void staleLeasesAreRemovedEvenBesideAnActiveCurrentLease() throws Exception {
        temp = temp.toRealPath();
        Path current = Files.createDirectory(temp.resolve("a".repeat(64)));
        Path archive = Files.writeString(temp.resolve("dependencies-" + "a".repeat(64) + ".zip"), "fixture");
        var token = new ProcessRunner.Token();
        try (var setup = PipStorage.setup(temp, token); var live = PipStorage.use(current, archive.getFileName().toString(), token)) {
            Path stale = Files.createFile(current.resolve(".lease-" + java.util.UUID.randomUUID()));
            PipStorage.prune(temp, current, archive, System.currentTimeMillis());
            assertFalse(Files.exists(stale));
            try (var files = Files.list(current)) {
                assertEquals(1, files.filter(f -> f.getFileName().toString().startsWith(".lease-")).count());
            }
        }
    }

    @Test
    void setupLockTimeoutHasSpecificLocalizedStatusAndStillHonorsCancellation() throws Exception {
        temp = temp.toRealPath();
        var token = new ProcessRunner.Token();
        try (var held = PipStorage.setup(temp, token)) {
            var error = assertThrows(java.io.IOException.class, () -> new PipStorage.Lease(temp.resolve(".setup-lock"), token, 0));
            assertEquals("pip.setup.busy", PipNativeTools.failure(error));
            var cancelled = new ProcessRunner.Token();
            cancelled.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, () -> new PipStorage.Lease(temp.resolve(".setup-lock"), cancelled, 0));
        }
    }

    @Test
    void extractionFailuresCleanTheirOwnStaging() throws Exception {
        byte[] deps = PipRuntimeTest.zip(java.util.Map.of("DarkMusic.Pip.exe", new byte[]{1}, "../escape.dll", new byte[]{2}));
        Path archive = Files.write(temp.resolve("deps.zip"), deps);
        assertThrows(java.io.IOException.class, () -> PipNativeTools.unpack(archive, PipRuntimeTest.hash(deps), temp));
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("prepare-")));
        }
    }

    @Test
    void competingPublicationIsReusedOnlyAfterFullVerification() throws Exception {
        byte[] deps = PipRuntimeTest.zip(java.util.Map.of("DarkMusic.Pip.exe", new byte[]{1}, "runtime.dll", new byte[]{2}));
        String dh = PipRuntimeTest.hash(deps);
        Path archive = Files.write(temp.resolve("deps.zip"), deps);
        String combined = dh;
        for (boolean valid : java.util.List.of(true, false)) {
            Path root = Files.createDirectory(temp.resolve(valid ? "valid" : "invalid"));
            Runnable publish = () -> {
                try {
                    Path winner = Files.createDirectory(root.resolve(combined));
                    Files.write(winner.resolve("DarkMusic.Pip.exe"), new byte[]{(byte) (valid ? 1 : 9)});
                    Files.write(winner.resolve("runtime.dll"), new byte[]{2});
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            };
            if (valid) assertTrue(Files.isRegularFile(PipNativeTools.unpack(archive, dh, root, publish)));
            else
                assertThrows(java.io.IOException.class, () -> PipNativeTools.unpack(archive, dh, root, publish));
            try (var files = Files.list(root)) {
                assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("prepare-")));
            }
        }
    }
}