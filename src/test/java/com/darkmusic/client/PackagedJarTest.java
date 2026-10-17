package com.darkmusic.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.sound.sampled.AudioInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class PackagedJarTest {
    @TempDir
    Path temp;

    @Test
    void packagedNestedLibrariesDecodeWithoutTheDevelopmentClasspath() throws Exception {
        var music = TestMusic.files();
        Path artifact = Path.of(System.getProperty("darkmusic.jar"));
        List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        // JOrbis is provided by Minecraft and intentionally not bundled twice.
        urls.add(com.jcraft.jorbis.Info.class.getProtectionDomain().getCodeSource().getLocation());
        try (var jar = new JarFile(artifact.toFile())) {
            var nested = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")).toList();
            assertEquals(6, nested.size());
            for (var entry : nested) {
                Path target = temp.resolve(Path.of(entry.getName()).getFileName().toString());
                try (var input = jar.getInputStream(entry)) {
                    Files.copy(input, target);
                }
                urls.add(target.toUri().toURL());
            }
        }
        try (var isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            var open = isolated.loadClass("com.darkmusic.client.audio.PcmDecoder").getMethod("open", Path.class);
            for (Path fixture : music) {
                try (var stream = (AudioInputStream) open.invoke(null, fixture)) {
                    assertTrue(stream.readNBytes(32768).length > 0, fixture.toString());
                }
            }
        }
    }

    @Test
    void artifactIsClientOnlyAndContainsNoNetworkingOrTestEntrypoints() throws Exception {
        try (var jar = new JarFile(System.getProperty("darkmusic.jar"))) {
            for (var entry : jar.stream().toList())
                assertFalse(entry.getName().matches("(?i).*(\\.(exe|mp3|wav|flac|ogg)|(^|/)(Music|images_private)/.*)$"), "Development resources must not ship: " + entry.getName());
            var metadata = JsonParser.parseString(new String(jar.getInputStream(jar.getJarEntry("fabric.mod.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("client", metadata.get("environment").getAsString());
            assertEquals(Set.of("client"), metadata.getAsJsonObject("entrypoints").keySet());
            assertNotNull(jar.getJarEntry("THIRD-PARTY-NOTICES.md"));
            assertNotNull(jar.getJarEntry("licenses/LGPL-2.1.txt"));
            String notices = new String(jar.getInputStream(jar.getJarEntry("THIRD-PARTY-NOTICES.md")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(notices.contains("darkmusic-" + metadata.get("version").getAsString() + "-third-party-sources.zip"));
            assertTrue(jar.getJarEntry("assets/darkmusic/icon.png").getSize() < 1024 * 1024, "Packaged icon exceeded the 1 MiB budget");
            var english = JsonParser.parseString(new String(jar.getInputStream(jar.getJarEntry("assets/darkmusic/lang/en_us.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var chinese = JsonParser.parseString(new String(jar.getInputStream(jar.getJarEntry("assets/darkmusic/lang/zh_tw.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(english.keySet(), chinese.keySet());
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                assertFalse(entry.getName().contains("smoke") || entry.getName().contains("Test"));
                String constants = new String(jar.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                assertFalse(owner.equals("net/minecraft/client/Options") && (method.equals("save") || method.equals("broadcastOptions")), "Options persistence can indirectly broadcast packets: " + entry.getName());
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (String forbidden : List.of("net/minecraft/network/protocol/", "net/minecraft/network/Connection", "net/fabricmc/fabric/api/client/networking/", "java/net/Socket", "java/net/URL", "java/net/http/", "net/minecraft/server/", "net/minecraft/client/multiplayer/MultiPlayerGameMode"))
                    assertFalse(constants.contains(forbidden), entry.getName() + " references " + forbidden);
            }
            var depends = metadata.getAsJsonObject("depends");
            assertFalse(depends.has("fabric-networking-api-v1"));
        }
    }
}
