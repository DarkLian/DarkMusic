package com.darkmusic.client.systemmedia;

import com.darkmusic.client.tools.Hashes;
import com.darkmusic.client.tools.ManagedTools;
import com.darkmusic.client.tools.ProcessRunner;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/**
 * The optional Media Controls extension. It is downloaded only when the player clicks Install, checked against the
 * manifest built into the JAR, unpacked outside {@code mods}, and kept as exactly one version. Nothing native is
 * loaded into Minecraft: the helper runs as its own process.
 */
public final class MediaControlsPackage implements AutoCloseable {
    static final String RESOURCE = "/assets/darkmusic/media/windows-x64/media-controls.json";
    static final String EXECUTABLE = "DarkMusic.Media.exe";
    private static final long LIMIT = 64L * 1024 * 1024;

    public record Manifest(String version, String name, long size, String sha256, String executableSha256) {
        static Manifest parse(String json) throws IOException {
            try {
                var o = JsonParser.parseString(json).getAsJsonObject();
                var m = new Manifest(o.get("version").getAsString(), o.get("name").getAsString(), o.get("size").getAsLong(),
                    o.get("sha256").getAsString(), o.get("executableSha256").getAsString());
                if (o.get("schema").getAsInt() != 1 || !m.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || !m.name.equals("darkmusic-media-controls-" + m.version + "-windows-x64.zip") || m.size <= 0 || m.size > LIMIT
                    || !m.sha256.matches("[a-f0-9]{64}") || !m.executableSha256.matches("[a-f0-9]{64}"))
                    throw new IOException("media.package_damaged");
                return m;
            } catch (RuntimeException invalid) {
                throw new IOException("media.package_damaged", invalid);
            }
        }

        URI url() {
            return URI.create("https://github.com/DarkLian/DarkMusic/releases/download/v" + version + "/" + name);
        }
    }

    @FunctionalInterface
    interface Download {
        void fetch(URI uri, Path out, long max, ProcessRunner.Token token, Consumer<Long> progress) throws Exception;
    }

    public enum State {UNSUPPORTED, MISSING, INSTALLING, READY, FAILED}

    private final Path root;
    private final Manifest manifest;
    private final Download download;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-media-setup").factory());
    private final ProcessRunner.Token token = new ProcessRunner.Token();
    private volatile State state;
    private volatile Path executable;
    private volatile long downloaded;
    private volatile String failure = "";

    public MediaControlsPackage(Path config) {
        this(config, manifest(), developmentDownload(FabricLoader.getInstance().isDevelopmentEnvironment(),
            System.getProperty("darkmusic.mediaControls", ""), ManagedTools::download), supported());
    }

    MediaControlsPackage(Path config, Manifest manifest, Download download, boolean supported) {
        root = config.resolve("media-native");
        this.manifest = manifest;
        this.download = download;
        state = supported && manifest != null ? State.MISSING : State.UNSUPPORTED;
        // Hashing the installed helper reads several megabytes; never on the client thread.
        if (state == State.MISSING) worker.execute(this::refresh);
    }

    static boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows")
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
    }

    private static Manifest manifest() {
        try (InputStream in = MediaControlsPackage.class.getResourceAsStream(RESOURCE)) {
            // Java-only builds carry no manifest; the feature is then simply unavailable.
            return in == null ? null : Manifest.parse(new String(in.readNBytes(4096), StandardCharsets.UTF_8));
        } catch (IOException damaged) {
            LoggerFactory.getLogger("darkmusic-media").warn("Media Controls manifest unreadable", damaged);
            return null;
        }
    }

    /**
     * Development builds install their locally built package, which differs from the release's.
     */
    static Download developmentDownload(boolean development, String archive, Download network) {
        if (!development || archive.isBlank()) return network;
        return (uri, out, max, request, progress) -> {
            Path local = Path.of(archive);
            if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) || Files.size(local) > max)
                throw new IOException("media.package_damaged");
            Files.copy(local, out, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(Files.size(out));
        };
    }

    public State state() {
        return state;
    }

    /**
     * Windows x64 with a package built for this version.
     */
    public boolean available() {
        return state != State.UNSUPPORTED;
    }

    public long size() {
        return manifest == null ? 0 : manifest.size;
    }

    public long downloaded() {
        return downloaded;
    }

    public String failure() {
        return failure;
    }

    /**
     * The verified helper, or null while it is missing, unverified or being installed.
     */
    public Path executable() {
        return state == State.READY ? executable : null;
    }

    private void refresh() {
        try {
            Path exe = installed(manifest);
            if (exe != null) {
                executable = exe;
                state = State.READY;
            }
        } catch (IOException unreadable) {
            LoggerFactory.getLogger("darkmusic-media").debug("Media Controls not installed", unreadable);
        }
    }

    private Path installed(Manifest m) throws IOException {
        Path exe = root.resolve(m.executableSha256).resolve(EXECUTABLE);
        if (!Files.isRegularFile(exe, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(exe.getParent())) return null;
        return Hashes.sha256(exe, LinkOption.NOFOLLOW_LINKS).equals(m.executableSha256) ? exe : null;
    }

    /**
     * Downloads, verifies and installs this version's helper. Completes normally; {@link #state} tells the outcome.
     */
    public synchronized CompletableFuture<Void> install() {
        if (state == State.UNSUPPORTED || state == State.INSTALLING) return CompletableFuture.completedFuture(null);
        state = State.INSTALLING;
        downloaded = 0;
        failure = "";
        return CompletableFuture.runAsync(() -> {
            try {
                executable = install(manifest);
                state = State.READY;
            } catch (Exception error) {
                LoggerFactory.getLogger("darkmusic-media").warn("Media Controls installation failed", error);
                failure = error.getMessage() != null && error.getMessage().startsWith("media.") ? error.getMessage() : "media.install_failed";
                state = State.FAILED;
            }
        }, worker);
    }

    Path install(Manifest m) throws Exception {
        for (Path path = root.toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IOException("media.package_damaged");
        Files.createDirectories(root);
        // Another Minecraft instance installing at the same time waits here instead of racing.
        try (var channel = FileChannel.open(root.resolve(".setup-lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            Path existing = installed(m);
            if (existing != null) {
                removeOthers(m);
                return existing;
            }
            Path zip = Files.createTempFile(root, "download-", ".zip");
            Path staging = root.resolve("staging-" + UUID.randomUUID());
            try {
                download.fetch(m.url(), zip, m.size, token, n -> downloaded = n);
                if (Files.size(zip) != m.size || !Hashes.sha256(zip, token).equals(m.sha256))
                    throw new IOException("media.package_damaged");
                extract(zip, staging);
                Path exe = staging.resolve(EXECUTABLE);
                if (!Files.isRegularFile(exe) || !Hashes.sha256(exe).equals(m.executableSha256))
                    throw new IOException("media.package_damaged");
                Path target = root.resolve(m.executableSha256);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTree(target);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(zip);
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(staging);
            }
            removeOthers(m);
            return root.resolve(m.executableSha256).resolve(EXECUTABLE);
        }
    }

    /**
     * Unpacks only plain names one level deep (the helper, its notices and a licenses folder), refusing any entry
     * that could land outside the staging folder.
     */
    static void extract(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        long total = 0;
        try (var input = new ZipInputStream(Files.newInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                String name = entry.getName();
                if (!name.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?/?") || name.contains(".."))
                    throw new IOException("media.package_damaged");
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) throw new IOException("media.package_damaged");
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var output = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    for (int read; (read = input.read(buffer)) != -1; ) {
                        total += read;
                        if (total > LIMIT) throw new IOException("media.package_damaged");
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /**
     * Keeps exactly one version: removes other unpacked versions, interrupted downloads and staging folders. A helper
     * another Minecraft is still running cannot be deleted on Windows and is left for next time.
     */
    void removeOthers(Manifest m) {
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                boolean stale = name.matches("[a-f0-9]{64}") && !name.equals(m.executableSha256)
                    || name.startsWith("staging-") || name.matches("download-[0-9]+\\.zip");
                if (!stale || Files.isSymbolicLink(child)) continue;
                try {
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) deleteTree(child);
                    else Files.deleteIfExists(child);
                } catch (IOException busy) {
                    LoggerFactory.getLogger("darkmusic-media").debug("Kept {} for now", child, busy);
                }
            }
        } catch (IOException unreadable) {
            LoggerFactory.getLogger("darkmusic-media").debug("Media Controls cleanup deferred", unreadable);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        try (var tree = Files.walk(path)) {
            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    /**
     * Removes the extension's files. Call after the helper was stopped; files it still holds are retried for a few
     * seconds.
     */
    public synchronized CompletableFuture<Void> remove() {
        if (state == State.UNSUPPORTED || state == State.INSTALLING) return CompletableFuture.completedFuture(null);
        executable = null;
        state = State.MISSING;
        return CompletableFuture.runAsync(this::removeAll, worker);
    }

    void removeAll() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try (var children = Files.list(root)) {
                for (Path child : children.toList()) {
                    if (child.getFileName().toString().equals(".setup-lock") || Files.isSymbolicLink(child)) continue;
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) deleteTree(child);
                    else Files.deleteIfExists(child);
                }
                return;
            } catch (NoSuchFileException absent) {
                return;
            } catch (IOException busy) {
                if (System.nanoTime() > deadline) {
                    LoggerFactory.getLogger("darkmusic-media").warn("Some Media Controls files are still in use", busy);
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        token.cancel();
        worker.shutdownNow();
    }
}
