package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;

/**
 * Worker-owned, allocation-free sample processing. All optional coloration bypasses in Neutral.
 */
public final class AudioProcessor {
    public static final double[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private final double[][] x1 = new double[10][2], x2 = new double[10][2], y1 = new double[10][2], y2 = new double[10][2];
    private final double[][] coefficients = new double[10][5];
    private final double[] smoothed = new double[10];
    private RoomReverb room;
    private int channels;
    private double rate, gain = 1, envelope, compressorGain = 1, duck = 1;
    private AudioSettings settings = new AudioSettings();

    public void configure(AudioSettings value) {
        settings = value;
    }

    public void begin(double sampleRate, int channelCount) {
        rate = sampleRate;
        channels = channelCount;
        envelope = 0;
        compressorGain = 1;
        room = null;
        for (int i = 0; i < 10; i++) {
            java.util.Arrays.fill(x1[i], 0);
            java.util.Arrays.fill(x2[i], 0);
            java.util.Arrays.fill(y1[i], 0);
            java.util.Arrays.fill(y2[i], 0);
            smoothed[i] = 0;
        }
    }

    public void process(float[] pcm, int count, double volume, double normalization) {
        var s = settings;
        boolean effects = !s.neutral;
        double target = volume * (effects && s.normalization ? normalization : 1);
        if (s.listening == AudioSettings.Listening.AMBIENT) target *= s.ambientGain;
        boolean eq = effects && (s.equalizer || s.bass > 0);
        double maxBoost = 0;
        if (eq) for (int b = 0; b < 10; b++) {
            double desired = (s.equalizer ? s.bands[b] : 0) + (b < 3 ? s.bass * (3 - b) / 3 : 0);
            smoothed[b] += (desired - smoothed[b]) * (1 - Math.exp(-count / (channels * rate * .04)));
            maxBoost = Math.max(maxBoost, Math.max(desired, smoothed[b]));
            peak(b, Math.min(FREQUENCIES[b], rate * .43), smoothed[b]);
        }
        // Reserve headroom before the filter cascade; do not normalize back into clipping.
        double headroom = eq ? Math.pow(10, -Math.max(maxBoost, responsePeak()) / 20) : 1;
        if (effects && s.reverb != AudioSettings.Reverb.DRY && (room == null || room.mode != s.reverb))
            room = new RoomReverb(rate, s.reverb);
        if (!effects || s.reverb == AudioSettings.Reverb.DRY) room = null;
        double smooth = 1 - Math.exp(-1 / (rate * .02));
        double duckTarget = s.ducking && ListeningMix.ducking() ? s.duckGain : 1;
        double duckSmoothing = 1 - Math.exp(-1 / (rate * (duckTarget < duck ? s.duckAttack : s.duckRelease)));
        for (int i = 0; i < count; i += channels) {
            gain += (target - gain) * smooth;
            duck += (duckTarget - duck) * duckSmoothing;
            double left = pcm[i] * headroom, right = channels == 2 ? pcm[i + 1] * headroom : left;
            if (eq) {
                left = equalize(left, 0);
                if (channels == 2) right = equalize(right, 1);
                else right = left;
            }
            if (effects && channels == 2 && s.width != 1) {
                double mid = (left + right) * .5, side = (left - right) * .5 * s.width;
                double trim = 1 / Math.max(1, s.width);
                left = (mid + side) * trim;
                right = (mid - side) * trim;
            }
            if (room != null) {
                left = room.sample(left, 0);
                right = channels == 2 ? room.sample(right, 1) : left;
            }
            left *= gain * duck;
            right *= gain * duck;
            if (effects && s.compressor) {
                double peak = Math.max(Math.abs(left), Math.abs(right));
                double alpha = 1 - Math.exp(-1 / (rate * (peak > envelope ? .01 : .2)));
                envelope += (peak - envelope) * alpha;
                double db = 20 * Math.log10(Math.max(1e-9, envelope));
                double desired = db > -18 ? Math.pow(10, (-18 + (db + 18) / 4 - db) / 20) : 1;
                compressorGain += (desired - compressorGain) * (1 - Math.exp(-1 / (rate * .01)));
                left *= compressorGain;
                right *= compressorGain;
            }
            if (effects && s.limiter) {
                left = limit(left);
                right = limit(right);
            }
            pcm[i] = (float) (Double.isFinite(left) ? left : 0);
            if (channels == 2) pcm[i + 1] = (float) (Double.isFinite(right) ? right : 0);
        }
    }

    private static double limit(double x) {
        double a = Math.abs(x);
        return a <= .95 ? x : Math.copySign(.95 + .04 * Math.tanh((a - .95) / .04), x);
    }

    public void initialVolume(double value) {
        gain = Math.clamp(value, 0, 1);
    }

    private double responsePeak() {
        double peak = 0;
        for (int i = 0; i < 96; i++) {
            double w = 2 * Math.PI * (20 * Math.pow(Math.min(20000, rate * .45) / 20, i / 95.0)) / rate, c = Math.cos(w), c2 = Math.cos(2 * w), db = 0;
            for (double[] q : coefficients) {
                double numerator = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + 2 * (q[0] * q[1] + q[1] * q[2]) * c + 2 * q[0] * q[2] * c2;
                double denominator = 1 + q[3] * q[3] + q[4] * q[4] + 2 * (q[3] + q[3] * q[4]) * c + 2 * q[4] * c2;
                db += 10 * Math.log10(Math.max(1e-12, numerator) / Math.max(1e-12, denominator));
            }
            peak = Math.max(peak, db);
        }
        return peak > .01 ? peak + .5 : 0;
    }

    private double equalize(double x, int c) {
        for (int b = 0; b < 10; b++) {
            double[] q = coefficients[b];
            double y = q[0] * x + q[1] * x1[b][c] + q[2] * x2[b][c] - q[3] * y1[b][c] - q[4] * y2[b][c];
            x2[b][c] = x1[b][c];
            x1[b][c] = x;
            y2[b][c] = y1[b][c];
            y1[b][c] = y;
            x = y;
        }
        return x;
    }

    private void peak(int b, double hz, double db) {
        double a = Math.pow(10, db / 40), w = 2 * Math.PI * hz / rate, alpha = Math.sin(w) / (2 * 1.4), c = Math.cos(w), d = 1 + alpha / a;
        double[] q = coefficients[b];
        q[0] = (1 + alpha * a) / d;
        q[1] = -2 * c / d;
        q[2] = (1 - alpha * a) / d;
        q[3] = -2 * c / d;
        q[4] = (1 - alpha / a) / d;
    }

    public static void quantize(float[] input, byte[] out, int count) {
        quantize(input, out, count, 2);
    }

    public static void quantize(float[] input, byte[] out, int count, int sampleBytes) {
        if (sampleBytes != 2 && sampleBytes != 3) throw new IllegalArgumentException("Unsupported output precision");
        long scale = 1L << (sampleBytes * 8 - 1);
        for (int i = 0; i < count; i++) {
            int n = (int) Math.clamp(Math.round(input[i] * scale), -scale, scale - 1);
            for (int b = 0; b < sampleBytes; b++) out[sampleBytes * i + b] = (byte) (n >> (b * 8));
        }
    }
}
