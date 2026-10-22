package com.darkmusic.client.download;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * An instance-wide lock prevents recovery from deleting another client's active work.
 */
final class DownloadWorkspace implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    DownloadWorkspace(Path data) throws IOException {
        this(data, true);
    }

    DownloadWorkspace(Path data, boolean recover) throws IOException {
        Path directory = data.resolve("downloads");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory))
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.download_workspace_must_not_be_a_symbolic_link"));
        channel = FileChannel.open(directory.resolve("workspace.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null)
                throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.another_minecraft_instance_is_using_these_download_tools_close_it_before_downloadi"));
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.another_minecraft_instance_is_using_these_download_tools_close_it_before_downloadi"), e);
        }
        try {
            Path root = directory.resolve("work");
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root))
                throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.download_scratch_folder_must_not_be_a_symbolic_link"));
            if (recover) try (var children = Files.list(root)) {
                for (Path child : children.filter(p -> p.getFileName().toString().startsWith("item-")).toList()) {
                    if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        remove(child);
                    } catch (IOException e) {
                        org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Could not remove stale download workspace {}", child, e);
                    }
                }
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    static void remove(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
