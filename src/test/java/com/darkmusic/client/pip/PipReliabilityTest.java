package com.darkmusic.client.pip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.darkmusic.client.download.ProcessRunner;

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
    void cleanupRespectsOtherClientsAndUnknownLegacyRuntimes() throws Exception {
        temp = temp.toRealPath();
        var token = new ProcessRunner.Token();
        Path current = Files.createDirectory(temp.resolve("a".repeat(64))), old = Files.createDirectory(temp.resolve("b".repeat(64))), legacy = Files.createDirectory(temp.resolve("c".repeat(64)));
        Path now = Files.writeString(temp.resolve("dependencies-" + "a".repeat(64) + ".zip"), "new"), prior = Files.writeString(temp.resolve("dependencies-" + "b".repeat(64) + ".zip"), "old");
        try (var setup = PipStorage.setup(temp, token); var live = PipStorage.use(current, now.getFileName().toString(), token)) {
            var busy = PipStorage.use(old, prior.getFileName().toString(), token);
            PipStorage.prune(temp, current, now, System.currentTimeMillis() + 2L * 86_400_000);
            assertTrue(Files.exists(old));
            assertTrue(Files.exists(legacy));
            busy.close();
            PipStorage.prune(temp, current, now, System.currentTimeMillis() + 2L * 86_400_000);
            assertFalse(Files.exists(old));
            assertTrue(Files.exists(prior));
            Files.delete(legacy);
            PipStorage.prune(temp, current, now, System.currentTimeMillis() + 2L * 86_400_000);
            assertFalse(Files.exists(prior));
            assertTrue(Files.exists(current));
            assertTrue(Files.exists(now));
        }
    }

    @Test
    void extractionFailuresCleanTheirOwnStaging() throws Exception {
        byte[] code = PipRuntimeTest.zip(java.util.Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = PipRuntimeTest.zip(java.util.Map.of("../escape.dll", new byte[]{2}));
        Path archive = Files.write(temp.resolve("deps.zip"), deps);
        assertThrows(java.io.IOException.class, () -> PipNativeTools.unpack(code, PipRuntimeTest.hash(code), archive, PipRuntimeTest.hash(deps), temp));
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("prepare-")));
        }
    }

    @Test
    void competingPublicationIsReusedOnlyAfterFullVerification() throws Exception {
        byte[] code = PipRuntimeTest.zip(java.util.Map.of("DarkMusic.Pip.exe", new byte[]{1})), deps = PipRuntimeTest.zip(java.util.Map.of("runtime.dll", new byte[]{2}));
        String ch = PipRuntimeTest.hash(code), dh = PipRuntimeTest.hash(deps);
        Path archive = Files.write(temp.resolve("deps.zip"), deps);
        String combined = PipRuntimeTest.hash((ch + dh).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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
            if (valid) assertTrue(Files.isRegularFile(PipNativeTools.unpack(code, ch, archive, dh, root, publish)));
            else
                assertThrows(java.io.IOException.class, () -> PipNativeTools.unpack(code, ch, archive, dh, root, publish));
            try (var files = Files.list(root)) {
                assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("prepare-")));
            }
        }
    }
}