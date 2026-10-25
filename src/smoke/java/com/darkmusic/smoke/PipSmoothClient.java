package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.PlaybackController;
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
    private String phase = "manual";
    private long lastReceived, lastUpload, lastRender, draws, diagnosticDrops;

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
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = 1;
            } else if (stage == 1 && !app.library.snapshot().scanning() && !app.library.snapshot().tracks().isEmpty()) {
                SmokeUi.displayReady(mc);
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(144);
                var track = app.library.snapshot().tracks().getFirst();
                if (Boolean.getBoolean("darkmusic.pipBenchmark")) app.playback.stop();
                else {
                    app.playback.repeat(PlaybackController.Repeat.ONE);
                    app.playback.play(List.of(track), track);
                }
                mc.gui.setScreen(new PipScreen(app, null));
                if (Boolean.getBoolean("darkmusic.pipBenchmark") && Files.exists(folder.resolve("select-fixture")))
                    app.pip.tools.windows(new com.darkmusic.client.download.ProcessRunner.Token()).thenAccept(sources -> mc.execute(() -> {
                        var fixtures = sources.stream().filter(s -> s.App().equals("DarkMusic.Pip.BenchmarkSource")).toList();
                        if (fixtures.size() != 1)
                            throw new IllegalStateException("Expected one explicitly requested private capture fixture");
                        app.pip.select(fixtures.getFirst());
                        mc.gui.setScreen(new com.darkmusic.client.gui.PipEditorScreen(app, new PipScreen(app, null), false));
                    }));
                stage = 2;
            }
            if (stage == 2 && Files.exists(folder.resolve("check-spectrum"))) {
                if (spectrumCheck == null) spectrumCheck = new PipSpectrumSmoke();
                if (spectrumCheck.tick(mc, app)) Files.writeString(folder.resolve("stop-minecraft"), "done");
            }
            if (stage == 2 && Files.exists(folder.resolve("check-setup"))) {
                if (setupCheck == null) setupCheck = new PipSetupSmoke();
                if (setupCheck.tick(mc, app)) Files.writeString(folder.resolve("stop-minecraft"), "done");
            }
            if (stage == 2 && ticks % 20 == 0) {
                if (Files.isRegularFile(folder.resolve("phase"))) {
                    String requested = Files.readString(folder.resolve("phase")).strip();
                    if (!requested.equals(phase) && java.util.Set.of("off", "video", "video4", "audio").contains(requested) && app.pip.source() != null) {
                        app.pip.stop();
                        phase = requested;
                        if (requested.equals("video4")) System.setProperty("darkmusic.pip.minIntervalMs", "4");
                        else System.clearProperty("darkmusic.pip.minIntervalMs");
                        app.settings.pip.frameRate = 144;
                        app.settings.pip.audioSpectrum = requested.equals("audio");
                        if (!requested.equals("off")) app.pip.start();
                        lastReceived = lastUpload = 0;
                    }
                }
                var audio = app.playback.state();
                var capturedAudio = app.pip.audio();
                offer("{\"kind\":\"capture\",\"epochMillis\":" + System.currentTimeMillis() + ",\"phase\":\"" + phase + "\",\"received\":" + app.pip.received() + ",\"uploaded\":" + app.pip.uploaded() + ",\"audioPackets\":" + (capturedAudio == null ? 0 : capturedAudio.packets()) + ",\"audioStatus\":\"" + (capturedAudio == null ? "off" : capturedAudio.status()) + "\",\"world\":" + (mc.level != null) + ",\"screen\":\"" + (mc.gui.screen() == null ? "HUD" : mc.gui.screen().getClass().getSimpleName()) + "\"}");
                offer("{\"kind\":\"state\",\"nanos\":" + System.nanoTime() + ",\"status\":\"" + audio.status() + "\",\"position\":" + audio.position() + ",\"draws\":" + draws + ",\"uploads\":" + app.pip.uploaded() + ",\"dropped\":" + app.pip.dropped() + ",\"pip\":\"" + app.pip.status() + "\",\"focusSuccesses\":" + app.pip.focusSuccesses() + ",\"focusFailures\":" + app.pip.focusFailures() + ",\"fullscreen\":" + mc.getWindow().isFullscreen() + ",\"monitor\":" + GLFW.glfwGetWindowMonitor(mc.getWindow().handle()) + ",\"diagnosticDrops\":" + diagnosticDrops + "}");
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
