package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class LyricsOptionsScreen extends AudioPage {
    private final Track track;
    private String feedback = "";
    private UiStatus.Tone feedbackTone = UiStatus.Tone.INFO;
    private int offset;
    private boolean hadSidecar, hadBackup;

    public LyricsOptionsScreen(DarkMusicClient app, Screen parent, Track track) {
        super(app, parent, "lyrics.options");
        this.track = track;
        offset = app.lyrics.lookup.choice(track).offset();
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.LYRICS;
    }

    private void result(CompletableFuture<Void> future) {
        future.whenComplete((done, failure) -> minecraft.execute(() -> {
            feedbackTone = failure == null ? UiStatus.Tone.SUCCESS : UiStatus.Tone.ERROR;
            feedback = Text.get(failure == null ? "lyrics.operation_saved" : "lyrics.operation_failed");
            if (isCurrent()) rebuildWidgets();
        }));
    }

    @Override
    protected void populate() {
        section("lyrics.sync");
        info(() -> track.title());
        slider("lyrics.offset", offset, -30000, 30000, 600, v -> {
            offset = (int) Math.round(v);
            app.lyrics.lookup.offset(track, offset).exceptionally(failure -> {
                minecraft.execute(() -> {
                    feedback = Text.get("lyrics.operation_failed");
                    feedbackTone = UiStatus.Tone.ERROR;
                });
                return null;
            });
        }, " ms");
        info("lyrics.offset.help");
        labeledAction(Text.get("lyrics.offset.earlier"), Text.get("lyrics.offset.help"), () -> {
            offset = Math.max(-30000, offset - 100);
            result(app.lyrics.lookup.offset(track, offset));
        });
        labeledAction(Text.get("lyrics.offset.later"), Text.get("lyrics.offset.help"), () -> {
            offset = Math.min(30000, offset + 100);
            result(app.lyrics.lookup.offset(track, offset));
        });
        action("lyrics.offset.reset", () -> {
            offset = 0;
            result(app.lyrics.lookup.offset(track, 0));
        });
        section("lyrics.versions");
        labeledAction(Text.get("lyrics.recovery.keep"), Text.get("lyrics.recovery.help"), () ->
            show(new ConfirmationScreen(this, Text.component("lyrics.recovery.keep"), Text.component("lyrics.recovery.help"), Text.component("lyrics.recovery.keep"),
                () -> app.lyrics.lookup.keepCurrentRecovery(track)).nonDestructive()));
        labeledAction(Text.get("lyrics.encoding") + ": " + app.lyrics.lookup.encoding(track), Text.get("lyrics.encoding.help"), () -> {
            var values = java.util.List.of("Auto", "Big5", "GB18030", "windows-31j", "EUC-KR");
            int index = values.indexOf(app.lyrics.lookup.encoding(track));
            result(app.lyrics.lookup.encoding(track, values.get((index + 1) % values.size())));
        });
        action("lyrics.track_language", () -> show(new TextPromptScreen(this, Text.get("lyrics.track_language"), app.lyrics.lookup.choice(track).language(), value -> {
            result(app.lyrics.lookup.language(track, value));
        })));
        action("lyrics.keep_local", () -> result(app.lyrics.lookup.select(track, null)));
        action("lyrics.import", () -> show(TextPromptScreen.validated(this, Text.get("lyrics.import_path"), "", 4096, value -> {
            Path selected = Path.of(value);
            minecraft.execute(() -> confirmImport(selected));
            return true;
        })));
        hadSidecar = app.lyrics.sidecarExists(track);
        hadBackup = app.lyrics.backupExists(track);
        int saveStart = rowCount();
        action("lyrics.save", () -> {
            var document = app.lyrics.document(track);
            show(new ConfirmationScreen(this, Text.component("lyrics.save"), Text.component("lyrics.save.help"), Text.component("lyrics.save"),
                () -> app.lyrics.lookup.save(track, document, false)));
        });
        enableSince(saveStart, app.lyrics.document(track).available() && !hadSidecar);
        int replaceStart = rowCount();
        action("lyrics.replace", () -> {
            var document = app.lyrics.document(track);
            show(new ConfirmationScreen(this, Text.component("lyrics.replace"), Text.component("lyrics.replace.help"), Text.component("lyrics.replace"),
                () -> app.lyrics.lookup.save(track, document, true)));
        });
        enableSince(replaceStart, app.lyrics.document(track).available() && hadSidecar);
        int restoreStart = rowCount();
        action("lyrics.restore", () -> show(new ConfirmationScreen(this, Text.component("lyrics.restore"), Text.component("lyrics.restore.help"), Text.component("lyrics.restore"),
            () -> app.lyrics.lookup.restore(track).thenCompose(done -> app.lyrics.lookup.select(track, null)))));
        enableSince(restoreStart, hadBackup);
        status(() -> feedback, () -> feedbackTone);
    }

    @Override
    public void tick() {
        app.lyrics.request(track);
        if (hadSidecar != app.lyrics.sidecarExists(track) || hadBackup != app.lyrics.backupExists(track))
            rebuildWidgets();
    }

    @Override
    public void onFilesDrop(java.util.List<Path> files) {
        if (files.size() == 1) confirmImport(files.getFirst());
        else {
            feedback = Text.get("lyrics.import.single");
            feedbackTone = UiStatus.Tone.WARNING;
        }
    }

    private void confirmImport(Path path) {
        show(new ConfirmationScreen(this, Text.component("lyrics.import"), Text.component("lyrics.import.confirm", path.getFileName()),
            Text.component("lyrics.import"), () -> app.lyrics.lookup.importFile(track, path)).nonDestructive());
    }
}
