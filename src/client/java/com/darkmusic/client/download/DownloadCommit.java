package com.darkmusic.client.download;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.library.SourceLinks;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.*;

/**
 * A bounded, file-bound receipt repairs only bookkeeping after an interrupted download commit.
 */
final class DownloadCommit {
    private record Receipt(int schema, String identity, String filename, String url, long size, long modified,
                           String hash) {
    }

    private static Path file(Path destination, String identity) {
        return destination.resolve(".darkmusic-download-commit-" + com.darkmusic.client.cleanup.DuplicateScanner.digest(identity) + ".json");
    }

    private static final java.util.concurrent.ConcurrentHashMap<Path, Long> pruned = new java.util.concurrent.ConcurrentHashMap<>();

    static void prune(Path destination, long now) throws IOException {
        Path base = destination.toAbsolutePath().normalize();
        Long last = pruned.get(base);
        if (last != null && now - last < 86_400_000L) return;
        if (pruned.size() > 256) pruned.clear();
        pruned.put(base, now);
        try (var files = Files.newDirectoryStream(base, ".darkmusic-download-commit-*.json")) {
            for (Path pending : files) {
                if (Files.isSymbolicLink(pending) || !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || now - Files.getLastModifiedTime(pending).toMillis() < 30L * 86_400_000)
                    continue;
                boolean repairable = false;
                try {
                    if (Files.size(pending) <= 20_000) {
                        var receipt = ConfigStore.JSON.fromJson(Files.readString(pending), Receipt.class);
                        if (receipt != null && receipt.schema == 1 && receipt.filename != null && !receipt.filename.contains("/") && !receipt.filename.contains("\\") && !receipt.filename.startsWith(".")) {
                            Path target = base.resolve(receipt.filename);
                            try {
                                var a = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                                repairable = a.isRegularFile() && a.size() == receipt.size && a.lastModifiedTime().toMillis() == receipt.modified;
                            } catch (NoSuchFileException absent) {
                                // Positive absence is safe; permission/offline I/O errors defer cleanup instead.
                            }
                        }
                    }
                } catch (com.google.gson.JsonParseException invalid) {
                }
                // Never touch the recording, source associations or lyric companions here.
                if (!repairable) Files.deleteIfExists(pending);
            }
        }
    }

    static void prepare(Path incoming, Path target, String identity, String url) throws IOException {
        try {
            prune(target.getParent(), System.currentTimeMillis());
        } catch (IOException cleanup) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").debug("Receipt cleanup deferred", cleanup);
        }
        Path pending = file(target.getParent(), identity);
        if (Files.isSymbolicLink(pending)) throw new IOException("Unsafe download commit receipt");
        var a = Files.readAttributes(incoming, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var receipt = new Receipt(1, identity, target.getFileName().toString(), SourceLinks.safe(url), a.size(), a.lastModifiedTime().toMillis(), SourceLinks.contentHash(incoming));
        ConfigStore.atomicWrite(pending, ConfigStore.JSON.toJson(receipt));
    }

    static boolean pending(Path destination, String identity) {
        return Files.exists(file(destination, identity), LinkOption.NOFOLLOW_LINKS);
    }

    static void finish(Path target, String identity) throws IOException {
        if (SourceLinks.matchesArchive(target, identity)) Files.deleteIfExists(file(target.getParent(), identity));
    }

    static Path recover(Path destination, String identity, JsonObject archive) throws IOException {
        Path pending = file(destination, identity);
        if (!Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pending) || Files.size(pending) > 20_000)
            return null;
        Receipt receipt;
        try {
            receipt = ConfigStore.JSON.fromJson(Files.readString(pending), Receipt.class);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (receipt == null || receipt.schema != 1 || !identity.equals(receipt.identity) || receipt.filename == null
            || receipt.filename.contains("/") || receipt.filename.contains("\\") || receipt.filename.startsWith(".")
            || receipt.hash == null || !receipt.hash.matches("[a-f0-9]{64}") || SourceLinks.safe(receipt.url).isEmpty())
            return null;
        Path target = destination.resolve(receipt.filename).normalize();
        if (!target.getParent().equals(destination.toAbsolutePath().normalize()) || Files.isSymbolicLink(target)
            || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        var a = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.size() != receipt.size || a.lastModifiedTime().toMillis() != receipt.modified || !receipt.hash.equals(SourceLinks.contentHash(target)))
            return null;
        if (!SourceLinks.record(target, receipt.url, identity, receipt.hash)) return null;
        if (!SourceLinks.matchesArchive(target, identity)) return null;
        archive.addProperty(identity, receipt.filename);
        try {
            ConfigStore.atomicWrite(destination.resolve(".darkmusic-downloads.json"), ConfigStore.JSON.toJson(archive));
            Files.deleteIfExists(pending);
        } catch (IOException failure) {
            // Keep the receipt; the verified audio can still be recognized without claiming a new download.
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Download bookkeeping recovery remains pending", failure);
        }
        return target;
    }
}
