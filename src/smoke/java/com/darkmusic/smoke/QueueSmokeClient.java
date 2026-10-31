package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.download.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.DownloadDiagnostics;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;

import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.darkmusic.smoke.SmokeUi.field;
import static com.darkmusic.smoke.SmokeUi.set;

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
            if (app == null || !app.services().playlistsReady() || !mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
            if (stage == -2) {
                Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                app.services().settings().ffmpegPath = tools.resolve("ffmpeg" + ext).toString();
                app.services().settings().ffprobePath = tools.resolve("ffprobe" + ext).toString();
                app.services().apply();
                app.sources().chooseMusicFolder(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = -1;
                return;
            }
            if (stage == -1) {
                if (!app.services().library().root().equals(Path.of(System.getProperty("darkmusic.music")).toAbsolutePath().normalize()))
                    return;
                if (app.services().library().snapshot().scanning()) return;
                stage = 0;
            }
            if (app.services().library().snapshot().scanning()) return;
            switch (stage) {
                case 0 -> {
                    check(mc.options.guiScale().get() == 2 && mc.getWindow().getGuiScale() == 2, "GUI scale 2");
                    var tracks = app.services().library().snapshot().tracks();
                    check(tracks.size() >= 3, "Private real music required");
                    a = tracks.get(0);
                    b = tracks.get(1);
                    x = tracks.get(2);
                    var diagnostic = DownloadDiagnostics.classify(new DownloadDiagnostics.Failure(DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.YT_DLP, 1,
                        "Unable to extract initial state https://user:password@host/id?token=secret"), DownloadDiagnostics.Stage.ANALYSIS, "https://www.bilibili.com/video/private-id", 1, 1, "2026.08.19");
                    set(app.services().downloads(), "analysisDiagnostic", diagnostic);
                    var download = new DownloadScreen(app.sources(), null);
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
                    set(app.services().downloads(), "analysisDiagnostic", null);
                    var actions = new SongActionsScreen(app.sources(), null, x);
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
                    check(app.services().playback().upNextSnapshot().manual().size() == 2, "Queue without playback");
                    activate(mc, "upnext.title");
                    var queue = (UpNextScreen) mc.gui.screen();
                    for (int width : new int[]{320, 426, 960}) bounds(queue, width, width == 960 ? 540 : 240);
                    var rows = (RowList) field(queue, "manual");
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 8, new MouseButtonInfo(0, 0)), false);
                    long id = app.services().playback().upNextSnapshot().manual().getFirst().id();
                    var press = new MouseButtonEvent(rows.getX() + 48, rows.getY() + 8, new MouseButtonInfo(0, 0));
                    var drop = new MouseButtonEvent(rows.getX() + 48, rows.getY() + 52, new MouseButtonInfo(0, 0));
                    queue.mouseClicked(press, false);
                    queue.mouseDragged(drop, 0, 44);
                    queue.mouseReleased(drop);
                    check(app.services().playback().upNextSnapshot().manual().get(1).id() == id, "Stable duplicate selection on reorder");
                    activate(mc, "upnext.remove");
                    check(app.services().playback().upNextSnapshot().manual().size() == 1, "Remove occurrence");
                    activate(mc, "upnext.clear");
                    check(app.services().playback().upNextSnapshot().manual().isEmpty(), "Clear queue");
                    // All Songs with a search: double-clicking a match queues it at the end.
                    pressView(queue, "upnext.view.collection");
                    check((boolean) field(queue, "allSongs"), "View did not switch to All Songs");
                    String term = b.title().strip();
                    // The queue's search box is its only text input.
                    var searchBox = queue.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
                    searchBox.setValue(term.substring(0, Math.min(6, term.length())));
                    queue.tick();
                    @SuppressWarnings("unchecked") var matches = (List<Track>) field(queue, "source");
                    check(!matches.isEmpty() && matches.stream().allMatch(track -> track.matches(term.substring(0, Math.min(6, term.length())).toLowerCase(Locale.ROOT))), "Search did not filter All Songs");
                    var found = (RowList) field(queue, "automatic");
                    var hit = new MouseButtonEvent(found.getX() + 48, found.getY() + 8, new MouseButtonInfo(0, 0));
                    found.mouseClicked(hit, false);
                    found.mouseClicked(hit, true);
                    var queuedNow = app.services().playback().upNextSnapshot().manual();
                    check(queuedNow.size() == 1 && queuedNow.getFirst().track().id().equals(matches.getFirst().id()), "Double-click did not queue the search result");
                    activate(mc, "upnext.clear");
                    searchBox.setValue("");
                    pressView(queue, "upnext.view.all");
                    rows = (RowList) field(queue, "manual");
                    // Remove takes every checked row, not only the highlighted one.
                    for (var track : List.of(a, b, a))
                        app.services().playback().insertQueued(track, app.services().playback().upNextSnapshot().manual().size());
                    queue.tick();
                    long kept = app.services().playback().upNextSnapshot().manual().get(1).id();
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 8, new MouseButtonInfo(0, 0)), false);
                    queue.mouseClicked(new MouseButtonEvent(rows.getX() + 8, rows.getY() + 28 * 2 + 8, new MouseButtonInfo(0, 0)), false);
                    activate(mc, "upnext.remove");
                    var left = app.services().playback().upNextSnapshot().manual();
                    check(left.size() == 1 && left.getFirst().id() == kept, "Bulk remove left " + left.size() + " entries");
                    activate(mc, "upnext.clear");
                    app.services().playback().repeat(Repeat.OFF);
                    app.services().playback().shuffle(false);
                    app.services().playback().play(List.of(a, b), a);
                    for (int width : new int[]{320, 426, 960}) {
                        bounds(queue, width, width == 960 ? 540 : 240);
                        queue.tick();
                        var collection = (RowList) field(queue, "automatic");
                        var queued = (RowList) field(queue, "manual");
                        var upcoming = app.services().playback().upNextSnapshot().automatic();
                        var begin = new MouseButtonEvent(collection.getX() + 48, collection.getY() + 8, new MouseButtonInfo(0, 0));
                        var end = new MouseButtonEvent(queued.getX() + 48, queued.getY() + 8, new MouseButtonInfo(0, 0));
                        queue.mouseClicked(begin, false);
                        queue.mouseDragged(end, end.x() - begin.x(), end.y() - begin.y());
                        queue.mouseReleased(end);
                        check(app.services().playback().upNextSnapshot().manual().size() == 1, "Collection drop adds queued copy");
                        check(upcoming.equals(app.services().playback().upNextSnapshot().automatic()), "Collection drop preserves source preview");
                        app.services().playback().clearQueued();
                        queue.tick();
                    }
                    app.services().playback().stop();
                    queue.onClose();
                    check(mc.gui.screen() == actions, "Queue Back");
                    for (var page : List.of(new SettingsScreen(app.sources(), actions), new AudioSettingsScreen(app.sources(), actions),
                        new SoundEnvironmentScreen(app.sources(), actions), new DownloadToolsScreen(app.sources(), actions),
                        new DownloadPathsScreen(app.sources(), actions), new ImportHistoryScreen(app.sources(), actions),
                        new LibraryManagerScreen(app.sources(), actions), new LyricsScreen(app.sources(), actions), new PlaylistScreen(app.sources(), actions),
                        new ColorsScreen(app.sources(), actions, true), new ColorPickerScreen(actions, 0x336699, 0x336699, color -> {
                        }),
                        new HudEditorScreen(app.sources(), actions))) {
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
                    var player = new MusicScreen(app.sources(), null);
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
                    app.services().settings().audio.neutral = true;
                    app.services().settings().audio.gapless = true;
                    app.services().settings().audio.crossfade = .15;
                    app.services().playback().configure(app.services().settings().audio, app.services().settings().environment);
                    app.services().playback().repeat(Repeat.OFF);
                    app.services().playback().shuffle(false);
                    app.services().playback().play(List.of(a, b), a);
                    app.services().playback().addToQueue(x);
                    mc.gui.setScreen(new UpNextScreen(app.sources(), player));
                    since = ticks;
                    stage = 1;
                }
                case 1 -> {
                    check(app.services().playback().state().status() != AudioBackend.Status.ERROR, app.services().playback().state().error());
                    if (app.services().playback().state().status() != AudioBackend.Status.PLAYING || app.services().playback().state().position() < .15 || ticks - since < 20)
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
                    var entries = app.services().playback().upNextSnapshot().manual();
                    check(entries.size() == 2, "Rendered collection drag adds a copy");
                    app.services().playback().removeQueued(entries.getLast().id());
                    app.services().playback().pause();
                    stage = 2;
                }
                case 2 -> {
                    if (app.services().playback().state().status() != AudioBackend.Status.PAUSED) return;
                    check(app.services().playback().upNextSnapshot().manual().size() == 1, "Pause retains queue");
                    app.services().playback().seek(Math.max(0, a.duration() - .35));
                    app.services().playback().resume();
                    stage = 3;
                }
                case 3 -> {
                    if (!x.equals(app.services().playback().state().track())) return;
                    check(app.services().playback().upNextSnapshot().manual().isEmpty(), "Real manual promotion consumes once");
                    check(app.services().playback().source().mode().equals("All"), "Original source retained");
                    mc.gui.setScreen(new MusicScreen(app.sources(), null));
                    since = ticks;
                    stage = 4;
                }
                case 4 -> {
                    if (ticks - since < 20 || app.services().playback().state().position() < .15) return;
                    app.services().playback().seek(Math.max(0, x.duration() - .35));
                    stage = 5;
                }
                case 5 -> {
                    if (!b.equals(app.services().playback().state().track())) return;
                    app.services().playback().stop();
                    app.services().playback().addToQueue(x);
                    var player = new MusicScreen(app.sources(), null);
                    mc.gui.setScreen(player);
                    player.tick();
                    SmokeUi.click(mc, Text.get("text.darkmusic.play"));
                    check(app.services().playback().upNextSnapshot().manual().size() == 1, "Restarting the current song after Stop retains Up Next");
                    app.services().playback().stop();
                    app.services().playback().clearQueued();
                    app.services().listeningHistory().replace(Map.of());
                    mc.gui.setScreen(new MusicScreen(app.sources(), null));
                    SmokeUi.click(mc, Text.get("view.darkmusic.history"));
                    var historyPlayer = (MusicScreen) mc.gui.screen();
                    var categories = (RowList) field(historyPlayer, "navigation");
                    categories.mouseClicked(new MouseButtonEvent(categories.getX() + 48, categories.getY() + 3 * 24 + 8, new MouseButtonInfo(0, 0)), false);
                    check(field(historyPlayer, "group").equals("never"), "Never Played selected");
                    var historySongs = (RowList) field(historyPlayer, "songs");
                    var firstSong = new MouseButtonEvent(historySongs.getX() + 48, historySongs.getY() + 8, new MouseButtonInfo(0, 0));
                    historySongs.mouseClicked(firstSong, false);
                    historySongs.mouseClicked(firstSong, true);
                    since = ticks;
                    stage = 9;
                }
                case 9 -> {
                    check(app.services().playback().state().status() != AudioBackend.Status.ERROR, app.services().playback().state().error());
                    var current = app.services().playback().state().track();
                    if (current == null || app.services().playback().state().position() < .15) return;
                    check(app.services().playback().playingSource().mode().equals("History") && app.services().playback().playingSource().group().equals("never"), "Playing from Never Played");
                    if (app.services().playback().state().position() < 31) return;
                    check(app.sources().collection("History", "never").contains(current), "Current song stays in Never Played after 30 seconds");
                    app.services().playback().pause();
                    app.services().playback().addToQueue(x);
                    var source = app.services().playback().playingSource();
                    var queued = app.services().playback().upNextSnapshot().manual();
                    SmokeUi.click(mc, Text.get("view.darkmusic.all"));
                    var player = (MusicScreen) mc.gui.screen();
                    app.services().warnings().clear();
                    SmokeUi.click(mc, Text.get("player.darkmusic.jump"));
                    check(field(player, "mode").equals("History") && field(player, "group").equals("never"), "Jump returns to Never Played");
                    check(((Track) field(player, "selected")).id().equals(current.id()), "Jump selects the playing song in Never Played");
                    check(app.services().warnings().current().isEmpty(), "Jump must not warn for a song still in the library: " + app.services().warnings().current());
                    check(app.services().playback().playingSource().equals(source) && app.services().playback().upNextSnapshot().manual().equals(queued), "Jump preserves playback source and Up Next");
                    app.services().playback().stop();
                    check(!app.sources().collection("History", "never").contains(current), "Stopping removes the listened song from Never Played");
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
