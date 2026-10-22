package com.darkmusic.client.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ManagedToolsTest {
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
        var copies = new java.util.ArrayList<Path>();
        for (int i = 0; i < 4; i++) {
            Path p = Files.createDirectory(root.resolve("bundle-" + java.util.UUID.randomUUID()));
            Files.writeString(p.resolve("ffmpeg"), "metadata fixture");
            copies.add(p);
            Files.writeString(p.resolve("manifest.json"), "{}");
            Files.setLastModifiedTime(p.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - (10 - i) * 86_400_000L));
            Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - (10 - i) * 86_400_000L));
        }
        Path unknown = Files.createDirectory(root.resolve("personal-files"));
        Path stage = Files.createDirectory(root.resolve("staging-123456"));
        Files.setLastModifiedTime(stage, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3 * 86_400_000L));
        try (var channel = java.nio.channels.FileChannel.open(copies.get(1).resolve("usage.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
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
        assertThrows(java.util.concurrent.CancellationException.class, () -> installer.install(capabilities, new ProcessRunner.Token(), text -> {
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
}
