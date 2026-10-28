package com.darkmusic.client.gui;

import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

/**
 * An explicitly started, session-scoped library lookup; leaving the screen does not cancel it.
 */
public final class LyricsLibraryScreen extends AudioPage<LyricsScreen.Sources> {
    private boolean running;

    public LyricsLibraryScreen(LyricsScreen.Sources app, Screen parent) {
        super(app, parent, "lyrics.library.find");
    }

    @Override
    protected void populate() {
        running = app.lyrics().libraryLookupRunning();
        section("lyrics.library.find");
        info("lyrics.consent.scope");
        if (!app.settings().lyricsEnabled) status(() -> Text.get("lyrics.library.enable"), () -> UiStatus.Tone.WARNING);
        status(() -> Text.get("lyrics.library.progress", app.lyrics().libraryChecked(), app.lyrics().libraryTotal(), app.lyrics().libraryRequests()));
        status(this::status, () -> !app.settings().lyricsEnabled || !app.settings().lyricsOnline || app.lyrics().lookup.cooldownSeconds() > 0
            ? UiStatus.Tone.WARNING : UiStatus.Tone.INFO);
        int start = rowCount();
        action("lyrics.library.start", () -> {
            app.lyrics().startLibraryLookup();
            rebuildWidgets();
        }).runs();
        enableSince(start, !running && app.settings().lyricsEnabled && app.settings().lyricsOnline && !app.library().snapshot().scanning());
        start = rowCount();
        action("lyrics.library.cancel", () -> {
            app.lyrics().cancelLibraryLookup();
            rebuildWidgets();
        }).runs();
        enableSince(start, running);
    }

    private String status() {
        if (!app.settings().lyricsEnabled || !app.settings().lyricsOnline) return Text.get("lyrics.online_required");
        if (app.library().snapshot().scanning() || app.mediaBusy()) return Text.get("lyrics.library.wait_library");
        if (!app.lyrics().libraryLookupRunning()) return Text.get("lyrics.library.idle");
        long seconds = app.lyrics().lookup.cooldownSeconds();
        if (seconds > 0) return Text.get("lyrics.library.wait_provider", seconds);
        if (app.lyrics().libraryChecked() >= app.lyrics().libraryTotal() && app.lyrics().libraryRetrySeconds() > 0)
            return Text.get("lyrics.retry_in", app.lyrics().libraryRetrySeconds());
        return Text.get(app.lyrics().lookup.busy() ? "lyrics.library.looking" : "lyrics.library.checking");
    }

    @Override
    public void tick() {
        if (running != app.lyrics().libraryLookupRunning()) rebuildWidgets();
    }
}
