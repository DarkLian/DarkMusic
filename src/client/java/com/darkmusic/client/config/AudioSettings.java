package com.darkmusic.client.config;

/**
 * Audio-only preferences. Neutral is an explicit bypass, independent of rendering quality.
 */
public final class AudioSettings {
    public boolean neutral = true, equalizer, normalization, analyzeLoudness, limiter, compressor;
    public double targetLufs = -18, width = 1, bass = 0, crossfade = 0;
    public boolean gapless = true;
    public Reverb reverb = Reverb.DRY;
    public Normalization gainMode = Normalization.TRACK;
    public Listening listening = Listening.NORMAL;
    public double ambientGain = .5, focusGain = .35;
    public boolean ducking;
    public double duckGain = .35, duckAttack = .03, duckHold = .4, duckRelease = .5;
    public double[] bands = new double[10];
    public String eqPreset = "FLAT", profile = "DEFAULT";
    public java.util.Map<String, String> savedProfiles = new java.util.LinkedHashMap<>();
    public AudioEnhancements enhancements = new AudioEnhancements();

    public enum Reverb {DRY, STUDIO, SMALL_ROOM, CONCERT_HALL}

    public enum Normalization {TRACK, ALBUM}

    public enum Listening {NORMAL, FOCUS, AMBIENT}

    public enum Equalizer {FLAT, BASS_BOOST, VOCAL_CLARITY, CLASSICAL, ELECTRONIC, ROCK, CUSTOM}

    /**
     * A detached snapshot containing only values consumed by playback.
     */
    public AudioSettings playbackCopy() {
        var copy = new AudioSettings();
        copy.neutral = neutral;
        copy.equalizer = equalizer;
        copy.normalization = normalization;
        copy.analyzeLoudness = analyzeLoudness;
        copy.limiter = limiter;
        copy.compressor = compressor;
        copy.targetLufs = targetLufs;
        copy.width = width;
        copy.bass = bass;
        copy.crossfade = crossfade;
        copy.gapless = gapless;
        copy.reverb = reverb;
        copy.gainMode = gainMode;
        copy.listening = listening;
        copy.ambientGain = ambientGain;
        copy.focusGain = focusGain;
        copy.ducking = ducking;
        copy.duckGain = duckGain;
        copy.duckAttack = duckAttack;
        copy.duckHold = duckHold;
        copy.duckRelease = duckRelease;
        copy.bands = bands == null ? null : bands.clone();
        copy.enhancements = enhancements == null ? new AudioEnhancements() : enhancements.copy();
        copy.validate();
        return copy;
    }

    public boolean samePlayback(AudioSettings other) {
        return other != null && neutral == other.neutral && equalizer == other.equalizer
            && normalization == other.normalization && analyzeLoudness == other.analyzeLoudness
            && limiter == other.limiter && compressor == other.compressor && targetLufs == other.targetLufs
            && width == other.width && bass == other.bass && crossfade == other.crossfade && gapless == other.gapless
            && reverb == other.reverb && gainMode == other.gainMode && listening == other.listening
            && ambientGain == other.ambientGain && focusGain == other.focusGain && ducking == other.ducking
            && duckGain == other.duckGain && duckAttack == other.duckAttack && duckHold == other.duckHold
            && duckRelease == other.duckRelease && java.util.Arrays.equals(bands, other.bands)
            && enhancements.same(other.enhancements);
    }

    public void validate() {
        if (bands == null || bands.length != 10) bands = new double[10];
        for (int i = 0; i < bands.length; i++) bands[i] = finite(bands[i], -12, 12, 0);
        targetLufs = finite(targetLufs, -24, -9, -18);
        bass = finite(bass, 0, 6, 0);
        width = finite(width, 0, 1.5, 1);
        crossfade = finite(crossfade, 0, 10, 0);
        ambientGain = finite(ambientGain, 0, 1, .5);
        focusGain = finite(focusGain, 0, 1, .35);
        duckGain = finite(duckGain, 0, 1, .35);
        duckAttack = finite(duckAttack, .01, .2, .03);
        duckHold = finite(duckHold, .1, 2, .4);
        duckRelease = finite(duckRelease, .1, 2, .5);
        if (reverb == null) reverb = Reverb.DRY;
        if (gainMode == null) gainMode = Normalization.TRACK;
        if (listening == null) listening = Listening.NORMAL;
        if (savedProfiles == null) savedProfiles = new java.util.LinkedHashMap<>();
        if (eqPreset == null) eqPreset = "CUSTOM";
        if (profile == null) profile = "CUSTOM";
        if (enhancements == null) enhancements = new AudioEnhancements();
        enhancements.validate();
    }

    private static double finite(double v, double min, double max, double fallback) {
        return Double.isFinite(v) ? Math.clamp(v, min, max) : fallback;
    }

    public void equalizer(Equalizer preset) {
        eqPreset = preset.name();
        profile = "CUSTOM";
        bands = switch (preset) {
            case BASS_BOOST -> new double[]{5, 4, 3, 1, 0, 0, 0, 0, 0, 0};
            case VOCAL_CLARITY -> new double[]{-2, -2, -1, 0, 2, 3, 3, 1, 0, 0};
            case CLASSICAL -> new double[]{1, 1, 0, 0, 0, 0, 1, 2, 2, 1};
            case ELECTRONIC -> new double[]{4, 3, 1, 0, -1, 0, 1, 2, 3, 3};
            case ROCK -> new double[]{3, 2, 0, -1, -1, 1, 2, 3, 2, 1};
            default -> new double[10];
        };
    }

    public void selectProfile(String name) {
        // Explicitly choosing an audio profile opts into its described effects.
        neutral = name.equals("DEFAULT");
        equalizer = false;
        bands = new double[10];
        eqPreset = Equalizer.FLAT.name();
        normalization = false;
        compressor = false;
        limiter = !neutral;
        width = 1;
        bass = 0;
        reverb = Reverb.DRY;
        listening = Listening.NORMAL;
        ducking = false;
        boolean preservePitch = enhancements != null && enhancements.preservePitch;
        enhancements = new AudioEnhancements();
        enhancements.preservePitch = preservePitch;
        if (name.equals("MUSIC")) {
            normalization = true;
            analyzeLoudness = true;
        }
        if (name.equals("GAMING")) {
            listening = Listening.AMBIENT;
        }
        if (name.equals("VOCAL")) {
            equalizer(Equalizer.VOCAL_CLARITY);
            equalizer = true;
        }
        if (name.equals("NIGHT")) {
            compressor = true;
            normalization = true;
            analyzeLoudness = true;
        }
        profile = name;
    }
}
