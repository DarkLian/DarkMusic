package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.download.LyricsLanguages;
import com.google.gson.annotations.JsonAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Immutable lyric content; timing and provenance never depend on the interface language.
 */
@JsonAdapter(value = LyricsDocumentAdapter.class, nullSafe = false)
public record LyricsDocument(String provider, String recordId, String title, String artist, String album,
                             double duration, String language, Variant variant, boolean generated,
                             boolean instrumental, String text, Lrc timed, List<String> plain, LyricsScript script) {
    public enum Variant {ORIGINAL, TRANSLATED, TRANSLITERATED, UNKNOWN}

    public static final LyricsDocument EMPTY = of("", "", "", "", "", 0, "und", Variant.UNKNOWN, false, false, "");

    public LyricsDocument {
        plain = List.copyOf(plain);
    }

    /**
     * Existing lyric files may contain legacy EOF markers or padding; imports remain strict.
     */
    public static String localText(String text) {
        var out = new StringBuilder(text.length());
        text.codePoints().filter(c -> !Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t').forEach(out::appendCodePoint);
        return out.toString();
    }

    public static LyricsDocument of(String provider, String id, String title, String artist, String album,
                                    double duration, String language, Variant variant, boolean generated,
                                    boolean instrumental, String text) {
        if (text == null || text.length() > 1_000_000) throw new IllegalArgumentException("Invalid lyric size");
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Binary or control characters in lyric text");
        text = clean(text);
        title = clean(title);
        artist = clean(artist);
        album = clean(album);
        if (!Double.isFinite(duration) || duration < 0) duration = 0;
        Lrc timed = Lrc.parse(text);
        List<String> plain = text.lines().filter(s -> !s.matches("\\s*\\[(?:ar|al|ti|by|offset|length|re|ve):.*]\\s*")).toList();
        if (!timed.lines().isEmpty()) plain = timed.lines().stream().map(Lrc.Line::text).toList();
        if (plain.stream().allMatch(String::isBlank)) plain = List.of();
        return new LyricsDocument(provider, id, title, artist, album, duration,
            LyricsLanguages.normalizeLanguage(language), variant == null ? Variant.UNKNOWN : variant, generated, instrumental, text, timed, plain, LyricsScript.detect(timed.lines().isEmpty() ? text : timed.lines().stream().map(Lrc.Line::text).collect(java.util.stream.Collectors.joining("\n"))));
    }

    public boolean synchronizedLyrics() {
        return !timed.lines().isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\u00a7[^\\r\\n]", "");
    }

    public boolean available() {
        return instrumental || !plain.isEmpty();
    }

    /**
     * Writes this document as the audio file's lyric sidecar plus its attribution record.
     */
    public void save(Path audio, boolean replace) throws IOException {
        LyricsFiles.save(audio, text, ConfigStore.JSON.toJson(this), replace);
    }
}
