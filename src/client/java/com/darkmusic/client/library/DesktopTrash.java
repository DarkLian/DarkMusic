package com.darkmusic.client.library;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/**
 * One isolated desktop helper per batch, with bounded waits and no shell commands.
 */
final class DesktopTrash implements AudioDeletion.Trash {
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean available;
    private Tail diagnostics = new Tail();

    private static final class Tail {
        private final StringBuilder text = new StringBuilder();

        synchronized void append(String value) {
            text.append(value);
            if (text.length() > 4096) text.delete(0, text.length() - 4096);
        }

        @Override
        public synchronized String toString() {
            return text.toString();
        }
    }

    public boolean supported() {
        if (process != null) return available && process.isAlive();
        try {
            String binary = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
            Path javaBinary = Path.of(System.getProperty("java.home"), "bin", binary);
            Path classes = Path.of(DesktopTrashApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            process = new ProcessBuilder(List.of(javaBinary.toString(), "-Djava.awt.headless=false", "-cp", classes.toString(),
                DesktopTrashApp.class.getName(), Long.toString(ProcessHandle.current().pid()))).start();
            diagnostics = new Tail();
            var tail = diagnostics;
            var errors = process.getErrorStream();
            Thread.ofVirtual().name("darkmusic-trash-errors").start(() -> {
                try (var reader = new InputStreamReader(errors, java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buffer = new char[512];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) if (count > 0) tail.append(new String(buffer, 0, count));
                } catch (IOException ignored) {
                }
            });
            input = new DataInputStream(process.getInputStream());
            output = new DataOutputStream(process.getOutputStream());
            available = response(input::readBoolean, 10);
            return available;
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Desktop Trash is unavailable", failure);
            close();
            return false;
        }
    }

    public boolean move(AudioDeletion.Item item) {
        if (!supported()) return false;
        Path file = item.file();
        try {
            boolean moved = response(() -> {
                output.writeBoolean(true);
                output.writeUTF(file.toString());
                output.writeLong(item.track().size());
                output.writeLong(item.track().modified());
                output.writeUTF(String.valueOf(item.fileKey()));
                output.writeUTF(item.hash());
                output.flush();
                return input.readBoolean();
            }, 120);
            if (!moved)
                org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Desktop Trash rejected {}: {}", file, diagnostics);
            return moved;
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Desktop Trash operation failed for {}: {}", file, diagnostics, failure);
            close();
            return false;
        }
    }

    private boolean response(Callable<Boolean> read, int seconds) throws Exception {
        var task = new FutureTask<>(read);
        Thread.ofVirtual().name("darkmusic-trash-response").start(task);
        try {
            return task.get(seconds, TimeUnit.SECONDS);
        } finally {
            if (!task.isDone()) {
                close();
                task.cancel(true);
            }
        }
    }

    public void close() {
        available = false;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }
}
