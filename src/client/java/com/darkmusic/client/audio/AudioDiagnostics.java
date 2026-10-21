package com.darkmusic.client.audio;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Opt-in worker-owned measurements. Publication follows the device clock, not decode time.
 */
public final class AudioDiagnostics {
    public static final int FFT_SIZE = 2048, POINTS = 128, STAGES = 5;
    public static final int SPECTRUM = 1, WAVEFORM = 2, RESPONSE = 4, METERS = 8, ALL = 15;
    private final float[][] captured = new float[4][8192];
    private final double[][][] history = new double[STAGES][2][FFT_SIZE];
    private final double[][][] aligned = new double[3][2][128];
    private int alignmentCursor, alignmentDelay, sourceChannels;
    private final double[] real = new double[FFT_SIZE], imaginary = new double[FFT_SIZE];
    private final double[] holds = new double[STAGES];
    private final long[] clips = new long[STAGES];
    private final AudioFft fft = new AudioFft(FFT_SIZE);
    private final ArrayDeque<Snapshot> pending = new ArrayDeque<>();
    private volatile int panels;
    private volatile Snapshot visible;
    private volatile boolean resetRequested;
    private double rate;
    private int channels, cursor, accumulated, elapsed;

    public void panels(int value) {
        int selected = value & ALL;
        if (panels != selected) visible = null;
        panels = selected;
    }

    public int panels() {
        return panels;
    }

    public Snapshot snapshot() {
        var snapshot = visible;
        return panels == 0 || snapshot != null && snapshot.panels != panels ? null : snapshot;
    }

    public void resetHolds() {
        resetRequested = true;
    }

    void sourceChannels(int value) {
        sourceChannels = value;
    }

    void begin(double sampleRate, int channelCount) {
        rate = sampleRate;
        channels = channelCount;
        cursor = accumulated = elapsed = 0;
        sourceChannels = channelCount;
        alignmentCursor = alignmentDelay = 0;
        for (var stage : aligned) for (var channel : stage) Arrays.fill(channel, 0);
        pending.clear();
        visible = null;
        Arrays.fill(holds, 0);
        Arrays.fill(clips, 0);
        for (var stage : history) for (var channel : stage) Arrays.fill(channel, 0);
    }

    void clear() {
        pending.clear();
        visible = null;
        accumulated = elapsed = 0;
    }

    void capture(int stage, int offset, double left, double right) {
        if (panels == 0 || offset + channels > captured[stage].length) return;
        captured[stage][offset] = (float) left;
        if (channels == 2) captured[stage][offset + 1] = (float) right;
    }

    void submitted(byte[] pcm, int offset, int length, long firstFrame, int sampleBytes, AudioProcessor processor) {
        int mask = panels;
        if (mask == 0) {
            clear();
            return;
        }
        if (resetRequested) {
            Arrays.fill(holds, 0);
            Arrays.fill(clips, 0);
            resetRequested = false;
        }
        int frameBytes = sampleBytes * channels;
        int delay = processor.latencyFrames();
        if (delay != alignmentDelay) {
            alignmentDelay = delay;
            accumulated = 0;
            pending.clear();
            visible = null;
        }
        for (int at = offset; at + frameBytes <= offset + length; at += frameBytes) {
            int source = at / sampleBytes;
            if (source + channels > captured[0].length) break;
            for (int c = 0; c < channels; c++) {
                for (int s = 0; s < 4; s++) {
                    double value = captured[s][source + c];
                    if (s < 3) {
                        aligned[s][c][alignmentCursor] = value;
                        value = aligned[s][c][(alignmentCursor - delay + 128) & 127];
                    }
                    history[s][c][cursor] = value;
                }
                int bits = 0;
                for (int b = 0; b < sampleBytes; b++) bits |= (pcm[at + c * sampleBytes + b] & 255) << (8 * b);
                history[4][c][cursor] = (bits << (32 - sampleBytes * 8) >> (32 - sampleBytes * 8)) / Math.scalb(1.0, sampleBytes * 8 - 1);
            }
            if (channels == 1) for (int s = 0; s < STAGES; s++) history[s][1][cursor] = history[s][0][cursor];
            for (int s = 0; s < STAGES; s++) {
                double peak = Math.max(Math.abs(history[s][0][cursor]), Math.abs(history[s][1][cursor]));
                holds[s] = Math.max(holds[s], peak);
                if (peak >= 1 || s == 4 && (Math.abs(captured[3][source]) >= 1 || channels == 2 && Math.abs(captured[3][source + 1]) >= 1))
                    clips[s]++;
            }
            cursor = (cursor + 1) & (FFT_SIZE - 1);
            accumulated = Math.min(FFT_SIZE, accumulated + 1);
            alignmentCursor = (alignmentCursor + 1) & 127;
            if (++elapsed >= rate / 20 && accumulated == FFT_SIZE) {
                elapsed = 0;
                long due = firstFrame + (at - offset) / frameBytes + 1;
                if (pending.size() == 16) pending.removeFirst();
                pending.addLast(measure(due, mask, processor));
            }
        }
    }

    void advance(long played) {
        while (!pending.isEmpty() && pending.getFirst().frame <= played) visible = pending.removeFirst();
        if (panels == 0) visible = null;
    }

    private Snapshot measure(long frame, int mask, AudioProcessor processor) {
        var spectra = new double[STAGES][POINTS];
        var waves = new double[STAGES][POINTS * 2];
        var meters = new Meter[STAGES];
        double correlation = Double.NaN, leftRms = 0, rightRms = 0;
        for (int stage = 0; stage < STAGES; stage++) {
            double sum = 0, peak = 0, ll = 0, rr = 0, lr = 0;
            for (int i = 0; i < FFT_SIZE; i++) {
                int at = (cursor + i) & (FFT_SIZE - 1);
                double l = history[stage][0][at], r = history[stage][1][at], mono = (l + r) * .5;
                sum += (l * l + r * r) * .5;
                ll += l * l;
                rr += r * r;
                lr += l * r;
                peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r)));
                if ((mask & SPECTRUM) != 0) {
                    real[i] = mono * (.5 - .5 * Math.cos(2 * Math.PI * i / FFT_SIZE));
                    imaginary[i] = 0;
                }
                if ((mask & WAVEFORM) != 0) {
                    int p = i * POINTS / FFT_SIZE * 2;
                    if (i % (FFT_SIZE / POINTS) == 0) {
                        waves[stage][p] = Math.min(l, r);
                        waves[stage][p + 1] = Math.max(l, r);
                    } else {
                        waves[stage][p] = Math.min(waves[stage][p], Math.min(l, r));
                        waves[stage][p + 1] = Math.max(waves[stage][p + 1], Math.max(l, r));
                    }
                }
            }
            meters[stage] = new Meter(AudioDynamics.db(peak), AudioDynamics.db(Math.sqrt(sum / FFT_SIZE)), AudioDynamics.db(holds[stage]), clips[stage]);
            if (stage == 4) {
                correlation = ll * rr > 1e-24 ? Math.clamp(lr / Math.sqrt(ll * rr), -1, 1) : Double.NaN;
                leftRms = AudioDynamics.db(Math.sqrt(ll / FFT_SIZE));
                rightRms = AudioDynamics.db(Math.sqrt(rr / FFT_SIZE));
            }
            if ((mask & SPECTRUM) != 0) {
                fft.transform(real, imaginary, false);
                for (int p = 0; p < POINTS; p++) {
                    int bin = Math.clamp((int) Math.round(frequency(p, rate) * FFT_SIZE / rate), 1, FFT_SIZE / 2);
                    spectra[stage][p] = AudioDynamics.db(Math.hypot(real[bin], imaginary[bin]) * 4 / FFT_SIZE);
                }
            }
        }
        double[] filters = new double[POINTS], net = new double[POINTS], dynamic = new double[POINTS], environment = new double[POINTS];
        if ((mask & RESPONSE) != 0) for (int p = 0; p < POINTS; p++) {
            double hz = frequency(p, rate);
            filters[p] = processor.response(hz, true);
            dynamic[p] = processor.response(hz, false);
            net[p] = dynamic[p] + AudioDynamics.db(processor.headroom());
            environment[p] = processor.environmentResponse(hz);
        }
        return new Snapshot(frame, mask, rate, sourceChannels, spectra, waves, meters, filters, net, dynamic, environment, correlation, leftRms, rightRms,
            processor.detectorDb(), processor.reductionDb(), processor.limiterReduction(), processor.truePeak(), processor.latencyFrames(), processor.convolutionState());
    }

    public static double frequency(int point, double rate) {
        return 20 * Math.pow(Math.min(20000, rate * .45) / 20, point / (double) (POINTS - 1));
    }

    public record Meter(double peakDb, double rmsDb, double holdDb, long clips) {
    }

    public static final class Snapshot {
        public final long frame;
        public final double rate, correlation, leftRms, rightRms, detectorDb, reductionDb, limiterReduction, truePeak;
        public final int channels, latencyFrames;
        public final int convolutionState;
        public final int panels;
        public final boolean convolutionReady;
        private final double[][] spectra, waves;
        private final Meter[] meters;
        private final double[] filters, net, dynamic, environment;

        private Snapshot(long frame, int panels, double rate, int channels, double[][] spectra, double[][] waves, Meter[] meters, double[] filters, double[] net,
                         double[] dynamic, double[] environment, double correlation, double leftRms, double rightRms, double detectorDb, double reductionDb,
                         double limiterReduction, double truePeak, int latencyFrames, int convolutionState) {
            this.frame = frame;
            this.panels = panels;
            this.rate = rate;
            this.channels = channels;
            this.spectra = spectra;
            this.waves = waves;
            this.meters = meters;
            this.filters = filters;
            this.net = net;
            this.dynamic = dynamic;
            this.environment = environment;
            this.correlation = correlation;
            this.leftRms = leftRms;
            this.rightRms = rightRms;
            this.detectorDb = detectorDb;
            this.reductionDb = reductionDb;
            this.limiterReduction = limiterReduction;
            this.truePeak = truePeak;
            this.latencyFrames = latencyFrames;
            this.convolutionState = convolutionState;
            this.convolutionReady = convolutionState == 2;
        }

        public double spectrum(int stage, int point) {
            return spectra[stage][point];
        }

        public double wave(int stage, int point, boolean maximum) {
            return waves[stage][point * 2 + (maximum ? 1 : 0)];
        }

        public Meter meter(int stage) {
            return meters[stage];
        }

        public double filter(int point) {
            return filters[point];
        }

        public double net(int point) {
            return net[point];
        }

        public double dynamic(int point) {
            return dynamic[point];
        }

        public double environment(int point) {
            return environment[point];
        }
    }
}
