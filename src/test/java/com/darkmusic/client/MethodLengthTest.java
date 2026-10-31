package com.darkmusic.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long methods are where changes go wrong, so none may grow. A method's length is the span of its compiled line
 * numbers: last executable line minus first, so blank lines, comments and a closing brace do not count, and the IDE
 * formatter can move it a little. Lambda bodies are measured separately, including synthetic lambda methods.
 * Their compiler-assigned numeric suffix is dropped; siblings in one enclosing method share the longest span.
 * Other synthetic and bridge methods are skipped. Constructors and static
 * initializers are skipped too: field initializers anywhere in the file compile into them, so their span measures the
 * class. Overloads share one entry, the longest.
 */
class MethodLengthTest {
    private static final int LIMIT = 60;
    // Every method over the limit, with its length. Lower these as methods are split; never raise one or add one.
    // Most are debt. A hot loop or a table written as code is long on purpose and names why.
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
        hot("AudioProcessor.process", 127, "the per-sample DSP chain, run for every frame"),
        debt("AudioSettingsScreen.init", 144),
        hot("CircularHudMesh$Rings.buildVertices", 73, "builds the circular HUD mesh each frame"),
        debt("NowPlayingHud.renderAt", 147),
        debt("PipRuntime.begin$lambda", 83),
        hot("SoundEnvironmentProcessor$Engine.process", 88, "the per-sample environment loop, run for every frame"),
        data("EnvironmentRecipes.recipe", 149, "a 22-case switch of preset parameters: a table written as code"),
        hot("SpectrumAnalyzer.analyze", 75, "the per-block FFT and band fold"),
        hot("SpectrumRenderer.bars", 87, "draws every spectrum bar each frame"),
        hot("TrackWaveform.analyze", 72, "one pass over every sample of a song"));

    // G1 records one previously invisible existing lambda. From this baseline debt only goes down.
    private static final int MAX_DEBT = 3;

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
                byte[] bytes = Files.readAllBytes(file);
                var code = codeLengths(bytes);
                var reader = new ClassReader(bytes);
                String type = reader.getClassName().substring(reader.getClassName().lastIndexOf('/') + 1);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        boolean lambda = name.startsWith("lambda$");
                        if (!lambda && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
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
                                String key = type + "." + stableName(name);
                                if (first <= last) lengths.merge(key, last - first, Math::max);
                                if (ALLOWED.containsKey(key) || lambda && last - first > 40)
                                    System.out.printf("METHOD_SIZE %s%s: %d lines, %d bytes%n",
                                        key, descriptor, last - first, code.get(name + descriptor));
                                if (ALLOWED.containsKey(key) && ALLOWED.get(key).kind() == Kind.HOT)
                                    assertTrue(code.get(name + descriptor) <= 8000,
                                        key + " exceeds the huge-method JIT threshold; split and measure it first");
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        return lengths;
    }

    static String stableName(String name) {
        return name.startsWith("lambda$") ? name.substring(7, name.lastIndexOf('$')) + "$lambda" : name;
    }

    @Test
    void lambdaKeysIgnoreOnlyTheCompilerOrdinal() {
        assertEquals("begin$lambda", stableName("lambda$begin$0"));
        assertEquals("begin$lambda", stableName("lambda$begin$29"));
        assertEquals("other$lambda", stableName("lambda$other$0"));
        assertEquals("process", stableName("process"));
    }

    @Test
    void encodedLengthsKeepOverloadsAndWideOperands() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "Measured", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "field", "J", null, 1L).visitEnd();
        var shortMethod = writer.visitMethod(Opcodes.ACC_STATIC, "work", "()V", null, null);
        shortMethod.visitCode();
        shortMethod.visitInsn(Opcodes.RETURN);
        shortMethod.visitMaxs(0, 0);
        shortMethod.visitEnd();
        var wide = writer.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "work", "(I)V", null, null);
        wide.visitCode();
        wide.visitIincInsn(300, 1000); // WIDE IINC is six encoded bytes.
        wide.visitInsn(Opcodes.RETURN);
        wide.visitMaxs(0, 301);
        wide.visitEnd();
        writer.visitEnd();
        var lengths = codeLengths(writer.toByteArray());
        assertEquals(1, lengths.get("work()V"));
        assertEquals(7, lengths.get("work(I)V"));
        assertEquals(2, lengths.size());
    }

    // Read Code.code_length directly: ASM's visitor does not expose the encoded instruction length (switch padding,
    // wide instructions and branch encoding matter). Constant-pool UTF8 entries name attributes and methods.
    static Map<String, Integer> codeLengths(byte[] bytes) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            input.skipNBytes(8);
            String[] strings = new String[input.readUnsignedShort()];
            for (int i = 1; i < strings.length; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> strings[i] = input.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown constant-pool tag " + tag);
                }
            }
            input.skipNBytes(6);
            input.skipNBytes(input.readUnsignedShort() * 2L);
            readMembers(input, strings, false);
            return readMembers(input, strings, true);
        }
    }

    private static Map<String, Integer> readMembers(DataInputStream input, String[] strings, boolean methods) throws IOException {
        var sizes = new TreeMap<String, Integer>();
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            input.readUnsignedShort();
            String name = strings[input.readUnsignedShort()], descriptor = strings[input.readUnsignedShort()];
            int attributes = input.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = strings[input.readUnsignedShort()];
                long length = Integer.toUnsignedLong(input.readInt());
                if (methods && attribute.equals("Code")) {
                    input.skipNBytes(4);
                    sizes.put(name + descriptor, input.readInt());
                    length -= 8;
                }
                input.skipNBytes(length);
            }
        }
        return sizes;
    }
}
