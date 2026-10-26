package com.darkmusic.client.library;

import java.io.IOException;
import java.nio.file.*;

/**
 * New library files follow ordinary creation permissions/umask and inherited ACLs.
 */
public final class LibraryFiles {
    private LibraryFiles() {
    }

    public static Path incoming(Path directory, String prefix) throws IOException {
        return Files.createFile(directory.resolve(prefix + java.util.UUID.randomUUID() + ".tmp"));
    }

    public static void preservePermissions(Path source, Path incoming) throws IOException {
        if (Files.getFileAttributeView(source, java.nio.file.attribute.PosixFileAttributeView.class) != null
            && Files.getFileAttributeView(incoming, java.nio.file.attribute.PosixFileAttributeView.class) != null)
            Files.setPosixFilePermissions(incoming, Files.getPosixFilePermissions(source));
    }
}
