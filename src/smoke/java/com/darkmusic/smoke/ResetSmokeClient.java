package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.config.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Isolated reset verification; only copies of private real music enter reset's scope.
 */
final class ResetSmokeClient {
    private final Map<Path, String> hashes = new LinkedHashMap<>();
    private int tick, stage;
    private boolean configured;
    private Path data, custom, source;
    private CompletableFuture<Void> prepared;
    private String playlist, retainedPlaylist, jumpKey;
    private net.minecraft.client.gui.screens.Screen settingsScreen;
    private CompletableFuture<Settings> saved;

    private static List<String> differences(Settings expected, Settings actual) {
        var a = ConfigStore.JSON.toJsonTree(expected).getAsJsonObject();
        var b = ConfigStore.JSON.toJsonTree(actual).getAsJsonObject();
        var keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys.stream().filter(k -> !Objects.equals(a.get(k), b.get(k))).map(k -> k + "=" + b.get(k) + " expected " + a.get(k)).toList();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void click(Minecraft mc, String key) {
        SmokeUi.click(mc, Text.get(key));
    }

    private void escape(Minecraft mc) {
        mc.gui.screen().keyPressed(new KeyEvent(256, 0, 0));
    }

    private void tick(Minecraft mc) {
        if (stage == 999) return;
        try {
            if (++tick > 2400) throw new IllegalStateException("Reset smoke timeout at stage " + stage);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.settings == null || app.library == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null || tick % 5 != 0)
                return;
            if (!configured) {
                configured = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return;
            }
            if (stage == 0 && mc.options.guiScale().get() != 2) {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return;
            }
            switch (stage) {
                case 0 -> {
                    data = app.config.directory().toAbsolutePath().normalize();
                    require(data.toString().contains("reset-run"), "Reset test must use its isolated run directory");
                    if (Boolean.getBoolean("darkmusic.verifyHudRestart")) {
                        var expected = new Settings();
                        expected.validate();
                        expected.roots.putAll(app.settings.roots);
                        // Reset keeps tool locations along with folder identities.
                        expected.ytDlpPath = app.settings.ytDlpPath;
                        expected.ffmpegPath = app.settings.ffmpegPath;
                        expected.ffprobePath = app.settings.ffprobePath;
                        expected.javascriptPath = app.settings.javascriptPath;
                        // Activating the default folder records it as the only music folder; custom folders are gone.
                        expected.musicFolders = List.of(app.config.defaultMusic().toAbsolutePath().normalize().toString());
                        require(ConfigStore.JSON.toJson(expected).equals(ConfigStore.JSON.toJson(app.settings)), "Defaults did not survive a client restart");
                        require(app.library.root().equals(app.config.defaultMusic().toAbsolutePath().normalize()), "Default folder was not persisted");
                        for (var key : app.shortcuts.bindings())
                            require(key.isDefault(), "Default key did not survive restart: " + key.getName());
                        require(app.playlists.all().size() == 1, "Preserved playlist was not persisted");
                        stage = 20;
                        return;
                    }
                    // The private collection is Opus; copies keep the real extension and play through the managed ffmpeg.
                    try (var files = Files.list(Path.of(System.getProperty("darkmusic.music")))) {
                        source = files.filter(AudioFormats::candidate).sorted().findFirst().orElseThrow();
                    }
                    Path managed = Path.of(System.getProperty("darkmusic.downloadTools"));
                    String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                    app.settings.ffmpegPath = managed.resolve("ffmpeg" + ext).toString();
                    app.settings.ffprobePath = managed.resolve("ffprobe" + ext).toString();
                    String audioExtension = source.getFileName().toString().substring(source.getFileName().toString().lastIndexOf('.'));
                    custom = data.resolve("test-custom-music");
                    prepared = CompletableFuture.runAsync(() -> {
                        try {
                            Files.createDirectories(custom);
                            Path copy = custom.resolve(source.getFileName());
                            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
                            Path defaultCopy = app.config.defaultMusic().resolve("downloaded song" + audioExtension);
                            Files.copy(source, defaultCopy, StandardCopyOption.REPLACE_EXISTING);
                            Path userVideo = custom.resolve("user video.mp4");
                            Files.copy(source, userVideo, StandardCopyOption.REPLACE_EXISTING);
                            for (Path file : List.of(source, copy, defaultCopy, userVideo))
                                hashes.put(file, hash(file));
                            Files.writeString(custom.resolve(".darkmusic-downloads.json"), "{\"youtube:abc\":\"song.mp3\"}");
                            Files.writeString(custom.resolve("downloaded.txt"), "youtube abc\n");
                            Path tools = Files.createDirectories(data.resolve("tools/managed/bundle-1234-abcd"));
                            Files.copy(managed.resolve("yt-dlp" + ext), tools.resolve("yt-dlp" + ext), StandardCopyOption.REPLACE_EXISTING);
                            Files.writeString(tools.resolve("manifest.json"), "{\"ytDlpRelease\":\"real\",\"ffmpegRelease\":\"real\",\"digests\":{}}");
                            Files.writeString(tools.getParent().resolve("current.json"), "{\"directory\":\"bundle-1234-abcd\"}");
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, app.io);
                    stage++;
                }
                case 1 -> {
                    if (!prepared.isDone()) return;
                    prepared.join();
                    app.settings.roots.clear();
                    app.folders.choose(custom, () -> {
                    });
                    stage++;
                }
                case 2 -> {
                    if (!custom.equals(app.library.root()) || app.library.snapshot().scanning()) return;
                    require(!app.library.snapshot().tracks().isEmpty(), "Copied real collection did not scan");
                    require(app.settings.roots.keySet().stream().allMatch(r -> Path.of(r).startsWith(data)), "Original collection entered reset scope");
                    for (var old : app.playlists.all()) app.playlists.delete(old.id());
                    playlist = app.playlists.create("Reset confirmation test");
                    retainedPlaylist = app.playlists.create("Keep this playlist");
                    app.playlists.addAll(retainedPlaylist, app.library.snapshot().tracks());
                    app.settings.hudEnabled = true;
                    app.settings.hudScale = 2;
                    app.settings.hudX = .7;
                    app.settings.speed = 2.3;
                    app.settings.volume = .1;
                    app.shortcuts.next.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.n"));
                    net.minecraft.client.KeyMapping.resetMapping();
                    jumpKey = mc.options.keyJump.saveString();
                    app.playback.play(app.library.snapshot().tracks(), app.library.snapshot().tracks().getFirst());
                    mc.gui.setScreen(new PlaylistScreen(app, null));
                    stage++;
                }
                case 3 -> {
                    // Built-in Favorite Music is listed and selected first and cannot be deleted.
                    var listsField = PlaylistScreen.class.getDeclaredField("lists");
                    listsField.setAccessible(true);
                    var lists = (RowList) listsField.get(mc.gui.screen());
                    var rowsField = RowList.class.getDeclaredField("rows");
                    rowsField.setAccessible(true);
                    var titles = ((List<RowList.Row>) rowsField.get(lists)).stream().map(RowList.Row::title).toList();
                    int index = titles.indexOf(titles.stream().filter(t -> t.startsWith(app.playlists.displayName(app.playlists.get(playlist)) + " (")).findFirst().orElse(""));
                    require(index >= 0, "Reset test playlist is not listed: " + titles);
                    var rowHeight = RowList.class.getDeclaredField("rowHeight");
                    rowHeight.setAccessible(true);
                    lists.onClick(new MouseButtonEvent(lists.getX() + 4, lists.getY() + index * (int) rowHeight.get(lists) + 4, new MouseButtonInfo(0, 0)), false);
                    mc.gui.screen().tick();
                    click(mc, "text.darkmusic.delete");
                    require(mc.gui.screen() instanceof ConfirmationScreen, "Playlist delete did not open shared confirmation");
                    require(app.playlists.get(playlist) != null, "Playlist deleted before confirmation");
                    escape(mc);
                    require(mc.gui.screen() instanceof PlaylistScreen && app.playlists.get(playlist) != null, "Escape changed playlist");
                    click(mc, "text.darkmusic.delete");
                    click(mc, "text.darkmusic.cancel");
                    require(app.playlists.get(playlist) != null, "Cancel deleted playlist");
                    click(mc, "text.darkmusic.delete");
                    click(mc, "text.darkmusic.delete");
                    stage++;
                }
                case 4 -> {
                    require(app.playlists.get(playlist) == null && app.playlists.get(retainedPlaylist) != null, "Confirmed playlist deletion failed");
                    settingsScreen = new SettingsScreen(app, null);
                    mc.gui.setScreen(settingsScreen);
                    click(mc, "reset.darkmusic.button");
                    require(mc.gui.screen() instanceof ConfirmationScreen, "Reset did not open shared confirmation");
                    require(app.settings.volume == .1, "Reset occurred before confirmation");
                    escape(mc);
                    require(mc.gui.screen() == settingsScreen && app.settings.volume == .1, "Escape reset settings");
                    click(mc, "reset.darkmusic.button");
                    click(mc, "text.darkmusic.cancel");
                    require(app.settings.hudScale == 2 && Files.exists(custom.resolve("downloaded.txt")), "Cancel changed settings or history");
                    click(mc, "reset.darkmusic.button");
                    stage++;
                }
                case 5 -> {
                    require(mc.getWindow().isFullscreen() && mc.options.guiScale().get() == 2 && mc.getWindow().getGuiScale() == 2, "Confirmation capture is not fullscreen GUI scale 2: " + mc.getWindow().isFullscreen() + "/" + mc.options.guiScale().get() + "/" + mc.getWindow().getGuiScale());
                    Path images = Path.of(System.getProperty("darkmusic.privateImages"));
                    Files.createDirectories(images.resolve("screenshots"));
                    net.minecraft.client.Screenshot.grab(images.toFile(), "reset-confirmation.png", mc.gameRenderer.mainRenderTarget(), 1, text -> {
                    });
                    click(mc, "reset.darkmusic.confirm");
                    escape(mc);
                    require(mc.gui.screen() instanceof ConfirmationScreen, "Busy confirmation closed before reset completed");
                    stage++;
                }
                case 6 -> {
                    if (mc.gui.screen() instanceof ConfirmationScreen) return;
                    require(mc.gui.screen() == settingsScreen, "Reset returned to wrong screen");
                    var expected = new Settings();
                    expected.validate();
                    expected.roots.putAll(app.settings.roots);
                    // Reset keeps tool locations along with folder identities.
                    expected.ytDlpPath = app.settings.ytDlpPath;
                    expected.ffmpegPath = app.settings.ffmpegPath;
                    expected.ffprobePath = app.settings.ffprobePath;
                    expected.javascriptPath = app.settings.javascriptPath;
                    // Activating the default folder records it as the only music folder; custom folders are gone.
                    expected.musicFolders = List.of(app.config.defaultMusic().toAbsolutePath().normalize().toString());
                    require(ConfigStore.JSON.toJson(expected).equals(ConfigStore.JSON.toJson(app.settings)), "Not all settings restored to defaults: " + differences(expected, app.settings));
                    require(app.library.root().equals(app.config.defaultMusic().toAbsolutePath().normalize()), "Default music directory not restored");
                    for (var key : app.shortcuts.bindings())
                        require(key.isDefault(), "Shortcut not reset: " + key.getName());
                    require(mc.options.keyJump.saveString().equals(jumpKey), "Reset changed a Minecraft shortcut");
                    require(mc.options.guiScale().get() == 2, "Reset changed Minecraft GUI scale");
                    require(app.playback.state().track() == null, "Old track/queue survived reset");
                    require(app.downloads.snapshot().items().isEmpty() && app.downloads.snapshot().preview() == null && app.downloads.lastAnalyzedUrl().isEmpty(), "Download state survived reset");
                    require(app.playlists.get(retainedPlaylist) != null, "Reset removed user playlist");
                    // Since v2.2.0 reset keeps installed download tools (USER_GUIDE: Reset to Defaults).
                    require(Files.exists(data.resolve("tools/managed/current.json")) && Files.exists(data.resolve("tools/managed/bundle-1234-abcd/yt-dlp.exe")), "Reset removed installed download tools");
                    require(!Files.exists(custom.resolve("downloaded.txt")) && !Files.exists(custom.resolve(".darkmusic-downloads.json")), "Download history survived reset");
                    saved = CompletableFuture.supplyAsync(app.config::load, app.io);
                    stage++;
                }
                case 7 -> {
                    if (!saved.isDone() || app.library.snapshot().scanning()) return;
                    require(ConfigStore.JSON.toJson(saved.join()).equals(ConfigStore.JSON.toJson(app.settings)), "Defaults were not saved");
                    require(app.library.snapshot().tracks().size() == 1, "Preserved default-folder music did not rescan immediately");
                    var field = SettingsScreen.class.getDeclaredField("previewScale");
                    field.setAccessible(true);
                    require((double) field.get(settingsScreen) <= 1, "Preview scale did not reset");
                    for (var entry : hashes.entrySet())
                        require(entry.getValue().equals(hash(entry.getKey())), "Reset changed media bytes: " + entry.getKey());
                    stage = 20;
                }
                case 20 -> {
                    org.slf4j.LoggerFactory.getLogger("darkmusic-reset-smoke").info("DARKMUSIC_SMOKE_PASS:{} reset confirmation/cancel/Escape, playlist confirmation, real-media integrity, installed tools kept, history clearing, defaults/native keys, default folder rescan, and persistence; restart={}",
                        Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip(), Boolean.getBoolean("darkmusic.verifyHudRestart"));
                    stage = 999;
                    mc.stop();
                }
                default -> throw new IllegalStateException("Unknown reset stage");
            }
        } catch (Throwable e) {
            stage = 999;
            org.slf4j.LoggerFactory.getLogger("darkmusic-reset-smoke").error("DARKMUSIC_SMOKE_FAIL stage=" + stage, e);
            mc.stop();
        }
    }
}
