package com.darkmusic.client.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Separate real subprocess for lifecycle and Unicode/argument-boundary tests.
 */
public final class ProcessFixture {
    static void main(String[] args) throws Exception {
        if (args[0].equals("echo")) {
            System.out.write((args[1] + "\n").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (args[0].equals("tree")) {
            var java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            var child = new ProcessBuilder(java.toString(), "-cp", Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), ProcessFixture.class.getName(), "wait").start();
            Path target = Path.of(args[1]);
            Path ready = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(ready, Long.toString(child.pid()));
            Files.move(ready, target, StandardCopyOption.ATOMIC_MOVE);
        }
        if (args[0].equals("output")) {
            for (int i = 0; i < 2000; i++) System.out.println("Progress message " + i);
            System.out.println("ERROR: HTTP 429 final diagnostic");
            return;
        }
        Thread.sleep(120_000);
    }
}
