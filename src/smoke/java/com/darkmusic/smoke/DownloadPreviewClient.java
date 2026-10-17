package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.download.*;
import com.darkmusic.client.gui.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real-device audition and download-screen safety checks; local private music never leaves loopback.
 */
final class DownloadPreviewClient {
    private int ticks, step = -1, frame;
    private boolean selecting, fullscreen;
    private CompletableFuture<String> serving;
    private HttpServer server;
    private MusicScreen player;
    private String address;

    private void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private Object field(Object value, String name) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    private void set(Object value, String name, Object data) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(value, data);
    }

    private AbstractWidget button(Minecraft mc, String label) {
        return mc.gui.screen().children().stream().filter(x -> x instanceof AbstractWidget b && b.getMessage().getString().equals(label)).map(x -> (AbstractWidget) x).findFirst().orElseThrow(() -> new IllegalStateException("Missing button: " + label));
    }

    private void click(Minecraft mc, String label) {
        var b = button(mc, label);
        require(b.active, "Inactive: " + label);
        mc.gui.screen().mouseClicked(mouse(b.getX() + 3, b.getY() + 3), false);
    }

    private MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    }

    private void escape(Minecraft mc) {
        mc.gui.screen().keyPressed(new KeyEvent(256, 0, 0));
    }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void finish(Minecraft mc, Throwable failure) {
        if (server != null) server.stop(0);
        if (failure == null)
            org.slf4j.LoggerFactory.getLogger("darkmusic-preview-smoke").info("DARKMUSIC_SMOKE_PASS:{} URL re-entry/explicit re-analysis, preview search/range/check modes, temporary real-device audition/stop/exit/music restore, single visualizer settings entry", readId());
        else org.slf4j.LoggerFactory.getLogger("darkmusic-preview-smoke").error("DARKMUSIC_SMOKE_FAIL", failure);
        step = 999;
        mc.stop();
    }

    private String readId() {
        try {
            return Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String serve(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(task -> Thread.ofVirtual().start(task));
        server.createContext("/song.mp3", exchange -> {
            int first = 0, last = bytes.length - 1;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.matches("bytes=\\d+-\\d*")) {
                var parts = range.substring(6).split("-", -1);
                first = Integer.parseInt(parts[0]);
                if (!parts[1].isEmpty()) last = Math.min(last, Integer.parseInt(parts[1]));
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (first > last) {
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            int size = last - first + 1;
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(size));
            if (range != null)
                exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + bytes.length);
            if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(range == null ? 200 : 206, -1);
            else {
                exchange.sendResponseHeaders(range == null ? 200 : 206, size);
                try {
                    exchange.getResponseBody().write(bytes, first, size);
                } catch (java.io.IOException ignored) {
                }
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/song.mp3";
    }

    private void tick(Minecraft mc) {
        if (step == 999) return;
        try {
            if (++ticks > 2400) throw new IllegalStateException("Preview checks timed out");
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.library == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (!fullscreen) {
                fullscreen = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                mc.options.guiScale().set(3);
                mc.resizeGui();
                return;
            }
            if (!selecting) {
                selecting = true;
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                return;
            }
            if (app.library.snapshot().scanning() || app.library.snapshot().tracks().isEmpty()) return;
            if (step == -1) {
                if (serving == null) {
                    Path file = app.library.snapshot().tracks().getFirst().file();
                    serving = CompletableFuture.supplyAsync(() -> {
                        try {
                            return serve(file);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    });
                    return;
                }
                if (!serving.isDone()) return;
                address = serving.join();
                Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                app.downloads.configure(new DownloadModels.Tools(tools.resolve("yt-dlp.exe").toString(), tools.resolve("ffmpeg.exe").toString(), tools.resolve("ffprobe.exe").toString(), ""));
                app.settings.ytDlpPath = tools.resolve("yt-dlp.exe").toString();
                app.settings.ffmpegPath = tools.resolve("ffmpeg.exe").toString();
                app.settings.ffprobePath = tools.resolve("ffprobe.exe").toString();
                var track = app.library.snapshot().tracks().getFirst();
                app.playback.play(List.of(track), track);
                app.open();
                player = (MusicScreen) mc.gui.screen();
                click(mc, "Downloads");
                ((EditBox) field(mc.gui.screen(), "url")).setValue(address);
                click(mc, "Analyze");
                step = 0;
                return;
            }
            if (++frame % 20 != 0) return;
            switch (step) {
                case 0 -> {
                    if (app.downloads.snapshot().analyzing()) return;
                    require(app.downloads.snapshot().preview() != null, "Local analysis failed: " + app.downloads.snapshot().message());
                    require(field(mc.gui.screen(), "mode").toString().equals("VIDEO"), "Single video did not default to This Video Only");
                    var search = (EditBox) field(mc.gui.screen(), "search");
                    search.setValue("no matching title");
                    require(((List<?>) field(mc.gui.screen(), "visible")).isEmpty(), "Preview search did not filter");
                    search.setValue("");
                    var rows = (RowList) field(mc.gui.screen(), "rows");
                    rows.onClick(mouse(rows.getX() + 40, rows.getY() + 5), false);
                    mc.gui.screen().tick();
                    click(mc, "Play Audio Preview");
                    step++;
                }
                case 1 -> {
                    require(app.audition != null, "No audition player");
                    var snapshot = app.audition.snapshot();
                    require(snapshot.status() != AuditionPlayer.Status.ERROR, "Audition failed: " + snapshot.error());
                    var audio = (AudioBackend) field(app.audition, "audio");
                    if (audio == null || audio.state().status() != AudioBackend.Status.PLAYING || audio.state().position() < .2)
                        return;
                    require(app.playback.state().status() == AudioBackend.Status.PAUSED, "Main music was not paused during audition");
                    click(mc, "Stop Audio Preview");
                    step++;
                }
                case 2 -> {
                    if (app.playback.state().status() != AudioBackend.Status.PLAYING) return;
                    click(mc, "Play Audio Preview");
                    escape(mc);
                    require(mc.gui.screen() == player, "Escape did not return to the player");
                    require(app.downloads.snapshot().preview() == null, "Preview survived leaving Downloads");
                    require(app.audition == null, "Audition survived leaving Downloads");
                    click(mc, "Downloads");
                    require(((EditBox) field(mc.gui.screen(), "url")).getValue().equals(address), "Last analyzed URL was not restored");
                    require(!button(mc, "Download One Video").active, "Re-entered screen did not require re-analysis");
                    step++;
                }
                case 3 -> {
                    String link = "https://example.com/collection";
                    ((EditBox) field(mc.gui.screen(), "url")).setValue(link);
                    var entries = new ArrayList<DownloadModels.Entry>();
                    for (int i = 0; i < 5; i++)
                        entries.add(new DownloadModels.Entry(i + 1, app.library.snapshot().tracks().get(i % app.library.snapshot().tracks().size()).title(), "https://example.com/" + i, "Generic", Integer.toString(i), true));
                    set(app.downloads, "preview", new DownloadModels.Preview(link, "Real collection titles", "Generic", true, false, 1, 5, false, entries));
                    set(app.downloads, "published", null);
                    mc.gui.screen().tick();
                    require(field(mc.gui.screen(), "mode").toString().equals("SELECTED"), "A collection-only URL defaulted to a video mode");
                    var rows = (RowList) field(mc.gui.screen(), "rows");
                    rows.onClick(mouse(rows.getX() + 4, rows.getY() + 4), false);
                    rows.onClick(mouse(rows.getX() + 4, rows.getY() + 36), false);
                    require(((SongSelection) field(mc.gui.screen(), "selection")).size() == 2, "Checkbox selection failed");
                    var mode = (AbstractWidget) field(mc.gui.screen(), "modeButton");
                    mc.gui.screen().mouseClicked(mouse(mode.getX() + 3, mode.getY() + 3), false);
                    require(field(mc.gui.screen(), "mode").toString().equals("RANGE"), "First N was not merged into Range");
                    require(((SongSelection) field(mc.gui.screen(), "selection")).isEmpty() && field(rows, "selection") == null, "Range mode retained active checks");
                    ((EditBox) field(mc.gui.screen(), "first")).setValue("3");
                    ((EditBox) field(mc.gui.screen(), "last")).setValue("5");
                    ((EditBox) field(mc.gui.screen(), "search")).setValue(entries.get(2).title());
                    require(((List<?>) field(mc.gui.screen(), "visible")).size() == 1, "Collection search failed");
                    require(((EditBox) field(mc.gui.screen(), "first")).getValue().equals("3"), "Filtering altered range indices");
                    escape(mc);
                    click(mc, "Settings");
                    require(mc.gui.screen().children().stream().noneMatch(x -> x instanceof AbstractWidget b && b.getMessage().getString().equals("Music Visualizer")), "Visualizer remains duplicated in general settings");
                    require(mc.gui.screen() instanceof SettingsScreen, "Unified settings are missing");
                    step++;
                }
                default -> {
                    server.stop(0);
                    step = 999;
                    new HudSmokeClient().start();
                }
            }
        } catch (Throwable e) {
            finish(mc, e);
        }
    }
}
