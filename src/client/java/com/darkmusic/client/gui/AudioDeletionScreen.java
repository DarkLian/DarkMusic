package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.AudioDeletion;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reviews exact paths before any file operation; failures never switch to permanent deletion.
 */
public final class AudioDeletionScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Screen parent, completedScreen;
    private final List<Track> requested;
    private AudioDeletion.Plan plan;
    private AudioDeletion.Result result;
    private volatile AudioDeletion.Progress progress;
    private boolean preparing, busy;
    private String error = "";
    private RowList rows;
    private Button trash, permanent, back;

    public AudioDeletionScreen(DarkMusicClient app, Screen parent, List<Track> tracks, Screen completedScreen) {
        super(Text.component("delete_audio.title"), parent);
        this.app = app;
        this.parent = parent;
        this.completedScreen = completedScreen;
        requested = List.copyOf(tracks);
    }

    @Override
    protected void init() {
        rows = addRenderableWidget(new RowList(12, 66, width - 24, Math.max(28, height - 142), 36,
            Text.get("delete_audio.files"), (i, activated) -> {
        }));
        refreshRows();
        int w = (width - 32) / 3;
        back = button("text.darkmusic.cancel", 12, height - 28, w, this::onClose);
        trash = button("delete_audio.trash", 16 + w, height - 28, w, () -> {
            if (result != null) {
                var current = app.playlists.index(app.library.snapshot().tracks());
                show(new AudioDeletionScreen(app, this, result.failed().stream()
                    .map(f -> current.getOrDefault(f.track().id(), f.track())).toList(), completedScreen));
            } else start(AudioDeletion.Mode.TRASH);
        });
        permanent = button("delete_audio.permanent", 20 + w * 2, height - 28, w, () -> show(new ConfirmationScreen(this,
            Text.component("delete_audio.permanent"), Text.component("delete_audio.permanent_confirm", plan.items().size()),
            Text.component("delete_audio.permanent_confirm_button"), () -> start(AudioDeletion.Mode.PERMANENT), this)));
        UiButton.style(trash, UiButton.Style.DESTRUCTIVE);
        UiButton.style(permanent, UiButton.Style.DESTRUCTIVE);
        setInitialFocus(back);
        if (!preparing && plan == null && error.isEmpty()) {
            preparing = true;
            app.prepareAudioDeletion(requested).whenComplete((prepared, failure) -> minecraft.execute(() -> {
                preparing = false;
                if (failure != null) error = Text.failure(failure, "delete_audio.failed");
                else plan = prepared;
                if (isCurrent()) {
                    refreshRows();
                    updateButtons();
                }
            }));
        }
        updateButtons();
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(UiButton.builder(Text.component(key), b -> action.run()).bounds(x, y, w, 20).build());
    }

    private void refreshRows() {
        var tracks = plan == null ? requested : plan.items().stream().map(AudioDeletion.Item::track).toList();
        var failures = result == null ? java.util.Map.<String, String>of() : result.failed().stream()
            .collect(java.util.stream.Collectors.toMap(f -> f.track().id(), AudioDeletion.Failure::message));
        rows.rows(tracks.size(), i -> {
            var track = tracks.get(i);
            String status = result == null ? "" : Text.get(failures.containsKey(track.id()) ? "delete_audio.item_failed" : "delete_audio.item_removed") + " | ";
            return new RowList.Row(status + track.title(), track.file().toString() + (failures.containsKey(track.id()) ? " | " + failures.get(track.id()) : ""));
        });
    }

    private CompletableFuture<Void> start(AudioDeletion.Mode mode) {
        if (plan == null || busy || result != null)
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("library.darkmusic.busy")));
        busy = true;
        error = "";
        updateButtons();
        var finished = new CompletableFuture<Void>();
        app.deleteAudio(plan, mode, value -> progress = value).whenComplete((deleted, failure) -> minecraft.execute(() -> {
            busy = false;
            if (failure != null) error = Text.failure(failure, "delete_audio.failed");
            else result = deleted;
            refreshRows();
            updateButtons();
            // Return from permanent confirmation to the detailed result, including per-file failures.
            finished.complete(null);
        }));
        return finished;
    }

    private void updateButtons() {
        boolean ready = plan != null && !busy && result == null && !app.mediaBusy;
        trash.active = ready && plan.trashSupported();
        permanent.active = ready;
        back.active = !busy;
        if (result != null) {
            back.setMessage(Text.component("phase2.back"));
            trash.setMessage(Text.component("delete_audio.retry"));
            trash.active = !result.failed().isEmpty();
            permanent.visible = false;
        }
    }

    @Override
    public void tick() {
        updateButtons();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }

    @Override
    protected Screen returnScreen() {
        return result == null ? parent : completedScreen;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.DELETE);
        longText.draw(g, font, "warning", Text.get("delete_audio.explanation"), 12, 34, width - 24, UiTheme.WARNING);
        longText.draw(g, font, "count", Text.get("delete_audio.count", plan == null ? requested.size() : plan.items().size()), 12, 49, width - 24, UiTheme.SECONDARY);
        String status = !error.isEmpty() ? error : busy && progress != null ? Text.get("delete_audio.progress", progress.completed(), progress.total(), progress.filename())
            : busy || preparing ? Text.get("reset.darkmusic.working") : result != null ? Text.get("delete_audio.result", result.removed().size(), result.failed().size())
            : plan != null && !plan.trashSupported() ? Text.get("delete_audio.no_trash") : Text.get("delete_audio.trash_help");
        longText.draw(g, font, "status", status, 12, height - 54, width - 24, UiTheme.SECONDARY);
        super.extractRenderState(g, x, y, delta);
    }
}
