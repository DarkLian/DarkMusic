package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

public final class ExistingDownloadsScreen extends AudioPage {
    private final DownloadModels.Entry entry;

    public ExistingDownloadsScreen(DarkMusicClient app, Screen parent, DownloadModels.Entry entry) {
        super(app, parent, "download.library.existing");
        this.entry = entry;
    }

    @Override
    protected void populate() {
        var lookup = app.sourceIndex().lookup(entry);
        info(entry::title);
        if (!lookup.confirmed().isEmpty()) {
            section("download.library.already");
            for (var f : lookup.confirmed()) {
                info(() -> f.track().title() + " | " + f.format());
                labeledAction(f.track().file().toString(), Text.get("cleanup.location"), () -> app.openFolder(f.track().file().getParent()));
            }
        } else if (!lookup.possible().isEmpty()) {
            section("download.library.possible");
            info("download.library.no_fuzzy_skip");
            for (var t : lookup.possible())
                labeledAction(t.file().toString(), Text.get("cleanup.location"), () -> app.openFolder(t.file().getParent()));
        } else info(lookup.complete() ? "download.library.not_detected" : "download.library.incomplete");
        action("cleanup.title", () -> show(new SmartCleanupScreen(app, this)));
    }
}
