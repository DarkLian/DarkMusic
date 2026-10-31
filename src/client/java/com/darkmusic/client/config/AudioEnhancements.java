package com.darkmusic.client.config;

/**
 * Additive controls: missing fields in legacy profiles retain the old signal path.
 */
public final class AudioEnhancements implements Cloneable {
    public EqMode eqMode = EqMode.GRAPHIC;
    public Node[] nodes = nodes(5), dynamic = nodes(2);
    public boolean bassTone, trebleTone, crossfeed, deEsser, preservePitch;
    public double bassGain, trebleGain, bassFrequency = 120, trebleFrequency = 6000;
    public double crossfeedAmount = .2, reverbAmount = 1;
    public double threshold = -18, ratio = 4, attack = .01, release = .2, knee, makeup;
    public double deEsserStrength = .5, deEsserFrequency = 6500, deEsserThreshold = -26;
    public Protection protection = Protection.SOFT;
    public ReverbEngine reverbEngine = ReverbEngine.ALGORITHMIC;
    public Impulse impulse = Impulse.SHORT_ROOM;

    public enum Protection {SOFT, TRUE_PEAK}

    public enum ReverbEngine {ALGORITHMIC, CONVOLUTION}

    public enum Impulse {SHORT_ROOM, HALL, DARK_CAVE}

    public enum EqMode {GRAPHIC, PARAMETRIC}

    public enum Type {PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS}

    public static final class Node implements Cloneable {
        public boolean enabled;
        public Type type = Type.PEAK;
        public double frequency = 1000, gain, q = 1.4;
        public double threshold = -24, range = -6, attack = .01, release = .15;

        public Node copy() {
            try {
                return (Node) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        public boolean same(Node b) {
            return b != null && enabled == b.enabled && type == b.type && frequency == b.frequency
                && gain == b.gain && q == b.q && threshold == b.threshold && range == b.range
                && attack == b.attack && release == b.release;
        }

        public static final Range FREQUENCY = Range.of(20, 20000, 1000);
        public static final Range GAIN = Range.of(-12, 12, 0);
        public static final Range Q = Range.of(.25, 8, 1.4);
        public static final Range THRESHOLD = Range.of(-60, 0, -24);
        public static final Range RANGE = Range.of(-12, 6, -6);
        public static final Range ATTACK = Range.of(.001, .2, .01);
        public static final Range RELEASE = Range.of(.02, 2, .15);

        public void validate() {
            if (type == null || type == Type.BAND_PASS) type = Type.PEAK;
            frequency = FREQUENCY.apply(frequency);
            gain = GAIN.apply(gain);
            q = Q.apply(q);
            threshold = THRESHOLD.apply(threshold);
            range = RANGE.apply(range);
            attack = ATTACK.apply(attack);
            release = RELEASE.apply(release);
        }

        // Each applies the same range as validate(); the fields stay public for Gson and for reading.
        public void frequency(double value) {
            frequency = FREQUENCY.apply(value);
        }

        public void gain(double value) {
            gain = GAIN.apply(value);
        }

        public void q(double value) {
            q = Q.apply(value);
        }

        public void threshold(double value) {
            threshold = THRESHOLD.apply(value);
        }

        public void range(double value) {
            range = RANGE.apply(value);
        }

        public void attack(double value) {
            attack = ATTACK.apply(value);
        }

        public void release(double value) {
            release = RELEASE.apply(value);
        }
    }

    private static Node[] nodes(int count) {
        Node[] result = new Node[count];
        for (int i = 0; i < count; i++) {
            result[i] = new Node();
            result[i].frequency = 80 * Math.pow(100, i / (double) Math.max(1, count - 1));
        }
        return result;
    }

    private static Node[] validated(Node[] source, int count) {
        Node[] result = nodes(count);
        if (source != null) for (int i = 0; i < Math.min(count, source.length); i++)
            if (source[i] != null) result[i] = source[i];
        for (Node n : result) n.validate();
        return result;
    }

    public static final Range BASS_GAIN = Range.of(-12, 12, 0);
    public static final Range TREBLE_GAIN = Range.of(-12, 12, 0);
    public static final Range BASS_FREQUENCY = Range.of(30, 500, 120);
    public static final Range TREBLE_FREQUENCY = Range.of(1000, 16000, 6000);
    public static final Range CROSSFEED_AMOUNT = Range.of(0, .5, .2);
    public static final Range REVERB_AMOUNT = Range.of(0, 1, 1);
    public static final Range THRESHOLD = Range.of(-60, 0, -18);
    public static final Range RATIO = Range.of(1, 20, 4);
    public static final Range ATTACK = Range.of(.001, .2, .01);
    public static final Range RELEASE = Range.of(.02, 2, .2);
    public static final Range KNEE = Range.of(0, 24, 0);
    public static final Range MAKEUP = Range.of(0, 12, 0);
    public static final Range DE_ESSER_STRENGTH = Range.of(0, 1, .5);
    public static final Range DE_ESSER_FREQUENCY = Range.of(2000, 12000, 6500);
    public static final Range DE_ESSER_THRESHOLD = Range.of(-60, -6, -26);

    public void validate() {
        if (protection == null) protection = Protection.SOFT;
        if (reverbEngine == null) reverbEngine = ReverbEngine.ALGORITHMIC;
        if (impulse == null) impulse = Impulse.SHORT_ROOM;
        if (eqMode == null) eqMode = EqMode.GRAPHIC;
        nodes = validated(nodes, 5);
        dynamic = validated(dynamic, 2);
        bassGain = BASS_GAIN.apply(bassGain);
        trebleGain = TREBLE_GAIN.apply(trebleGain);
        bassFrequency = BASS_FREQUENCY.apply(bassFrequency);
        trebleFrequency = TREBLE_FREQUENCY.apply(trebleFrequency);
        crossfeedAmount = CROSSFEED_AMOUNT.apply(crossfeedAmount);
        reverbAmount = REVERB_AMOUNT.apply(reverbAmount);
        threshold = THRESHOLD.apply(threshold);
        ratio = RATIO.apply(ratio);
        attack = ATTACK.apply(attack);
        release = RELEASE.apply(release);
        knee = KNEE.apply(knee);
        makeup = MAKEUP.apply(makeup);
        deEsserStrength = DE_ESSER_STRENGTH.apply(deEsserStrength);
        deEsserFrequency = DE_ESSER_FREQUENCY.apply(deEsserFrequency);
        deEsserThreshold = DE_ESSER_THRESHOLD.apply(deEsserThreshold);
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void bassGain(double value) {
        bassGain = BASS_GAIN.apply(value);
    }

    public void trebleGain(double value) {
        trebleGain = TREBLE_GAIN.apply(value);
    }

    public void bassFrequency(double value) {
        bassFrequency = BASS_FREQUENCY.apply(value);
    }

    public void trebleFrequency(double value) {
        trebleFrequency = TREBLE_FREQUENCY.apply(value);
    }

    public void crossfeedAmount(double value) {
        crossfeedAmount = CROSSFEED_AMOUNT.apply(value);
    }

    public void reverbAmount(double value) {
        reverbAmount = REVERB_AMOUNT.apply(value);
    }

    public void threshold(double value) {
        threshold = THRESHOLD.apply(value);
    }

    public void ratio(double value) {
        ratio = RATIO.apply(value);
    }

    public void attack(double value) {
        attack = ATTACK.apply(value);
    }

    public void release(double value) {
        release = RELEASE.apply(value);
    }

    public void knee(double value) {
        knee = KNEE.apply(value);
    }

    public void makeup(double value) {
        makeup = MAKEUP.apply(value);
    }

    public void deEsserStrength(double value) {
        deEsserStrength = DE_ESSER_STRENGTH.apply(value);
    }

    public void deEsserFrequency(double value) {
        deEsserFrequency = DE_ESSER_FREQUENCY.apply(value);
    }

    public void deEsserThreshold(double value) {
        deEsserThreshold = DE_ESSER_THRESHOLD.apply(value);
    }

    public AudioEnhancements copy() {
        try {
            var result = (AudioEnhancements) clone();
            result.nodes = copyNodes(nodes, 5);
            result.dynamic = copyNodes(dynamic, 2);
            result.validate();
            return result;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    private static Node[] copyNodes(Node[] source, int count) {
        Node[] result = nodes(count);
        if (source != null) for (int i = 0; i < Math.min(source.length, count); i++)
            if (source[i] != null) result[i] = source[i].copy();
        return result;
    }

    public boolean same(AudioEnhancements b) {
        if (b == null || protection != b.protection || reverbEngine != b.reverbEngine || impulse != b.impulse
            || eqMode != b.eqMode || bassTone != b.bassTone || trebleTone != b.trebleTone
            || crossfeed != b.crossfeed || deEsser != b.deEsser || preservePitch != b.preservePitch
            || bassGain != b.bassGain || trebleGain != b.trebleGain || bassFrequency != b.bassFrequency
            || trebleFrequency != b.trebleFrequency || crossfeedAmount != b.crossfeedAmount || reverbAmount != b.reverbAmount
            || threshold != b.threshold || ratio != b.ratio || attack != b.attack || release != b.release
            || knee != b.knee || makeup != b.makeup || deEsserStrength != b.deEsserStrength
            || deEsserFrequency != b.deEsserFrequency || deEsserThreshold != b.deEsserThreshold) return false;
        if (nodes == null || b.nodes == null || dynamic == null || b.dynamic == null
            || nodes.length != b.nodes.length || dynamic.length != b.dynamic.length) return false;
        for (int i = 0; i < nodes.length; i++) if (nodes[i] == null || !nodes[i].same(b.nodes[i])) return false;
        for (int i = 0; i < dynamic.length; i++) if (dynamic[i] == null || !dynamic[i].same(b.dynamic[i])) return false;
        return true;
    }

    public static double finite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
