package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.cleanup.DuplicateResult;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.hud.HudPreview;
import com.darkmusic.client.library.Track;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.ArrayList;

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
                var source = tracks.getFirst();
                // Short display labels derive from the real metadata; source audio/artwork/identity stay original.
                var display = new Track(source.rootId(), source.relativePath(), source.file(), shortLabel(source.title()),
                    shortLabel(source.artist()), source.album(), source.duration(), source.size(), source.modified(), source.problem());
                var order = new ArrayList<>(tracks);
                order.set(0, display);
                app.services().playback().play(order, display);
                state++;
                return;
            }
            if (!paused) {
                if (app.services().playback().state().status() != AudioBackend.Status.PLAYING) return;
                app.services().playback().pause();
                app.services().playback().seek(83);
                paused = true;
                return;
            }
            var playback = app.services().playback().state();
            if (playback.status() != AudioBackend.Status.PAUSED || Math.abs(playback.position() - 83) > .001) return;
            if (frame == 0) {
                mc.gui.setScreen(screen(app, state));
                mc.gui.screen().setFocused(null);
                GLFW.glfwSetCursorPos(mc.getWindow().handle(), 1918, 1078);
            }
            if (frame == 5 || frame == 7) capture(mc, state, frame == 5 ? "a" : "b");
            if (++frame > 8) {
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
        var tools = Path.of(System.getProperty("darkmusic.downloadTools"));
        s.ffmpegPath = tools.resolve("ffmpeg.exe").toString();
        s.ffprobePath = tools.resolve("ffprobe.exe").toString();
        s.ytDlpPath = tools.resolve("yt-dlp.exe").toString();
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
                yield new OpaqueEditor(new HudEditorScreen(sources, null));
            }
        };
    }

    private static String shortLabel(String value) {
        return value.substring(0, Math.min(8, value.length()));
    }

    /**
     * Preserve the actual editor/HUD renderer, with an opaque test backdrop over the title panorama.
     */
    private static final class OpaqueEditor extends Screen implements HudPreview {
        private final HudEditorScreen editor;

        OpaqueEditor(HudEditorScreen editor) {
            super(Component.empty());
            this.editor = editor;
        }

        @Override
        protected void init() {
            editor.init(width, height);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
            g.fill(0, 0, width, height, 0xFF111420);
            editor.extractRenderState(g, x, y, delta);
        }
    }

    private void capture(Minecraft mc, int index, String repeat) throws Exception {
        SmokeUi.captureReady(mc);
        Path root = Path.of(System.getProperty("darkmusic.remainingCapture"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), "%02d-%s.png".formatted(index, repeat), mc.gameRenderer.mainRenderTarget(), 1, text -> completed++);
    }
}
