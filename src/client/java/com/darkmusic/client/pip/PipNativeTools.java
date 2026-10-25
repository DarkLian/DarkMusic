package com.darkmusic.client.pip;

import com.darkmusic.client.download.ProcessRunner;
import com.google.gson.Gson;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;

/**
 * Bundled, offline helper. Extraction and executable verification never run on the client thread.
 */
public final class PipNativeTools {
    private final Path directory;
    private CompletableFuture<Path> prepared;

    public PipNativeTools(Path config) {
        directory = config.resolve("pip-native");
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
    }

    public static String failure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        String key = error.getMessage();
        return key != null && Set.of("pip.bundle_missing", "pip.bundle_damaged", "pip.unsupported").contains(key) ? key : "pip.capture_error";
    }

    public synchronized CompletableFuture<Path> prepare() {
        if (prepared == null || prepared.isCompletedExceptionally()) prepared = CompletableFuture.supplyAsync(() -> {
            try {
                return extract();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, r -> Thread.ofPlatform().daemon().name("darkmusic-pip-prepare").start(r));
        return prepared;
    }

    private Path extract() throws Exception {
        if (!supported()) throw new IOException("pip.unsupported");
        String development = System.getProperty("darkmusic.pipHelper", "");
        if (!development.isBlank()) {
            Path exe = Path.of(development).toAbsolutePath();
            if (!Files.isRegularFile(exe)) throw new IOException("pip.bundle_missing");
            return exe;
        }
        String base = "/assets/darkmusic/pip/windows-x64/";
        byte[] archive;
        String expected;
        try (var input = PipNativeTools.class.getResourceAsStream(base + "helper.zip"); var checksum = PipNativeTools.class.getResourceAsStream(base + "helper.sha256")) {
            if (input == null || checksum == null) throw new IOException("pip.bundle_missing");
            archive = input.readNBytes(100_000_001);
            expected = new String(checksum.readNBytes(128), java.nio.charset.StandardCharsets.US_ASCII).strip();
        }
        return unpack(archive, expected, directory);
    }

    static Path unpack(byte[] archive, String expected, Path directory) throws Exception {
        String digest = hash(archive);
        if (archive.length > 100_000_000 || !digest.equals(expected)) throw new IOException("pip.bundle_damaged");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("pip.bundle_damaged");
        Path target = directory.resolve(digest);
        boolean existing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(target)) throw new IOException("pip.bundle_damaged");
        Path staging = existing ? target : Files.createTempDirectory(directory, "prepare-");
        int count = 0;
        long total = 0;
        java.util.Set<Path> seen = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path file = staging.resolve(entry.getName()).normalize();
                if (!file.startsWith(staging) || file.equals(staging) || file.getNameCount() - staging.getNameCount() > 4 || !seen.add(file) || ++count > 2048)
                    throw new IOException("pip.bundle_damaged");
                for (Path parent = file; !parent.equals(staging); parent = parent.getParent())
                    if (Files.isSymbolicLink(parent)) throw new IOException("pip.bundle_damaged");
                if (entry.isDirectory()) {
                    if (!existing) Files.createDirectories(file);
                    else if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("pip.bundle_damaged");
                    continue;
                }
                byte[] content = zip.readNBytes(40_000_001);
                total += content.length;
                if (content.length > 40_000_000 || total > 300_000_000) throw new IOException("pip.bundle_damaged");
                if (existing) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != content.length || !hash(Files.readAllBytes(file)).equals(hash(content)))
                        throw new IOException("pip.bundle_damaged");
                } else {
                    Files.createDirectories(file.getParent());
                    Files.write(file, content, StandardOpenOption.CREATE_NEW);
                }
            }
        }
        if (existing) try (var files = Files.walk(target)) {
            for (Path path : files.toList())
                if (!path.equals(target) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !seen.contains(path))
                    throw new IOException("pip.bundle_damaged");
        }
        if (!Files.isRegularFile(staging.resolve("DarkMusic.Pip.exe"))) throw new IOException("pip.bundle_damaged");
        if (!existing) Files.move(staging, target);
        return target.resolve("DarkMusic.Pip.exe");
    }

    private static String hash(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public CompletableFuture<List<PipSource>> windows(ProcessRunner.Token token) {
        return prepare().thenApplyAsync(exe -> {
            try {
                var result = new ProcessRunner().run(List.of(exe.toString(), "--list"), exe.getParent(), token, Duration.ofSeconds(10), 262144, line -> {
                });
                if (result.exit() != 0) throw new IOException("pip.capture_error");
                var sources = new Gson().fromJson(result.output().strip(), PipSource[].class);
                if (sources == null || sources.length > 256) throw new IOException("pip.capture_error");
                return Arrays.stream(sources).filter(Objects::nonNull).filter(PipSource::valid).filter(s -> s.Pid() != ProcessHandle.current().pid()).toList();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, r -> Thread.ofPlatform().daemon().name("darkmusic-pip-windows").start(r));
    }
}
