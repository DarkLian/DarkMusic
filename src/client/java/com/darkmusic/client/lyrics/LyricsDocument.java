package com.darkmusic.client.lyrics;

import java.util.List;
import java.util.Locale;

/**
 * Immutable lyric content; timing and provenance never depend on the interface language.
 */
public record LyricsDocument(String provider, String recordId, String title, String artist, String album,
                             double duration, String language, Variant variant, boolean generated,
                             boolean instrumental, String text, Lrc timed, List<String> plain, LyricsScript script) {
    public enum Variant {ORIGINAL, TRANSLATED, TRANSLITERATED, UNKNOWN}

    public static final LyricsDocument EMPTY = of("", "", "", "", "", 0, "und", Variant.UNKNOWN, false, false, "");

    public LyricsDocument {
        plain = List.copyOf(plain);
    }

    public static LyricsDocument of(String provider, String id, String title, String artist, String album,
                                    double duration, String language, Variant variant, boolean generated,
                                    boolean instrumental, String text) {
        if (text == null || text.length() > 1_000_000) throw new IllegalArgumentException("Invalid lyric size");
        text = text.replace("\u0000", "");
        Lrc timed = Lrc.parse(text);
        List<String> plain = text.lines().filter(s -> !s.matches("\\s*\\[(?:ar|al|ti|by|offset|length|re|ve):.*]\\s*")).toList();
        if (!timed.lines().isEmpty()) plain = timed.lines().stream().map(Lrc.Line::text).toList();
        if (plain.stream().allMatch(String::isBlank)) plain = List.of();
        return new LyricsDocument(provider, id, title, artist, album, duration,
            normalizeLanguage(language), variant == null ? Variant.UNKNOWN : variant, generated, instrumental, text, timed, plain, LyricsScript.detect(timed.lines().isEmpty() ? text : timed.lines().stream().map(Lrc.Line::text).collect(java.util.stream.Collectors.joining("\n"))));
    }

    public boolean synchronizedLyrics() {
        return !timed.lines().isEmpty();
    }

    public boolean available() {
        return instrumental || !plain.isEmpty();
    }

    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) return "und";
        String tag = language.strip().replace('_', '-');
        tag = switch (tag.toLowerCase(Locale.ROOT)) {
            case "eng" -> "en";
            case "jpn" -> "ja";
            case "kor" -> "ko";
            case "zho", "chi" -> "zh";
            case "mul" -> "mul";
            default -> tag;
        };
        try {
            return new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
        } catch (java.util.IllformedLocaleException invalid) {
            return "und";
        }
    }
}
