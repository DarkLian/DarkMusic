package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.gui.PipEditorScreen;
import com.darkmusic.client.gui.PipScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Exercises the shipping PiP controls in an isolated instance. No automatic source selection.
 */
final class PipSmoothClient {
    private final Path folder = Path.of(System.getProperty("darkmusic.pipFolder"));
    private final ArrayBlockingQueue<String> measurements = new ArrayBlockingQueue<>(8192);
    private volatile boolean closed;
    private int stage, ticks;
    private PipSpectrumSmoke spectrumCheck;
    private PipSetupSmoke setupCheck;
    private HudMenusSmoke hudMenusCheck;
    private String phase = "manual";
    private long lastReceived, lastUpload, lastRender, draws, diagnosticDrops;
    private final String sourceTitle = System.getProperty("darkmusic.pipSourceTitle", "");
    private int joinedTick;
    private boolean selecting;
    private int refocuses;

    void start() {
        Thread.ofPlatform().daemon().name("pip-smoke-metrics").start(() -> {
            try {
                Files.createDirectories(folder);
                try (var output = Files.newBufferedWriter(folder.resolve("render.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    while (!closed || !measurements.isEmpty()) {
                        var line = measurements.poll(500, TimeUnit.MILLISECONDS);
                        if (line != null) {
                            output.write(line);
                            output.newLine();
                        }
                        if (measurements.isEmpty()) output.flush();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> ScreenEvents.afterExtract(screen).register((s, g, x, y, delta) -> record(mc)));
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("darkmusic_smoke", "pip_metrics"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (mc.gui.screen() == null) record(mc);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void record(Minecraft mc) {
        if (stage < 2 || closed) return;
        var app = DarkMusicClient.INSTANCE;
        long now = System.nanoTime();
        draws++;
        var f = app.pip.frame();
        if (f != null && f.receivedNanos() != lastReceived) {
            long gap = lastUpload == 0 ? 0 : now - lastUpload;
            lastUpload = now;
            lastReceived = f.receivedNanos();
            offer("{\"kind\":\"frame\",\"nanos\":" + now + ",\"sequence\":" + f.sequence() + ",\"gapMicros\":" + gap / 1000 + ",\"ageMicros\":" + (System.currentTimeMillis() * 1000 - f.capturedMicros()) + ",\"screen\":\"" + (mc.gui.screen() == null ? "HUD" : mc.gui.screen().getClass().getSimpleName()) + "\"}");
        }
        if (draws % 140 == 0)
            offer("{\"kind\":\"render\",\"nanos\":" + now + ",\"gapMicros\":" + (lastRender == 0 ? 0 : (now - lastRender) / 1000) + ",\"draws\":" + draws + "}");
        lastRender = now;
    }

    /**
     * Disposable copy of an existing development world, so the shared run folder is never opened.
     */
    private void joinBenchmarkWorld(Minecraft mc) throws java.io.IOException {
        if (System.getProperty("darkmusic.pipWorldSource") == null)
            throw new IllegalStateException("Joining a world needs -PpipWorldSource=<existing world folder>");
        var source = Path.of(System.getProperty("darkmusic.pipWorldSource"));
        var target = mc.gameDirectory.toPath().resolve("saves").resolve("PipBenchmark");
        if (!Files.exists(target)) try (var paths = Files.walk(source)) {
            for (var path : (Iterable<Path>) paths::iterator) {
                var copy = target.resolve(source.relativize(path).toString());
                // A client with the source world open holds its lock; the copy gets a fresh one.
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else if (!path.getFileName().toString().equals("session.lock")) Files.copy(path, copy);
            }
        }
        mc.createWorldOpenFlows().openWorld("PipBenchmark", () -> {
        });
    }

    /**
     * Consents inside this isolated instance only, using this build's own verified dependency archive.
     */
    private void titledSource(Minecraft mc, DarkMusicClient app) {
        if (mc.level == null) return;
        if (joinedTick == 0) joinedTick = ticks;
        if (selecting || ticks - joinedTick < 200 || !Files.exists(folder.resolve("select-fixture"))) return;
        selecting = true;
        var setup = app.pip.tools.runtime;
        app.settings.pip.enabled = true;
        note("select-start");
        setup.check().thenCompose(done -> {
                note("checked ready=" + setup.ready() + " status=" + setup.status());
                return setup.ready() ? CompletableFuture.<Void>completedFuture(null) : setup.install();
            })
            .thenCompose(done -> app.pip.tools.windows(new ProcessRunner.Token()))
            .thenAccept(sources -> mc.execute(() -> {
                var matches = sources.stream().filter(s -> s.Title().contains(sourceTitle)).toList();
                note("windows=" + sources.size() + " matches=" + matches.size());
                if (matches.size() != 1)
                    throw new IllegalStateException("Expected one window titled " + sourceTitle + ", found " + matches.size());
                app.pip.select(matches.getFirst());
                mc.gui.setScreen(null);
            })).exceptionally(failure -> {
                note("failed " + failure);
                failure.printStackTrace();
                return null;
            });
    }

    private void note(String text) {
        try {
            Files.writeString(folder.resolve("select.log"), System.currentTimeMillis() + " " + text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
        }
    }

    private void offer(String line) {
        if (!measurements.offer(line)) diagnosticDrops++;
    }

    private void tick(Minecraft mc) {
        try {
            ticks++;
            var app = DarkMusicClient.INSTANCE;
            if (closed || app == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (stage == 0) {
                app.settings.lyricsEnabled = app.settings.lyricsOnline = app.settings.waveformProgress = false;
                // The private collection may need ffmpeg to decode (Opus); use the tools the run was given.
                if (System.getProperty("darkmusic.downloadTools") != null) {
                    Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                    String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                    app.settings.ffmpegPath = tools.resolve("ffmpeg" + ext).toString();
                    app.settings.ffprobePath = tools.resolve("ffprobe" + ext).toString();
                }
                app.folders.choose(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = 1;
            } else if (stage == 1 && !app.library.snapshot().scanning() && !app.library.snapshot().tracks().isEmpty()) {
                SmokeUi.displayReady(mc);
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(144);
                var track = app.library.snapshot().tracks().getFirst();
                if (Boolean.getBoolean("darkmusic.pipBenchmark")) app.playback.stop();
                else {
                    app.playback.repeat(Repeat.ONE);
                    app.playback.play(List.of(track), track);
                }
                if (!sourceTitle.isEmpty() || Files.exists(folder.resolve("check-hud-menus"))) joinBenchmarkWorld(mc);
                else mc.gui.setScreen(new PipScreen(app, null));
                if (sourceTitle.isEmpty() && Boolean.getBoolean("darkmusic.pipBenchmark") && Files.exists(folder.resolve("select-fixture")))
                    app.pip.tools.windows(new ProcessRunner.Token()).thenAccept(sources -> mc.execute(() -> {
                        var fixtures = sources.stream().filter(s -> s.App().equals("DarkMusic.Pip.BenchmarkSource")).toList();
                        if (fixtures.size() != 1)
                            throw new IllegalStateException("Expected one explicitly requested private capture fixture");
                        app.pip.select(fixtures.getFirst());
                        mc.gui.setScreen(new PipEditorScreen(app, new PipScreen(app, null), false));
                    }));
                stage = 2;
            }
            if (stage == 2 && !sourceTitle.isEmpty()) {
                mc.getFramerateLimitTracker().onInputReceived();
                mc.options.pauseOnLostFocus = false;
                if (GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                    GLFW.glfwRestoreWindow(mc.getWindow().handle());
                    GLFW.glfwFocusWindow(mc.getWindow().handle());
                    refocuses++;
                }
                if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) mc.gui.setScreen(null);
                titledSource(mc, app);
            }
            if (stage == 2 && Files.exists(folder.resolve("check-spectrum"))) {
                if (spectrumCheck == null) spectrumCheck = new PipSpectrumSmoke();
                if (spectrumCheck.tick(mc, app)) Files.writeString(folder.resolve("stop-minecraft"), "done");
            }
            if (stage == 2 && Files.exists(folder.resolve("check-setup"))) {
                if (setupCheck == null) setupCheck = new PipSetupSmoke();
                if (setupCheck.tick(mc, app)) Files.writeString(folder.resolve("stop-minecraft"), "done");
            }
            if (stage == 2 && Files.exists(folder.resolve("check-hud-menus"))) {
                if (hudMenusCheck == null) hudMenusCheck = new HudMenusSmoke();
                if (hudMenusCheck.tick(mc, app)) Files.writeString(folder.resolve("stop-minecraft"), "done");
            }
            if (stage == 2 && ticks % 20 == 0) {
                if (Files.isRegularFile(folder.resolve("phase"))) {
                    String requested = Files.readString(folder.resolve("phase")).strip();
                    if (!requested.equals(phase) && java.util.Set.of("off", "video", "video4", "audio").contains(requested) && app.pip.source() != null) {
                        app.pip.stop();
                        phase = requested;
                        if (requested.equals("video4")) System.setProperty("darkmusic.pip.minIntervalMs", "4");
                        else System.clearProperty("darkmusic.pip.minIntervalMs");
                        app.settings.pip.frameRate = Integer.getInteger("darkmusic.pipFrameRate", 144);
                        app.settings.pip.audioSpectrum = requested.equals("audio");
                        if (!requested.equals("off")) app.pip.start();
                        lastReceived = lastUpload = 0;
                    }
                }
                var audio = app.playback.state();
                var capturedAudio = app.pip.audio();
                offer("{\"kind\":\"capture\",\"epochMillis\":" + System.currentTimeMillis() + ",\"phase\":\"" + phase + "\",\"received\":" + app.pip.received() + ",\"uploaded\":" + app.pip.uploaded() + ",\"audioPackets\":" + (capturedAudio == null ? 0 : capturedAudio.packets()) + ",\"audioStatus\":\"" + (capturedAudio == null ? "off" : capturedAudio.status()) + "\",\"world\":" + (mc.level != null) + ",\"screen\":\"" + (mc.gui.screen() == null ? "HUD" : mc.gui.screen().getClass().getSimpleName()) + "\"}");
                offer("{\"kind\":\"state\",\"nanos\":" + System.nanoTime() + ",\"status\":\"" + audio.status() + "\",\"position\":" + audio.position() + ",\"draws\":" + draws + ",\"uploads\":" + app.pip.uploaded() + ",\"dropped\":" + app.pip.dropped() + ",\"pip\":\"" + app.pip.status() + "\",\"focusSuccesses\":" + app.pip.focusSuccesses() + ",\"focusFailures\":" + app.pip.focusFailures() + ",\"fullscreen\":" + mc.getWindow().isFullscreen() + ",\"exclusive\":" + SmokeUi.field(mc.getWindow(), "exclusiveFullscreen") + ",\"refocuses\":" + refocuses + ",\"throttle\":\"" + mc.getFramerateLimitTracker().getThrottleReason() + "\",\"monitor\":" + GLFW.glfwGetWindowMonitor(mc.getWindow().handle()) + ",\"diagnosticDrops\":" + diagnosticDrops + "}");
            }
            if (stage == 2 && (Files.exists(folder.resolve("stop-minecraft")) || ticks > 36000)) {
                closed = true;
                app.pip.close();
                System.out.println("DARKMUSIC_SMOKE_PASS:" + Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                mc.stop();
            }
        } catch (Exception e) {
            e.printStackTrace();
            closed = true;
            mc.stop();
        }
    }
}
