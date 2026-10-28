package com.darkmusic.client.lyrics;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Inspection outcome, kept separate from playable lyric data and source-caption candidates.
 */
public record LyricsInfo(State state, Evidence evidence, String lookupKey) {
    public enum State {AVAILABLE, UNAVAILABLE, UNKNOWN}

    public enum Evidence {LOCAL, CAPTIONS, UNCHECKED, LOOKUP, FAILED, CHOICE, WAITING}

    public LyricsInfo(State state, Evidence evidence) {
        this(state, evidence, "");
    }

    public static final LyricsInfo UNKNOWN = new LyricsInfo(State.UNKNOWN, Evidence.UNCHECKED);
    public static final LyricsInfo SEARCHING = new LyricsInfo(State.UNKNOWN, Evidence.LOOKUP, "lyrics.status.searching");
    public static final LyricsInfo CHECKING = new LyricsInfo(State.UNKNOWN, Evidence.LOOKUP, "lyrics.status.checking");
    public static final LyricsInfo LOCAL_ERROR = new LyricsInfo(State.UNKNOWN, Evidence.FAILED, "lyrics.status.local_error");
    public static final LyricsInfo AVAILABLE = new LyricsInfo(State.AVAILABLE, Evidence.LOCAL);
    public static final LyricsInfo PLAIN = new LyricsInfo(State.AVAILABLE, Evidence.LOCAL, "lyrics.plain");
    public static final LyricsInfo LOCAL_MISSING = new LyricsInfo(State.UNKNOWN, Evidence.LOCAL, "lyrics.darkmusic.availability.unavailable");
    public static final LyricsInfo INSTRUMENTAL = new LyricsInfo(State.UNAVAILABLE, Evidence.CHOICE, "lyrics.instrumental");
    public static final LyricsInfo UNAVAILABLE = new LyricsInfo(State.UNAVAILABLE, Evidence.LOCAL);
    private static final Map<LyricsLookup.Status, LyricsInfo> LOOKUP_STATES = lookupStates();

    private static Map<LyricsLookup.Status, LyricsInfo> lookupStates() {
        var states = new EnumMap<LyricsLookup.Status, LyricsInfo>(LyricsLookup.Status.class);
        for (var status : LyricsLookup.Status.values()) {
            if (status == LyricsLookup.Status.IDLE) continue;
            Evidence evidence = switch (status) {
                case NETWORK, RATE_LIMITED, PROVIDER, SOURCE, TOOLS, INVALID, REJECTED -> Evidence.FAILED;
                case READY, AMBIGUOUS -> Evidence.CHOICE;
                case CANCELLED, DISABLED, SAVED -> Evidence.WAITING;
                default -> Evidence.LOOKUP;
            };
            states.put(status, new LyricsInfo(status == LyricsLookup.Status.NOT_FOUND ? State.UNAVAILABLE : State.UNKNOWN,
                evidence, "lyrics.status." + status.name().toLowerCase(Locale.ROOT)));
        }
        return Map.copyOf(states);
    }

    public static LyricsInfo lookup(LyricsLookup.Status status) {
        return LOOKUP_STATES.get(status);
    }

    public String key() {
        if (lookupKey != null && !lookupKey.isEmpty()) return lookupKey;
        if (evidence == Evidence.CAPTIONS)
            return state == State.UNAVAILABLE ? "lyrics.darkmusic.no_manual_captions" : "lyrics.darkmusic.captions_detected";
        return "lyrics.darkmusic.availability." + state.name().toLowerCase(Locale.ROOT);
    }

    public int color() {
        if (evidence == Evidence.FAILED) return 0xFFE4A36A;
        if (evidence == Evidence.CHOICE) return 0xFFB8A8FA;
        return switch (state) {
            case AVAILABLE -> 0xFF83D9B1;
            case UNAVAILABLE -> 0xFF9096AD;
            case UNKNOWN -> 0xFFE7BD79;
        };
    }

    public String mark() {
        if (evidence == Evidence.FAILED) return "\u2717";
        return switch (state) {
            case AVAILABLE -> "\u2713";
            case UNAVAILABLE -> "\u2717";
            case UNKNOWN -> "?";
        };
    }
}
