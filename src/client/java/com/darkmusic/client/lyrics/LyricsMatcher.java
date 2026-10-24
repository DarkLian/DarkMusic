package com.darkmusic.client.lyrics;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Conservative identity checks, not a probability that a lyric is correct.
 */
public final class LyricsMatcher {
    private LyricsMatcher() {
    }

    public static String normalized(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replaceAll("(?i)\\b(?:official music video|official audio|official video|music video|official lyric video|lyric video|lyrics video)\\b", " ")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip().replaceAll("\\s+", " ");
    }

    private static Set<String> versions(String text) {
        return com.darkmusic.client.library.RecordingVersions.WORDS
            .stream().filter(v -> normalized(text).matches(".*(?:^| )" + v + "(?: |$).*")).collect(Collectors.toSet());
    }

    public static boolean reliable(LyricsProvider.Query query, LyricsDocument candidate) {
        String title = normalized(query.title()), artist = normalized(query.artist());
        return !title.isEmpty() && !artist.isEmpty() && !Set.of("unknown", "unknown artist", "未知").contains(artist)
            && title.equals(normalized(candidate.title())) && artist.equals(normalized(candidate.artist()))
            && Double.isFinite(query.duration()) && query.duration() > 0 && candidate.duration() > 0
            && Math.abs(query.duration() - candidate.duration()) <= 2
            && versions(query.title()).equals(versions(candidate.title()));
    }
}
