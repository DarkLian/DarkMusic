package com.darkmusic.client.audio;

import java.util.Arrays;

/**
 * Worker-owned FFT of post-volume, post-speed PCM, published at the device playback clock.
 */
public final class SpectrumAnalyzer {
    public static final int MAX_BARS = 64, SIZE = 2048;
    private static final int HOP = SIZE / 2, QUEUE = 64;
    private final double[] samples = new double[SIZE], real = new double[SIZE], imaginary = new double[SIZE], window = new double[SIZE];
    private final float[][] pending = new float[QUEUE][MAX_BARS];
    private final long[] due = new long[QUEUE];
    private final float[] target = new float[MAX_BARS], levels = new float[MAX_BARS];
    private volatile Options options = new Options(false, 24, 1, .6);
    private Options applied = options;
    private int cursor, filled, hop, head, count, channels;
    private double sampleRate;
    private long clock;

    public SpectrumAnalyzer() {
        for (int i = 0; i < SIZE; i++) window[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (SIZE - 1));
    }

    public void configure(boolean enabled, int bars, double sensitivity, double smoothing) {
        bars = Math.clamp(bars, 8, MAX_BARS);
        sensitivity = Double.isFinite(sensitivity) ? Math.clamp(sensitivity, .25, 4) : 1;
        smoothing = Double.isFinite(smoothing) ? Math.clamp(smoothing, 0, .95) : .6;
        Options old = options;
        if (old.enabled != enabled || old.bars != bars || old.sensitivity != sensitivity || old.smoothing != smoothing)
            options = new Options(enabled, bars, sensitivity, smoothing);
    }

    /**
     * Called only by the audio worker when opening/releasing a device.
     */
    public void begin(double rate, int channelCount) {
        clear();
        sampleRate = rate;
        channels = channelCount;
    }

    public void clear() {
        cursor = filled = hop = head = count = 0;
        clock = 0;
        Arrays.fill(samples, 0);
        Arrays.fill(target, 0);
        synchronized (levels) {
            Arrays.fill(levels, 0);
        }
    }

    /**
     * Only successfully written signed 16-bit little-endian output frames enter analysis.
     */
    public void accept(byte[] pcm, int offset, int length, long firstFrame) {
        Options selected = options;
        if (selected != applied) {
            clear();
            applied = selected;
            clock = firstFrame;
        }
        if (!selected.enabled || channels < 1 || sampleRate <= 0) return;
        int frameSize = channels * 2;
        for (int position = offset; position + frameSize <= offset + length; position += frameSize) {
            double sample = 0;
            for (int channel = 0; channel < channels; channel++) {
                int p = position + channel * 2;
                sample += (short) ((pcm[p] & 255) | pcm[p + 1] << 8) / 32768.0;
            }
            samples[cursor] = sample / channels;
            cursor = (cursor + 1) % SIZE;
            filled = Math.min(SIZE, filled + 1);
            hop++;
            if (filled == SIZE && hop >= HOP) {
                hop = 0;
                analyze(selected, firstFrame + (position - offset) / frameSize + 1 - SIZE / 2);
            }
        }
    }

    private void analyze(Options selected, long outputFrame) {
        for (int i = 0; i < SIZE; i++) {
            real[i] = samples[(cursor + i) % SIZE] * window[i];
            imaginary[i] = 0;
        }
        for (int i = 1, j = 0; i < SIZE; i++) {
            int bit = SIZE >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double value = real[i];
                real[i] = real[j];
                real[j] = value;
            }
        }
        for (int size = 2; size <= SIZE; size <<= 1) {
            double angle = -2 * Math.PI / size, wr = Math.cos(angle), wi = Math.sin(angle);
            for (int block = 0; block < SIZE; block += size) {
                double ar = 1, ai = 0;
                for (int j = 0; j < size / 2; j++) {
                    int a = block + j, b = a + size / 2;
                    double br = real[b] * ar - imaginary[b] * ai, bi = real[b] * ai + imaginary[b] * ar;
                    real[b] = real[a] - br;
                    imaginary[b] = imaginary[a] - bi;
                    real[a] += br;
                    imaginary[a] += bi;
                    double next = ar * wr - ai * wi;
                    ai = ar * wi + ai * wr;
                    ar = next;
                }
            }
        }
        if (count == QUEUE) {
            head = (head + 1) % QUEUE;
            count--;
        }
        int slot = (head + count++) % QUEUE;
        due[slot] = outputFrame;
        Arrays.fill(pending[slot], 0);
        double upper = Math.min(16_000, sampleRate / 2);
        for (int band = 0; band < selected.bars; band++) {
            double low = 30 * Math.pow(upper / 30, band / (double) selected.bars);
            double high = 30 * Math.pow(upper / 30, (band + 1.0) / selected.bars);
            int from = Math.clamp((int) Math.floor(low * SIZE / sampleRate), 1, SIZE / 2 - 1);
            int to = Math.clamp((int) Math.ceil(high * SIZE / sampleRate), from + 1, SIZE / 2);
            double peak = 0;
            for (int bin = from; bin < to; bin++)
                peak = Math.max(peak, Math.hypot(real[bin], imaginary[bin]) * 4 / SIZE);
            pending[slot][band] = (float) Math.clamp((20 * Math.log10(Math.max(1e-8, peak * selected.sensitivity)) + 65) / 65, 0, 1);
        }
    }

    /**
     * Freeze on pause; do not show samples queued ahead of the audible device position.
     */
    public void advance(long playedFrame, boolean playing) {
        if (!options.enabled) {
            if (filled != 0 || count != 0 || applied.enabled) {
                clear();
                applied = options;
            }
            return;
        }
        if (!playing) return;
        while (count > 0 && due[head] <= playedFrame) {
            System.arraycopy(pending[head], 0, target, 0, MAX_BARS);
            head = (head + 1) % QUEUE;
            count--;
        }
        double elapsed = sampleRate > 0 ? Math.clamp((playedFrame - clock) / sampleRate, 0, .1) : 0;
        clock = playedFrame;
        synchronized (levels) {
            for (int i = 0; i < MAX_BARS; i++) {
                double tau = target[i] > levels[i] ? .012 + applied.smoothing * .12 : .04 + applied.smoothing * .6;
                levels[i] += (target[i] - levels[i]) * (float) (1 - Math.exp(-elapsed / tau));
            }
        }
    }

    /**
     * Renderers reuse their own fixed arrays; no FFT, allocation, or mutable array sharing.
     */
    public void copyTo(float[] destination) {
        synchronized (levels) {
            System.arraycopy(levels, 0, destination, 0, Math.min(destination.length, MAX_BARS));
        }
    }

    private record Options(boolean enabled, int bars, double sensitivity, double smoothing) {
    }
}
