package com.darkmusic.client.audio;

import com.darkmusic.client.text.Text;

import javax.sound.sampled.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/**
 * Java Sound playback outputs. Mixer indices are deliberately never persisted.
 */
public final class OutputDevices implements JavaSoundBackend.Output {
    public record Device(String id, String name, boolean selectable) {
    }

    private final String preferred;
    private final boolean fallback;
    private final Consumer<String> notice;
    private volatile String actual = "";
    private boolean warned;
    private Mixer.Info resolved;

    public OutputDevices(String preferred, boolean fallback, Consumer<String> notice) {
        this.preferred = Objects.requireNonNullElse(preferred, "");
        this.fallback = fallback;
        this.notice = notice;
    }

    static String id(Mixer.Info info) {
        try {
            String descriptor = info.getName() + "\n" + info.getVendor() + "\n" + info.getDescription() + "\n" + info.getVersion();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(descriptor.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static List<Mixer.Info> mixers() {
        var result = new ArrayList<Mixer.Info>();
        for (var info : AudioSystem.getMixerInfo())
            try {
                if (AudioSystem.getMixer(info).isLineSupported(new DataLine.Info(SourceDataLine.class, null)))
                    result.add(info);
            } catch (RuntimeException ignored) {
            }
        return result;
    }

    public static List<Device> available() {
        var mixers = mixers();
        var counts = new HashMap<String, Integer>();
        mixers.forEach(m -> counts.merge(id(m), 1, Integer::sum));
        return mixers.stream().map(m -> new Device(id(m), m.getName() + " — " + m.getDescription(), counts.get(id(m)) == 1))
            .distinct().toList();
    }

    public String actual() {
        return actual;
    }

    public boolean isExplicit() {
        return !preferred.isEmpty() && !actual.isEmpty();
    }

    public void reportFallback() {
        notice.accept(Text.get("device.fallback"));
    }

    @Override
    public SourceDataLine open(AudioFormat format) throws Exception {
        SourceDataLine line;
        if (preferred.isEmpty()) line = AudioSystem.getSourceDataLine(format);
        else {
            var matches = resolved == null ? mixers().stream().filter(m -> id(m).equals(preferred)).toList() : List.of(resolved);
            if (matches.size() != 1) {
                if (!fallback) throw new LineUnavailableException("Saved output is unavailable or ambiguous");
                line = AudioSystem.getSourceDataLine(format);
                actual = "";
                if (!warned) {
                    warned = true;
                    notice.accept(Text.get("device.fallback"));
                }
            } else {
                resolved = matches.getFirst();
                try {
                    line = (SourceDataLine) AudioSystem.getMixer(resolved).getLine(new DataLine.Info(SourceDataLine.class, format));
                } catch (RuntimeException | LineUnavailableException failed) {
                    resolved = null;
                    throw failed;
                }
                actual = preferred;
            }
        }
        try {
            line.open(format, Math.max(4096, (int) (format.getFrameRate() / 5) * format.getFrameSize()));
            return line;
        } catch (Exception e) {
            resolved = null;
            line.close();
            throw e;
        }
    }
}
