package com.darkmusic.client.library;

import com.darkmusic.client.testing.Await;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MetadataRecoveryStatusTest {
    @TempDir
    Path temp;

    @Test
    void recoveryFoldersAppearInStatusWithoutReadingOrDeletingRetainedFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("music"));
        Path work = Files.createDirectory(root.resolve(".darkmusic-work-disposable"));
        Path nested = Files.createDirectory(Files.createDirectory(root.resolve("album")).resolve(".darkmusic-work-second"));
        Path retained = Files.writeString(work.resolve("retained.dat"), "Original recovery fixture");
        Path other = Files.writeString(nested.resolve("retained.dat"), "Second recovery fixture");
        try (var library = new MusicLibrary(temp.resolve("data"))) {
            library.setRoot(root, "root");
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "Recovery status scan completes");
            assertTrue(library.snapshot().message().contains(Text.get("library.status.recovery_folders", 2)));
            assertTrue(library.snapshot().tracks().isEmpty());
            assertEquals("Original recovery fixture", Files.readString(retained));
            assertEquals("Second recovery fixture", Files.readString(other));
            Files.move(work, temp.resolve("reviewed-one"));
            Files.move(nested, temp.resolve("reviewed-two"));
            long before = library.snapshot().revision();
            library.rescan();
            Await.until(Duration.ofSeconds(5), () -> library.snapshot().revision() > before && !library.snapshot().scanning(), "Status updates after review");
            assertTrue(library.snapshot().message().isBlank());
            assertEquals("Original recovery fixture", Files.readString(temp.resolve("reviewed-one/retained.dat")));
        }
    }
}
