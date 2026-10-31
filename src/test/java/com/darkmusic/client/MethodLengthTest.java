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
    private static final Map<String, Integer> ALLOWED = Map.ofEntries(
        Map.entry("ArtworkCache.get", 83),
        Map.entry("AudioProcessor.process", 127),
        Map.entry("AudioSettingsScreen.init", 144),
        Map.entry("BackupArchive.prepare", 76),
        Map.entry("CircularHudMesh$Rings.buildVertices", 73),
        Map.entry("ColorsScreen.init", 86),
        Map.entry("DownloadDiagnostics.classify", 73),
        Map.entry("DownloadPipeline.download", 149),
        Map.entry("DuplicateComparisonScreen.init", 70),
        Map.entry("DuplicateScanner.scan", 137),
        Map.entry("ExternalAudio.openAt", 116),
        Map.entry("JavaSoundBackend.changeOutput", 61),
        Map.entry("LibrarySourceIndex.drain", 71),
        Map.entry("LocalCommands.register", 82),
        Map.entry("LyricsOptionsScreen.populate", 64),
        Map.entry("ManagedTools.install", 88),
        Map.entry("MediaActions.deleteAudio", 64),
        Map.entry("MediaActions.importAudio", 61),
        Map.entry("MediaFiles.edit", 134),
        Map.entry("MusicScreen.extractRenderState", 62),
        Map.entry("NowPlayingHud.renderAt", 147),
        Map.entry("PipScreen.populate", 83),
        Map.entry("PlaylistScreen.init", 103),
        Map.entry("RestoreJournal.recover", 70),
        Map.entry("SettingsScreen.init", 64),
        Map.entry("SoundEnvironmentProcessor$Engine.process", 88),
        Map.entry("SoundEnvironmentScreen.populate", 111),
        Map.entry("SoundEnvironmentSettings.recipe", 149),
        Map.entry("SpectrumAnalyzer.analyze", 75),
        Map.entry("SpectrumRenderer.bars", 87),
        Map.entry("TrackWaveform.analyze", 72),
        Map.entry("UpNextScreen.init", 73));

    @Test
    void longestMethodsOnlyShrink() throws IOException, URISyntaxException {
        var lengths = measure();
        var violations = new ArrayList<String>();
        lengths.forEach((method, length) -> {
            Integer allowed = ALLOWED.get(method);
            if (length > LIMIT && (allowed == null || length > allowed))
                violations.add("Map.entry(\"" + method + "\", " + length + ")" + (allowed == null ? "" : " // allowed " + allowed));
        });
        var stale = new ArrayList<String>();
        ALLOWED.forEach((method, allowed) -> {
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
