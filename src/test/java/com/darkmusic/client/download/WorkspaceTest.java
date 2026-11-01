package com.darkmusic.client.download;

import com.darkmusic.client.testing.FaultFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
            assertThrows(IOException.class, () -> new DownloadWorkspace(data));
            assertTrue(Files.exists(active));
        }
        try (var recovered = new DownloadWorkspace(data)) {
            assertFalse(Files.exists(root.resolve("item-active")));
        }
    }

    @Test
    void pipelinePreparationRecoversStaleItemsButNeverAnActiveUser() throws Exception {
        Path data = temp.resolve("pipeline"), root = Files.createDirectories(data.resolve("downloads/work"));
        Path stale = Files.createDirectory(root.resolve("item-a"));
        Files.writeString(stale.resolve("intermediate.txt"), "disposable download scratch");
        Path keep = Files.createDirectory(root.resolve("unrelated"));
        Path itemFile = Files.writeString(root.resolve("item-file"), "unrelated non-directory");
        var pipeline = new DownloadPipeline(null, data);
        pipeline.prepare();
        try {
            assertFalse(Files.exists(stale));
            assertTrue(Files.isDirectory(keep));
            assertEquals("unrelated non-directory", Files.readString(itemFile));
            Path active = Files.createDirectory(root.resolve("item-active"));
            pipeline.prepare();
            pipeline.close();
            assertTrue(Files.exists(active), "a second user shares the existing lease without recovering");
            assertThrows(IOException.class, () -> new DownloadPipeline(null, data).prepare());
            assertTrue(Files.exists(active), "another pipeline cannot recover a leased directory");
        } finally {
            pipeline.close();
        }
        pipeline.prepare();
        try {
            assertFalse(Files.exists(root.resolve("item-active")));
            assertTrue(Files.isDirectory(keep));
        } finally {
            pipeline.close();
        }
    }

    @Test
    void anotherWorkspaceLockPreventsPipelineRecovery() throws Exception {
        Path data = temp.resolve("locked");
        try (var owner = new DownloadWorkspace(data)) {
            Path active = Files.createDirectory(data.resolve("downloads/work/item-owned"));
            var pipeline = new DownloadPipeline(null, data);
            assertThrows(IOException.class, pipeline::prepare);
            assertTrue(Files.isDirectory(active));
            pipeline.close();
        }
    }

    @Test
    void aRefusedRecoveryScanDoesNotDenyAnOtherwiseWritableWorkspace() throws Exception {
        try (var fs = new FaultFileSystem()) {
            Path data = fs.wrap(temp.resolve("scan-denied"));
            fs.before((operation, path, target) -> {
                if (operation.equals("list")) throw new IOException("scratch listing refused");
            });
            var pipeline = new DownloadPipeline(null, data);
            pipeline.prepare();
            try {
                Path active = Files.createDirectory(data.resolve("downloads/work/item-new"));
                assertTrue(Files.isDirectory(active));
            } finally {
                pipeline.close();
            }
        }
    }

    @Test
    void recoveryNeverFollowsAnItemSymlink() throws Exception {
        Path data = temp.resolve("linked"), root = Files.createDirectories(data.resolve("downloads/work"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "outside disposable fixture");
        Path link = root.resolve("item-b");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("Symlink unavailable on " + System.getProperty("os.name"));
        }
        Path stale = Files.createDirectory(root.resolve("item-a"));
        var pipeline = new DownloadPipeline(null, data);
        pipeline.prepare();
        try {
            assertFalse(Files.exists(stale));
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("outside disposable fixture", Files.readString(sentinel));
        } finally {
            pipeline.close();
        }
    }

}
