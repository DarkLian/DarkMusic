package com.darkmusic.client.render;

import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.lyrics.LyricsInfo;
import com.darkmusic.client.lyrics.LyricsLookup;
import com.darkmusic.client.text.Text;

import java.util.Locale;

/**
 * Semantic status colors, independent of the user's decorative accent.
 */
public final class UiStatus {
    public enum Tone {INFO, SUCCESS, WARNING, ERROR}

    private UiStatus() {
    }

    private static final int[] palette = {0xff82bdff, 0xff66ddb8, 0xffebc37c, 0xffef8798};

    public static int color(Tone tone) {
        return palette[tone.ordinal()];
    }

    static void refresh() {
        for (var tone : Tone.values()) palette[tone.ordinal()] = compute(tone);
    }

    private static int compute(Tone tone) {
        boolean light = UiTheme.contrast(UiTheme.SURFACE, 0xff000000) > UiTheme.contrast(UiTheme.SURFACE, 0xffffffff);
        int initial = switch (tone) {
            case INFO -> light ? 0xff175b9f : 0xff82bdff;
            case SUCCESS -> light ? 0xff176444 : 0xff66ddb8;
            case WARNING -> light ? 0xff804900 : 0xffebc37c;
            case ERROR -> light ? 0xffa62340 : 0xffef8798;
        };
        for (int step = 0; step <= 16; step++) {
            int candidate = UiTheme.mix(initial, UiTheme.TEXT, step / 16f);
            if (UiTheme.contrast(candidate, UiTheme.SURFACE) >= 4.5 && UiTheme.contrast(candidate, UiTheme.RAISED) >= 4.5
                && UiTheme.contrast(candidate, UiTheme.HOVER) >= 4.5 && UiTheme.contrast(candidate, UiTheme.SELECTED) >= 4.5)
                return candidate;
        }
        return UiTheme.TEXT;
    }

    public static Tone download(DownloadModels.Status status) {
        return switch (status) {
            case FAILED -> Tone.ERROR;
            case SAVED, SKIPPED -> Tone.SUCCESS;
            case CANCELLED -> Tone.WARNING;
            default -> Tone.INFO;
        };
    }

    public static int lyricsColor(LyricsInfo info) {
        // A confirmed absence (none found, or instrumental) is information, not a problem.
        if (LyricsStatusIcon.kind(info) == LyricsStatusIcon.Kind.NONE || LyricsStatusIcon.kind(info) == LyricsStatusIcon.Kind.INSTRUMENTAL)
            return UiTheme.SECONDARY;
        var tone = info.state() == LyricsInfo.State.AVAILABLE ? Tone.SUCCESS
            : info.evidence() == LyricsInfo.Evidence.FAILED
            ? ("lyrics.status.rate_limited".equals(info.lookupKey()) ? Tone.WARNING : Tone.ERROR)
            : info.evidence() == LyricsInfo.Evidence.LOOKUP ? Tone.INFO : Tone.WARNING;
        return color(tone);
    }

    public static Tone lyrics(LyricsLookup.Status status) {
        return switch (status) {
            case NETWORK, PROVIDER, SOURCE, TOOLS, INVALID, REJECTED -> Tone.ERROR;
            case RATE_LIMITED, NOT_FOUND, AMBIGUOUS, CANCELLED, DISABLED -> Tone.WARNING;
            case READY, SAVED -> Tone.SUCCESS;
            default -> Tone.INFO;
        };
    }

    public static String label(String constant) {
        return Text.get("state.darkmusic." + constant.toLowerCase(Locale.ROOT));
    }
}
