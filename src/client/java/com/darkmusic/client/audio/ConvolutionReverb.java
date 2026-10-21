package com.darkmusic.client.audio;

import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Uniformly partitioned stereo convolution. Kernels are modelled, not recorded room responses.
 */
final class ConvolutionReverb {
    static final int HOP = 512, SIZE = HOP * 2;

    enum Impulse {SHORT_ROOM, HALL, DARK_CAVE}

    private record Key(int rate, Impulse impulse) {
    }

    private record Kernel(double[][][] real, double[][][] imaginary, double[][] samples) {
    }

    private record Prepared(Kernel kernel, double[][][] historyReal, double[][][] historyImaginary) {
    }

    private static final AudioFft FFT = new AudioFft(SIZE);
    private static final ThreadPoolExecutor PREPARE = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> {
        var t = new Thread(r, "DarkMusic impulse preparation");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final java.util.Map<Key, CompletableFuture<Kernel>> CACHE = new java.util.LinkedHashMap<>();
    private final CompletableFuture<Prepared> future;
    private final boolean unsupported;
    private final double[][] input = new double[2][SIZE], imaginary = new double[2][SIZE];
    private final double[][] output = new double[2][HOP], overlap = new double[2][HOP];
    private final double[][] sumReal = new double[2][SIZE], sumImaginary = new double[2][SIZE];
    private double[][][] historyReal, historyImaginary;
    private Kernel kernel;
    private int cursor, head;

    ConvolutionReverb(double rate, Impulse impulse) {
        unsupported = !Double.isFinite(rate) || rate < 8000 || rate > 192000;
        if (unsupported) {
            future = CompletableFuture.failedFuture(new IllegalArgumentException("Modelled convolution supports device rates from 8 to 192 kHz"));
            return;
        }
        Key key = new Key((int) rate, impulse);
        synchronized (CACHE) {
            CompletableFuture<Kernel> found = CACHE.get(key);
            if (found == null || found.isCompletedExceptionally()) {
                try {
                    found = CompletableFuture.supplyAsync(() -> create(key), PREPARE);
                } catch (RejectedExecutionException e) {
                    found = CompletableFuture.failedFuture(e);
                }
                if (CACHE.size() >= 4) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(key, found);
            }
            CompletableFuture<Prepared> preparation;
            try {
                preparation = found.thenApplyAsync(k -> {
                    int count = k.real[0].length;
                    return new Prepared(k, new double[2][count][SIZE], new double[2][count][SIZE]);
                }, PREPARE);
            } catch (RejectedExecutionException e) {
                preparation = CompletableFuture.failedFuture(e);
            }
            future = preparation;
        }
    }

    boolean ready() {
        if (kernel == null && future.isDone() && !future.isCompletedExceptionally()) {
            var prepared = future.getNow(null);
            kernel = prepared.kernel;
            historyReal = prepared.historyReal;
            historyImaginary = prepared.historyImaginary;
        }
        return kernel != null;
    }

    boolean failed() {
        return future.isCompletedExceptionally();
    }

    boolean unsupportedRate() {
        return unsupported;
    }

    double sample(double value, int channel) {
        if (kernel == null) return 0;
        double result = output[channel][cursor];
        input[channel][cursor] = value;
        if (channel == 1 && ++cursor == HOP) {
            cursor = 0;
            block();
        }
        return result;
    }

    private void block() {
        int count = kernel.real[0].length;
        for (int c = 0; c < 2; c++) {
            Arrays.fill(input[c], HOP, SIZE, 0);
            Arrays.fill(imaginary[c], 0);
            FFT.transform(input[c], imaginary[c], false);
            System.arraycopy(input[c], 0, historyReal[c][head], 0, SIZE);
            System.arraycopy(imaginary[c], 0, historyImaginary[c][head], 0, SIZE);
            Arrays.fill(sumReal[c], 0);
            Arrays.fill(sumImaginary[c], 0);
            for (int p = 0; p < count; p++) {
                int h = (head - p + count) % count;
                double[] ar = historyReal[c][h], ai = historyImaginary[c][h];
                double[] br = kernel.real[c][p], bi = kernel.imaginary[c][p];
                for (int i = 0; i < SIZE; i++) {
                    sumReal[c][i] += ar[i] * br[i] - ai[i] * bi[i];
                    sumImaginary[c][i] += ar[i] * bi[i] + ai[i] * br[i];
                }
            }
            FFT.transform(sumReal[c], sumImaginary[c], true);
            for (int i = 0; i < HOP; i++) {
                output[c][i] = sumReal[c][i] + overlap[c][i];
                overlap[c][i] = sumReal[c][i + HOP];
            }
        }
        head = (head + 1) % count;
    }

    double[][] impulseSamples() {
        return kernel == null ? null : kernel.samples;
    }

    private static Kernel create(Key key) {
        double seconds = switch (key.impulse) {
            case SHORT_ROOM -> .32;
            case HALL -> 1.25;
            case DARK_CAVE -> 1.8;
        };
        int length = (int) (key.rate * seconds), parts = (length + HOP - 1) / HOP;
        double[][] samples = new double[2][length];
        double[][][] real = new double[2][parts][SIZE], imaginary = new double[2][parts][SIZE];
        // Deterministic original model: diffuse exponential decay and different stereo reflections.
        // No downloaded/recorded IR or third-party acoustic asset is incorporated.
        for (int c = 0; c < 2; c++) {
            long seed = 0x632be59bd9b4e019L + key.impulse.ordinal() * 341L + c * 7919L;
            double low = 0, absolute = 0;
            int delay = (int) (key.rate * (key.impulse == Impulse.SHORT_ROOM ? .006 : .021));
            for (int i = delay; i < length; i++) {
                seed ^= seed << 13;
                seed ^= seed >>> 7;
                seed ^= seed << 17;
                double noise = (seed >>> 11) * 0x1.0p-52 - 1;
                double coefficient = key.impulse == Impulse.DARK_CAVE ? .12 : .65;
                low += coefficient * (noise - low);
                double value = i % 53 == c * 11 ? low * Math.exp(-7 * (i - delay) / (double) length) : 0;
                samples[c][i] = value;
                absolute += Math.abs(value);
            }
            // Bounded L1 gain guarantees a bounded wet response even for pathological input.
            double scale = .9 / Math.max(1, absolute);
            for (int i = 0; i < length; i++) samples[c][i] *= scale;
            double[] early = {.25, .19, .15, .11, .08, .06};
            for (int i = 0; i < early.length; i++) {
                int at = Math.min(length - 1, delay + (int) (key.rate * (.007 * Math.pow(1.7, i) + c * .0007)));
                samples[c][at] += early[i] * (i % 2 == 0 ? 1 : -1);
            }
            if (key.impulse == Impulse.DARK_CAVE) {
                low = 0;
                for (int i = 0; i < length; i++) {
                    low += (samples[c][i] - low) * .18;
                    samples[c][i] = low;
                }
            }
            for (int p = 0; p < parts; p++) {
                System.arraycopy(samples[c], p * HOP, real[c][p], 0, Math.min(HOP, length - p * HOP));
                FFT.transform(real[c][p], imaginary[c][p], false);
            }
        }
        return new Kernel(real, imaginary, samples);
    }
}
