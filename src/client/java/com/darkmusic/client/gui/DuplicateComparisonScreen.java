package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.*;
import com.darkmusic.client.cleanup.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;

/**
 * Explicit A/B decisions; the only deletion entry point is the existing exact-path review.
 */
public final class DuplicateComparisonScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final DuplicateResult pair;
    private final Screen parent;
    private final DuplicateMediaInfo[] details = {DuplicateMediaInfo.EMPTY, DuplicateMediaInfo.EMPTY};
    private ComparisonAudition audition;
    private RowList[] fields = new RowList[2];
    private final List<Button> actions = new ArrayList<>();
    private final Button[] playback = new Button[2];
    private int side, columnWidth, columnX, cardY = 58;
    private boolean loading, loaded;
    private String notice = "";

    public DuplicateComparisonScreen(DarkMusicClient app, Screen parent, DuplicateResult pair) {
        super(Text.component("cleanup.compare"), parent);
        this.app = app;
        this.parent = parent;
        this.pair = pair;
    }

    private Track track(int i) {
        return i == 0 ? pair.a() : pair.b();
    }

    private Button button(String key, int x, int y, int w, Runnable run) {
        var button = addRenderableWidget(UiButton.builder(Text.component(key), b -> run.run()).bounds(x, y, w, 20).build());
        return button;
    }

    @Override
    protected void init() {
        backButton();
        actions.clear();
        boolean wide = width >= 640;
        columnWidth = wide ? (width - 36) / 2 : width - 24;
        if (!wide) {
            int w = (width - 28) / 2;
            button("cleanup.file_a", 12, 34, w, () -> {
                side = 0;
                rebuildWidgets();
            });
            button("cleanup.file_b", 16 + w, 34, w, () -> {
                side = 1;
                rebuildWidgets();
            });
        }
        for (int i = 0; i < 2; i++) {
            int index = i, x = wide ? 12 + i * (columnWidth + 12) : 12;
            fields[i] = addRenderableWidget(new RowList(x, cardY + 70, columnWidth, Math.max(28, height - cardY - 144), 28, Text.get(i == 0 ? "cleanup.file_a" : "cleanup.file_b"), (row, a) -> {
            }));
            fields[i].rowTooltips(false);
            fields[i].visible = wide || side == i;
            int w = (columnWidth - 8) / 3;
            var play = button(i == 0 ? "cleanup.play_a" : "cleanup.play_b", x, height - 68, w, () -> play(index));
            playback[i] = play;
            UiButton.icon(play, UiIcon.PLAY, false);
            var location = button("cleanup.location", x + w + 4, height - 68, w, () -> app.folders.open(track(index).file().getParent()));
            var delete = button(i == 0 ? "cleanup.delete_a" : "cleanup.delete_b", x + (w + 4) * 2, height - 68, columnWidth - (w + 4) * 2, () -> {
                if (app.mediaBusy()) return;
                closePreview();
                show(new AudioDeletionScreen(app, this, List.of(track(index)), parent));
            });
            UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
            for (var b : List.of(play, location, delete)) {
                b.visible = wide || side == i;
                actions.add(b);
            }
        }
        int half = (width - 28) / 2;
        button("cleanup.keep", 12, height - 42, half, () -> {
            app.cleanup().dismiss(pair.id());
            onClose();
        });
        var ignore = button("cleanup.ignore", 16 + half, height - 42, half, () -> {
            if (app.cleanupReviews().ignore(pair)) onClose();
            else
                notice = Text.get(app.cleanupReviews().error().isEmpty() ? "cleanup.store_failed" : app.cleanupReviews().error());
        });
        ignore.active = app.cleanupReviews().ready();
        Help.tip(ignore, Text.get("cleanup.ignore.help"));
        fill();
        updatePlayback();
        if (!loading && !loaded) {
            loading = true;
            try {
                app.browsing.execute(() -> {
                    var a = DuplicateMediaInfo.read(pair.a());
                    var b = DuplicateMediaInfo.read(pair.b());
                    minecraft.execute(() -> {
                        details[0] = a;
                        details[1] = b;
                        loaded = true;
                        loading = false;
                        if (isCurrent()) fill();
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                loading = false;
                notice = Text.get("library.darkmusic.busy");
            }
        }
    }

    private void fill() {
        for (int i = 0; i < 2; i++) {
            Track t = track(i);
            var d = details[i];
            String name = t.file().getFileName().toString();
            int dot = name.lastIndexOf('.');
            var rows = new ArrayList<RowList.Row>();
            rows.add(new RowList.Row(Text.get("cleanup.strength." + pair.strength().name().toLowerCase(Locale.ROOT)), SmartCleanupScreen.reasons(pair)));
            add(rows, "cleanup.album", t.album());
            add(rows, "cleanup.filename", name);
            add(rows, "cleanup.path", t.file().toString());
            add(rows, "cleanup.duration", t.duration() > 0 ? String.format(Locale.ROOT, "%.1f s", t.duration()) : "");
            add(rows, "cleanup.size", String.format(Locale.ROOT, "%.2f MiB", t.size() / 1048576.0));
            add(rows, "cleanup.format", dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "");
            add(rows, "cleanup.codec", d.codec());
            add(rows, "cleanup.bitrate", d.bitrate());
            add(rows, "cleanup.rate", d.sampleRate());
            add(rows, "cleanup.channels", d.channels());
            add(rows, "cleanup.source", d.source());
            int scroll = fields[i].scrollOffset();
            fields[i].rows(rows);
            fields[i].scrollOffset(scroll);
        }
    }

    private void add(List<RowList.Row> list, String key, String value) {
        list.add(new RowList.Row(Text.get(key), value.isBlank() ? Text.get("cleanup.unavailable") : value));
    }

    private void play(int side) {
        if (app.mediaBusy()) return;
        if (audition == null)
            audition = new ComparisonAudition(app.playback, () -> new JavaSoundBackend(minecraft::execute, PcmDecoder::openPrecise, new OutputDevices(app.settings.outputDevice, true, app::warn)));
        audition.play(track(side), app.settings.volume);
        updatePlayback();
    }

    private void updatePlayback() {
        for (int i = 0; i < 2; i++) {
            boolean active = audition != null && audition.active(track(i));
            playback[i].setMessage(Text.component(active ? i == 0 ? "cleanup.pause_a" : "cleanup.pause_b" : i == 0 ? "cleanup.play_a" : "cleanup.play_b"));
            UiButton.icon(playback[i], active ? UiIcon.PAUSE : UiIcon.PLAY, false);
        }
    }

    private void closePreview() {
        if (audition != null) {
            audition.close();
            audition = null;
        }
    }

    @Override
    public void removed() {
        closePreview();
        super.removed();
    }

    @Override
    public void tick() {
        for (var b : actions) b.active = !app.mediaBusy();
        updatePlayback();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.SEARCH);
        boolean wide = width >= 640;
        if (wide)
            longText.draw(g, font, "evidence", SmartCleanupScreen.reasons(pair), 12, 36, width - 24, UiTheme.SECONDARY);
        for (int i = 0; i < 2; i++)
            if (wide || i == side) {
                int left = wide ? 12 + i * (columnWidth + 12) : 12;
                SongCard.draw(app, g, font, longText, track(i), "compare" + i, left, cardY, columnWidth);
            }
        String status = !notice.isEmpty() ? notice : audition != null && !audition.error().isEmpty() ? Text.get("cleanup.preview_failed") : Text.get("cleanup.quality_note");
        longText.draw(g, font, "notice", status, 12, height - 15, width - 24, UiStatus.color(audition != null && !audition.error().isEmpty() ? UiStatus.Tone.ERROR : !notice.isEmpty() ? UiStatus.Tone.WARNING : UiStatus.Tone.INFO));
        super.extractRenderState(g, x, y, delta);
    }
}
