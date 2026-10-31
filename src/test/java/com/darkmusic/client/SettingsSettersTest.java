package com.darkmusic.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A settings field with a range has a same-named setter that applies it (config.Range), so the range is written once.
 * Outside the config package, client code sets such a field only through its setter. The protected fields are found
 * from the setters themselves. Tests and smoke code may still write fields directly.
 */
class SettingsSettersTest {
    private static final String CONFIG = "com/darkmusic/client/config/";

    @Test
    void constrainedSettingsAreSetThroughSetters() throws IOException, URISyntaxException {
        var root = Path.of(DarkMusicClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var classes = new HashSet<byte[]>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList())
                classes.add(Files.readAllBytes(file));
        }
        var protectedFields = new HashSet<String>();
        for (byte[] bytes : classes) {
            var reader = new ClassReader(bytes);
            if (!reader.getClassName().startsWith(CONFIG)) continue;
            String owner = reader.getClassName();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if ((access & Opcodes.ACC_PUBLIC) != 0 && (descriptor.equals("(D)V") || descriptor.equals("(I)V")))
                        protectedFields.add(owner + "." + name);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        assertTrue(protectedFields.size() > 80, "Found only " + protectedFields.size() + " setters; the check no longer reads config");
        Set<String> violations = new TreeSet<>();
        for (byte[] bytes : classes) {
            var reader = new ClassReader(bytes);
            String type = reader.getClassName();
            if (type.startsWith(CONFIG)) continue;
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String fieldDescriptor) {
                            if (opcode == Opcodes.PUTFIELD && protectedFields.contains(owner + "." + name))
                                violations.add(type.substring(type.lastIndexOf('/') + 1) + " sets " + owner.substring(CONFIG.length()) + "." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        assertEquals(Set.of(), violations);
    }
}
