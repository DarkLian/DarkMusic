package com.darkmusic.client.download;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.text.Text;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Opt-in, instance-local executable installation. Nothing is fetched during normal startup.
 */
public final class ManagedTools {
    private static final long MAX_ASSET = 200_000_000, MAX_EXPANDED = 400_000_000;
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private final Path data;
    private final Fetch fetch;
    private static final Map<Path, FileChannel> PINS = new HashMap<>();
    private static final Set<String> OWNED_FILES = Set.of("yt-dlp", "yt-dlp.exe", "ffmpeg", "ffmpeg.exe", "ffprobe", "ffprobe.exe", "deno", "deno.exe",
        "FFmpeg.LICENSE", "FFmpeg.README", "manifest.json", "usage.lock", "release.json", "asset.download");

    private static synchronized void pin(Path directory) throws IOException {
        if (PINS.containsKey(directory)) return;
        var channel = FileChannel.open(directory.resolve("usage.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            channel.lock(0, Long.MAX_VALUE, true);
            PINS.put(directory, channel);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static long installedAt(Path directory) {
        try {
            Path manifest = directory.resolve("manifest.json");
            return Files.getLastModifiedTime(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) ? manifest : directory).toMillis();
        } catch (IOException failure) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Install lock held. Preserve current/previous bundles and every bundle leased by a running client.
     */
    static void prune(Path root, Path current) {
        if (current == null || Files.isSymbolicLink(root)) return;
        try (var files = Files.list(root)) {
            var candidates = files.filter(p -> !Files.isSymbolicLink(p) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.getFileName().toString().matches("bundle-[a-f0-9-]{36}|staging-[0-9]+"))
                .sorted(Comparator.comparingLong(ManagedTools::installedAt).reversed()).toList();
            Path previous = candidates.stream().filter(p -> !p.equals(current) && p.getFileName().toString().startsWith("bundle-")).findFirst().orElse(null);
            for (Path path : candidates) {
                if (path.equals(current) || path.equals(previous) || System.currentTimeMillis() - installedAt(path) < 86_400_000L)
                    continue;
                if (ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> Path.of(c).toAbsolutePath().normalize().startsWith(path)).orElse(false)))
                    continue;
                Path lease = path.resolve("usage.lock");
                try (var channel = FileChannel.open(lease, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     var lock = channel.tryLock()) {
                    if (lock == null) continue;
                    try (var children = Files.list(path)) {
                        var entries = children.toList();
                        if (entries.stream().anyMatch(p -> !OWNED_FILES.contains(p.getFileName().toString()) || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)))
                            continue;
                        for (Path child : entries) if (!child.equals(lease)) Files.delete(child);
                    }
                } catch (java.nio.channels.OverlappingFileLockException busy) {
                    continue;
                }
                Files.deleteIfExists(lease);
                Files.delete(path);
            }
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").debug("Deferred managed-tool cleanup", failure);
        }
    }

    public ManagedTools(Path data) {
        this(data, ManagedTools::download);
    }

    ManagedTools(Path data, Fetch fetch) {
        this.data = data.toAbsolutePath().normalize();
        this.fetch = fetch;
    }

    /**
     * Only a single safe child of our managed directory may be selected by the pointer.
     */
    public static Path current(Path data) {
        Path root = data.resolve("tools/managed").toAbsolutePath().normalize();
        try {
            Path pointer = root.resolve("current.json");
            if (Files.isSymbolicLink(data.resolve("tools")) || Files.isSymbolicLink(root) || Files.isSymbolicLink(pointer) || !Files.isRegularFile(pointer) || Files.size(pointer) > 4096)
                return null;
            String directory = JsonParser.parseString(Files.readString(pointer)).getAsJsonObject().get("directory").getAsString();
            if (!directory.matches("bundle-[a-f0-9-]{36}")) return null;
            Path value = root.resolve(directory);
            if (Files.isSymbolicLink(value) || !Files.isDirectory(value)) return null;
            try {
                pin(value);
            } catch (IOException failure) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-download").debug("Could not lease read-only managed tools", failure);
            }
            return value;
        } catch (Exception e) {
            return null;
        }
    }

    static JsonObject asset(JsonObject release, String name) throws IOException {
        for (var value : release.getAsJsonArray("assets")) {
            var a = value.getAsJsonObject();
            if (!name.equals(a.get("name").getAsString())) continue;
            String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : "";
            long size = a.get("size").getAsLong();
            if (!digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0 || size > MAX_ASSET)
                throw new IOException(Text.get("tools.error.checksum", name));
            trusted(URI.create(a.get("browser_download_url").getAsString()));
            return a;
        }
        throw new IOException(Text.get("tools.error.asset", name));
    }

    static void verify(Path file, String expected, long size) throws Exception {
        if (Files.size(file) != size) throw new IOException(Text.get("tools.error.incomplete"));
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!expected.equalsIgnoreCase("sha256:" + HexFormat.of().formatHex(digest.digest())))
            throw new IOException(Text.get("tools.error.integrity"));
    }

    static void extractZip(Path archive, String name, Path output, ProcessRunner.Token token) throws IOException {
        boolean found = false;
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                token.check();
                // Only the expected root executable is copied. No archive path is ever resolved on disk.
                if (!entry.isDirectory() && entry.getName().equals(name)) {
                    if (found) throw new IOException(Text.get("tools.error.archive"));
                    unpack(zip, output, token);
                    found = true;
                }
            }
        }
        if (!found) throw new IOException(Text.get("tools.error.archive"));
    }

    private static void unpack(InputStream input, Path output, ProcessRunner.Token token) throws IOException {
        try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                token.check();
                size += count;
                if (size > MAX_EXPANDED) throw new IOException(Text.get("tools.error.size"));
                out.write(buffer, 0, count);
            }
        }
    }

    private static void executable(Path path) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows") && !path.toFile().setExecutable(true, true))
            throw new IOException(Text.get("tools.error.executable", path.getFileName()));
    }

    static void trusted(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443)
            throw new IOException(Text.get("tools.error.address"));
    }

    public static void download(URI address, Path output, long limit, ProcessRunner.Token token, Consumer<Long> progress) throws Exception {
        URI uri = address;
        for (int redirects = 0; redirects <= 5; redirects++) {
            token.check();
            trusted(uri);
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "DarkMusic-Tool-Setup");
            try {
                int code = connection.getResponseCode();
                if (Set.of(301, 302, 303, 307, 308).contains(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank())
                        throw new IOException(Text.get("tools.error.address"));
                    uri = uri.resolve(location);
                    continue;
                }
                if (code != 200)
                    throw new IOException(Text.get("tools.error.http", code));
                if (connection.getContentLengthLong() > limit)
                    throw new IOException(Text.get("tools.error.size"));
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
                try (var input = connection.getInputStream(); var out = Files.newOutputStream(output)) {
                    byte[] buffer = new byte[65536];
                    long size = 0, notified = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        token.check();
                        size += count;
                        if (size > limit) throw new IOException(Text.get("tools.error.size"));
                        if (System.nanoTime() > deadline) throw new IOException(Text.get("tools.error.timeout"));
                        out.write(buffer, 0, count);
                        long now = System.nanoTime();
                        if (now - notified > 100_000_000L) {
                            progress.accept(size);
                            notified = now;
                        }
                    }
                    progress.accept(size);
                }
                token.check();
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException(Text.get("tools.error.address"));
    }

    public Toolchain install(ProcessRunner runner, ProcessRunner.Token token, Consumer<String> status) throws Exception {
        Platform platform = Platform.detect(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path tools = data.resolve("tools"), root = tools.resolve("managed");
        Files.createDirectories(tools);
        if (Files.isSymbolicLink(tools)) throw new IOException(Text.get("tools.error.symlink"));
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException(Text.get("tools.error.symlink"));
        try (var channel = FileChannel.open(tools.resolve("install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException(Text.get("tools.error.busy"));
            prune(root, current(data));
            Path stage = Files.createTempDirectory(root, "staging-");
            try {
                token.check();
                status.accept(Text.get("tools.check_releases"));
                var yt = release("yt-dlp/yt-dlp", stage, token);
                var ff = release("eugeneware/ffmpeg-static", stage, token);
                var deno = release("denoland/deno", stage, token);
                Map<String, JsonObject> assets = new LinkedHashMap<>();
                assets.put("yt-dlp" + platform.extension(), asset(yt, platform.ytAsset()));
                assets.put("ffmpeg" + platform.extension(), asset(ff, "ffmpeg-" + platform.os + "-" + platform.arch + ".gz"));
                assets.put("ffprobe" + platform.extension(), asset(ff, "ffprobe-" + platform.os + "-" + platform.arch + ".gz"));
                assets.put("deno" + platform.extension(), asset(deno, platform.denoAsset()));
                assets.put("FFmpeg.LICENSE", asset(ff, platform.os + "-" + platform.arch + ".LICENSE"));
                assets.put("FFmpeg.README", asset(ff, platform.os + "-" + platform.arch + ".README"));
                long bytes = assets.values().stream().mapToLong(a -> a.get("size").getAsLong()).sum();
                if (Files.getFileStore(root).getUsableSpace() < bytes + 800_000_000L)
                    throw new IOException(Text.get("tools.error.disk"));
                JsonObject manifest = new JsonObject();
                manifest.addProperty("platform", platform.os + "-" + platform.arch);
                manifest.addProperty("ytDlpRelease", yt.get("tag_name").getAsString());
                manifest.addProperty("ffmpegRelease", ff.get("tag_name").getAsString());
                manifest.addProperty("denoRelease", deno.get("tag_name").getAsString());
                JsonObject hashes = new JsonObject();
                assets.forEach((name, a) -> hashes.addProperty(name, a.get("digest").getAsString()));
                manifest.add("digests", hashes);
                Path previous = current(data);
                if (previous != null && Files.isRegularFile(previous.resolve("manifest.json"))) {
                    var old = JsonParser.parseString(Files.readString(previous.resolve("manifest.json"))).getAsJsonObject();
                    if (hashes.equals(old.get("digests"))) {
                        status.accept(Text.get("tools.check_installed"));
                        try {
                            return validate(previous, platform, runner, token);
                        } catch (IOException e) {
                            status.accept(Text.get("tools.repair"));
                        }
                    }
                }
                int index = 0;
                for (var entry : assets.entrySet()) {
                    token.check();
                    String name = entry.getKey(), label = ++index + "/" + assets.size() + " " + name;
                    var info = entry.getValue();
                    Path packed = stage.resolve("asset.download");
                    long size = info.get("size").getAsLong();
                    fetch.get(URI.create(info.get("browser_download_url").getAsString()), packed, size, token,
                        n -> status.accept(label + " " + Math.min(100, n * 100 / size) + "%"));
                    verify(packed, info.get("digest").getAsString(), size);
                    Path output = stage.resolve(name);
                    String source = info.get("name").getAsString();
                    if (source.endsWith(".gz")) {
                        try (var in = new GZIPInputStream(Files.newInputStream(packed))) {
                            unpack(in, output, token);
                        }
                    } else if (source.endsWith(".zip")) {
                        extractZip(packed, name, output, token);
                    } else Files.move(packed, output);
                    Files.deleteIfExists(packed);
                    if (!name.startsWith("FFmpeg.")) executable(output);
                }
                status.accept(Text.get("tools.verify"));
                Toolchain checked = validate(stage, platform, runner, token);
                manifest.addProperty("sources", "https://github.com/yt-dlp/yt-dlp | https://github.com/eugeneware/ffmpeg-static | https://github.com/denoland/deno");
                Files.writeString(stage.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
                token.check();
                Path installed = root.resolve("bundle-" + UUID.randomUUID());
                Files.move(stage, installed, StandardCopyOption.ATOMIC_MOVE);
                try {
                    ConfigStore.atomicWrite(root.resolve("current.json"), "{\"directory\":\"" + installed.getFileName() + "\"}");
                } catch (IOException e) {
                    DownloadWorkspace.remove(installed);
                    throw e;
                }
                String ext = platform.extension();
                return new Toolchain(installed.resolve("yt-dlp" + ext), installed.resolve("ffmpeg" + ext), installed.resolve("ffprobe" + ext), "deno:" + installed.resolve("deno" + ext), checked.versions());
            } finally {
                if (Files.exists(stage)) DownloadWorkspace.remove(stage);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new IOException(Text.get("tools.error.busy"), e);
        }
    }

    private Toolchain validate(Path directory, Platform platform, ProcessRunner runner, ProcessRunner.Token token) throws Exception {
        String ext = platform.extension();
        return Toolchain.resolve(new DownloadModels.Tools(directory.resolve("yt-dlp" + ext).toString(), directory.resolve("ffmpeg" + ext).toString(), directory.resolve("ffprobe" + ext).toString(), directory.resolve("deno" + ext).toString()), data, runner, token);
    }

    private JsonObject release(String repo, Path stage, ProcessRunner.Token token) throws Exception {
        Path metadata = stage.resolve("release.json");
        fetch.get(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"), metadata, 4_000_000, token, n -> {
        });
        var result = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        Files.delete(metadata);
        if (result.get("draft").getAsBoolean() || result.get("prerelease").getAsBoolean())
            throw new IOException(Text.get("tools.error.release"));
        return result;
    }

    @FunctionalInterface
    interface Fetch {
        void get(URI uri, Path output, long limit, ProcessRunner.Token token, Consumer<Long> progress) throws Exception;
    }

    record Platform(String os, String arch) {
        static Platform detect(String os, String arch) throws IOException {
            os = os.toLowerCase(Locale.ROOT);
            String platform = os.startsWith("windows") ? "win32" : os.startsWith("mac") || os.startsWith("darwin") ? "darwin" : os.startsWith("linux") ? "linux" : "";
            String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64", "x64" -> "x64";
                case "aarch64", "arm64" -> "arm64";
                default -> "";
            };
            // FFmpeg's selected publisher has no Windows ARM64 executable. Never silently use another architecture.
            if (platform.isEmpty() || cpu.isEmpty() || platform.equals("win32") && cpu.equals("arm64"))
                throw new IOException(Text.get("tools.error.platform", os, arch));
            return new Platform(platform, cpu);
        }

        String extension() {
            return os.equals("win32") ? ".exe" : "";
        }

        String ytAsset() {
            return os.equals("win32") ? "yt-dlp.exe" : os.equals("darwin") ? "yt-dlp_macos" : arch.equals("arm64") ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        }

        String denoAsset() {
            return "deno-" + (arch.equals("x64") ? "x86_64" : "aarch64") + "-" + switch (os) {
                case "win32" -> "pc-windows-msvc";
                case "darwin" -> "apple-darwin";
                default -> "unknown-linux-gnu";
            } + ".zip";
        }
    }
}
