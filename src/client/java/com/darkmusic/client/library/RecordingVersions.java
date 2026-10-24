package com.darkmusic.client.library;

import java.util.Set;

/**
 * Version labels must remain meaningful both when matching lyrics and reviewing related recordings.
 */
public final class RecordingVersions {
    private RecordingVersions() {
    }

    public static final Set<String> WORDS = Set.of("live", "remix", "instrumental", "cover", "extended", "acoustic", "sped", "slowed",
        "nightcore", "karaoke", "remaster", "remastered", "現場", "翻唱", "伴奏");
}
