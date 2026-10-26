package com.darkmusic.client.pip;

import com.darkmusic.client.download.ManagedTools;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.update.GitHubRelease;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * Explicit, instance-local runtime setup. Construction never starts a process or network request.
 */
public final class PipRuntime implements AutoCloseable {
    static final String BASE = "/assets/darkmusic/pip/windows-x64/";

    public record Bundle(String version, String name, long size, String sha256) {
        static Bundle parse(String json) throws IOException {
            try {
                var o = JsonParser.parseString(json).getAsJsonObject();
                var b = new Bundle(o.get("version").getAsString(), o.get("name").getAsString(), o.get("size").getAsLong(), o.get("sha256").getAsString());
                if (o.get("schema").getAsInt() != 2 || !b.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
                    !b.name.equals("darkmusic-pip-dependencies-" + b.version + "-windows-x64.zip") || b.size <= 0 || b.size > 100_000_000 || !b.sha256.matches("[a-f0-9]{64}"))
                    throw new IOException("pip.bundle_damaged");
                return b;
            } catch (RuntimeException e) {
                throw new IOException("pip.bundle_damaged", e);
            }
        }

        URI url() {
            return URI.create("https://github.com/DarkLian/DarkMusic/releases/download/v" + version + "/" + name);
        }

        void verify(Path path) throws Exception {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size || !GitHubRelease.hash(path).equals(sha256))
                throw new IOException("pip.bundle_damaged");
        }
    }

    private final Path root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-pip-setup").factory());
    private volatile boolean busy, compatible, ready, closed;
    private volatile long downloaded;
    private volatile String status = "pip.setup.unchecked";
    private volatile Path executable;
    private volatile int build;
    private ProcessRunner.Token token;
    private volatile Bundle bundle;
    private PipStorage.Lease lease;

    @FunctionalInterface
    interface Resources {
        byte[] read(String name, int max) throws IOException;
    }

    @FunctionalInterface
    interface Download {
        void fetch(URI uri, Path out, long max, ProcessRunner.Token token, java.util.function.Consumer<Long> progress) throws Exception;
    }

    @FunctionalInterface
    interface Requirements {
        int build(ProcessRunner.Token token) throws Exception;
    }

    @FunctionalInterface
    interface Graphics {
        boolean supported(Path exe, ProcessRunner.Token token) throws Exception;
    }

    private final Resources resources;
    private final Download download;
    private final Requirements requirements;
    private final Graphics graphics;

    public PipRuntime(Path config) {
        this(config, PipRuntime::resource, developmentDownload(net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment(),
            System.getProperty("darkmusic.pipDependencies", ""), ManagedTools::download), null, PipRuntime::graphics);
    }

    /**
     * Development builds pin their locally generated archive, which can differ from release CI.
     */
    static Download developmentDownload(boolean development, String archive, Download network) {
        if (!development || archive.isBlank()) return network;
        return (uri, out, max, request, progress) -> {
            Path local = Path.of(archive);
            request.check();
            if (Files.isSymbolicLink(local) || !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) || Files.size(local) > max)
                throw new IOException("pip.bundle_damaged");
            try (var input = Files.newInputStream(local); var output = Files.newOutputStream(out, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    request.check();
                    total += count;
                    if (total > max) throw new IOException("pip.bundle_damaged");
                    output.write(buffer, 0, count);
                    progress.accept(total);
                }
            }
            // The installer still verifies the manifest's exact size and SHA-256 before extraction.
        };
    }

    PipRuntime(Path config, Resources resources, Download download, Requirements requirements, Graphics graphics) {
        root = config.resolve("pip-native");
        this.resources = resources;
        this.download = download;
        this.requirements = requirements == null ? this::requirements : requirements;
        this.graphics = graphics;
    }

    public boolean busy() {
        return busy;
    }

    public boolean compatible() {
        return compatible;
    }

    public boolean ready() {
        return ready;
    }

    public boolean audioSupported() {
        return build >= 20348;
    }

    public String status() {
        return status;
    }

    public long downloaded() {
        return downloaded;
    }

    public long size() {
        return bundle == null ? 0 : bundle.size;
    }

    public Path executable() throws IOException {
        if (!ready || executable == null) throw new IOException("pip.setup.required");
        return executable;
    }

    static byte[] resource(String name, int max) throws IOException {
        try (var in = PipRuntime.class.getResourceAsStream(BASE + name)) {
            if (in == null) throw new IOException("pip.bundle_missing");
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) throw new IOException("pip.bundle_damaged");
            return bytes;
        }
    }

    private Path archive() {
        return root.resolve("dependencies-" + bundle.sha256 + ".zip");
    }

    private void directory() throws IOException {
        for (Path path = root.toAbsolutePath(); path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IOException("pip.bundle_damaged");
        Files.createDirectories(root);
    }

    /**
     * Read-only Windows/WGC check, before offering any installation. No selected-window access.
     */
    private int requirements(ProcessRunner.Token request) throws Exception {
        if (!PipNativeTools.supported()) return 0;
        String system = System.getenv("SystemRoot");
        if (system == null) return 0;
        var result = new ProcessRunner().run(java.util.List.of(Path.of(system, "System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-Command",
                "$v=[Environment]::OSVersion.Version; if($v.Build -ge 19041 -and [Windows.Graphics.Capture.GraphicsCaptureSession,Windows.Graphics.Capture,ContentType=WindowsRuntime]::IsSupported()){ 'supported '+$v.Build }"),
            root.getParent(), request, Duration.ofSeconds(15), 4096, line -> {
            });
        String output = result.output().strip();
        return requirementsResult(result.exit(), output);
    }

    static int requirementsResult(int exit, String output) throws IOException {
        if (exit != 0 || !output.isEmpty() && !output.matches("supported [0-9]{5,6}"))
            throw new IOException("PiP requirements probe failed");
        return output.isEmpty() ? 0 : Integer.parseInt(output.substring(10));
    }

    static boolean graphics(Path exe, ProcessRunner.Token request) throws Exception {
        var probe = new ProcessRunner().run(java.util.List.of(exe.toString(), "--requirements"), exe.getParent(), request, Duration.ofSeconds(15), 4096, line -> {
        });
        return probe.exit() == 0 && probe.output().strip().equals("supported");
    }

    public CompletableFuture<Void> check() {
        return begin(false);
    }

    public CompletableFuture<Void> install() {
        return compatible ? begin(true) : CompletableFuture.completedFuture(null);
    }

    private synchronized CompletableFuture<Void> begin(boolean install) {
        if (closed || busy) return CompletableFuture.completedFuture(null);
        busy = true;
        ready = false;
        downloaded = 0;
        var request = new ProcessRunner.Token();
        token = request;
        status = install ? "pip.setup.installing" : "pip.setup.checking";
        return CompletableFuture.runAsync(() -> {
            try {
                build = requirements.build(request);
                compatible = build >= 19041;
                request.check();
                if (!compatible) {
                    status = "pip.setup.unsupported";
                    return;
                }
                bundle = Bundle.parse(new String(resources.read("dependencies.json", 4096), StandardCharsets.UTF_8));
                directory();
                try (var setup = PipStorage.setup(root, request)) {
                    if (install) {
                        Path temporary = Files.createTempFile(root, "runtime-", ".download");
                        try {
                            download.fetch(bundle.url(), temporary, bundle.size, request, n -> downloaded = n);
                            bundle.verify(temporary);
                            request.check();
                            if (Files.exists(archive(), LinkOption.NOFOLLOW_LINKS)) bundle.verify(archive());
                            else try {
                                Files.move(temporary, archive());
                            } catch (FileAlreadyExistsException raced) {
                                bundle.verify(archive());
                            }
                        } finally {
                            Files.deleteIfExists(temporary);
                        }
                    }
                    if (!Files.exists(archive(), LinkOption.NOFOLLOW_LINKS)) {
                        status = "pip.setup.required";
                        return;
                    }
                    bundle.verify(archive());
                    request.check();
                    var exe = PipNativeTools.unpack(archive(), bundle.sha256, root);
                    request.check();
                    if (!graphics.supported(exe, request)) {
                        status = "pip.setup.graphics_failed";
                        return;
                    }
                    request.check();
                    if (lease != null) lease.close();
                    lease = PipStorage.use(exe.getParent(), archive().getFileName().toString(), request);
                    executable = exe;
                    ready = true;
                    status = "pip.setup.ready";
                    try {
                        PipStorage.prune(root, exe.getParent(), archive(), System.currentTimeMillis());
                    } catch (Exception cleanup) {
                        org.slf4j.LoggerFactory.getLogger("darkmusic-pip-setup").warn("Unused PiP runtime cleanup deferred", cleanup);
                    }
                }
            } catch (Exception error) {
                if (!request.cancelled())
                    org.slf4j.LoggerFactory.getLogger("darkmusic-pip-setup").warn("PiP runtime setup failed", error);
                status = request.cancelled() ? "pip.setup.cancelled" : PipNativeTools.failure(error);
                if (status.equals("pip.capture_error"))
                    status = install ? "pip.setup.install_failed" : "pip.setup.check_failed";
            } finally {
                if (closed && lease != null) try {
                    lease.close();
                    lease = null;
                } catch (IOException ignored) {
                }
                busy = false;
            }
        }, worker);
    }

    public synchronized void cancel() {
        if (token != null) token.cancel();
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
        worker.shutdown();
        if (!busy && lease != null) try {
            lease.close();
            lease = null;
        } catch (IOException ignored) {
        }
    }
}
