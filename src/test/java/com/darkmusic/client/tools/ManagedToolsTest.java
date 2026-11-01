package com.darkmusic.client.tools;

import com.google.gson.JsonArray;
import com.darkmusic.client.text.Text;
import org.junit.jupiter.api.Assumptions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ManagedToolsTest {
    @Test
    void aFailedPointerPublicationRemovesTheNewBundleAndKeepsTheBlockingDocument() throws Exception {
        var publisher = new Publisher();
        Path root = Files.createDirectories(temp.resolve("tools/managed"));
        Path pointer = Files.createDirectory(root.resolve("current.json"));
        Path sentinel = Files.writeString(pointer.resolve("unrelated.txt"), "User-owned identity fixture");
        assertThrows(IOException.class, () -> new ManagedTools(temp, publisher).install(capabilities, new ProcessRunner.Token(), text -> {
        }));
        assertEquals("User-owned identity fixture", Files.readString(sentinel));
        try (var paths = Files.list(root)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("bundle-") || p.getFileName().toString().startsWith("staging-")));
        }
    }

    @Test
    void aConcurrentInstallAndAFailedRepairKeepThePreviousBundle() throws Exception {
        var publisher = new Publisher();
        var installer = new ManagedTools(temp, publisher);
        installer.install(capabilities, new ProcessRunner.Token(), text -> {
        });
        Path previous = ManagedTools.current(temp);
        try (var channel = FileChannel.open(temp.resolve("tools/install.lock"), StandardOpenOption.WRITE);
             var held = channel.lock()) {
            var failure = assertThrows(IOException.class, () -> installer.install(capabilities, new ProcessRunner.Token(), text -> {
            }));
            assertInstanceOf(OverlappingFileLockException.class, failure.getCause());
            assertEquals(previous, ManagedTools.current(temp));
        }
        var statuses = new ArrayList<String>();
        int before = publisher.binaries;
        var rejected = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path directory, Token token, Duration timeout, int max, Consumer<String> lines) {
                return new Result(1, "Fixture capability rejection");
            }
        };
        assertThrows(IOException.class, () -> installer.install(rejected, new ProcessRunner.Token(), statuses::add));
        assertTrue(publisher.binaries > before, "an unchanged but unhealthy bundle was prepared again for repair");
        assertEquals(previous, ManagedTools.current(temp));
        assertTrue(Files.isDirectory(previous));
        try (var files = Files.list(temp.resolve("tools/managed"))) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("staging-")));
        }
    }

    private final ProcessRunner capabilities = new ProcessRunner() {
        @Override
        public Result run(List<String> args, Path directory, Token token, Duration timeout, int max, Consumer<String> lines) {
            token.check();
            if (args.contains("--help"))
                return new Result(0, "--dump-single-json --flat-playlist --progress-template --no-playlist --no-plugin-dirs");
            if (args.contains("-encoders")) return new Result(0, "libmp3lame");
            return new Result(0, args.getFirst().contains("deno") ? "deno 2.9.7" : "2026.08.19");
        }
    };
    @TempDir
    Path temp;

    @Test
    void malformedHostsAndUnexpectedPortsAreRejectedCleanly() {
        assertThrows(IOException.class, () -> ManagedTools.trusted(URI.create("https:/missing-host")));
        assertThrows(IOException.class, () -> ManagedTools.trusted(URI.create("https://github.com:8443/file")));
        assertDoesNotThrow(() -> ManagedTools.trusted(URI.create("https://github.com:443/file")));
    }

    @Test
    void cleanupPreservesCurrentPreviousLeasedAndUnknownDirectories() throws Exception {
        Path root = Files.createDirectories(temp.resolve("managed"));
        var copies = new ArrayList<Path>();
        for (int i = 0; i < 4; i++) {
            Path p = Files.createDirectory(root.resolve("bundle-" + UUID.randomUUID()));
            Files.writeString(p.resolve("ffmpeg"), "metadata fixture");
            copies.add(p);
            Files.writeString(p.resolve("manifest.json"), "{}");
            Files.setLastModifiedTime(p.resolve("manifest.json"), FileTime.fromMillis(System.currentTimeMillis() - (10 - i) * 86_400_000L));
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - (10 - i) * 86_400_000L));
        }
        Path unknown = Files.createDirectory(root.resolve("personal-files"));
        Path stage = Files.createDirectory(root.resolve("staging-123456"));
        Files.setLastModifiedTime(stage, FileTime.fromMillis(System.currentTimeMillis() - 3 * 86_400_000L));
        try (var channel = FileChannel.open(copies.get(1).resolve("usage.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock(0, Long.MAX_VALUE, true)) {
            ManagedTools.prune(root, copies.get(3));
            assertFalse(Files.exists(copies.get(0)));
            assertTrue(Files.exists(copies.get(1)));
            assertTrue(Files.exists(copies.get(2)));
            assertTrue(Files.exists(copies.get(3)));
            assertTrue(Files.exists(unknown));
            assertFalse(Files.exists(stage));
        }
    }

    @Test
    void platformAssetsMatchSupportedArchitecturesAndRejectUnsupportedOnes() throws Exception {
        assertEquals("yt-dlp.exe", ManagedTools.Platform.detect("Windows 11", "amd64").ytAsset());
        assertEquals("yt-dlp_linux_aarch64", ManagedTools.Platform.detect("Linux", "aarch64").ytAsset());
        assertEquals("deno-x86_64-apple-darwin.zip", ManagedTools.Platform.detect("Mac OS X", "x86_64").denoAsset());
        assertEquals("deno-aarch64-apple-darwin.zip", ManagedTools.Platform.detect("Mac OS X", "arm64").denoAsset());
        assertThrows(IOException.class, () -> ManagedTools.Platform.detect("Windows", "arm64"));
        assertThrows(IOException.class, () -> ManagedTools.Platform.detect("Linux", "riscv64"));
    }

    @Test
    void untrustedDownloadsAndMissingChecksumsAreRejected() throws Exception {
        for (String url : List.of("http://github.com/a", "https://github.com.evil.test/a", "https://user@github.com/a", "https://localhost/tool"))
            assertThrows(IOException.class, () -> ManagedTools.trusted(URI.create(url)));
        assertDoesNotThrow(() -> ManagedTools.trusted(URI.create("https://release-assets.githubusercontent.com/a")));
        var release = JsonParser.parseString("{\"assets\":[{\"name\":\"tool\",\"digest\":null,\"size\":10}]}").getAsJsonObject();
        assertThrows(IOException.class, () -> ManagedTools.asset(release, "tool"));
        Path file = temp.resolve("bytes");
        Files.writeString(file, "checksum test");
        assertThrows(IOException.class, () -> ManagedTools.verify(file, "sha256:" + "0".repeat(64), Files.size(file)));
    }

    @Test
    void zipExtractionCannotEscapeAndRejectsMissingExecutables() throws Exception {
        Path archive = temp.resolve("archive.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("../escaped"));
            out.write("untrusted path".getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("deno"));
            out.write("executable bytes, never run".getBytes());
            out.closeEntry();
        }
        ManagedTools.extractZip(archive, "deno", temp.resolve("deno"), new ProcessRunner.Token());
        assertFalse(Files.exists(temp.getParent().resolve("escaped")));
        assertFalse(Files.exists(temp.resolve("escaped")));
        assertThrows(IOException.class, () -> ManagedTools.extractZip(archive, "missing", temp.resolve("missing"), new ProcessRunner.Token()));
    }

    @Test
    void publicationIsAtomicAndFailedOrCancelledUpdatesKeepThePreviousBundle() throws Exception {
        var publisher = new Publisher();
        var installer = new ManagedTools(temp, publisher);
        installer.install(capabilities, new ProcessRunner.Token(), text -> {
        });
        Path original = ManagedTools.current(temp);
        assertNotNull(original);
        int downloads = publisher.binaries;
        installer.install(capabilities, new ProcessRunner.Token(), text -> {
        });
        assertEquals(downloads, publisher.binaries, "An unchanged healthy release should not download again");
        assertEquals(original, ManagedTools.current(temp));
        var assets = publisher.release.getAsJsonArray("assets");
        assets.get(0).getAsJsonObject().addProperty("digest", "sha256:" + "0".repeat(64));
        publisher.corrupt = true;
        assertThrows(IOException.class, () -> installer.install(capabilities, new ProcessRunner.Token(), text -> {
        }));
        assertEquals(original, ManagedTools.current(temp));
        publisher.corrupt = false;
        publisher.cancel = true;
        assertThrows(CancellationException.class, () -> installer.install(capabilities, new ProcessRunner.Token(), text -> {
        }));
        assertEquals(original, ManagedTools.current(temp));
        try (var children = Files.list(temp.resolve("tools/managed"))) {
            assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith("staging-")));
        }
    }

    @Test
    void pointerCannotRedirectToolLookupOutsideItsManagedDirectory() throws Exception {
        Path root = Files.createDirectories(temp.resolve("tools/managed"));
        Files.writeString(root.resolve("current.json"), "{\"directory\":\"../../../elsewhere\"}");
        assertNull(ManagedTools.current(temp));
    }

    private class Publisher implements ManagedTools.Fetch {
        final Map<String, byte[]> files = new HashMap<>();
        final JsonObject release = new JsonObject();
        boolean corrupt, cancel;
        int binaries;

        Publisher() throws Exception {
            release.addProperty("tag_name", "test-release");
            release.addProperty("draft", false);
            release.addProperty("prerelease", false);
            var assets = new JsonArray();
            release.add("assets", assets);
            var p = ManagedTools.Platform.detect(System.getProperty("os.name"), System.getProperty("os.arch"));
            for (String name : List.of(p.ytAsset(), "ffmpeg-" + p.os() + "-" + p.arch() + ".gz", "ffprobe-" + p.os() + "-" + p.arch() + ".gz", p.denoAsset(), p.os() + "-" + p.arch() + ".LICENSE", p.os() + "-" + p.arch() + ".README")) {
                byte[] bytes = "Executable integrity fixture; not audio, never executed".getBytes();
                if (name.endsWith(".gz")) {
                    var output = new ByteArrayOutputStream();
                    try (var gz = new GZIPOutputStream(output)) {
                        gz.write(bytes);
                    }
                    bytes = output.toByteArray();
                } else if (name.endsWith(".zip")) {
                    var output = new ByteArrayOutputStream();
                    try (var zip = new ZipOutputStream(output)) {
                        zip.putNextEntry(new ZipEntry("deno" + p.extension()));
                        zip.write(bytes);
                        zip.closeEntry();
                    }
                    bytes = output.toByteArray();
                }
                files.put(name, bytes);
                var a = new JsonObject();
                a.addProperty("name", name);
                a.addProperty("size", bytes.length);
                a.addProperty("browser_download_url", "https://github.com/test/releases/download/test/" + name);
                a.addProperty("digest", "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                assets.add(a);
            }
        }

        @Override
        public void get(URI uri, Path output, long max, ProcessRunner.Token token, Consumer<Long> progress) throws Exception {
            if (uri.getHost().equals("api.github.com")) {
                Files.writeString(output, release.toString());
                return;
            }
            binaries++;
            byte[] bytes = files.get(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1)).clone();
            if (corrupt) bytes[0] ^= 1;
            Files.write(output, bytes);
            progress.accept((long) bytes.length);
            if (cancel) token.cancel();
            token.check();
        }
    }

    @Test
    void diskExhaustionRejectsBeforeAssetsAndPreservesCurrentAndPreviousBundles() throws Exception {
        var publisher = new Publisher();
        new ManagedTools(temp, publisher).install(capabilities, new ProcessRunner.Token(), text -> {
        });
        Path active = ManagedTools.current(temp);
        byte[] manifest = Files.readAllBytes(active.resolve("manifest.json"));
        Path previous = Files.createDirectory(active.resolveSibling("bundle-" + UUID.randomUUID()));
        Path sentinel = Files.writeString(previous.resolve("manifest.json"), "previous bundle identity fixture");
        int binaries = publisher.binaries;
        var failure = assertThrows(IOException.class,
            () -> new ManagedTools(temp, publisher, path -> 0).install(capabilities, new ProcessRunner.Token(), text -> {
            }));
        assertEquals(Text.get("tools.error.disk"), failure.getMessage());
        assertEquals(binaries, publisher.binaries, "no asset downloaded with insufficient space");
        assertEquals(active, ManagedTools.current(temp));
        assertArrayEquals(manifest, Files.readAllBytes(active.resolve("manifest.json")));
        assertEquals("previous bundle identity fixture", Files.readString(sentinel));
        try (var children = Files.list(active.getParent())) {
            assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith("staging-")));
        }
    }

    @Test
    void linkedToolDirectoriesAreRejectedOnHostsWithSymlinkSupport() throws Exception {
        for (boolean managed : List.of(false, true)) {
            Path data = Files.createDirectory(temp.resolve("links-" + managed));
            Path external = Files.createDirectory(temp.resolve("external-" + managed));
            Path sentinel = Files.writeString(external.resolve("keep.txt"), "outside fixture stays intact");
            Path link = managed ? Files.createDirectory(data.resolve("tools")).resolve("managed") : data.resolve("tools");
            try {
                Files.createSymbolicLink(link, external);
            } catch (IOException | UnsupportedOperationException unavailable) {
                Assumptions.abort("Symlink unavailable on " + System.getProperty("os.name") + ": " + unavailable.getClass().getSimpleName());
            }
            var publisher = new Publisher();
            var failure = assertThrows(IOException.class,
                () -> new ManagedTools(data, publisher).install(capabilities, new ProcessRunner.Token(), text -> {
                }));
            assertEquals(Text.get("tools.error.symlink"), failure.getMessage());
            assertEquals(0, publisher.binaries);
            assertEquals("outside fixture stays intact", Files.readString(sentinel));
            System.out.println("MANAGED_SYMLINK_CHECK " + System.getProperty("os.name") + " managed=" + managed + " passed");
        }
    }

}
