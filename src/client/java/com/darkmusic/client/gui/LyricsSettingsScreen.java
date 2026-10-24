package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

public final class LyricsSettingsScreen extends AudioPage {

    public LyricsSettingsScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "lyrics.settings");
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.LYRICS;
    }

    @Override
    protected void populate() {
        var s = app.settings;
        section("lyrics.settings");
        info("lyrics.consent.scope");
        toggle("lyrics.online", () -> s.lyricsOnline, v -> {
            if (v && s.lyricsConsentVersion < 1) show(LyricsConsent.request(app, this));
            else s.lyricsOnline = v;
        });
        int start = rowCount();
        toggle("lyrics.automatic", () -> s.lyricsAutomaticLookup, v -> s.lyricsAutomaticLookup = v);
        action("lyrics.library.find", () -> show(new LyricsLibraryScreen(app, this)));
        toggle("lyrics.after_download", () -> s.lyricsDownloadLookup, v -> s.lyricsDownloadLookup = v);
        enableSince(start, s.lyricsOnline);
        toggle("lyrics.auto_captions", () -> s.lyricsAutoCaptions, v -> s.lyricsAutoCaptions = v);
        labeledAction(Text.get("lyrics.darkmusic.languages") + ": " + (s.lyricsLanguages.equals("auto") ? Text.get("lyrics.darkmusic.language_auto") : s.lyricsLanguages),
            Text.get("lyrics.darkmusic.languages_help"), () -> show(new TextPromptScreen(this, Text.get("lyrics.darkmusic.languages_prompt"), s.lyricsLanguages, value -> {
                s.lyricsLanguages = com.darkmusic.client.download.LyricsLanguages.normalize(value);
                app.save();
            })));
        action("lyrics.clear_cache", () -> show(new ConfirmationScreen(this, Text.component("lyrics.clear_cache"), Text.component("lyrics.clear_cache.help"),
            Text.component("lyrics.clear_cache"), () -> app.lyrics.lookup.clearCache())));
    }
}
