package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.*;
import com.darkmusic.client.download.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;

import java.nio.file.*;
import java.util.List;

/**
 * Isolated native UI and real-collection/device checks; never contacts an external website.
 */
final class QueueSmokeClient {
    private int ticks, stage = -2, since;
    private Track a, b, x;

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(name);
    }

    private static void pressView(Screen screen, String current) {
        String label = Text.get("format.darkmusic.view", Text.get(current));
        var view = screen.children().stream().filter(c -> c instanceof AbstractWidget w && w.visible && w.getMessage().getString().equals(label))
            .map(c -> (AbstractWidget) c).findFirst().orElseThrow(() -> new IllegalStateException("Missing view button: " + label));
        screen.mouseClicked(new MouseButtonEvent(view.getX() + 3, view.getY() + 3, new MouseButtonInfo(0, 0)), false);
    }

    private static void bounds(Screen screen, int width, int height) {
        screen.init(width, height);
        for (var child : screen.children())
            if (child instanceof AbstractWidget widget && widget.visible)
                check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= width && widget.getBottom() <= height, "Bounds: " + widget.getMessage().getString());
    }

    private static void activate(Minecraft mc, String key) {
        var screen = mc.gui.screen();
        for (int i = 0; i < 50; i++) {
            if (screen.getFocused() instanceof AbstractWidget widget && widget.getMessage().getString().equals(Text.get(key)) && widget.visible) {
                check(widget.active, "Inactive: " + key);
                screen.keyPressed(new KeyEvent(32, 0, 0));
                return;
            }
            screen.keyPressed(new KeyEvent(258, 0, 0));
        }
        throw new AssertionError("Unreachable: " + key);
    }

    private void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            if (++ticks > 4000) throw new AssertionError("Queue smoke timeout at " + stage);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
            if (stage == -2) {
                Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                app.settings.ffmpegPath = tools.resolve("ffmpeg" + ext).toString();
                app.settings.ffprobePath = tools.resolve("ffprobe" + ext).toString();
                app.folders.choose(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = -1;
                return;
            }
            if (stage == -1) {
                if (!app.library.root().equals(Path.of(System.getProperty("darkmusic.music")).toAbsolutePath().normalize()))
                    return;
                if (app.library.snapshot().scanning()) return;
                stage = 0;
            }
            if (app.library.snapshot().scanning()) return;
            switch (stage) {
                case 0 -> {
                    check(mc.options.guiScale().get() == 2 && mc.getWindow().getGuiScale() == 2, "GUI scale 2");
                    var tracks = app.library.snapshot().tracks();
                    check(tracks.size() >= 3, "Private real music required");
                    a = tracks.get(0);
                    b = tracks.get(1);
                    x = tracks.get(2);
                    var diagnostic = DownloadDiagnostics.classify(new DownloadDiagnostics.Failure(DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.YT_DLP, 1,
                        "Unable to extract initial state https://user:password@host/id?token=secret"), DownloadDiagnostics.Stage.ANALYSIS, "https://www.bilibili.com/video/private-id", 1, 1, "2026.08.19");
                    var saved = DownloadService.class.getDeclaredField("analysisDiagnostic");
                    saved.setAccessible(true);
                    saved.set(app.downloads, diagnostic);
                    var download = new DownloadScreen(app, null);
                    mc.gui.setScreen(download);
                    for (int width : new int[]{320, 426, 960}) bounds(download, width, width == 960 ? 540 : 240);
                    var details = (AbstractWidget) field(download, "errorDetails");
                    check(details.active && details.visible, "Analysis details visible");
                    download.mouseClicked(new MouseButtonEvent(details.getX() + 2, details.getY() + 2, new MouseButtonInfo(0, 0)), false);
                    check(mc.gui.screen() instanceof DownloadDiagnosticScreen, "Analysis details open");
                    for (int width : new int[]{320, 426, 960}) bounds(mc.gui.screen(), width, width == 960 ? 540 : 240);
                    since = ticks;
                    stage = 6;
                }
                case 6 -> {
                    if (ticks - since < 10) return;
                    activate(mc, "download.diag.copy");
                    String report = mc.keyboardHandler.getClipboard();
                    check(report.contains("EXTRACTOR_FAILURE") && !report.contains("secret") && !report.contains("password") && !report.contains("private-id"), "Safe real clipboard");
                    mc.gui.screen().onClose();
                    check(mc.gui.screen() instanceof DownloadScreen, "Diagnostic Back");
                    var saved = DownloadService.class.getDeclaredField("analysisDiagnostic");
                    saved.setAccessible(true);
                    saved.set(app.downloads, null);
                    var actions = new SongActionsScreen(app, null, x);
                    mc.gui.setScreen(actions);
                    for (int width : new int[]{320, 426, 960}) bounds(actions, width, width == 960 ? 540 : 240);
                    since = ticks;
                    stage = 7;
                }
                case 7 -> {
                    if (ticks - since < 10) return;
                    var actions = (SongActionsScreen) mc.gui.screen();
                    activate(mc, "upnext.play_next");
                    activate(mc, "upnext.add");
                    check(app.playback.upNextSnapshot().manual().size() == 2, "Queue without playback");
                    activate(mc, "upnext.title");
                    var queue = (UpNextScreen) mc.gui.screen();
                    for (int width : new int[]{320, 426, 960}) bounds(queue, width, width == 960 ? 540 : 240);
                    var rows = (RowList) field(queue, "manual");
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 8, new MouseButtonInfo(0, 0)), false);
                    long id = app.playback.upNextSnapshot().manual().getFirst().id();
                    var press = new MouseButtonEvent(rows.getX() + 48, rows.getY() + 8, new MouseButtonInfo(0, 0));
                    var drop = new MouseButtonEvent(rows.getX() + 48, rows.getY() + 52, new MouseButtonInfo(0, 0));
                    queue.mouseClicked(press, false);
                    queue.mouseDragged(drop, 0, 44);
                    queue.mouseReleased(drop);
                    check(app.playback.upNextSnapshot().manual().get(1).id() == id, "Stable duplicate selection on reorder");
                    activate(mc, "upnext.remove");
                    check(app.playback.upNextSnapshot().manual().size() == 1, "Remove occurrence");
                    activate(mc, "upnext.clear");
                    check(app.playback.upNextSnapshot().manual().isEmpty(), "Clear queue");
                    // All Songs with a search: double-clicking a match queues it at the end.
                    pressView(queue, "upnext.view.collection");
                    check((boolean) field(queue, "allSongs"), "View did not switch to All Songs");
                    String term = b.title().strip();
                    var searchBox = (net.minecraft.client.gui.components.EditBox) field(queue, "search");
                    searchBox.setValue(term.substring(0, Math.min(6, term.length())));
                    queue.tick();
                    @SuppressWarnings("unchecked") var matches = (List<Track>) field(queue, "source");
                    check(!matches.isEmpty() && matches.stream().allMatch(track -> track.matches(term.substring(0, Math.min(6, term.length())).toLowerCase(java.util.Locale.ROOT))), "Search did not filter All Songs");
                    var found = (RowList) field(queue, "automatic");
                    var hit = new MouseButtonEvent(found.getX() + 48, found.getY() + 8, new MouseButtonInfo(0, 0));
                    found.mouseClicked(hit, false);
                    found.mouseClicked(hit, true);
                    var queuedNow = app.playback.upNextSnapshot().manual();
                    check(queuedNow.size() == 1 && queuedNow.getFirst().track().id().equals(matches.getFirst().id()), "Double-click did not queue the search result");
                    activate(mc, "upnext.clear");
                    searchBox.setValue("");
                    pressView(queue, "upnext.view.all");
                    rows = (RowList) field(queue, "manual");
                    // Remove takes every checked row, not only the highlighted one.
                    for (var track : List.of(a, b, a))
                        app.playback.insertQueued(track, app.playback.upNextSnapshot().manual().size());
                    queue.tick();
                    long kept = app.playback.upNextSnapshot().manual().get(1).id();
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 8, new MouseButtonInfo(0, 0)), false);
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 28 * 2 + 8, new MouseButtonInfo(0, 0)), false);
                    activate(mc, "upnext.remove");
                    var left = app.playback.upNextSnapshot().manual();
                    check(left.size() == 1 && left.getFirst().id() == kept, "Bulk remove left " + left.size() + " entries");
                    activate(mc, "upnext.clear");
                    app.playback.repeat(PlaybackController.Repeat.OFF);
                    app.playback.shuffle(false);
                    app.playback.play(List.of(a, b), a);
                    for (int width : new int[]{320, 426, 960}) {
                        bounds(queue, width, width == 960 ? 540 : 240);
                        queue.tick();
                        var collection = (RowList) field(queue, "automatic");
                        var queued = (RowList) field(queue, "manual");
                        var upcoming = app.playback.upNextSnapshot().automatic();
                        var begin = new MouseButtonEvent(collection.getX() + 48, collection.getY() + 8, new MouseButtonInfo(0, 0));
                        var end = new MouseButtonEvent(queued.getX() + 48, queued.getY() + 8, new MouseButtonInfo(0, 0));
                        queue.mouseClicked(begin, false);
                        queue.mouseDragged(end, end.x() - begin.x(), end.y() - begin.y());
                        queue.mouseReleased(end);
                        check(app.playback.upNextSnapshot().manual().size() == 1, "Collection drop adds queued copy");
                        check(upcoming.equals(app.playback.upNextSnapshot().automatic()), "Collection drop preserves source preview");
                        app.playback.clearQueued();
                        queue.tick();
                    }
                    app.playback.stop();
                    queue.onClose();
                    check(mc.gui.screen() == actions, "Queue Back");
                    for (var page : List.of(new SettingsScreen(app, actions), new AudioSettingsScreen(app, actions),
                        new SoundEnvironmentScreen(app, actions), new DownloadToolsScreen(app, actions),
                        new DownloadPathsScreen(app, actions), new ImportHistoryScreen(app, actions),
                        new LibraryManagerScreen(app, actions), new LyricsScreen(app, actions), new PlaylistScreen(app, actions),
                        new ColorsScreen(app, actions, true), new ColorPickerScreen(actions, 0x336699, color -> {
                        }),
                        new HudEditorScreen(app, actions))) {
                        mc.gui.setScreen(page);
                        for (int width : new int[]{320, 426, 960}) {
                            bounds(page, width, width == 960 ? 540 : 240);
                            var back = page.children().stream().filter(c -> c instanceof AbstractWidget w && w.visible && w.getMessage().getString().equals(Text.get("phase2.back")))
                                .map(c -> (AbstractWidget) c).findFirst().orElseThrow();
                            for (var child : page.children())
                                if (child instanceof AbstractWidget other && other != back && other.visible)
                                    check(other.getX() >= back.getRight() || other.getRight() <= back.getX() || other.getY() >= back.getBottom() || other.getBottom() <= back.getY(), "Back overlaps " + page.getClass().getSimpleName());
                        }
                        activate(mc, "phase2.back");
                        check(mc.gui.screen() == actions, "Visible Back returns from " + page.getClass().getSimpleName());
                    }
                    var player = new MusicScreen(app, null);
                    mc.gui.setScreen(player);
                    for (int width : new int[]{320, 426, 480, 640, 960}) {
                        bounds(player, width, width >= 640 ? 540 : width >= 480 ? 360 : 240);
                        player.tick();
                        var actionsButton = (AbstractWidget) field(player, "queueActions");
                        check(actionsButton.active, "Song actions available");
                        if (!actionsButton.visible) continue;
                        for (var child : player.children())
                            if (child instanceof AbstractWidget other && other != actionsButton && other.visible)
                                check(other.getX() >= actionsButton.getRight() || other.getRight() <= actionsButton.getX() || other.getY() >= actionsButton.getBottom() || other.getBottom() <= actionsButton.getY(), "Song actions overlap: " + other.getMessage().getString());
                    }
                    app.settings.audio.neutral = true;
                    app.settings.audio.gapless = true;
                    app.settings.audio.crossfade = .15;
                    app.playback.configure(app.settings.audio, app.settings.environment);
                    app.playback.repeat(PlaybackController.Repeat.OFF);
                    app.playback.shuffle(false);
                    app.playback.play(List.of(a, b), a);
                    app.playback.addToQueue(x);
                    mc.gui.setScreen(new UpNextScreen(app, player));
                    since = ticks;
                    stage = 1;
                }
                case 1 -> {
                    check(app.playback.state().status() != AudioBackend.Status.ERROR, app.playback.state().error());
                    if (app.playback.state().status() != AudioBackend.Status.PLAYING || app.playback.state().position() < .15 || ticks - since < 20)
                        return;
                    var queue = (UpNextScreen) mc.gui.screen();
                    var collection = (RowList) field(queue, "automatic");
                    var manual = (RowList) field(queue, "manual");
                    var begin = new MouseButtonEvent(collection.getX() + 48, collection.getY() + 8, new MouseButtonInfo(0, 0));
                    var end = new MouseButtonEvent(manual.getX() + 48, manual.getY() + 50, new MouseButtonInfo(0, 0));
                    queue.mouseClicked(begin, false);
                    queue.mouseDragged(end, end.x() - begin.x(), end.y() - begin.y());
                    since = ticks;
                    stage = 8;
                }
                case 8 -> {
                    if (ticks - since < 10) return;
                    var queue = (UpNextScreen) mc.gui.screen();
                    var manual = (RowList) field(queue, "manual");
                    queue.mouseReleased(new MouseButtonEvent(manual.getX() + 48, manual.getY() + 50, new MouseButtonInfo(0, 0)));
                    var entries = app.playback.upNextSnapshot().manual();
                    check(entries.size() == 2, "Rendered collection drag adds a copy");
                    app.playback.removeQueued(entries.getLast().id());
                    app.playback.pause();
                    stage = 2;
                }
                case 2 -> {
                    if (app.playback.state().status() != AudioBackend.Status.PAUSED) return;
                    check(app.playback.upNextSnapshot().manual().size() == 1, "Pause retains queue");
                    app.playback.seek(Math.max(0, a.duration() - .35));
                    app.playback.resume();
                    stage = 3;
                }
                case 3 -> {
                    if (!x.equals(app.playback.state().track())) return;
                    check(app.playback.upNextSnapshot().manual().isEmpty(), "Real manual promotion consumes once");
                    check(app.playback.source().mode().equals("All"), "Original source retained");
                    mc.gui.setScreen(new MusicScreen(app, null));
                    since = ticks;
                    stage = 4;
                }
                case 4 -> {
                    if (ticks - since < 20 || app.playback.state().position() < .15) return;
                    app.playback.seek(Math.max(0, x.duration() - .35));
                    stage = 5;
                }
                case 5 -> {
                    if (!b.equals(app.playback.state().track())) return;
                    app.playback.stop();
                    app.playback.addToQueue(x);
                    var player = new MusicScreen(app, null);
                    mc.gui.setScreen(player);
                    player.tick();
                    SmokeUi.click(mc, Text.get("text.darkmusic.play"));
                    check(app.playback.upNextSnapshot().manual().size() == 1, "Restarting the current song after Stop retains Up Next");
                    app.playback.stop();
                    System.out.println("DARKMUSIC_QUEUE_UI_PASS: compact/wide layouts, keyboard queue actions, duplicate reorder/remove, diagnostic clipboard/Back, real-device pause/seek/resume, manual promotion, original-source continuation");
                    System.out.println("DARKMUSIC_SMOKE_PASS:" + Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                    stage = 99;
                    mc.stop();
                }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            stage = 99;
            mc.stop();
        }
    }
}
