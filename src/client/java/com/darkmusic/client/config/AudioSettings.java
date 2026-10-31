package com.darkmusic.client.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audio-only preferences. Neutral is an explicit bypass, independent of rendering quality.
 */
public final class AudioSettings {
    public boolean neutral = true, equalizer, normalization, analyzeLoudness, limiter, compressor;
    public boolean parallelLoudnessAnalysis;
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
    public Map<String, String> savedProfiles = new LinkedHashMap<>();
    // The saved profile being used; edits stay with it and Save writes them back. Null for built-ins and Custom.
    public String selectedSaved;
    public AudioEnhancements enhancements = new AudioEnhancements();

    public enum Reverb {DRY, STUDIO, SMALL_ROOM, CONCERT_HALL}

    public enum Normalization {TRACK, ALBUM}

    public enum Listening {NORMAL, FOCUS, AMBIENT}

    public enum Equalizer {FLAT, BASS_BOOST, VOCAL_CLARITY, CLASSICAL, ELECTRONIC, ROCK, CUSTOM}

    // Built-in profiles in the order the profile row cycles through them. DEFAULT is shown as Original.
    public static final List<String> PROFILES = List.of("DEFAULT", "MUSIC", "GAMING", "VOCAL", "NIGHT");
    public static final String CUSTOM = "CUSTOM";

    /**
     * A detached snapshot containing only values consumed by playback.
     */
    public AudioSettings playbackCopy() {
        var copy = new AudioSettings();
        copy.neutral = neutral;
        copy.equalizer = equalizer;
        copy.normalization = normalization;
        copy.analyzeLoudness = analyzeLoudness;
        copy.parallelLoudnessAnalysis = parallelLoudnessAnalysis;
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
            && parallelLoudnessAnalysis == other.parallelLoudnessAnalysis
            && limiter == other.limiter && compressor == other.compressor && targetLufs == other.targetLufs
            && width == other.width && bass == other.bass && crossfade == other.crossfade && gapless == other.gapless
            && reverb == other.reverb && gainMode == other.gainMode && listening == other.listening
            && ambientGain == other.ambientGain && focusGain == other.focusGain && ducking == other.ducking
            && duckGain == other.duckGain && duckAttack == other.duckAttack && duckHold == other.duckHold
            && duckRelease == other.duckRelease && Arrays.equals(bands, other.bands)
            && enhancements.same(other.enhancements);
    }

    public static final Range BAND = Range.of(-12, 12, 0);
    public static final Range TARGET_LUFS = Range.of(-24, -9, -18);
    public static final Range BASS = Range.of(0, 6, 0);
    public static final Range WIDTH = Range.of(0, 1.5, 1);
    public static final Range CROSSFADE = Range.of(0, 10, 0);
    public static final Range AMBIENT_GAIN = Range.of(0, 1, .5);
    public static final Range FOCUS_GAIN = Range.of(0, 1, .35);
    public static final Range DUCK_GAIN = Range.of(0, 1, .35);
    public static final Range DUCK_ATTACK = Range.of(.01, .2, .03);
    public static final Range DUCK_HOLD = Range.of(.1, 2, .4);
    public static final Range DUCK_RELEASE = Range.of(.1, 2, .5);

    public void validate() {
        if (bands == null || bands.length != 10) bands = new double[10];
        for (int i = 0; i < bands.length; i++) bands[i] = BAND.apply(bands[i]);
        targetLufs = TARGET_LUFS.apply(targetLufs);
        bass = BASS.apply(bass);
        width = WIDTH.apply(width);
        crossfade = CROSSFADE.apply(crossfade);
        ambientGain = AMBIENT_GAIN.apply(ambientGain);
        focusGain = FOCUS_GAIN.apply(focusGain);
        duckGain = DUCK_GAIN.apply(duckGain);
        duckAttack = DUCK_ATTACK.apply(duckAttack);
        duckHold = DUCK_HOLD.apply(duckHold);
        duckRelease = DUCK_RELEASE.apply(duckRelease);
        if (reverb == null) reverb = Reverb.DRY;
        if (gainMode == null) gainMode = Normalization.TRACK;
        if (listening == null) listening = Listening.NORMAL;
        if (savedProfiles == null) savedProfiles = new LinkedHashMap<>();
        if (eqPreset == null) eqPreset = "CUSTOM";
        if (profile == null) profile = "CUSTOM";
        if (enhancements == null) enhancements = new AudioEnhancements();
        enhancements.validate();
        if (selectedSaved != null && !savedProfiles.containsKey(selectedSaved)) selectedSaved = null;
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void targetLufs(double value) {
        targetLufs = TARGET_LUFS.apply(value);
    }

    public void bass(double value) {
        bass = BASS.apply(value);
    }

    public void width(double value) {
        width = WIDTH.apply(value);
    }

    public void crossfade(double value) {
        crossfade = CROSSFADE.apply(value);
    }

    public void ambientGain(double value) {
        ambientGain = AMBIENT_GAIN.apply(value);
    }

    public void focusGain(double value) {
        focusGain = FOCUS_GAIN.apply(value);
    }

    public void duckGain(double value) {
        duckGain = DUCK_GAIN.apply(value);
    }

    public void duckAttack(double value) {
        duckAttack = DUCK_ATTACK.apply(value);
    }

    public void duckHold(double value) {
        duckHold = DUCK_HOLD.apply(value);
    }

    public void duckRelease(double value) {
        duckRelease = DUCK_RELEASE.apply(value);
    }


    public void equalizer(Equalizer preset) {
        eqPreset = preset.name();
        bands = switch (preset) {
            case BASS_BOOST -> new double[]{5, 4, 3, 1, 0, 0, 0, 0, 0, 0};
            case VOCAL_CLARITY -> new double[]{-2, -2, -1, 0, 2, 3, 3, 1, 0, 0};
            case CLASSICAL -> new double[]{1, 1, 0, 0, 0, 0, 1, 2, 2, 1};
            case ELECTRONIC -> new double[]{4, 3, 1, 0, -1, 0, 1, 2, 3, 3};
            case ROCK -> new double[]{3, 2, 0, -1, -1, 1, 2, 3, 2, 1};
            default -> new double[10];
        };
    }

    /**
     * A fresh profile recipe. Profiles own the sound: Original's bypass, equalizer, loudness, compressor, limiter,
     * width, bass, reverb and the advanced chain. Track transitions, the game audio mix, Preserve Pitch and parallel
     * analysis stay as the listener set them.
     */
    public static AudioSettings recipe(String name) {
        var r = new AudioSettings();
        r.neutral = name.equals("DEFAULT");
        r.limiter = !r.neutral;
        switch (name) {
            case "MUSIC" -> {
                r.normalization = true;
                r.analyzeLoudness = true;
            }
            // Steady and a little quieter, so music sits under game sounds without the game mix being touched.
            case "GAMING" -> {
                r.compressor = true;
                r.normalization = true;
                r.analyzeLoudness = true;
                r.targetLufs = -21;
            }
            case "VOCAL" -> {
                r.equalizer(Equalizer.VOCAL_CLARITY);
                r.equalizer = true;
            }
            case "NIGHT" -> {
                r.compressor = true;
                r.normalization = true;
                r.analyzeLoudness = true;
            }
            default -> {
            }
        }
        return r;
    }

    /**
     * Copies the values profiles own from {@code from}; everything else, including Preserve Pitch, stays.
     */
    public void applyProfileValues(AudioSettings from) {
        neutral = from.neutral;
        equalizer = from.equalizer;
        bands = from.bands.clone();
        eqPreset = from.eqPreset;
        normalization = from.normalization;
        analyzeLoudness = from.analyzeLoudness;
        targetLufs = from.targetLufs;
        gainMode = from.gainMode;
        compressor = from.compressor;
        limiter = from.limiter;
        width = from.width;
        bass = from.bass;
        reverb = from.reverb;
        boolean preservePitch = enhancements.preservePitch;
        enhancements = from.enhancements.copy();
        enhancements.preservePitch = preservePitch;
    }

    /**
     * Whether the values profiles own match, allowing for slider rounding.
     */
    public boolean sameProfileValues(AudioSettings other) {
        var mine = enhancements.copy();
        var theirs = other.enhancements.copy();
        mine.preservePitch = theirs.preservePitch = false;
        boolean bandsMatch = bands.length == other.bands.length;
        for (int i = 0; bandsMatch && i < bands.length; i++) bandsMatch = close(bands[i], other.bands[i]);
        return neutral == other.neutral && equalizer == other.equalizer && bandsMatch
            && normalization == other.normalization && analyzeLoudness == other.analyzeLoudness
            && close(targetLufs, other.targetLufs) && gainMode == other.gainMode && compressor == other.compressor
            && limiter == other.limiter && close(width, other.width) && close(bass, other.bass) && reverb == other.reverb
            && mine.same(theirs);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /**
     * Names the profile: Custom while a saved profile is being used, otherwise the built-in profile the values match, or
     * Custom. Called on load and whenever settings are applied, so editing a built-in profile switches to Custom and
     * setting it back returns to the profile.
     */
    public void matchProfile() {
        var original = recipe("DEFAULT");
        // Saved with the removed Original / Neutral switch on over other values: those were not heard, so Original stays.
        if (neutral && !sameProfileValues(original)) applyProfileValues(original);
        if (selectedSaved != null && !savedProfiles.containsKey(selectedSaved)) selectedSaved = null;
        if (selectedSaved == null)
            for (String name : PROFILES)
                if (sameProfileValues(recipe(name))) {
                    profile = name;
                    return;
                }
        profile = CUSTOM;
    }

    /**
     * A saved profile, or null when it is missing or unreadable.
     */
    public AudioSettings savedProfile(String name) {
        try {
            var saved = ConfigStore.JSON.fromJson(savedProfiles.get(name), AudioSettings.class);
            saved.validate();
            return saved;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The saved profile being used, or null.
     */
    public String savedName() {
        return selectedSaved;
    }

    /**
     * Writes the current sound back to the saved profile being used.
     */
    public void saveSelected() {
        if (selectedSaved != null && savedProfiles.containsKey(selectedSaved))
            savedProfiles.put(selectedSaved, profileJson());
    }

    /**
     * This sound as a saved profile: everything except the saved list and the selection.
     */
    public String profileJson() {
        var copy = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(this), AudioSettings.class);
        copy.savedProfiles = new LinkedHashMap<>();
        copy.selectedSaved = null;
        return ConfigStore.JSON.toJson(copy);
    }

    /**
     * Applies a saved profile's sound; transitions and the game mix stay as they are.
     */
    public boolean loadSaved(String name) {
        var saved = savedProfile(name);
        if (saved == null) return false;
        applyProfileValues(saved);
        selectedSaved = name;
        matchProfile();
        return true;
    }

    /**
     * Applies a built-in profile, or for Custom a blank start: every enhancement off but adjustable, which no built-in
     * profile matches.
     */
    public void selectProfile(String name) {
        selectedSaved = null;
        if (name.equals(CUSTOM)) {
            var blank = recipe("DEFAULT");
            blank.neutral = false;
            applyProfileValues(blank);
        } else applyProfileValues(recipe(name));
        matchProfile();
    }
}
