package com.darkmusic.client.config;

/**
 * Rendering/analysis quality only: never enables audio coloration or changes playback gain.
 */
public final class VisualQuality {
    public boolean frequencySmoothing = true, peakHold = true, trails = true, glow = true, beatReactive;
    public int detail = 192;

    public enum Preset {STANDARD, ENHANCED, EXTREME, CUSTOM}

    public void next() {
        select(switch (preset()) {
            case STANDARD -> Preset.ENHANCED;
            case ENHANCED -> Preset.EXTREME;
            case EXTREME, CUSTOM -> Preset.STANDARD;
        });
    }

    public void select(Preset preset) {
        if (preset == Preset.CUSTOM) return;
        frequencySmoothing = peakHold = beatReactive = preset != Preset.STANDARD;
        trails = glow = preset == Preset.EXTREME;
        detail = preset == Preset.STANDARD ? 192 : preset == Preset.ENHANCED ? 256 : 384;
    }

    public Preset preset() {
        for (var p : new Preset[]{Preset.STANDARD, Preset.ENHANCED, Preset.EXTREME}) {
            var v = new VisualQuality();
            v.select(p);
            if (frequencySmoothing == v.frequencySmoothing && peakHold == v.peakHold && trails == v.trails && glow == v.glow && beatReactive == v.beatReactive && detail == v.detail)
                return p;
        }
        return Preset.CUSTOM;
    }

    public static final Range DETAIL = Range.whole(96, 384);

    public void validate() {
        detail = DETAIL.apply(detail);
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void detail(int value) {
        detail = DETAIL.apply(value);
    }
}
