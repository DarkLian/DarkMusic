package com.darkmusic.client.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Ends a journaled install the way a dying storage task does: after the journal and the first file are written, and
 * without the rollback the install runs for its own failures.
 */
public final class JournalFixture {
    public static final class Died extends Error {
        Died() {
            super("storage task died");
        }
    }

    private JournalFixture() {
    }

    public static void dieAfterFirstWrite(Path directory, Map<String, String> contents) throws IOException {
        RestoreJournal.install(directory, contents, (path, value) -> {
            ConfigStore.atomicWrite(path, value);
            throw new Died();
        });
    }
}
