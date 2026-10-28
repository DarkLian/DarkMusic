package com.darkmusic.client.update;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.command.ChatPalette;
import com.darkmusic.client.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Client-only notices and consent-driven staging. No Minecraft connection is used for updates.
 */
public final class UpdateService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("darkmusic-update");
    private final DarkMusicClient app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-update").factory());
    // Install buttons carry this code, so only DarkMusic's own buttons install without the command being typed.
    private final String installCode = HexFormat.of().formatHex(randomBytes());
    private final String install = "/da update install " + installCode;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Path installed, directory;
    private final String current;
    private boolean notified;
    private GitHubRelease release;
    private boolean checking, downloading, closed;
    private long checkedAt;
    private final ArrayList<Consumer<String>> checkListeners = new ArrayList<>();
    private volatile Path pending;
    private Path installFailureLog;
    private boolean recoveryReady, failureNotified;

    public UpdateService(DarkMusicClient app) {
        this.app = app;
        var mod = FabricLoader.getInstance().getModContainer("darkmusic").orElseThrow();
        current = mod.getMetadata().getVersion().getFriendlyString();
        var paths = mod.getOrigin().getPaths();
        installed = paths.size() == 1 && Files.isRegularFile(paths.getFirst()) ? paths.getFirst().toAbsolutePath().normalize() : null;
        directory = app.config.directory().resolve("updates");
        CompletableFuture.supplyAsync(() -> {
            try {
                return UpdateRecovery.read(directory, installed);
            } catch (Exception failure) {
                LOG.warn("Cannot inspect previous update results", failure);
                return new UpdateRecovery.Result(null, null);
            }
        }, worker).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            if (closed) return;
            pending = result.pending();
            installFailureLog = result.failureLog();
            recoveryReady = true;
            if (!checkListeners.isEmpty()) check();
        }));
    }

    public void tick(Minecraft mc) {
        if (closed || app.settings == null || !recoveryReady) return;
        if (!app.settings.updateNotifications) return;
        if (mc.level == null || mc.player == null || mc.getConnection() == null) return;
        if (installed == null) return; // Development source sets are never replaced.
        if (installFailureLog != null && !failureNotified) {
            failureNotified = true;
            var log = Text.component("update.open_log").withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(installFailureLog.toString())));
            mc.gui.hud.getChat().addClientSystemMessage(Text.component("update.install_failed").withStyle(ChatFormatting.RED)
                .append(Component.literal(" ")).append(log));
        }
        if (!checking && (checkedAt == 0 || System.nanoTime() - checkedAt > TimeUnit.HOURS.toNanos(6))) check();
        if (release != null && !notified && pending == null) {
            notified = true;
            var palette = palette();
            mc.gui.hud.getChat().addClientSystemMessage(palette.info("update.available", palette.plain(release.version(), palette.accent()))
                .append(Component.literal(" ")).append(palette.button("update.action", install, palette.success()))
                .append(Component.literal(" ")).append(palette.link("update.notes", notes(release))));
        }
    }

    public void checkNow(Consumer<String> result) {
        if (closed) {
            result.accept("update.check_failed");
            return;
        }
        if (pending != null) {
            result.accept("update.ready");
            return;
        }
        checkListeners.add(result);
        if (recoveryReady && !checking) check();
    }

    public String availableVersion() {
        return release == null ? "" : release.version();
    }

    public boolean canInstall() {
        return installed != null;
    }

    public Path installFailureLog() {
        return installFailureLog;
    }

    public void openInstallLog() {
        Path log = installFailureLog;
        if (log == null || closed) return;
        CompletableFuture.runAsync(() -> {
            try {
                Util.getPlatform().openPath(Files.isRegularFile(log) ? log : log.getParent());
            } catch (Exception failure) {
                LOG.warn("Cannot open update installation log", failure);
                Minecraft.getInstance().execute(() -> app.warn(Text.get("update.log_failed")));
            }
        }, worker);
    }

    public String downloadStatus() {
        return pending != null ? "update.ready" : downloading ? "update.downloading" : "update.failed";
    }

    private void check() {
        checking = true;
        checkedAt = System.nanoTime();
        boolean[] failed = {false};
        CompletableFuture.supplyAsync(() -> {
            try {
                return GitHubRelease.parse(new String(fetch(URI.create(GitHubRelease.API), 2 * 1024 * 1024), StandardCharsets.UTF_8), current).orElse(null);
            } catch (Exception failure) {
                LOG.warn("Update check failed", failure);
                failed[0] = true;
                return null;
            }
        }, worker).thenAccept(found -> Minecraft.getInstance().execute(() -> {
            checking = false;
            if (!closed) {
                release = found;
                String status = pending != null ? "update.ready" : failed[0] ? "update.check_failed" : found == null ? "update.latest" : "update.found";
                var listeners = List.copyOf(checkListeners);
                checkListeners.clear();
                for (var listener : listeners) listener.accept(status);
            }
        }));
    }

    /**
     * "/da update": check only, then offer the download. Nothing downloads until the offer is accepted.
     */
    public void checkFromCommand() {
        if (closed) return;
        if (pending != null) {
            message("update.ready");
            return;
        }
        message("update.checking");
        checkNow(status -> {
            if ("update.found".equals(status) && release != null) offer(release);
            else message(status);
        });
    }

    private void offer(GitHubRelease found) {
        var palette = palette();
        var line = palette.info("update.found_current", palette.plain(found.version(), palette.accent()), palette.plain(current, palette.text()));
        if (canInstall())
            line.append(Component.literal(" ")).append(palette.button("update.install_action", install, palette.success()));
        line.append(Component.literal(" ")).append(palette.link("update.notes", notes(found)));
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(line);
        if (!canInstall()) message("update.development");
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public boolean installCode(String code) {
        return installCode.equals(code);
    }

    private static String notes(GitHubRelease found) {
        return "https://github.com/DarkLian/DarkMusic/releases/tag/v" + found.version();
    }

    private ChatPalette palette() {
        var settings = app.settings;
        return ChatPalette.of(settings == null ? ChatPalette.DEFAULT_ACCENT : settings.menuAccentColor);
    }

    /**
     * "/da update install" and the notice's button: the user's consent to download and stage the update.
     */
    public void requestUpdate() {
        if (release != null && canInstall()) {
            download();
            return;
        }
        message("update.checking");
        checkNow(status -> {
            if ("update.found".equals(status)) {
                if (canInstall()) download();
                else message("update.development");
            } else message(status);
        });
    }

    public void download() {
        if (closed || app.settings == null || release == null || installed == null)
            return;
        if (pending != null) {
            message("update.ready");
            return;
        }
        if (downloading) {
            message("update.downloading");
            return;
        }
        downloading = true;
        message("update.downloading");
        GitHubRelease selected = release;
        CompletableFuture.runAsync(() -> {
            Path staged, job = null;
            try {
                byte[] bytes = fetch(selected.url(), selected.size());
                if (bytes.length != selected.size()) throw new IOException("Unexpected asset size");
                Files.createDirectories(directory);
                job = Files.createDirectory(directory.resolve(UUID.randomUUID().toString()));
                staged = job.resolve("download.jar");
                Files.write(staged, bytes, StandardOpenOption.CREATE_NEW);
                selected.verify(staged);
                Properties p = new Properties();
                p.setProperty("old", installed.toString());
                p.setProperty("oldHash", GitHubRelease.hash(installed));
                p.setProperty("name", selected.name());
                p.setProperty("sha256", selected.sha256());
                Path manifest = job.resolve("install.properties");
                // Copy the JDK-only helper outside the loaded mod so Windows can release the old JAR.
                String resource = UpdateInstaller.class.getName().replace('.', '/') + ".class";
                Path helper = job.resolve(resource);
                Files.createDirectories(helper.getParent());
                try (var input = UpdateInstaller.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) throw new IOException("Installer class unavailable");
                    Files.copy(input, helper);
                }
                try (var output = Files.newOutputStream(manifest, StandardOpenOption.CREATE_NEW)) {
                    p.store(output, "Approved DarkMusic update");
                }
                pending = manifest;
                Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    if (!closed) message("update.ready");
                });
            } catch (Exception failure) {
                LOG.warn("Update download failed", failure);
                if (job != null && (pending == null || !pending.getParent().equals(job))) try {
                    UpdateRecovery.discard(job);
                } catch (Exception cleanup) {
                    LOG.warn("Cannot clean failed update", cleanup);
                }
                Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    if (!closed) message("update.failed");
                });
            }
        }, worker);
    }

    private byte[] fetch(URI url, long limit) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(url)) throw new IOException("Untrusted update host");
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(90)).header("User-Agent", "DarkMusic/" + current)
                .header("Accept", url.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream").GET().build();
            var response = http.send(request, info -> limited(info.statusCode() >= 300 && info.statusCode() < 400 ? 65536 : limit));
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                url = url.resolve(response.headers().firstValue("location").orElseThrow());
                continue;
            }
            if (response.statusCode() != 200) throw new IOException("GitHub HTTP " + response.statusCode());
            return response.body();
        }
        throw new IOException("Too many update redirects");
    }

    static boolean trusted(URI url) {
        return "https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443) &&
            Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(url.getHost());
    }

    static HttpResponse.BodySubscriber<byte[]> limited(long limit) {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            Flow.Subscription subscription;
            long count;

            public CompletionStage<byte[]> getBody() {
                return delegate.getBody();
            }

            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                delegate.onSubscribe(subscription);
            }

            public void onNext(List<ByteBuffer> buffers) {
                for (var buffer : buffers) count += buffer.remaining();
                if (count > limit) {
                    subscription.cancel();
                    delegate.onError(new IOException("Update response too large"));
                } else delegate.onNext(buffers);
            }

            public void onError(Throwable failure) {
                delegate.onError(failure);
            }

            public void onComplete() {
                delegate.onComplete();
            }
        };
    }

    private void message(String key) {
        var palette = palette();
        boolean failure = key.equals("update.failed") || key.equals("update.check_failed");
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(failure ? palette.error(key) : palette.info(key));
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        Path manifest = pending;
        if (manifest == null) return;
        try {
            String exe = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
            Path java = Path.of(System.getProperty("java.home"), "bin", exe);
            new ProcessBuilder(java.toString(), "-cp", manifest.getParent().toString(), UpdateInstaller.class.getName(),
                Long.toString(ProcessHandle.current().pid()), manifest.toString())
                .redirectErrorStream(true).redirectOutput(manifest.getParent().resolve("install.log").toFile()).start();
        } catch (Exception failure) {
            LOG.warn("Update remains staged; installer could not start", failure);
        }
    }
}
