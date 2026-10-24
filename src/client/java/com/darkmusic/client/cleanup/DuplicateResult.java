package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;

import java.util.*;

public record DuplicateResult(String id, Track a, Track b, String stampA, String stampB,
                              Set<Evidence> evidence, Strength strength) {
    public enum Evidence {EXACT, SOURCE, SOURCE_TAG, AUDIO, OVERLAP, TITLE_DURATION, FILENAME, VERSION}

    public enum Strength {STRONG, POSSIBLE, VERSIONS}

    public DuplicateResult {
        evidence = Set.copyOf(evidence);
    }

    public boolean matches(String query) {
        return a.matches(query) || b.matches(query);
    }

    public static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a.length() + ":" + a + b : b.length() + ":" + b + a;
    }

    public String reviewKey() {
        return pair(stampA, stampB);
    }
}
