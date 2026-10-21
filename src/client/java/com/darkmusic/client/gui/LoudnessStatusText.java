package com.darkmusic.client.gui;

import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.text.Text;

import java.util.Locale;

final class LoudnessStatusText {
    private LoudnessStatusText() {
    }

    static String describe(LoudnessCache.Status status) {
        if (status.state() != LoudnessCache.State.READY)
            return Text.get("analyzer.normalization." + status.state().name().toLowerCase(Locale.ROOT));
        String source = status.source() == null ? Text.get("analyzer.na") : Text.get("analyzer.source." + status.source().name().toLowerCase(Locale.ROOT));
        if (status.albumFallback()) source = Text.get("analyzer.album_fallback") + " · " + source;
        return Text.get("analyzer.normalization_status", "", source, String.format(Locale.ROOT, "%.1f", status.target()), String.format(Locale.ROOT, "%.1f", status.appliedDb())).strip()
            + (status.peakLimited() ? " · " + Text.get("analyzer.peak_limited") : "");
    }
}
