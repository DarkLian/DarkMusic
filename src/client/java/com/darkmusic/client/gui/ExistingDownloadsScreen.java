package com.darkmusic.client.gui;

import com.darkmusic.client.cleanup.LibrarySourceIndex;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

public final class ExistingDownloadsScreen extends AudioPage<ExistingDownloadsScreen.Sources> {
    /**
     * Where downloaded songs already are in the library, and the cleanup it opens.
     */
    public interface Sources extends AudioPage.Sources, SmartCleanupScreen.Sources {
        LibrarySourceIndex sourceIndex();
    }

    private final DownloadModels.Entry entry;

    public ExistingDownloadsScreen(Sources app, Screen parent, DownloadModels.Entry entry) {
        super(app, parent, "download.library.existing");
        this.entry = entry;
    }

    @Override
    protected void populate() {
        var lookup = app.sourceIndex().lookup(entry);
        info(entry::title);
        if (!lookup.confirmed().isEmpty()) {
            status(() -> Text.get("download.library.already"), () -> UiStatus.Tone.SUCCESS);
            for (var f : lookup.confirmed()) {
                info(() -> f.track().title() + " | " + f.format());
                labeledAction(f.track().file().toString(), Text.get("cleanup.location"), () -> app.openFolder(f.track().file().getParent())).runs();
            }
        } else if (!lookup.possible().isEmpty()) {
            status(() -> Text.get("download.library.possible"), () -> UiStatus.Tone.WARNING);
            info("download.library.no_fuzzy_skip");
            for (var t : lookup.possible())
                labeledAction(t.file().toString(), Text.get("cleanup.location"), () -> app.openFolder(t.file().getParent())).runs();
        } else
            status(() -> Text.get(lookup.complete() ? "download.library.not_detected" : "download.library.incomplete"), () -> lookup.complete() ? UiStatus.Tone.INFO : UiStatus.Tone.WARNING);
        action("cleanup.title", () -> show(new SmartCleanupScreen(app, this)));
    }
}
