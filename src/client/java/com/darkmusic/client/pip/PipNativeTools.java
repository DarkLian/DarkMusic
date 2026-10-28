package com.darkmusic.client.pip;

import com.darkmusic.client.tools.Hashes;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.update.GitHubRelease;
import com.google.gson.Gson;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;

/**
 * Optional, verified native helper. Extraction and executable verification never run on the client thread.
 */
public final class PipNativeTools {
    private final Path directory;
    public final PipRuntime runtime;
    private final BooleanSupplier allowed;
    private CompletableFuture<Path> prepared;

    public PipNativeTools(Path config) {
        this(config, () -> false);
    }

    public PipNativeTools(Path config, BooleanSupplier allowed) {
        directory = config.resolve("pip-native");
        runtime = new PipRuntime(config);
        this.allowed = allowed;
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
    }

    public static String failure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        String key = error.getMessage();
        return key != null && Set.of("pip.bundle_missing", "pip.bundle_damaged", "pip.unsupported", "pip.setup.required", "pip.setup.disabled", "pip.setup.busy", "pip.setup.runtime_in_use").contains(key) ? key : "pip.capture_error";
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
        if (!allowed.getAsBoolean()) throw new IOException("pip.setup.disabled");
        return runtime.executable();
    }

    static Path unpack(byte[] archive, String expected, Path directory) throws Exception {
        String digest = hash(archive);
        if (archive.length > 100_000_000 || !digest.equals(expected)) throw new IOException("pip.bundle_damaged");
        return unpackArchives(List.of(() -> new ByteArrayInputStream(archive)), digest, directory);
    }

    static Path unpack(Path archive, String expected, Path directory) throws Exception {
        return unpack(archive, expected, directory, () -> {
        });
    }

    static Path unpack(Path archive, String expected, Path directory, Runnable beforePublish) throws Exception {
        if (Files.isSymbolicLink(archive) || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(archive) > 100_000_000 || !GitHubRelease.hash(archive).equals(expected))
            throw new IOException("pip.bundle_damaged");
        return unpackArchives(List.of(() -> Files.newInputStream(archive)), expected, directory, beforePublish);
    }

    private interface Archive {
        InputStream open() throws IOException;
    }

    private static Path unpackArchives(List<Archive> archives, String digest, Path directory) throws Exception {
        return unpackArchives(archives, digest, directory, () -> {
        });
    }

    private static Path unpackArchives(List<Archive> archives, String digest, Path directory, Runnable beforePublish) throws Exception {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("pip.bundle_damaged");
        Path target = directory.resolve(digest);
        boolean existing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(target)) throw new IOException("pip.bundle_damaged");
        Path staging = existing ? target : Files.createTempDirectory(directory, "prepare-");
        try {
            int count = 0;
            long total = 0;
            Set<Path> seen = new HashSet<>();
            for (var archive : archives)
                try (var zip = new ZipInputStream(archive.open())) {
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
                        if (content.length > 40_000_000 || total > 300_000_000)
                            throw new IOException("pip.bundle_damaged");
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
                    if (!path.equals(target) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !seen.contains(path) && !PipStorage.auxiliary(path))
                        throw new IOException("pip.bundle_damaged");
            }
            if (!Files.isRegularFile(staging.resolve("DarkMusic.Pip.exe"))) throw new IOException("pip.bundle_damaged");
            if (!existing) try {
                beforePublish.run();
                Files.move(staging, target);
            } catch (FileAlreadyExistsException raced) {
                // Re-run full archive/file validation against the winner; never trust its name alone.
                return unpackArchives(archives, digest, directory);
            }
            return target.resolve("DarkMusic.Pip.exe");
        } finally {
            if (!existing && Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
                PipStorage.removeTree(directory, staging);
        }
    }

    private static String hash(byte[] bytes) {
        return Hashes.sha256(bytes);
    }

    public CompletableFuture<List<PipSource>> windows(ProcessRunner.Token token) {
        if (!allowed.getAsBoolean()) return CompletableFuture.failedFuture(new IOException("pip.setup.disabled"));
        return prepare().thenApplyAsync(exe -> {
            try {
                if (!allowed.getAsBoolean()) throw new IOException("pip.setup.disabled");
                var result = new ProcessRunner().run(List.of(exe.toString(), "--list"), exe.getParent(), token, Duration.ofSeconds(10), 262144, line -> {
                });
                if (!allowed.getAsBoolean()) throw new IOException("pip.setup.disabled");
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
