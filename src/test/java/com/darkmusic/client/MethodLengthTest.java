package com.darkmusic.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Long methods are where changes go wrong, so none may grow. A method's length is the span of its compiled line
 * numbers: last executable line minus first, so blank lines, comments and a closing brace do not count, and the IDE
 * formatter can move it a little. Lambda bodies compile to methods of their own and are skipped (their lines still
 * count toward the enclosing method's span), as are synthetic and bridge methods. Constructors and static
 * initializers are skipped too: field initializers anywhere in the file compile into them, so their span measures the
 * class. Overloads share one entry, the longest.
 */
class MethodLengthTest {
    private static final int LIMIT = 60;
    // Every method over the limit, with its length. Lower these as methods are split; never raise one or add one.
    // Most are debt. A hot loop or a table written as code is long on purpose and names why.
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
        debt("ArtworkCache.get", 83),
        hot("AudioProcessor.process", 127, "the per-sample DSP chain, run for every frame"),
        debt("AudioSettingsScreen.init", 144),
        debt("BackupArchive.prepare", 76),
        hot("CircularHudMesh$Rings.buildVertices", 73, "builds the circular HUD mesh each frame"),
        debt("ColorsScreen.init", 86),
        debt("DownloadDiagnostics.classify", 73),
        debt("DownloadPipeline.download", 149),
        debt("DuplicateComparisonScreen.init", 70),
        debt("DuplicateScanner.scan", 137),
        debt("ExternalAudio.openAt", 116),
        debt("JavaSoundBackend.changeOutput", 61),
        debt("LibrarySourceIndex.drain", 71),
        debt("LocalCommands.register", 82),
        debt("LyricsOptionsScreen.populate", 64),
        debt("ManagedTools.install", 88),
        debt("MediaActions.deleteAudio", 64),
        debt("MediaActions.importAudio", 61),
        debt("MediaFiles.edit", 134),
        debt("MusicScreen.extractRenderState", 62),
        debt("NowPlayingHud.renderAt", 147),
        debt("PipScreen.populate", 83),
        debt("PlaylistScreen.init", 103),
        debt("RestoreJournal.recover", 70),
        debt("SettingsScreen.init", 64),
        hot("SoundEnvironmentProcessor$Engine.process", 88, "the per-sample environment loop, run for every frame"),
        debt("SoundEnvironmentScreen.populate", 111),
        data("SoundEnvironmentSettings.recipe", 149, "a 22-case switch of preset parameters: a table written as code"),
        hot("SpectrumAnalyzer.analyze", 75, "the per-block FFT and band fold"),
        hot("SpectrumRenderer.bars", 87, "draws every spectrum bar each frame"),
        hot("TrackWaveform.analyze", 72, "one pass over every sample of a song"),
        debt("UpNextScreen.init", 73));

    // The debt entries; this only goes down as they are split.
    private static final int MAX_DEBT = 25;

    enum Kind {DEBT, DATA, HOT}

    record Allowed(int length, Kind kind, String reason) {
    }

    private static Map.Entry<String, Allowed> debt(String method, int length) {
        return Map.entry(method, new Allowed(length, Kind.DEBT, ""));
    }

    private static Map.Entry<String, Allowed> data(String method, int length, String reason) {
        return Map.entry(method, new Allowed(length, Kind.DATA, reason));
    }

    private static Map.Entry<String, Allowed> hot(String method, int length, String reason) {
        return Map.entry(method, new Allowed(length, Kind.HOT, reason));
    }

    @Test
    void deliberateLengthSaysWhyAndDebtOnlyShrinks() {
        assertEquals(new ArrayList<String>(), ALLOWED.entrySet().stream()
                .filter(e -> e.getValue().kind() != Kind.DEBT && e.getValue().reason().isBlank()).map(Map.Entry::getKey).toList(),
            "A hot or data entry names why it is long");
        long debt = ALLOWED.values().stream().filter(a -> a.kind() == Kind.DEBT).count();
        assertEquals(MAX_DEBT, debt, "Lower MAX_DEBT to the debt entries left; never add one");
    }

    @Test
    void longestMethodsOnlyShrink() throws IOException, URISyntaxException {
        var lengths = measure();
        var violations = new ArrayList<String>();
        lengths.forEach((method, length) -> {
            Integer allowed = ALLOWED.containsKey(method) ? ALLOWED.get(method).length() : null;
            if (length > LIMIT && (allowed == null || length > allowed))
                violations.add("Map.entry(\"" + method + "\", " + length + ")" + (allowed == null ? "" : " // allowed " + allowed));
        });
        var stale = new ArrayList<String>();
        ALLOWED.forEach((method, entry) -> {
            int allowed = entry.length();
            Integer length = lengths.get(method);
            if (length == null || length <= LIMIT || length < allowed)
                stale.add(method + " is now " + (length == null ? "gone" : length) + "; lower or remove its entry");
        });
        assertEquals(new ArrayList<String>(), violations, "Methods over " + LIMIT + " lines that grew or are new");
        assertEquals(new ArrayList<String>(), stale, "Allowlist entries to lower");
    }

    private static Map<String, Integer> measure() throws IOException, URISyntaxException {
        var root = Path.of(DarkMusicClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var lengths = new TreeMap<String, Integer>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                var reader = new ClassReader(Files.readAllBytes(file));
                String type = reader.getClassName().substring(reader.getClassName().lastIndexOf('/') + 1);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0 || name.startsWith("lambda$")
                            || name.equals("<init>") || name.equals("<clinit>"))
                            return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            private int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;

                            @Override
                            public void visitLineNumber(int line, Label start) {
                                first = Math.min(first, line);
                                last = Math.max(last, line);
                            }

                            @Override
                            public void visitEnd() {
                                if (first <= last) lengths.merge(type + "." + name, last - first, Math::max);
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        return lengths;
    }
}
