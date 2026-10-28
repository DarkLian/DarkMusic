package com.darkmusic.client.media;

import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.ManagedTools;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Local-only optional codec bridge. Reuses explicitly installed/configured tools; never installs them.
 */
public final class ExternalAudio {
    public record Paths(Path data, String ffmpeg, String ffprobe) {
    }

    private static volatile Supplier<Paths> configuration;
    private static final Map<AudioInputStream, Process> active = new ConcurrentHashMap<>();

    private ExternalAudio() {
    }

    public static void configure(Supplier<Paths> paths) {
        configuration = paths;
    }

    public static Path data() {
        var supplier = configuration;
        return supplier == null ? null : supplier.get().data();
    }

    public static void abort(AudioInputStream stream) {
        if (stream == null) return;
        var process = active.get(stream);
        if (process != null) process.destroyForcibly();
    }

    public static Path tool(boolean probe) throws IOException {
        var supplier = configuration;
        if (supplier == null)
            throw new IOException(Text.get("audio.error.tools"));
        Paths p = supplier.get();
        Path managed = ManagedTools.current(p.data());
        Path directory = managed == null ? p.data().resolve("tools") : managed;
        Path ff = Toolchain.executable(p.ffmpeg(), "ffmpeg", directory, null);
        return probe ? Toolchain.executable(p.ffprobe(), "ffprobe", directory, ff.getParent()) : ff;
    }

    public static JsonObject probe(Path file) throws Exception {
        return probe(file, true);
    }

    private static JsonObject probe(Path file, boolean metadata) throws Exception {
        return probe(file, metadata, new ProcessRunner.Token());
    }

    public static JsonObject probe(Path file, boolean metadata, ProcessRunner.Token token) throws Exception {
        Path absolute = file.toAbsolutePath();
        var args = new ArrayList<>(List.of(tool(true).toString(), "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0"));
        if (metadata)
            args.addAll(List.of("-show_entries", "stream=codec_name,sample_rate,channels,bit_rate,bits_per_raw_sample,duration:stream_tags=title,artist,album,track,replaygain_track_gain,replaygain_album_gain,replaygain_track_peak,replaygain_album_peak,purl,webpage_url,source_url,url,comment:format=duration,bit_rate:format_tags=title,artist,album,track,replaygain_track_gain,replaygain_album_gain,replaygain_track_peak,replaygain_album_peak,purl,webpage_url,source_url,url,comment"));
        else
            args.addAll(List.of("-show_entries", "stream=codec_name,sample_rate,channels,bits_per_raw_sample:format=duration"));
        args.addAll(List.of("-of", "json", absolute.toString()));
        var result = new ProcessRunner().run(args, absolute.getParent(), token, Duration.ofSeconds(15), 128_000);
        if (result.exit() != 0)
            throw new IOException(Text.get("audio.error.inspection"), new IOException(result.output()));
        return JsonParser.parseString(result.output()).getAsJsonObject();
    }

    public static AudioInputStream open(Path file) throws Exception {
        return openAt(file, 0).stream();
    }

    public static OpenedAudio openAt(Path file, double seconds) throws Exception {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid seek position");
        JsonObject info = probe(file, false);
        var streams = info.getAsJsonArray("streams");
        if (streams == null || streams.isEmpty())
            throw new IOException(Text.get("audio.error.stream"));
        var audio = streams.get(0).getAsJsonObject();
        int rate = audio.get("sample_rate").getAsInt(), originalChannels = audio.get("channels").getAsInt();
        if (rate < 8000 || rate > 384000 || originalChannels < 1 || originalChannels > 32)
            throw new IOException(Text.get("audio.error.layout"));
        int channels = Math.min(2, originalChannels);
        var command = new ArrayList<>(List.of(tool(false).toString(), "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", file.toAbsolutePath().toString(), "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", Integer.toString(channels), "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"));
        double offset = Math.floor(seconds * rate) / rate;
        if (offset > 0) {
            int input = command.indexOf("-i");
            command.addAll(input, List.of("-ss", BigDecimal.valueOf(offset).toPlainString()));
        }
        Process process = new ProcessBuilder(command).start();
        boolean registered = false;
        try {
            process.getOutputStream().close();
            var errors = new StringBuilder();
            Thread drain = Thread.ofVirtual().name("darkmusic-codec-errors").start(() -> {
                try (var reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                    int c;
                    while ((c = reader.read()) >= 0) synchronized (errors) {
                        if (errors.length() == 4096) errors.delete(0, 2048);
                        errors.append((char) c);
                    }
                } catch (IOException ignored) {
                }
            });
            InputStream pipe = new FilterInputStream(process.getInputStream()) {
                private volatile boolean closed, reading;
                private volatile long started;
                private final Thread watchdog = Thread.ofVirtual().name("darkmusic-codec-watch").start(() -> {
                    try {
                        while (!closed) {
                            Thread.sleep(500);
                            if (reading && System.nanoTime() - started > TimeUnit.SECONDS.toNanos(15)) {
                                process.destroyForcibly();
                                break;
                            }
                        }
                    } catch (InterruptedException ignored) {
                    }
                });

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    started = System.nanoTime();
                    reading = true;
                    try {
                        int n = in.read(b, off, len);
                        if (n < 0) {
                            try {
                                boolean exited = process.waitFor(2, TimeUnit.SECONDS);
                                if (exited) drain.join(500);
                                if (!exited || process.exitValue() != 0) {
                                    synchronized (errors) {
                                        throw new IOException(Text.get("audio.error.decode"), new IOException(errors.toString()));
                                    }
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException(e);
                            }
                        }
                        return n;
                    } finally {
                        reading = false;
                    }
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    watchdog.interrupt();
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroy();
                    try {
                        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    } finally {
                        super.close();
                    }
                }
            };
            var result = new AudioInputStream(pipe, new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, rate, 32, channels, channels * 4, rate, false), -1) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        active.remove(this);
                    }
                }
            };
            active.put(result, process);
            registered = true;
            return new OpenedAudio(result, offset);
        } finally {
            if (!registered) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                }
                try {
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
        }
    }
}
