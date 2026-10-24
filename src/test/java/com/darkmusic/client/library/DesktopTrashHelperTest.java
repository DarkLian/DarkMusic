package com.darkmusic.client.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class DesktopTrashHelperTest {
    @TempDir
    Path directory;

    @Test
    void desktopHelperReportsCapabilityRejectsUnconfirmedIdentityAndStopsCleanly() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.desktop"), "Requires a desktop session");
        var trash = new DesktopTrash();
        try {
            boolean expected = java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
            assertEquals(expected, trash.supported());
            // A changed identity must be rejected by the isolated helper without moving any file.
            Path file = Files.writeString(directory.resolve("unrelated.txt"), "Kept");
            var track = new Track("root", "unrelated.txt", file, "", "", "", 0, Files.size(file), Files.getLastModifiedTime(file).toMillis(), "");
            if (expected) assertFalse(trash.move(new AudioDeletion.Item(track, file, "not-the-confirmed-file")));
            assertTrue(Files.exists(file));
            if (expected) {
                Path disposable = Files.writeString(directory.resolve("DarkMusic-recycle-check-" + java.util.UUID.randomUUID() + ".txt"), "Disposable desktop Trash verification");
                var attributes = Files.readAttributes(disposable, java.nio.file.attribute.BasicFileAttributes.class);
                var reviewed = new Track("root", disposable.getFileName().toString(), disposable, "", "", "", 0,
                    attributes.size(), attributes.lastModifiedTime().toMillis(), "");
                assertTrue(trash.move(new AudioDeletion.Item(reviewed, disposable, attributes.fileKey(), attributes.fileKey() == null ? SourceLinks.contentHash(disposable) : "")), "Native desktop Trash transfer");
                assertTrue(Files.notExists(disposable), "Confirmed file must leave its original location");
            }
            var process = DesktopTrash.class.getDeclaredField("process");
            process.setAccessible(true);
            Process helper = (Process) process.get(trash);
            trash.close();
            assertFalse(helper.isAlive(), "Desktop helper must not remain orphaned");
        } finally {
            trash.close();
        }
    }

    @Test
    void noFileKeyFallbackHashRejectsSameSizeTimestampReplacement() throws Exception {
        Path file = Files.writeString(directory.resolve("identity.txt"), "first");
        var time = Files.getLastModifiedTime(file);
        String hash = SourceLinks.contentHash(file);
        assertTrue(DesktopTrashApp.matchesHash(file, hash));
        Files.writeString(file, "other");
        Files.setLastModifiedTime(file, time);
        assertFalse(DesktopTrashApp.matchesHash(file, hash));
        assertFalse(DesktopTrashApp.matchesHash(file, ""));
    }

}
