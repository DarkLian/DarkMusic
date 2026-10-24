package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.concurrent.CompletableFuture;

final class LyricsConsent {
    private LyricsConsent() {
    }

    static Screen request(DarkMusicClient app, Screen parent) {
        // Cancel/Escape keeps networking off, and does not repeatedly interrupt the player.
        app.settings.lyricsConsentVersion = 1;
        app.settings.lyricsOnline = false;
        app.settings.lyricsLibraryLookup = false;
        app.save();
        return new ConfirmationScreen(parent, Text.component("lyrics.consent.title"), Text.component("lyrics.consent.body"),
            Text.component("lyrics.consent.allow"), () -> {
            app.settings.lyricsOnline = true;
            app.save();
            return CompletableFuture.completedFuture(null);
        }).nonDestructive();
    }
}
