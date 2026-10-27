package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void recoversOnlyOwnedScratchAndDoesNotTouchActiveInstance() throws Exception {
        Path data = temp.resolve("data"), root = Files.createDirectories(data.resolve("downloads/work"));
        Path stale = Files.createDirectories(root.resolve("item-old"));
        Files.writeString(stale.resolve("incomplete.log"), "incomplete");
        Path keep = Files.createDirectories(root.resolve("unrelated"));
        try (var owner = new DownloadWorkspace(data)) {
            assertFalse(Files.exists(stale));
            assertTrue(Files.isDirectory(keep));
            Path active = Files.createDirectories(root.resolve("item-active"));
            assertThrows(java.io.IOException.class, () -> new DownloadWorkspace(data));
            assertTrue(Files.exists(active));
        }
        try (var recovered = new DownloadWorkspace(data)) {
            assertFalse(Files.exists(root.resolve("item-active")));
        }
    }
}
