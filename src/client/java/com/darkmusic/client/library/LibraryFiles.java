package com.darkmusic.client.library;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.UUID;

/**
 * New library files follow ordinary creation permissions/umask and inherited ACLs.
 */
public final class LibraryFiles {
    private LibraryFiles() {
    }

    public static Path incoming(Path directory, String prefix) throws IOException {
        return Files.createFile(directory.resolve(prefix + UUID.randomUUID() + ".tmp"));
    }

    public static void preservePermissions(Path source, Path incoming) throws IOException {
        if (Files.getFileAttributeView(source, PosixFileAttributeView.class) != null
            && Files.getFileAttributeView(incoming, PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(incoming, Files.getPosixFilePermissions(source));
    }
}
