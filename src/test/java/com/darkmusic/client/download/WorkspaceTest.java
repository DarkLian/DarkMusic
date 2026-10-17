package com.darkmusic.client.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

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

    @Test
    void diagnosticCaptureGrowsOnlyAsNeededAndRetainsTheTail() throws Exception {
        var output = new ProcessRunner.OutputTail(16_000_000);
        var field = output.getClass().getDeclaredField("data");
        field.setAccessible(true);
        assertTrue(((char[]) field.get(output)).length <= 4096);
        var small = new ProcessRunner.OutputTail(5000);
        String text = "0123456789".repeat(1200);
        for (char c : text.toCharArray()) small.append(c);
        assertEquals(text.substring(text.length() - 5000), small.text());
    }

}
