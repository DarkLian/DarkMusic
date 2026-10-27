package com.darkmusic.client.cleanup;

import com.darkmusic.client.audio.AudioFft;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.tools.ProcessRunner;

import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Offline, versioned log-band spectral signatures. Not Chromaprint or an AcoustID client.
 */
public final class FingerprintService {
    public static final String ALGORITHM = "dm-logbands-1";
    public static final int RATE = 11025, SECONDS = 180, FFT = 2048, HOP = 512, STRIDE = 10, WINDOW = 40;
    private final ProcessRunner runner;
    private final Path executable;

    public FingerprintService() {
        this(new ProcessRunner());
    }

    public FingerprintService(ProcessRunner runner) {
        this(runner, null);
    }

    public FingerprintService(ProcessRunner runner, Path executable) {
        this.runner = runner;
        this.executable = executable;
    }

    static void pruneScratch(Path scratch, long now) {
        if (!Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(scratch)) return;
        try (var files = Files.list(scratch)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("fingerprint-[0-9]+\\.pcm")).toList())
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && now - Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() > java.util.concurrent.TimeUnit.DAYS.toMillis(1))
                    try {
                        Files.deleteIfExists(file);
                    } catch (java.io.IOException busy) { /* Retain files that are still in use. */ }
        } catch (java.io.IOException unavailable) { /* Optional maintenance must not abort discovery. */ }
    }

    public int[] extract(Path file, Path scratch, ProcessRunner.Token token) throws Exception {
        Files.createDirectories(scratch);
        Path pcm = Files.createTempFile(scratch, "fingerprint-", ".pcm");
        try {
            var command = List.of((executable == null ? ExternalAudio.tool(false) : executable).toString(), "-nostdin", "-v", "error", "-threads", "1", "-protocol_whitelist", "file,pipe",
                "-i", file.toAbsolutePath().toString(), "-t", Integer.toString(SECONDS), "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-ac", "1", "-ar", Integer.toString(RATE), "-c:a", "pcm_f32le", "-f", "f32le", "-fs", "8000000", "-y", pcm.toString());
            var result = runner.run(command, scratch, token, Duration.ofSeconds(90), 8192, s -> {
            });
            if (result.exit() != 0 || Files.size(pcm) > 8_000_000)
                throw new java.io.IOException("Fingerprint decoding failed");
            token.check();
            return samples(ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(), token);
        } finally {
            Files.deleteIfExists(pcm);
        }
    }

    static int[] samples(FloatBuffer samples, ProcessRunner.Token token) {
        var fft = new AudioFft(FFT);
        double[] real = new double[FFT], imaginary = new double[FFT];
        double[] window = new double[FFT], sum = new double[33];
        double[][] ring = new double[WINDOW][33];
        for (int j = 0; j < FFT; j++) window[j] = .5 - .5 * Math.cos(2 * Math.PI * j / (FFT - 1));
        int[] edges = new int[34];
        for (int j = 0; j < edges.length; j++)
            edges[j] = Math.max(1, (int) Math.round(180 * Math.pow(4000.0 / 180, j / 33.0) * FFT / RATE));
        var prints = new ArrayList<Integer>();
        int frame = 0;
        for (int offset = 0; offset + FFT <= samples.limit() && prints.size() < DuplicatePolicy.MAX_FINGERPRINT; offset += HOP, frame++) {
            token.check();
            double power = 0;
            for (int j = 0; j < FFT; j++) {
                double value = samples.get(offset + j);
                if (!Double.isFinite(value)) value = 0;
                real[j] = value * window[j];
                imaginary[j] = 0;
                power += value * value;
            }
            fft.transform(real, imaginary, false);
            var old = ring[frame % WINDOW];
            for (int band = 0; band < 33; band++) {
                double energy = 0;
                for (int j = edges[band]; j < edges[band + 1]; j++)
                    energy += real[j] * real[j] + imaginary[j] * imaginary[j];
                double value = Math.log1p(energy / Math.max(1, edges[band + 1] - edges[band]));
                sum[band] += value - old[band];
                old[band] = value;
            }
            if (frame >= WINDOW - 1 && (frame - WINDOW + 1) % STRIDE == 0) {
                int bits = 0;
                for (int band = 0; band < 32; band++) if (sum[band + 1] > sum[band]) bits |= 1 << band;
                int count = Integer.bitCount(bits);
                prints.add(power / FFT < 1e-8 || count < 4 || count > 28 ? 0 : bits);
            }
        }
        return prints.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Sixteen min-hash landmarks across two-time, eight-band slices; independent of tags.
     */
    public static Set<Long> keys(int[] values) {
        if (values.length < 20) return Set.of();
        var output = new LinkedHashSet<Long>();
        for (int band = 0; band < 4; band++)
            for (int seed = 0; seed < 4; seed++) {
                long best = Long.MAX_VALUE;
                int chosen = -1;
                for (int i = 0; i + 3 < values.length; i++)
                    if (values[i] != 0 && values[i + 3] != 0) {
                        int code = ((values[i] >>> (band * 8)) & 255) << 8 | ((values[i + 3] >>> (band * 8)) & 255);
                        long hash = mix(code ^ ((seed + 1) * 0x9e3779b9));
                        if (hash < best) {
                            best = hash;
                            chosen = code;
                        }
                    }
                if (chosen >= 0) output.add(((long) (band * 4 + seed) << 16) | chosen);
            }
        return output;
    }

    private static long mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return Integer.toUnsignedLong(value);
    }

    public record Match(boolean matched, boolean partial, double distance, int overlapFrames) {
    }

    public static Match compare(int[] a, int[] b, DuplicatePolicy p) {
        return compare(a, b, keys(a), keys(b), p);
    }

    static Match compare(int[] a, int[] b, Set<Long> aKeys, Set<Long> bKeys, DuplicatePolicy p) {
        if (Math.min(a.length, b.length) < p.audioFrames) return new Match(false, false, 1, 0);
        var keys = new HashSet<>(aKeys);
        keys.retainAll(bKeys);
        if (keys.size() < 2) return new Match(false, false, 1, 0);
        var positions = new HashMap<Integer, ArrayList<Integer>>();
        for (int j = 0; j < b.length; j++)
            if (b[j] != 0) for (int band = 0; band < 2; band++) {
                int code = (b[j] >>> (band * 16) & 65535) | band << 16;
                var bucket = positions.computeIfAbsent(code, k -> new ArrayList<>());
                if (bucket.size() < 12) bucket.add(j);
            }
        var votes = new HashMap<Integer, Integer>();
        votes.put(0, 1);
        for (int i = 0; i < a.length; i++)
            if (a[i] != 0) for (int band = 0; band < 2; band++) {
                var indices = positions.get((a[i] >>> (band * 16) & 65535) | band << 16);
                if (indices != null) for (int j : indices)
                    for (int delta = -1; delta <= 1; delta++) votes.merge(i - j + delta, 1, Integer::sum);
            }
        int usable = Math.min((int) Arrays.stream(a).filter(v -> v != 0).count(), (int) Arrays.stream(b).filter(v -> v != 0).count());
        Match best = new Match(false, false, 1, 0);
        for (int offset : votes.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()).limit(12).map(Map.Entry::getKey).toList()) {
            int valid = 0, close = 0, bits = 0;
            for (int i = Math.max(0, offset), end = Math.min(a.length, b.length + offset); i < end; i++) {
                int av = a[i], bv = b[i - offset];
                if (av == 0 || bv == 0) continue;
                int distance = Integer.bitCount(av ^ bv);
                bits += distance;
                valid++;
                if (distance <= 10) close++;
            }
            double distance = valid == 0 ? 1 : bits / (32.0 * valid);
            boolean matched = valid >= p.audioFrames && valid >= usable * p.audioCoverage && close >= valid * p.audioCoverage && distance <= p.audioDistance;
            if (matched && (!best.matched() || distance < best.distance()))
                best = new Match(true, Math.abs(offset) > 4 || Math.abs(a.length - b.length) > Math.min(a.length, b.length) / 10, distance, valid);
        }
        return best;
    }
}
