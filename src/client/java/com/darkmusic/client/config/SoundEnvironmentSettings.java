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

        public static final Range HIGH_PASS = Range.of(20, 2000, 20);
        public static final Range PRESENCE_FREQUENCY = Range.of(100, 12000, 1200);
        public static final Range PRESENCE_GAIN = Range.of(-6, 6, 0);
        public static final Range DRIVE = Range.of(0, 3, 0);
        public static final Range DISTANCE = Range.of(0, 1, 0);
        public static final Range PAN = Range.of(-1, 1, 0);
        public static final Range ROOM_SIZE = Range.of(.3, 2.5, 1);
        public static final Range DECAY = Range.of(.1, 8, .7);
        public static final Range DAMPING = Range.of(.05, .95, .5);
        public static final Range WET = Range.of(0, .55, 0);
        public static final Range PREDELAY = Range.of(0, .25, 0);
        public static final Range ECHO_TIME = Range.of(.03, 1, .18);
        public static final Range ECHO_GAIN = Range.of(0, .55, 0);
        public static final Range MODULATION_MS = Range.of(0, 3, 0);
        public static final Range MODULATION_HZ = Range.of(.1, 6, .7);
        public static final Range NOISE_DB = Range.of(-72, -30, -50);

        public void validate() {
            if (reverbEngine == null) reverbEngine = AudioEnhancements.ReverbEngine.ALGORITHMIC;
            if (impulse == null) impulse = AudioEnhancements.Impulse.HALL;
            highPass = HIGH_PASS.apply(highPass);
            lowPass = AudioEnhancements.finite(lowPass, Math.max(100, highPass * 1.2), 20000, 20000);
            presenceFrequency = PRESENCE_FREQUENCY.apply(presenceFrequency);
            presenceGain = PRESENCE_GAIN.apply(presenceGain);
            drive = DRIVE.apply(drive);
            distance = DISTANCE.apply(distance);
            pan = PAN.apply(pan);
            roomSize = ROOM_SIZE.apply(roomSize);
            decay = DECAY.apply(decay);
            damping = DAMPING.apply(damping);
            wet = WET.apply(wet);
            predelay = PREDELAY.apply(predelay);
            echoTime = ECHO_TIME.apply(echoTime);
            echoGain = ECHO_GAIN.apply(echoGain);
            modulationMs = MODULATION_MS.apply(modulationMs);
            modulationHz = MODULATION_HZ.apply(modulationHz);
            noiseDb = NOISE_DB.apply(noiseDb);
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

        // Each applies the same range as validate(); the fields stay public for Gson and for reading.
        public void highPass(double value) {
            highPass = HIGH_PASS.apply(value);
        }

        public void presenceFrequency(double value) {
            presenceFrequency = PRESENCE_FREQUENCY.apply(value);
        }

        public void presenceGain(double value) {
            presenceGain = PRESENCE_GAIN.apply(value);
        }

        public void drive(double value) {
            drive = DRIVE.apply(value);
        }

        public void distance(double value) {
            distance = DISTANCE.apply(value);
        }

        public void pan(double value) {
            pan = PAN.apply(value);
        }

        public void roomSize(double value) {
            roomSize = ROOM_SIZE.apply(value);
        }

        public void decay(double value) {
            decay = DECAY.apply(value);
        }

        public void damping(double value) {
            damping = DAMPING.apply(value);
        }

        public void wet(double value) {
            wet = WET.apply(value);
        }

        public void predelay(double value) {
            predelay = PREDELAY.apply(value);
        }

        public void echoTime(double value) {
            echoTime = ECHO_TIME.apply(value);
        }

        public void echoGain(double value) {
            echoGain = ECHO_GAIN.apply(value);
        }

        public void modulationMs(double value) {
            modulationMs = MODULATION_MS.apply(value);
        }

        public void modulationHz(double value) {
            modulationHz = MODULATION_HZ.apply(value);
        }

        public void noiseDb(double value) {
            noiseDb = NOISE_DB.apply(value);
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

    public static final Range INTENSITY = Range.of(0, 1, 1);

    public void validate() {
        if (preset == null) preset = Preset.OFF;
        intensity = INTENSITY.apply(intensity);
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

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void intensity(double value) {
        intensity = INTENSITY.apply(value);
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
        return EnvironmentRecipes.recipe(preset);
    }
}
