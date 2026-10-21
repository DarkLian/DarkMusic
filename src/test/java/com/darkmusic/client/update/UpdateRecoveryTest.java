package com.darkmusic.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class UpdateRecoveryTest {
    @TempDir
    Path root;

    private Path result(String job, Path old, String status, long time) throws Exception {
        Path manifest = Files.createDirectories(root.resolve(job)).resolve("install.properties");
        var properties = new Properties();
        properties.setProperty("old", old.toAbsolutePath().normalize().toString());
        properties.setProperty("result", status);
        try (var output = Files.newOutputStream(manifest)) {
            properties.store(output, "test result");
        }
        Files.setLastModifiedTime(manifest, FileTime.fromMillis(time));
        return manifest;
    }

    @Test
    void installerFailureRecordIsRecognizedOnTheNextLaunch() throws Exception {
        Path old = root.resolve("mods/darkmusic.jar");
        Path manifest = result("installer", old, "", 1000);
        UpdateInstaller.main(new String[]{Long.toString(Long.MAX_VALUE), manifest.toString()});
        var properties = new Properties();
        try (var input = Files.newInputStream(manifest)) {
            properties.load(input);
        }
        assertEquals("failed", properties.getProperty("result"));
        assertEquals(manifest.getParent().resolve("install.log"), UpdateRecovery.read(root, old).failureLog());
        assertNull(UpdateRecovery.read(root, old).pending());
    }

    @Test
    void failedInstallIsReportedWithoutRestagingItOrRequiringNetworkAccess() throws Exception {
        Path old = root.resolve("mods/darkmusic.jar");
        Path manifest = result("failed", old, "failed", 1000);
        var recovered = UpdateRecovery.read(root, old);
        assertNull(recovered.pending());
        assertEquals(manifest.getParent().resolve("install.log"), recovered.failureLog());
        assertEquals(recovered, UpdateRecovery.read(root, old));
        assertNull(UpdateRecovery.read(root, null).failureLog());
        assertNull(UpdateRecovery.read(root, root.resolve("other.jar")).failureLog());
    }

    @Test
    void newestRelevantResultSupersedesOldFailureAndMalformedJobsDoNotHideIt() throws Exception {
        Path old = root.resolve("mods/darkmusic.jar");
        result("a-failed", old, "failed", 1000);
        Path pending = result("b-pending", old, "", 2000);
        assertEquals(pending, UpdateRecovery.read(root, old).pending());
        assertNull(UpdateRecovery.read(root, old).failureLog());
        result("c-success", old, "installed", 3000);
        assertEquals(new UpdateRecovery.Result(null, null), UpdateRecovery.read(root, old));
        Path failed = result("d-failed", old, "failed", 4000);
        result("other-install", root.resolve("other.jar"), "installed", 9000);
        Files.writeString(Files.createDirectories(root.resolve("malformed")).resolve("install.properties"), "bad=\\uINVALID");
        assertEquals(failed.getParent().resolve("install.log"), UpdateRecovery.read(root, old).failureLog());
    }
}
