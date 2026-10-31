package com.darkmusic.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke code reads and writes private members by name, so a renamed or moved field fails only when that smoke window
 * runs. Every name it reflects must still be declared by some class in src/client. The owner type is not checked, so
 * a name declared in several classes (rows, search, mode) still needs a look when its screen changes. Tests need no
 * such check: they fail in the normal build when a name they reflect disappears.
 */
class SmokeReflectionTest {
    private static final Path SMOKE = Path.of("src/smoke/java");
    // field(owner, "name"), field("name"), set(owner, "name", value), SmokeUi.field/set, getDeclaredField/Method("name").
    private static final Pattern CALL = Pattern.compile("(?<![\\w.])(?:SmokeUi\\.)?(field|set)\\(|\\bgetDeclared(?:Field|Method)\\(");
    private static final Pattern NAME = Pattern.compile("\\s*\"(\\w+)\"");
    // Members of Minecraft's own classes that smoke code reflects.
    private static final Set<String> MINECRAFT = Set.of("exclusiveFullscreen", "displayPos"); // Window, EditBox

    @Test
    void smokeReflectionNamesExist() throws IOException, URISyntaxException {
        var declared = declaredMembers();
        var reflected = new TreeSet<String>();
        try (var files = Files.walk(SMOKE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList())
                reflected.addAll(reflectedNames(Files.readString(file)));
        }
        assertTrue(reflected.size() > 50, "Found only " + reflected.size() + " reflected names; the check no longer reads the smoke code");
        var missing = new TreeSet<>(reflected);
        missing.removeAll(declared);
        missing.removeAll(MINECRAFT);
        assertEquals(Set.of(), missing, "Smoke code reflects names no class in src/client declares");
    }

    static Set<String> reflectedNames(String code) {
        var names = new HashSet<String>();
        var call = CALL.matcher(code);
        while (call.find()) {
            int at = call.end();
            // A name as the only argument, or the argument after the first one (whose own parentheses are skipped).
            var first = NAME.matcher(code).region(at, code.length());
            if (first.lookingAt()) {
                names.add(first.group(1));
                continue;
            }
            int depth = 0;
            for (int i = at; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && depth-- == 0) break;
                else if (c == ',' && depth == 0) {
                    var second = NAME.matcher(code).region(i + 1, code.length());
                    if (second.lookingAt()) names.add(second.group(1));
                    break;
                }
            }
        }
        return names;
    }

    private static Set<String> declaredMembers() throws IOException, URISyntaxException {
        var root = Path.of(DarkMusicClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new HashSet<String>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList())
                new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        names.add(name);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        names.add(name);
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
        }
        return names;
    }
}
