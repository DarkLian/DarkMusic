package com.darkmusic.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Immutable full-track envelope, independent of volume, speed and playback DSP.
 */
public final class TrackWaveform {
    public static final int BINS = 1024;

    static final class AnalysisLimit extends IOException {
        AnalysisLimit(String message) {
            super(message);
        }
    }

    private final float[] levels;
    private final double duration;

    public TrackWaveform(float[] levels, double duration) {
        if (levels.length != BINS || !Double.isFinite(duration) || duration <= 0)
            throw new IllegalArgumentException("Invalid waveform");
        this.levels = levels.clone();
        for (float level : levels)
            if (!Float.isFinite(level) || level < 0 || level > 1)
                throw new IllegalArgumentException("Invalid waveform level");
        this.duration = duration;
    }

    public double duration() {
        return duration;
    }

    public float level(int index) {
        return levels[Math.clamp(index, 0, BINS - 1)];
    }

    public float peak(double start, double end) {
        int first = Math.clamp((int) (start * BINS), 0, BINS - 1);
        int last = Math.clamp((int) Math.ceil(end * BINS), first + 1, BINS);
        float maximum = 0;
        for (int i = first; i < last; i++) maximum = Math.max(maximum, levels[i]);
        return maximum;
    }

    /**
     * Interpolated local average for a smooth contour without inventing audio detail.
     */
    public float amplitude(double center, double span, double smoothing, double contrast) {
        double position = Math.clamp(center, 0, 1) * (BINS - 1);
        int radius = (int) Math.ceil(Math.max(0, span) * BINS * Math.clamp(smoothing, 0, 1));
        float value;
        if (radius == 0) {
            int left = (int) position;
            value = level(left) + (level(left + 1) - level(left)) * (float) (position - left);
        } else {
            int first = Math.max(0, (int) position - radius), last = Math.min(BINS - 1, (int) position + radius);
            float sum = 0;
            for (int i = first; i <= last; i++) sum += levels[i];
            value = sum / (last - first + 1);
        }
        return (float) Math.pow(Math.clamp(value, 0, 1), contrast);
    }

    /**
     * Adaptive envelope: bounded memory even with missing/wrong duration metadata.
     */
    public static TrackWaveform analyze(AudioInputStream pcm, BooleanSupplier cancelled) throws Exception {
        var format = pcm.getFormat();
        int channels = format.getChannels(), frameSize = format.getFrameSize();
        double rate = format.getSampleRate();
        if (format.getSampleSizeInBits() != 16 || format.isBigEndian() ||
            !format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) ||
            channels < 1 || channels > 2 || frameSize != channels * 2 || !Double.isFinite(rate) || rate <= 0)
            throw new AnalysisLimit("Unsupported waveform PCM");
        float[] peaks = new float[BINS * 2];
        double[] energy = new double[BINS * 2];
        long perBin = 256, frames = 0, inBin = 0;
        int bins = 0;
        byte[] bytes = new byte[16384 / frameSize * frameSize];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        long progressed = System.nanoTime();
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (System.nanoTime() > deadline) throw new AnalysisLimit("Waveform analysis timed out");
            int read = pcm.read(bytes);
            if (read < 0) break;
            if (read == 0) {
                if (System.nanoTime() - progressed > TimeUnit.SECONDS.toNanos(5))
                    throw new IOException("Waveform decoder made no progress");
                Thread.sleep(2);
                continue;
            }
            progressed = System.nanoTime();
            for (int offset = 0; offset + frameSize <= read; offset += frameSize) {
                if (bins == peaks.length) {
                    for (int i = 0; i < BINS; i++) {
                        peaks[i] = Math.max(peaks[i * 2], peaks[i * 2 + 1]);
                        energy[i] = energy[i * 2] + energy[i * 2 + 1];
                    }
                    Arrays.fill(energy, BINS, energy.length, 0);
                    Arrays.fill(peaks, BINS, peaks.length, 0);
                    bins = BINS;
                    perBin *= 2;
                }
                float peak = 0;
                for (int c = 0; c < channels; c++) {
                    int pos = offset + c * 2;
                    int sample = (short) ((bytes[pos] & 255) | (bytes[pos + 1] << 8));
                    peak = Math.max(peak, Math.abs(sample) / 32768f);
                }
                peaks[bins] = Math.max(peaks[bins], peak);
                energy[bins] += peak * peak;
                frames++;
                if (++inBin == perBin) {
                    inBin = 0;
                    bins++;
                }
            }
            if (frames > rate * 21600) throw new AnalysisLimit("Waveform exceeds six-hour limit");
        }
        if (frames == 0) throw new AnalysisLimit("Empty waveform");
        float[] output = new float[BINS];
        float highest = 0;
        for (int i = 0; i < BINS; i++) {
            int first = (int) ((i * (double) frames / BINS) / perBin);
            int last = Math.min(peaks.length, (int) Math.ceil(((i + 1.0) * frames / BINS) / perBin));
            double power = 0;
            long count = 0;
            float maximum = 0;
            for (int j = first; j < last; j++) {
                power += energy[j];
                count += Math.max(0, Math.min(perBin, frames - j * perBin));
                maximum = Math.max(maximum, peaks[j]);
            }
            output[i] = count == 0 ? 0 : (float) (.85 * Math.sqrt(power / count) + .15 * maximum);
            highest = Math.max(highest, output[i]);
        }
        // RMS carries musical dynamics; a small peak contribution preserves transients.
        if (highest > 0) for (int i = 0; i < BINS; i++) output[i] /= highest;
        return new TrackWaveform(output, frames / rate);
    }
}
