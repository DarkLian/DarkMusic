package com.darkmusic.client.library;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class LibraryFilesTest {
    @TempDir
    Path temp;

    @Test
    void importedPermissionsPreservePrivacyAndDownloadsFollowOrdinaryCreation() throws Exception {
        Path normal = Files.createFile(temp.resolve("normal"));
        Path incoming = LibraryFiles.incoming(temp, ".darkmusic-");
        Assumptions.assumeTrue(Files.getFileAttributeView(normal, PosixFileAttributeView.class) != null);
        assertEquals(Files.getPosixFilePermissions(normal), Files.getPosixFilePermissions(incoming));
        Files.setPosixFilePermissions(normal, PosixFilePermissions.fromString("rw-------"));
        LibraryFiles.preservePermissions(normal, incoming);
        assertEquals(Files.getPosixFilePermissions(normal), Files.getPosixFilePermissions(incoming));
    }
}
