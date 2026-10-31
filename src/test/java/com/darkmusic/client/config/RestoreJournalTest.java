package com.darkmusic.client.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class RestoreJournalTest {
    @TempDir
    Path temp;

    @Test
    void everyOriginalAndHeldTargetIsInspectedBeforeTheFirstTargetChanges() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            Path data = Files.createDirectories(temp.resolve("case-" + variant));
            Files.writeString(data.resolve("config.json"), "Original config");
            Files.writeString(data.resolve("playlists.json"), "Original playlists");
            var updates = new LinkedHashMap<String, String>();
            updates.put("config.json", "Transaction config");
            updates.put("playlists.json", "Transaction playlists");
            assertThrows(AssertionError.class, () -> RestoreJournal.install(data, updates, (path, value) -> {
                ConfigStore.atomicWrite(path, value);
                if (path.getFileName().toString().equals("playlists.json"))
                    throw new AssertionError("Interrupted install");
            }));
            var journal = JsonParser.parseString(Files.readString(data.resolve("restore-pending.json"))).getAsJsonObject();
            Path originals = data.resolve("backups").resolve(journal.get("backup").getAsString());
            switch (variant) {
                case 0 -> Files.delete(originals.resolve("playlists.json"));
                case 1 -> Files.writeString(originals.resolve("playlists.json"), "Corrupted recovery copy");
                case 2 -> Files.writeString(data.resolve("playlists.json"), "External replacement");
                case 3 -> Files.writeString(originals.resolve("recover-playlists.json"), "External held file");
                default -> throw new AssertionError();
            }
            assertThrows(IOException.class, () -> RestoreJournal.recover(data));
            assertEquals("Transaction config", Files.readString(data.resolve("config.json")), "an earlier safe target was not applied before a later unsafe target was found");
            assertEquals("Original config", Files.readString(originals.resolve("config.json")));
            assertEquals(variant == 2 ? "External replacement" : "Transaction playlists", Files.readString(data.resolve("playlists.json")));
            assertTrue(RestoreJournal.recoveryPending(data));
            if (variant == 3)
                assertEquals("External held file", Files.readString(originals.resolve("recover-playlists.json")));
        }
    }

    @Test
    void absentAndUnsafeJournalsDoNotChangeAnExistingTarget() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Path config = Files.writeString(data.resolve("config.json"), "Existing config");
        assertFalse(RestoreJournal.recover(data));
        Path journal = Files.createDirectory(data.resolve("restore-pending.json"));
        assertThrows(IOException.class, () -> RestoreJournal.recover(data));
        Files.delete(journal);
        Files.writeString(journal, " ".repeat(16_385));
        assertThrows(IOException.class, () -> RestoreJournal.recover(data));
        assertEquals("Existing config", Files.readString(config));
    }
}
