package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

/**
 * An explicitly started, session-scoped library lookup; leaving the screen does not cancel it.
 */
public final class LyricsLibraryScreen extends AudioPage {
    private boolean running;

    public LyricsLibraryScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "lyrics.library.find");
    }

    @Override
    protected void populate() {
        running = app.lyrics.libraryLookupRunning();
        section("lyrics.library.find");
        info("lyrics.consent.scope");
        if (!app.settings.lyricsEnabled) info("lyrics.library.enable");
        info(() -> Text.get("lyrics.library.progress", app.lyrics.libraryChecked(), app.lyrics.libraryTotal(), app.lyrics.libraryRequests()));
        info(this::status);
        int start = rowCount();
        action("lyrics.library.start", () -> {
            app.lyrics.startLibraryLookup();
            rebuildWidgets();
        });
        enableSince(start, !running && app.settings.lyricsEnabled && app.settings.lyricsOnline && !app.library.snapshot().scanning());
        start = rowCount();
        action("lyrics.library.cancel", () -> {
            app.lyrics.cancelLibraryLookup();
            rebuildWidgets();
        });
        enableSince(start, running);
    }

    private String status() {
        if (!app.settings.lyricsEnabled || !app.settings.lyricsOnline) return Text.get("lyrics.online_required");
        if (app.library.snapshot().scanning() || app.mediaBusy) return Text.get("lyrics.library.wait_library");
        if (!app.lyrics.libraryLookupRunning()) return Text.get("lyrics.library.idle");
        long seconds = app.lyrics.lookup.cooldownSeconds();
        if (seconds > 0) return Text.get("lyrics.library.wait_provider", seconds);
        if (app.lyrics.libraryChecked() >= app.lyrics.libraryTotal() && app.lyrics.libraryRetrySeconds() > 0)
            return Text.get("lyrics.retry_in", app.lyrics.libraryRetrySeconds());
        return Text.get(app.lyrics.lookup.busy() ? "lyrics.library.looking" : "lyrics.library.checking");
    }

    @Override
    public void tick() {
        if (running != app.lyrics.libraryLookupRunning()) rebuildWidgets();
    }
}
