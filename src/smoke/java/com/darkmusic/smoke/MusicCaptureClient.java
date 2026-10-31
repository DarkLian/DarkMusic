package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ManagedTools;
import com.darkmusic.client.tools.ToolPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * Original fullscreen captures from the private collection; never included in the mod.
 */
final class MusicCaptureClient {
    private int ticks, step = -2, captures;
    private boolean fullscreen, choosing;
    private MusicScreen player;
    private String emptyPlaylist;

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private void capture(Minecraft mc, String name) throws Exception {
        SmokeUi.captureReady(mc);
        require(mc.getWindow().isFullscreen() && mc.options.guiScale().get() == 2 && mc.getWindow().getGuiScale() == 2, "Capture requires fullscreen GUI scale 2");
        Path root = Path.of(System.getProperty("darkmusic.privateImages"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), name + "-" + FabricLoader.getInstance().getModContainer("darkmusic").orElseThrow().getMetadata().getVersion().getFriendlyString() + "-2x.png", mc.gameRenderer.mainRenderTarget(), 1, text -> captures++);
    }

    private void click(Minecraft mc, String key) {
        String label = Text.get(key);
        AbstractWidget button = mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals(label)).map(c -> (AbstractWidget) c).findFirst().orElseThrow();
        if (!button.visible && mc.gui.screen() instanceof SettingsScreen)
            for (int i = 0; i < 50 && !button.visible; i++)
                mc.gui.screen().mouseScrolled(20, mc.gui.screen().height - 20, 0, -1);
        require(button.active && button.visible, "Unavailable capture control " + label);
        mc.gui.screen().mouseClicked(new MouseButtonEvent(button.getX() + 3, button.getY() + 3, new MouseButtonInfo(0, 0)), false);
    }

    private void tick(Minecraft mc) {
        if (step == 999) return;
        try {
            if (++ticks > 2400) throw new IllegalStateException("Release capture timed out at " + step);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.services().settings() == null || app.services().library() == null || !app.services().playlistsReady() || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (!fullscreen) {
                fullscreen = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                return;
            }
            if (mc.options.guiScale().get() != 2) {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return;
            }
            if (ticks % 20 != 0) return;
            if (step == -2) {
                require(app.services().config().directory().toString().contains("capture-run"), "Captures require isolated settings");
                app.services().replaceSettings(Settings.defaults(app.services().settings()));
                app.sources().volume(app.services().settings().volume);
                app.sources().speed(app.services().settings().speed);
                app.sources().chooseMusicFolder(Path.of(System.getProperty("darkmusic.music")), () -> choosing = true);
                step++;
                return;
            }
            if (!choosing || app.services().library().snapshot().scanning() || app.services().library().snapshot().tracks().isEmpty()) return;
            switch (step) {
                case -1 -> {
                    for (var playlist : app.services().playlists().all()) app.services().playlists().delete(playlist.id());
                    String favorites = app.services().playlists().create("Favorites");
                    app.services().playlists().addAll(favorites, app.services().library().snapshot().tracks());
                    emptyPlaylist = app.services().playlists().create("New Playlist");
                    app.services().playback().play(app.services().library().snapshot().tracks(), app.services().library().snapshot().tracks().getFirst());
                    player = new MusicScreen(app.sources(), null);
                    mc.gui.setScreen(player);
                }
                case 0 -> capture(mc, "player");
                case 1 -> mc.gui.setScreen(new SettingsScreen(app.sources(), player));
                case 2 -> capture(mc, "settings");
                case 3 -> {
                    mc.gui.screen().mouseScrolled(20, mc.gui.screen().height - 20, 0, -10);
                }
                case 4 -> capture(mc, "hud-settings");
                case 5 -> mc.gui.setScreen(new HudEditorScreen(app.sources(), player));
                case 6 -> capture(mc, "hud-editor");
                case 7 -> mc.gui.setScreen(new PlaylistScreen(app.sources(), player));
                case 8 -> capture(mc, "playlists");
                case 9 -> {
                    mc.gui.setScreen(new TrackPickerScreen(app.sources(), player, emptyPlaylist, tracks -> {
                    }));
                    click(mc, "text.darkmusic.select_all");
                }
                case 10 -> capture(mc, "bulk-picker");
                case 11 -> mc.gui.setScreen(new DownloadScreen(app.sources(), player));
                case 12 -> capture(mc, "downloads");
                case 13 -> {
                    Path development = Path.of(System.getProperty("darkmusic.downloadTools"));
                    Path managed = ManagedTools.current(development.getParent().resolve("build/managed-tools-integration"));
                    Path directory = managed == null ? development : managed;
                    String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                    String js = managed == null ? "" : directory.resolve("deno" + ext).toString();
                    app.services().downloads().configure(new ToolPaths(directory.resolve("yt-dlp" + ext).toString(), directory.resolve("ffmpeg" + ext).toString(), directory.resolve("ffprobe" + ext).toString(), js));
                    app.services().settings().ytDlpPath = directory.resolve("yt-dlp" + ext).toString();
                    app.services().settings().ffmpegPath = directory.resolve("ffmpeg" + ext).toString();
                    app.services().settings().ffprobePath = directory.resolve("ffprobe" + ext).toString();
                    app.services().apply();
                    app.services().settings().javascriptPath = js;
                    app.services().downloads().checkTools();
                    mc.gui.setScreen(new DownloadToolsScreen(app.sources(), player));
                }
                case 14 -> {
                    if (app.services().downloads().snapshot().analyzing()) return;
                    require(app.services().downloads().toolVersions().size() == 4, "Tool versions are not separated into four rows: " + app.services().downloads().snapshot().message());
                    capture(mc, "download-tools");
                }
                case 15 -> {
                    mc.gui.setScreen(new SettingsScreen(app.sources(), player));
                    click(mc, "reset.darkmusic.button");
                }
                case 16 -> capture(mc, "reset-confirmation");
                case 17 -> {
                    app.services().settings().spectrumColors.preset = VisualColors.Preset.AURORA;
                    mc.gui.setScreen(new ColorsScreen(app.sources(), player, true));
                }
                case 18 -> capture(mc, "spectrum-colors");
                case 19 -> {
                    app.services().settings().spectrumColors.preset = VisualColors.Preset.MOONLIGHT;
                    app.services().save();
                }
                case 20 -> {
                    app.services().settings().hudStyle = Settings.HudStyle.CIRCULAR;
                    app.services().settings().circularSpectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    app.services().settings().spectrumFinish = app.services().settings().progressFinish = Settings.SurfaceFinish.BEVELED;
                    mc.gui.setScreen(new SettingsScreen(app.sources(), player));
                }
                case 21 -> capture(mc, "circular-hud");
                case 22 -> {
                    app.services().settings().circularSpectrumStyle = Settings.SpectrumStyle.FILLED_SPIKE;
                    app.services().settings().circularText = false;
                    mc.gui.setScreen(new SettingsScreen(app.sources(), player));
                }
                case 23 -> capture(mc, "circular-spike");
                case 24 -> {
                    app.services().settings().hudStyle = Settings.HudStyle.CLASSIC;
                    app.services().settings().hudShape = Settings.HudShape.ROUNDED;
                    app.services().settings().hudRaised = true;
                    mc.gui.setScreen(new SettingsScreen(app.sources(), player));
                    mc.gui.screen().mouseScrolled(20, mc.gui.screen().height - 20, 0, -15);
                }
                case 25 -> capture(mc, "beveled-hud");
                case 26 -> {
                    app.services().settings().hudShape = Settings.HudShape.RECTANGLE;
                    app.services().settings().hudRaised = false;
                    app.services().settings().spectrumFinish = app.services().settings().progressFinish = Settings.SurfaceFinish.FLAT;
                    app.services().settings().circularSpectrumStyle = Settings.SpectrumStyle.BARS;
                    app.services().settings().circularText = true;
                    app.services().save();
                }
                default -> {
                    if (captures != 13) return;
                    LoggerFactory.getLogger("darkmusic-capture").info("DARKMUSIC_SMOKE_PASS:{} 13 original fullscreen GUI-scale-2 captures; real collection/artwork, Classic/Circular HUDs and beveled finishes, unified settings/defaults, playlists, downloads and four tool rows",
                        Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                    step = 999;
                    mc.stop();
                    return;
                }
            }
            step++;
        } catch (Throwable error) {
            LoggerFactory.getLogger("darkmusic-capture").error("DARKMUSIC_SMOKE_FAIL step=" + step, error);
            step = 999;
            mc.stop();
        }
    }
}
