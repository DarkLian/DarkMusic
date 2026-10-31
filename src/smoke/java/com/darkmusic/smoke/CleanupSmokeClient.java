package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.cleanup.*;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.download.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.*;
import java.util.*;

/**
 * Real, read-only collection checks in a disposable client configuration. No deletion or website access.
 */
final class CleanupSmokeClient {
    private int stage = -2, ticks, since;
    private Track a, b;
    private SmartCleanupScreen results;
    private DuplicateResult pair;
    private List<?> queue;
    private ComparisonAudition audition;
    private double previewPosition;

    private void capture(Minecraft mc, String name) throws Exception {
        SmokeUi.captureReady(mc);
        Path images = Path.of(System.getProperty("darkmusic.privateImages"));
        Files.createDirectories(images.resolve("screenshots"));
        Screenshot.grab(images.toFile(), "cleanup-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, s -> {
        });
    }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void bounds(Screen screen, int w, int h) {
        screen.init(w, h);
        var widgets = screen.children().stream().filter(c -> c instanceof AbstractWidget).map(c -> (AbstractWidget) c).filter(c -> c.visible).toList();
        for (var widget : widgets) {
            check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= w && widget.getBottom() <= h, "Outside bounds: " + widget.getMessage().getString());
            for (var other : widgets)
                if (other != widget)
                    check(widget.getX() >= other.getRight() || widget.getRight() <= other.getX() || widget.getY() >= other.getBottom() || widget.getBottom() <= other.getY(), "Overlap: " + widget.getMessage().getString() + " / " + other.getMessage().getString());
        }
    }

    /**
     * Button labels never animate; at the documented 1920x1080 / GUI scale 2 size, none should need shortening.
     */
    private static List<String> truncatedLabels(Screen screen) {
        screen.init(960, 540);
        return screen.children().stream().filter(c -> c instanceof UiButton b && b.visible && b.labelTruncated())
            .map(c -> screen.getClass().getSimpleName() + " | " + ((UiButton) c).getMessage().getString() + " | " + ((UiButton) c).getWidth()).toList();
    }

    private void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            if (++ticks > 4000) throw new AssertionError("Cleanup timeout: " + stage);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || !app.services().playlistsReady() || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (stage == -2) {
                var tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                app.services().settings().ffmpegPath = tools.resolve("ffmpeg" + suffix).toString();
                app.services().settings().ffprobePath = tools.resolve("ffprobe" + suffix).toString();
                app.services().apply();
                app.sources().chooseMusicFolder(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = -1;
                return;
            }
            if (stage == -1) {
                if (app.services().library().snapshot().scanning() || app.services().library().snapshot().tracks().size() < 2)
                    return;
                SmokeUi.displayReady(mc);
                a = app.services().library().snapshot().tracks().get(0);
                b = app.services().library().snapshot().tracks().get(1);
                pair = new DuplicateResult("layout-only", a, b, "layout-a", "layout-b", Set.of(DuplicateResult.Evidence.TITLE_DURATION), DuplicateResult.Strength.POSSIBLE);
                results = new SmartCleanupScreen(app.sources(), null);
                mc.gui.setScreen(results);
                SmokeUi.press(mc, (AbstractWidget) SmokeUi.field(results, SmartCleanupScreen.class, "audio"));
                SmokeUi.click(mc, Text.get("cleanup.start"));
                since = ticks;
                stage = 0;
                return;
            }
            if (stage == 0) {
                if (app.sources().cleanup().snapshot().running() || ticks - since < 20 || !app.sources().cleanupReviews().ready())
                    return;
                check(app.sources().cleanup().snapshot().stage() == DuplicateScanner.Stage.COMPLETE, "Real collection scan completes");
                System.out.println("CLEANUP_REAL_SCAN: tracks=" + app.sources().cleanup().snapshot().total() + ", pairs=" + app.sources().cleanup().snapshot().pairs().size() + ", unavailable=" + app.sources().cleanup().snapshot().unavailable() + ", audioFailed=" + app.sources().cleanup().snapshot().audioFailed());
                var comparison = new DuplicateComparisonScreen(app.sources(), results, pair);
                var ignored = new IgnoredMatchesScreen(app.sources(), results);
                var entry = new DownloadModels.Entry(1, a.title(), "https://youtu.be/abcDEFG_123", "Youtube", "abcDEFG_123", true);
                var existing = new LibrarySourceIndex.Existing(a, "mp3", Set.of(DownloadFormat.MP3), "layout");
                var choice = new DownloadService.DuplicateChoice(1, List.of(entry), app.services().library().root(), null, DownloadFormat.MP3, Map.of(entry.key(), List.of(existing)));
                for (var page : List.of(results, comparison, ignored, new DownloadDuplicateScreen(app.sources(), results, choice), new ExistingDownloadsScreen(app.sources(), results, entry), new DownloadScreen(app.sources(), results))) {
                    mc.gui.setScreen(page);
                    for (int width : new int[]{320, 426, 960}) bounds(page, width, width == 960 ? 540 : 240);
                }
                var truncated = new ArrayList<String>();
                for (var page : List.<Screen>of(new MusicScreen(app.sources(), results), new SettingsScreen(app.sources(), results), new DownloadScreen(app.sources(), results), new DownloadToolsScreen(app.sources(), results),
                    new DownloadPathsScreen(app.sources(), results), new UpNextScreen(app.sources(), results), new PlaylistScreen(app.sources(), results), new LibraryManagerScreen(app.sources(), results), results, comparison, ignored,
                    new ImportHistoryScreen(app.sources(), results), new HudEditorScreen(app.sources(), results), new PipSourcesScreen(app.sources(), results), new PipEditorScreen(app.sources(), results, false), new PipEditorScreen(app.sources(), results, true),
                    new ColorsScreen(app.sources(), results, true), new ColorsScreen(app.sources(), results, false), new LyricsScreen(app.sources(), results, a), new LyricsSearchScreen(app.sources(), results, a),
                    new MetadataScreen(app.sources(), results, a, t -> {
                    }), new AudioDeletionScreen(app.sources(), results, List.of(a), results), new DownloadDuplicateScreen(app.sources(), results, choice))) {
                    mc.gui.setScreen(page);
                    truncated.addAll(truncatedLabels(page));
                }
                mc.gui.setScreen(results);
                check(truncated.isEmpty(), "Shortened button labels at 960x540: " + truncated);
                app.services().playback().play(List.of(a, b), a);
                app.services().playback().addToQueue(b);
                queue = app.services().playback().upNextSnapshot().manual();
                mc.gui.setScreen(comparison);
                since = ticks;
                stage = 1;
            } else if (stage == 1) {
                check(app.services().playback().state().status() != AudioBackend.Status.ERROR, app.services().playback().state().error());
                if (app.services().playback().state().status() != AudioBackend.Status.PLAYING || app.services().playback().state().position() < .25 || ticks - since < 20)
                    return;
                capture(mc, "comparison");
                SmokeUi.click(mc, Text.get("cleanup.play_a"));
                audition = (ComparisonAudition) SmokeUi.field(mc.gui.screen(), DuplicateComparisonScreen.class, "audition");
                since = ticks;
                stage = 2;
            } else if (stage == 2) {
                check(audition.error().isEmpty(), "A/B preview error");
                if (ticks - since < 100) return;
                check(app.services().playback().state().status() == AudioBackend.Status.PAUSED, "Main music paused during preview");
                check(queue.equals(app.services().playback().upNextSnapshot().manual()), "Preview preserves Up Next");
                var output = (AudioBackend) SmokeUi.field(audition, ComparisonAudition.class, "preview");
                previewPosition = output.state().position();
                check(previewPosition > 3, "Preview advanced before pause");
                SmokeUi.click(mc, Text.get("cleanup.pause_a"));
                since = ticks;
                stage = 5;
            } else if (stage == 5) {
                if (ticks - since < 20) return;
                var output = (AudioBackend) SmokeUi.field(audition, ComparisonAudition.class, "preview");
                check(output.state().status() == AudioBackend.Status.PAUSED, "Preview pauses");
                previewPosition = output.state().position();
                since = ticks;
                stage = 6;
            } else if (stage == 6) {
                if (ticks - since < 20) return;
                var output = (AudioBackend) SmokeUi.field(audition, ComparisonAudition.class, "preview");
                check(Math.abs(output.state().position() - previewPosition) < .1, "Paused preview position is stable");
                SmokeUi.click(mc, Text.get("cleanup.play_a"));
                since = ticks;
                stage = 7;
            } else if (stage == 7) {
                if (ticks - since < 20) return;
                var output = (AudioBackend) SmokeUi.field(audition, ComparisonAudition.class, "preview");
                check(output.state().status() == AudioBackend.Status.PLAYING && output.state().position() > previewPosition + .25, "Preview resumes from paused position");
                SmokeUi.click(mc, Text.get("cleanup.play_b"));
                since = ticks;
                stage = 3;
            } else if (stage == 3) {
                check(audition.error().isEmpty(), "B preview error");
                if (ticks - since < 25) return;
                mc.gui.screen().onClose();
                since = ticks;
                stage = 4;
            } else if (stage == 4) {
                if (app.services().playback().state().status() != AudioBackend.Status.PLAYING || ticks - since < 20)
                    return;
                check(queue.equals(app.services().playback().upNextSnapshot().manual()), "Closing preview preserves queue");
                check(a.equals(app.services().playback().state().track()), "Closing preview restores original song");
                capture(mc, "results");
                app.services().playback().stop();
                System.out.println("DARKMUSIC_CLEANUP_UI_PASS: real scan, compact/wide widget bounds and nonoverlap, preview A/B on audio device, pause/resume and Up Next preservation");
                System.out.println("DARKMUSIC_SMOKE_PASS:" + Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                stage = 99;
                mc.stop();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            stage = 99;
            if (audition != null) audition.close();
            mc.stop();
        }
    }
}
