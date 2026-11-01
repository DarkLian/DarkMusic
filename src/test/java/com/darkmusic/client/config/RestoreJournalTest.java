package com.darkmusic.client.config;

import com.google.gson.JsonParser;
import com.darkmusic.client.testing.FaultFileSystem;

import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void failedApplyOperationsAndFinalJournalDeleteCanBeRetriedOnTheHostFileSystem() throws Exception {
        for (int variant = 0; variant < 6; variant++) {
            try (var fs = new FaultFileSystem()) {
                Path data = Files.createDirectories(fs.wrap(temp.resolve("fault-" + variant)));
                Files.writeString(data.resolve("config.json"), "original config");
                Files.writeString(data.resolve("playlists.json"), "original playlists");
                var updates = new LinkedHashMap<String, String>();
                updates.put("config.json", "replacement config");
                updates.put("playlists.json", "replacement playlists");
                assertThrows(AssertionError.class, () -> RestoreJournal.install(data, updates, (path, value) -> {
                    ConfigStore.atomicWrite(path, value);
                    if (path.getFileName().toString().equals("playlists.json")) throw new AssertionError("power loss");
                }));
                int scenario = variant;
                var reached = new AtomicBoolean();
                var denied = new IOException("injected recovery operation " + variant);
                fs.before((operation, path, target) -> {
                    String name = path.getFileName().toString();
                    boolean chosen = switch (scenario) {
                        case 0 -> operation.equals("move") && name.equals("config.json");
                        case 1 -> operation.equals("move") && name.startsWith("restore-");
                        case 2 -> operation.equals("move") && name.equals("playlists.json");
                        case 3 -> operation.equals("delete") && name.equals("recover-config.json");
                        case 4 -> operation.equals("delete") && name.equals("restore-pending.json");
                        case 5 -> operation.equals("write") && name.startsWith("restore-");
                        default -> false;
                    };
                    if (chosen && reached.compareAndSet(false, true)) throw denied;
                });
                assertSame(denied, assertThrows(IOException.class, () -> RestoreJournal.recover(data)));
                assertTrue(reached.get());
                assertTrue(RestoreJournal.recoveryPending(data));
                var journal = JsonParser.parseString(Files.readString(data.resolve("restore-pending.json"))).getAsJsonObject();
                Path originals = data.resolve("backups").resolve(journal.get("backup").getAsString());
                assertEquals("original config", Files.readString(originals.resolve("config.json")));
                assertEquals("original playlists", Files.readString(originals.resolve("playlists.json")));
                for (String name : updates.keySet())
                    assertTrue(Files.exists(data.resolve(name)) || Files.exists(originals.resolve("recover-" + name)),
                        "a missing target remains accounted for by its claimed replacement");
                assertTrue(RestoreJournal.recover(data));
                assertEquals("original config", Files.readString(data.resolve("config.json")));
                assertEquals("original playlists", Files.readString(data.resolve("playlists.json")));
                assertFalse(RestoreJournal.recoveryPending(data));
                assertFalse(Files.exists(originals.resolve("recover-config.json")));
                assertFalse(Files.exists(originals.resolve("recover-playlists.json")));
                assertFalse(RestoreJournal.recover(data));
            }
        }
    }

}
