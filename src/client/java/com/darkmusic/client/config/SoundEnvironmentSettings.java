package com.darkmusic.client.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent creative music coloration; never part of an Audio Options profile.
 */
public final class SoundEnvironmentSettings {
    public boolean enabled, custom, texture, ambience;
    public Preset preset = Preset.OFF;
    public double intensity = 1;
    public Parameters parameters = recipe(Preset.OFF);
    public Map<String, Saved> userPresets = new LinkedHashMap<>();
    // The saved environment being used; edits stay with it and Save writes them back. Null for built-ins and Custom.
    public String selectedSaved;

    public enum Preset {
        OFF, OLD_RADIO, TELEPHONE, WALKIE_TALKIE, VINYL, CASSETTE, BEDROOM, BATHROOM,
        CONCERT_HALL, CATHEDRAL, DEEP_CAVE, TUNNEL, WAREHOUSE, STADIUM, OPEN_MEADOW,
        FOREST_CLEARING, NEXT_ROOM, DISTANT_SPEAKERS, UNDERWATER, DREAMSCAPE,
        CAR_CABIN, STAIRWELL, ORBITAL_ECHO,
        // A blank recipe for building a new environment; it has no description and is not listed with the built-ins.
        CUSTOM
    }

    public static final List<Preset> BUILT_IN = Arrays.stream(Preset.values()).filter(p -> p != Preset.CUSTOM).toList();

    public static final class Parameters implements Cloneable {
        public AudioEnhancements.ReverbEngine reverbEngine = AudioEnhancements.ReverbEngine.ALGORITHMIC;
        public AudioEnhancements.Impulse impulse = AudioEnhancements.Impulse.HALL;
        public double highPass = 20, lowPass = 20000, presenceFrequency = 1200, presenceGain;
        public double drive, distance, pan, roomSize = 1, decay = .7, damping = .5, wet, predelay;
        public double echoTime = .18, echoGain, modulationMs, modulationHz = .7, noiseDb = -50;
        public boolean mono, acoustic;
        public double[] reflectionTimes = {.021, .037, .061}, reflectionGains = {0, 0, 0};

        public Parameters copy() {
            try {
                var p = (Parameters) clone();
                p.reflectionTimes = reflectionTimes == null ? null : reflectionTimes.clone();
                p.reflectionGains = reflectionGains == null ? null : reflectionGains.clone();
                p.validate();
                return p;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        /**
         * Like same, allowing for slider rounding.
         */
        public boolean close(Parameters p) {
            return p != null && reverbEngine == p.reverbEngine && impulse == p.impulse && near(highPass, p.highPass) && near(lowPass, p.lowPass)
                && near(presenceFrequency, p.presenceFrequency) && near(presenceGain, p.presenceGain)
                && near(drive, p.drive) && near(distance, p.distance) && near(pan, p.pan) && near(roomSize, p.roomSize)
                && near(decay, p.decay) && near(damping, p.damping) && near(wet, p.wet) && near(predelay, p.predelay)
                && near(echoTime, p.echoTime) && near(echoGain, p.echoGain) && near(modulationMs, p.modulationMs)
                && near(modulationHz, p.modulationHz) && near(noiseDb, p.noiseDb) && mono == p.mono && acoustic == p.acoustic
                && near(reflectionTimes, p.reflectionTimes) && near(reflectionGains, p.reflectionGains);
        }

        private static boolean near(double a, double b) {
            return Math.abs(a - b) < 1e-6;
        }

        private static boolean near(double[] a, double[] b) {
            if (a == null || b == null || a.length != b.length) return a == b;
            for (int i = 0; i < a.length; i++) if (!near(a[i], b[i])) return false;
            return true;
        }

        public boolean same(Parameters p) {
            return p != null && reverbEngine == p.reverbEngine && impulse == p.impulse && highPass == p.highPass && lowPass == p.lowPass
                && presenceFrequency == p.presenceFrequency && presenceGain == p.presenceGain
                && drive == p.drive && distance == p.distance && pan == p.pan && roomSize == p.roomSize
                && decay == p.decay && damping == p.damping && wet == p.wet && predelay == p.predelay
                && echoTime == p.echoTime && echoGain == p.echoGain && modulationMs == p.modulationMs
                && modulationHz == p.modulationHz && noiseDb == p.noiseDb && mono == p.mono && acoustic == p.acoustic
                && Arrays.equals(reflectionTimes, p.reflectionTimes)
                && Arrays.equals(reflectionGains, p.reflectionGains);
        }

        public void validate() {
            if (reverbEngine == null) reverbEngine = AudioEnhancements.ReverbEngine.ALGORITHMIC;
            if (impulse == null) impulse = AudioEnhancements.Impulse.HALL;
            highPass = AudioEnhancements.finite(highPass, 20, 2000, 20);
            lowPass = AudioEnhancements.finite(lowPass, Math.max(100, highPass * 1.2), 20000, 20000);
            presenceFrequency = AudioEnhancements.finite(presenceFrequency, 100, 12000, 1200);
            presenceGain = AudioEnhancements.finite(presenceGain, -6, 6, 0);
            drive = AudioEnhancements.finite(drive, 0, 3, 0);
            distance = AudioEnhancements.finite(distance, 0, 1, 0);
            pan = AudioEnhancements.finite(pan, -1, 1, 0);
            roomSize = AudioEnhancements.finite(roomSize, .3, 2.5, 1);
            decay = AudioEnhancements.finite(decay, .1, 8, .7);
            damping = AudioEnhancements.finite(damping, .05, .95, .5);
            wet = AudioEnhancements.finite(wet, 0, .55, 0);
            predelay = AudioEnhancements.finite(predelay, 0, .25, 0);
            echoTime = AudioEnhancements.finite(echoTime, .03, 1, .18);
            echoGain = AudioEnhancements.finite(echoGain, 0, .55, 0);
            modulationMs = AudioEnhancements.finite(modulationMs, 0, 3, 0);
            modulationHz = AudioEnhancements.finite(modulationHz, .1, 6, .7);
            noiseDb = AudioEnhancements.finite(noiseDb, -72, -30, -50);
            if (reflectionTimes == null || reflectionGains == null) {
                reflectionTimes = new double[]{.021, .037, .061};
                reflectionGains = new double[]{0, 0, 0};
            }
            int count = Math.min(8, Math.min(reflectionTimes.length, reflectionGains.length));
            reflectionTimes = Arrays.copyOf(reflectionTimes, count);
            reflectionGains = Arrays.copyOf(reflectionGains, count);
            for (int i = 0; i < count; i++) {
                reflectionTimes[i] = AudioEnhancements.finite(reflectionTimes[i], .001, 1.6, .02);
                reflectionGains[i] = AudioEnhancements.finite(reflectionGains[i], -.35, .35, 0);
            }
        }
    }

    public static final class Saved {
        public Preset preset = Preset.OFF;
        public double intensity = 1;
        public boolean texture, ambience;
        public Parameters parameters = recipe(Preset.OFF);
    }

    /**
     * Applies a built-in environment's recipe, also when it is already selected.
     */
    public void select(Preset value) {
        apply(value);
        selectedSaved = null;
    }

    /**
     * Starts a blank Custom recipe for building a new environment.
     */
    public void selectCustom() {
        apply(Preset.CUSTOM);
        selectedSaved = null;
    }

    private void apply(Preset value) {
        preset = value;
        enabled = value != Preset.OFF;
        parameters = recipe(value);
        intensity = 1;
        custom = value == Preset.CUSTOM;
        texture = ambience = false;
    }

    private void load(Saved s) {
        preset = s.preset;
        intensity = s.intensity;
        parameters = s.parameters.copy();
        texture = s.texture;
        ambience = s.ambience;
        enabled = preset != Preset.OFF;
    }

    private Saved snapshot() {
        var s = new Saved();
        s.preset = preset;
        s.intensity = intensity;
        s.parameters = parameters.copy();
        s.texture = texture;
        s.ambience = ambience;
        return s;
    }

    /**
     * Marks the recipe Custom when it is the blank Custom or a built-in whose values were changed; a saved environment
     * stays itself while it is edited. Called on load and whenever settings are applied.
     */
    public void matchPreset() {
        // Saved with the removed Enable switch off: nothing was playing, so nothing plays.
        if (!enabled && preset != Preset.OFF) apply(Preset.OFF);
        enabled = preset != Preset.OFF;
        if (selectedSaved != null && !userPresets.containsKey(selectedSaved)) selectedSaved = null;
        custom = selectedSaved == null && preset != Preset.OFF && (preset == Preset.CUSTOM || texture || ambience
            || Math.abs(intensity - 1) >= 1e-6 || !parameters.close(recipe(preset)));
    }

    /**
     * The saved environment being used, or null.
     */
    public String savedName() {
        return selectedSaved;
    }

    public void validate() {
        if (preset == null) preset = Preset.OFF;
        intensity = AudioEnhancements.finite(intensity, 0, 1, 1);
        if (parameters == null) parameters = recipe(preset);
        parameters.validate();
        if (userPresets == null) userPresets = new LinkedHashMap<>();
        var clean = new LinkedHashMap<String, Saved>();
        for (var e : userPresets.entrySet()) {
            if (clean.size() == 32) break;
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 80 || e.getValue() == null)
                continue;
            var s = e.getValue();
            if (s.preset == null) s.preset = Preset.OFF;
            s.intensity = AudioEnhancements.finite(s.intensity, 0, 1, 1);
            if (s.parameters == null) s.parameters = recipe(s.preset);
            s.parameters.validate();
            clean.put(e.getKey(), s);
        }
        userPresets = clean;
        if (selectedSaved != null && !userPresets.containsKey(selectedSaved)) selectedSaved = null;
    }

    public SoundEnvironmentSettings playbackCopy() {
        var s = new SoundEnvironmentSettings();
        s.enabled = enabled;
        s.preset = preset;
        s.intensity = intensity;
        s.texture = texture;
        s.ambience = ambience;
        s.parameters = parameters == null ? recipe(preset == null ? Preset.OFF : preset) : parameters.copy();
        s.validate();
        return s;
    }

    public boolean samePlayback(SoundEnvironmentSettings s) {
        return s != null && enabled == s.enabled && preset == s.preset && intensity == s.intensity
            && texture == s.texture && ambience == s.ambience && parameters.same(s.parameters);
    }

    public boolean active() {
        return enabled && preset != Preset.OFF && intensity > 0;
    }

    public boolean ownsReverb() {
        return active() && parameters.acoustic;
    }

    public void savePreset(String name) {
        savePreset(name, false);
    }

    /**
     * Saves the current recipe as a new saved environment, which becomes the one being used.
     */
    public void savePreset(String name, boolean replace) {
        PresetNames.save(userPresets, name, snapshot(), PresetNames.ENVIRONMENT_LIMIT, replace);
        if (userPresets.containsKey(name)) selectedSaved = name;
        matchPreset();
    }

    /**
     * Writes the current recipe back to the saved environment being used.
     */
    public void saveSelected() {
        if (selectedSaved != null && userPresets.containsKey(selectedSaved)) userPresets.put(selectedSaved, snapshot());
    }

    public void loadPreset(String name) {
        var s = userPresets.get(name);
        if (s == null) return;
        load(s);
        selectedSaved = name;
        matchPreset();
    }

    /**
     * Fresh parameter copies keep built-ins immutable and give every preset a distinct recipe.
     */
    public static Parameters recipe(Preset preset) {
        var p = new Parameters();
        switch (preset) {
            case OLD_RADIO -> {
                p.mono = true;
                p.highPass = 250;
                p.lowPass = 4200;
                p.presenceGain = 3;
                p.drive = 1.3;
                p.modulationMs = .25;
                p.noiseDb = -48;
            }
            case TELEPHONE -> {
                p.mono = true;
                p.highPass = 320;
                p.lowPass = 3300;
                p.presenceFrequency = 1600;
                p.presenceGain = 2;
                p.drive = .6;
                p.noiseDb = -64;
            }
            case WALKIE_TALKIE -> {
                p.mono = true;
                p.highPass = 430;
                p.lowPass = 2800;
                p.presenceFrequency = 2200;
                p.presenceGain = 4;
                p.drive = 2;
                p.noiseDb = -51;
            }
            case VINYL -> {
                p.highPass = 35;
                p.lowPass = 15500;
                p.drive = .15;
                p.modulationMs = .35;
                p.modulationHz = .55;
                p.noiseDb = -52;
            }
            case CASSETTE -> {
                p.highPass = 45;
                p.lowPass = 11500;
                p.drive = .75;
                p.modulationMs = .8;
                p.modulationHz = 1.2;
                p.noiseDb = -46;
            }
            case BEDROOM ->
                space(p, .65, .35, .68, .10, .008, new double[]{.009, .017, .026}, new double[]{.10, .07, .04});
            case BATHROOM -> {
                space(p, .55, .8, .12, .20, .004, new double[]{.006, .013, .019, .031}, new double[]{.13, .11, .09, .05});
                p.presenceFrequency = 2800;
                p.presenceGain = 1;
            }
            case CONCERT_HALL ->
                space(p, 1.5, 2.1, .42, .23, .035, new double[]{.033, .057, .083}, new double[]{.07, .06, .04});
            case CATHEDRAL ->
                space(p, 2.2, 5.5, .32, .32, .085, new double[]{.07, .113, .179, .251}, new double[]{.07, .06, .05, .04});
            case DEEP_CAVE -> {
                space(p, 1.9, 4.1, .82, .28, .065, new double[]{.103, .237, .389}, new double[]{.12, .09, .06});
                p.lowPass = 6500;
                p.echoTime = .43;
                p.echoGain = .27;
            }
            case TUNNEL -> {
                space(p, 1.3, 1.7, .57, .15, .02, new double[]{.12, .24, .36}, new double[]{.16, .10, .06});
                p.echoTime = .26;
                p.echoGain = .38;
                p.pan = -.1;
            }
            case WAREHOUSE -> {
                space(p, 1.7, 1.5, .26, .20, .024, new double[]{.047, .091, .143}, new double[]{.15, .10, .06});
                p.echoTime = .15;
                p.echoGain = .16;
            }
            case STADIUM -> {
                space(p, 2.0, 2.5, .65, .12, .12, new double[]{.21, .47, .79}, new double[]{.10, .08, .05});
                p.echoTime = .62;
                p.echoGain = .32;
                p.distance = .3;
            }
            case OPEN_MEADOW -> {
                p.acoustic = true;
                p.lowPass = 14000;
                p.distance = .15;
                p.noiseDb = -58;
            }
            case FOREST_CLEARING -> {
                p.acoustic = true;
                p.lowPass = 11000;
                p.distance = .18;
                p.reflectionTimes = new double[]{.052, .117, .193};
                p.reflectionGains = new double[]{.045, .025, .012};
                p.noiseDb = -60;
            }
            case NEXT_ROOM -> {
                space(p, .8, .45, .85, .10, .016, new double[]{.014, .038, .059}, new double[]{.05, .03, .02});
                p.lowPass = 1100;
                p.distance = .55;
                p.highPass = 35;
            }
            case DISTANT_SPEAKERS -> {
                p.acoustic = true;
                p.lowPass = 4800;
                p.highPass = 90;
                p.distance = .65;
                p.echoTime = .32;
                p.echoGain = .06;
            }
            case UNDERWATER -> {
                p.lowPass = 750;
                p.presenceFrequency = 350;
                p.presenceGain = 2;
                p.modulationMs = 1.3;
                p.modulationHz = .35;
                p.noiseDb = -58;
            }
            case DREAMSCAPE -> {
                space(p, 1.5, 2.8, .65, .25, .045, new double[]{.08, .137, .221}, new double[]{.10, .07, .05});
                p.lowPass = 10000;
                p.modulationMs = 2.2;
                p.modulationHz = .3;
                p.echoGain = .16;
                p.echoTime = .37;
            }
            case CAR_CABIN -> {
                space(p, .45, .22, .78, .08, .003, new double[]{.004, .009, .016}, new double[]{.13, .08, .04});
                p.highPass = 35;
                p.lowPass = 8500;
                p.presenceFrequency = 150;
                p.presenceGain = 3.5;
            }
            case STAIRWELL -> {
                space(p, 1.15, 2.1, .22, .17, .012, new double[]{.061, .127, .263, .521}, new double[]{.15, -.10, .075, -.035});
                p.highPass = 80;
                p.lowPass = 15500;
                p.echoTime = .185;
                p.echoGain = .21;
            }
            case ORBITAL_ECHO -> {
                space(p, 1.8, 3.4, .42, .22, .075, new double[]{.173, .347, .691}, new double[]{.075, -.055, .035});
                p.highPass = 100;
                p.lowPass = 12500;
                p.modulationMs = .8;
                p.modulationHz = .12;
                p.echoTime = .73;
                p.echoGain = .32;
            }
            default -> {
            }
        }
        return p;
    }

    private static void space(Parameters p, double size, double decay, double damping, double wet, double predelay, double[] times, double[] gains) {
        p.acoustic = true;
        p.roomSize = size;
        p.decay = decay;
        p.damping = damping;
        p.wet = wet;
        p.predelay = predelay;
        p.reflectionTimes = times;
        p.reflectionGains = gains;
    }
}
