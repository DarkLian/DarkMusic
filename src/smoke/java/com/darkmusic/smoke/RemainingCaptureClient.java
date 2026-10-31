package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Paused, settled original captures for the remaining-debt refactor; isolated capture-run settings only.
 */
final class RemainingCaptureClient {
    private int ticks, state = -2, frame, completed;
    private boolean choosing, paused;

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (state == 999) return;
        try {
            if (++ticks > 6000) throw new IllegalStateException("Capture timeout " + state);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.services().library() == null || !app.services().playlistsReady()
                || !mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
            if (ticks % 20 != 0) return;
            SmokeUi.captureReady(mc);
            if (state == -2) {
                prepare(app);
                state++;
                return;
            }
            if (!choosing || app.services().library().snapshot().scanning()
                || app.services().library().snapshot().tracks().size() < 2) return;
            if (state == -1) {
                var tracks = app.services().library().snapshot().tracks();
                app.services().playback().play(tracks, tracks.getFirst());
                state++;
                return;
            }
            if (!paused) {
                if (app.services().playback().state().status() != AudioBackend.Status.PLAYING) return;
                app.services().playback().pause();
                paused = true;
                return;
            }
            if (frame == 0) mc.gui.setScreen(screen(app, state));
            if (frame == 3 || frame == 5) capture(mc, state, frame == 3 ? "a" : "b");
            if (++frame > 6) {
                frame = 0;
                state++;
            }
            if (state == 15 && completed == 30) {
                LoggerFactory.getLogger("darkmusic-capture").info("DARKMUSIC_SMOKE_PASS:{} 30 settled remaining-debt captures",
                    Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                state = 999;
                mc.stop();
            }
        } catch (Throwable error) {
            LoggerFactory.getLogger("darkmusic-capture").error("DARKMUSIC_SMOKE_FAIL state=" + state, error);
            state = 999;
            mc.stop();
        }
    }

    private void prepare(DarkMusicClient app) {
        if (!app.services().config().directory().toString().contains("capture-run"))
            throw new IllegalStateException("Isolated settings required");
        app.services().replaceSettings(Settings.defaults(app.services().settings()));
        var s = app.services().settings();
        s.hudTransitions = false;
        s.hudIdleHide = 0;
        s.hudVisualizer = false;
        app.services().apply();
        app.sources().chooseMusicFolder(Path.of(System.getProperty("darkmusic.music")), () -> choosing = true);
    }

    private Screen screen(DarkMusicClient app, int index) {
        var sources = app.sources();
        var tracks = app.services().library().snapshot().tracks();
        return switch (index) {
            case 0 -> new MusicScreen(sources, null);
            case 1 -> new SettingsScreen(sources, null);
            case 2 -> new LyricsOptionsScreen(sources, null, tracks.getFirst());
            case 3 ->
                new DuplicateComparisonScreen(sources, null, new DuplicateResult("capture", tracks.getFirst(), tracks.get(1),
                    "a", "b", Set.of(DuplicateResult.Evidence.TITLE_DURATION), DuplicateResult.Strength.POSSIBLE));
            case 4 -> new UpNextScreen(sources, null);
            case 5 -> new PipScreen(sources, null);
            case 6 -> new ColorsScreen(sources, null, true);
            case 7 -> new PlaylistScreen(sources, null);
            case 8 -> new SoundEnvironmentScreen(sources, null);
            case 9 -> new AudioSettingsScreen(sources, null);
            default -> {
                app.services().settings().hudStyle = Settings.HudStyle.values()[(index - 10) % Settings.HudStyle.values().length];
                if (index == 14) app.services().settings().hudRaised = false;
                yield new HudEditorScreen(sources, null);
            }
        };
    }

    private void capture(Minecraft mc, int index, String repeat) throws Exception {
        SmokeUi.captureReady(mc);
        Path root = Path.of(System.getProperty("darkmusic.remainingCapture"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), "%02d-%s.png".formatted(index, repeat), mc.gameRenderer.mainRenderTarget(), 1, text -> completed++);
    }
}
