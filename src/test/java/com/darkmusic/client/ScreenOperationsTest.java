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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screens change playback, the queue and playlists only through gui.TransportOperations, QueueOperations and
 * PlaylistOperations, which apply the admission once for every screen. Checked on the compiled gui classes, by the
 * owner of each call, so methods named next or add on other types do not count.
 */
class ScreenOperationsTest {
    private static final String PLAYBACK = "com/darkmusic/client/audio/PlaybackController";
    private static final String PLAYLISTS = "com/darkmusic/client/playlist/PlaylistStore";
    // What TransportOperations and QueueOperations cover; shuffle and repeat with an argument are the setters.
    private static final Set<String> PLAYBACK_CHANGES = Set.of("play", "toggle", "next", "previous", "seek", "seekRelative",
        "stop", "pause", "resume", "playNext", "addToQueue", "insertQueued", "reorderQueued", "removeQueued", "clearQueued");
    // Everything else on the store changes it.
    private static final Set<String> PLAYLIST_READS = Set.of("all", "availableToAdd", "displayName", "favorite", "get",
        "index", "resolve", "revision", "missing");

    @Test
    void screensMutateThroughOperations() throws IOException, URISyntaxException {
        var root = Path.of(DarkMusicClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var calls = new TreeSet<String>();
        var violations = new TreeSet<String>();
        try (var files = Files.walk(root.resolve("com/darkmusic/client/gui"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                var reader = new ClassReader(Files.readAllBytes(file));
                String type = reader.getClassName().substring(reader.getClassName().lastIndexOf('/') + 1);
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String called, boolean isInterface) {
                                boolean setter = (name.equals("shuffle") || name.equals("repeat")) && !called.startsWith("()");
                                if (owner.equals(PLAYBACK)) calls.add(name);
                                if (owner.equals(PLAYBACK) && (PLAYBACK_CHANGES.contains(name) || setter)
                                    || owner.equals(PLAYLISTS) && !name.equals("<init>") && !PLAYLIST_READS.contains(name))
                                    violations.add(type + " calls " + owner.substring(owner.lastIndexOf('/') + 1) + "." + name);
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(calls.contains("diagnostics"), "No PlaybackController calls found in gui; the check no longer reads the classes");
        assertEquals(Set.of(), violations);
    }
}
