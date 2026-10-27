package com.darkmusic.client.config;

import com.darkmusic.client.backup.BackupArchive;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BackupConsentTest {
    @Test
    void portableBackupNeverTransfersPermissions() {
        var source = new Settings();
        source.lyricsConsentVersion = 1;
        source.lyricsOnline = true;
        source.lyricsLibraryLookup = true;
        source.pip.enabled = true;
        source.pip.audioAccess = true;
        source.pip.audioSpectrum = true;
        var exported = BackupArchive.snapshot(source, "root", List.of(), Map.of());
        for (String k : List.of("lyricsConsentVersion", "lyricsOnline", "lyricsAutomaticLookup", "lyricsDownloadLookup", "lyricsLibraryLookup", "updateNotifications"))
            assertFalse(exported.settings().has(k));
        for (String k : List.of("enabled", "audioAccess", "audioSpectrum"))
            assertFalse(exported.settings().getAsJsonObject("pip").has(k));
        var legacy = new BackupArchive.Data(1, "root", ConfigStore.JSON.toJsonTree(source).getAsJsonObject(), List.of(), Map.of());
        for (boolean allowed : List.of(false, true)) {
            var local = new Settings();
            local.lyricsConsentVersion = 1;
            local.lyricsOnline = allowed;
            local.lyricsLibraryLookup = allowed;
            local.lyricsAutomaticLookup = allowed;
            local.lyricsDownloadLookup = allowed;
            local.updateNotifications = allowed;
            local.pip.enabled = allowed;
            local.pip.audioAccess = allowed;
            local.pip.audioSpectrum = allowed;
            var v = BackupArchive.prepare(legacy, new BackupArchive.Selection(true, false, false, false, false, false), local, "root", List.of(), List.of(), Map.of(), Set.of()).settings();
            assertEquals(allowed, v.lyricsOnline);
            assertEquals(allowed, v.lyricsLibraryLookup);
            assertEquals(allowed, v.lyricsAutomaticLookup);
            assertEquals(allowed, v.lyricsDownloadLookup);
            assertEquals(allowed, v.updateNotifications);
            assertEquals(allowed, v.pip.enabled);
            assertEquals(allowed, v.pip.audioAccess);
            assertEquals(allowed, v.pip.audioSpectrum);
        }
    }
}