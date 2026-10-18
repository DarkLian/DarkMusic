package com.darkmusic.client.lyrics;

/**
 * Inspection outcome, kept separate from playable lyric data and source-caption candidates.
 */
public record LyricsInfo(State state, Evidence evidence) {
    public enum State {AVAILABLE, UNAVAILABLE, UNKNOWN}

    public enum Evidence {LOCAL, CAPTIONS, UNCHECKED}

    public static final LyricsInfo UNKNOWN = new LyricsInfo(State.UNKNOWN, Evidence.UNCHECKED);
    public static final LyricsInfo AVAILABLE = new LyricsInfo(State.AVAILABLE, Evidence.LOCAL);
    public static final LyricsInfo UNAVAILABLE = new LyricsInfo(State.UNAVAILABLE, Evidence.LOCAL);
    public static final LyricsInfo CAPTIONS = new LyricsInfo(State.UNKNOWN, Evidence.CAPTIONS);
    public static final LyricsInfo NO_CAPTIONS = new LyricsInfo(State.UNAVAILABLE, Evidence.CAPTIONS);

    public String key() {
        if (evidence == Evidence.CAPTIONS)
            return state == State.UNAVAILABLE ? "lyrics.darkmusic.no_manual_captions" : "lyrics.darkmusic.captions_detected";
        return "lyrics.darkmusic.availability." + state.name().toLowerCase(java.util.Locale.ROOT);
    }

    public int color() {
        return switch (state) {
            case AVAILABLE -> 0xFF83D9B1;
            case UNAVAILABLE -> 0xFF9096AD;
            case UNKNOWN -> 0xFFE7BD79;
        };
    }

    public String mark() {
        return switch (state) {
            case AVAILABLE -> "+";
            case UNAVAILABLE -> "/";
            case UNKNOWN -> "?";
        };
    }
}
