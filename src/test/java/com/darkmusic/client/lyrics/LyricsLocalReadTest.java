package com.darkmusic.client.lyrics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class LyricsLocalReadTest {
    @TempDir
    Path temp;

    @Test
    void knownLocalControlsAreRemovedButImportsStayStrictAndStorageStaysCompact() {
        String legacy = "[00:00]Words\u0000\u001a\u0081";
        assertThrows(IllegalArgumentException.class, () -> LyricsDocument.of("import", "", "", "", "", 0, "und", null, false, false, legacy));
        var d = LyricsDocument.of("sidecar", "", "", "", "", 0, "und", null, false, false, LyricsDocument.localText(legacy));
        assertEquals("Words", d.timed().lines().getFirst().text());
        String j = com.darkmusic.client.config.ConfigStore.JSON.toJson(d);
        assertFalse(j.contains("\"timed\""));
        assertFalse(j.contains("\"plain\""));
        assertEquals(d, com.darkmusic.client.config.ConfigStore.JSON.fromJson(j, LyricsDocument.class));
    }

    @Test
    void localReadsAreBoundedAndRejectSymlinks() throws Exception {
        var original = Files.writeString(temp.resolve("private.txt"), "private fixture");
        assertThrows(java.io.IOException.class, () -> LyricsService.readLocal(original, 3));
        var link = temp.resolve("song.lrc");
        try {
            Files.createSymbolicLink(link, original);
        } catch (UnsupportedOperationException | FileSystemException denied) {
            Assumptions.abort("No symlink privilege");
        }
        assertThrows(java.io.IOException.class, () -> LyricsService.readLocal(link, 100));
    }

    @Test
    void siblingFormatFindsLegacyRecoveryAndResolutionArchivesWithoutOverwriting() throws Exception {
        Path audio = temp.resolve("song.flac"), lrc = LyricsFiles.companions(audio).getFirst();
        Files.writeString(lrc, "external content");
        var c = new com.google.gson.JsonObject();
        c.addProperty("name", "song.lrc");
        c.addProperty("before", java.util.Base64.getEncoder().encodeToString("original".getBytes()));
        c.addProperty("existed", true);
        c.addProperty("afterHash", "f".repeat(64));
        var j = new com.google.gson.JsonObject();
        j.addProperty("schema", 1);
        j.addProperty("committed", false);
        var changes = new com.google.gson.JsonArray();
        changes.add(c);
        j.add("changes", changes);
        String id = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("song.mp3".getBytes())).substring(0, 24);
        Path pending = Files.writeString(temp.resolve(".darkmusic-lyrics-" + id + ".json"), j.toString());
        assertTrue(assertThrows(java.io.IOException.class, () -> LyricsFiles.recover(audio)).getMessage().contains(pending.toString()));
        LyricsFiles.keepCurrent(audio);
        assertFalse(Files.exists(pending));
        assertEquals("external content", Files.readString(lrc));
        try (var files = Files.newDirectoryStream(temp, ".darkmusic-lyrics-recovery-*.json")) {
            assertEquals(j.toString(), Files.readString(files.iterator().next()));
        }
        LyricsFiles.recover(audio);
    }
}