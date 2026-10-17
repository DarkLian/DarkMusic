package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.ManagedTools;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;

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
        require(mc.getWindow().isFullscreen() && mc.options.guiScale().get() == 3 && mc.getWindow().getGuiScale() == 3, "Capture requires fullscreen GUI scale 3");
        Path root = Path.of(System.getProperty("darkmusic.privateImages"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), name + "-1.0.2-3x.png", mc.gameRenderer.mainRenderTarget(), 1, text -> captures++);
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
            if (app == null || app.settings == null || app.library == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (!fullscreen) {
                fullscreen = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                return;
            }
            if (mc.options.guiScale().get() != 3) {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                return;
            }
            if (ticks % 20 != 0) return;
            if (step == -2) {
                require(app.config.directory().toString().contains("capture-run"), "Captures require isolated settings");
                app.settings = Settings.defaults(app.settings);
                app.volume(app.settings.volume);
                app.speed(app.settings.speed);
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> choosing = true);
                step++;
                return;
            }
            if (!choosing || app.library.snapshot().scanning() || app.library.snapshot().tracks().isEmpty()) return;
            switch (step) {
                case -1 -> {
                    for (var playlist : app.playlists.all()) app.playlists.delete(playlist.id());
                    String favorites = app.playlists.create("Favorites");
                    app.playlists.addAll(favorites, app.library.snapshot().tracks());
                    emptyPlaylist = app.playlists.create("New Playlist");
                    app.playback.play(app.library.snapshot().tracks(), app.library.snapshot().tracks().getFirst());
                    player = new MusicScreen(app, null);
                    mc.gui.setScreen(player);
                }
                case 0 -> capture(mc, "player");
                case 1 -> mc.gui.setScreen(new SettingsScreen(app, player));
                case 2 -> capture(mc, "settings");
                case 3 -> {
                    mc.gui.screen().mouseScrolled(20, mc.gui.screen().height - 20, 0, -10);
                }
                case 4 -> capture(mc, "hud-settings");
                case 5 -> mc.gui.setScreen(new HudEditorScreen(app, player));
                case 6 -> capture(mc, "hud-editor");
                case 7 -> mc.gui.setScreen(new PlaylistScreen(app, player));
                case 8 -> capture(mc, "playlists");
                case 9 -> {
                    mc.gui.setScreen(new TrackPickerScreen(app, player, emptyPlaylist, tracks -> {
                    }));
                    click(mc, "text.darkmusic.select_all");
                }
                case 10 -> capture(mc, "bulk-picker");
                case 11 -> mc.gui.setScreen(new DownloadScreen(app, player));
                case 12 -> capture(mc, "downloads");
                case 13 -> {
                    Path development = Path.of(System.getProperty("darkmusic.downloadTools"));
                    Path managed = ManagedTools.current(development.getParent().resolve("build/managed-tools-integration"));
                    Path directory = managed == null ? development : managed;
                    String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                    String js = managed == null ? "" : directory.resolve("deno" + ext).toString();
                    app.downloads.configure(new DownloadModels.Tools(directory.resolve("yt-dlp" + ext).toString(), directory.resolve("ffmpeg" + ext).toString(), directory.resolve("ffprobe" + ext).toString(), js));
                    app.settings.ytDlpPath = directory.resolve("yt-dlp" + ext).toString();
                    app.settings.ffmpegPath = directory.resolve("ffmpeg" + ext).toString();
                    app.settings.ffprobePath = directory.resolve("ffprobe" + ext).toString();
                    app.settings.javascriptPath = js;
                    app.downloads.checkTools();
                    mc.gui.setScreen(new DownloadToolsScreen(app, player));
                }
                case 14 -> {
                    if (app.downloads.snapshot().analyzing()) return;
                    require(app.downloads.toolVersions().size() == 4, "Tool versions are not separated into four rows: " + app.downloads.snapshot().message());
                    capture(mc, "download-tools");
                }
                case 15 -> {
                    mc.gui.setScreen(new SettingsScreen(app, player));
                    click(mc, "reset.darkmusic.button");
                }
                case 16 -> capture(mc, "reset-confirmation");
                default -> {
                    if (captures != 9) return;
                    org.slf4j.LoggerFactory.getLogger("darkmusic-capture").info("DARKMUSIC_SMOKE_PASS:{} nine original fullscreen GUI-scale-3 captures; real collection/artwork, unified settings/defaults, shared HUD, playlists, downloads and four tool rows",
                        Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                    step = 999;
                    mc.stop();
                    return;
                }
            }
            step++;
        } catch (Throwable error) {
            step = 999;
            org.slf4j.LoggerFactory.getLogger("darkmusic-capture").error("DARKMUSIC_SMOKE_FAIL", error);
            mc.stop();
        }
    }
}
