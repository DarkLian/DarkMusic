package com.darkmusic.client.gui;

import com.darkmusic.client.config.SettingsSource;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

import java.util.concurrent.CompletableFuture;

final class LyricsConsent {
    private LyricsConsent() {
    }

    static Screen request(SettingsSource app, Screen parent) {
        // Cancel/Escape keeps networking off, and does not repeatedly interrupt the player.
        app.update(s -> {
            s.lyricsConsentVersion = 1;
            s.lyricsOnline = false;
            s.lyricsLibraryLookup = false;
        });
        return new ConfirmationScreen(parent, Text.component("lyrics.consent.title"), Text.component("lyrics.consent.body"),
            Text.component("lyrics.consent.allow"), () -> {
            app.update(s -> s.lyricsOnline = true);
            return CompletableFuture.completedFuture(null);
        }).nonDestructive();
    }
}
