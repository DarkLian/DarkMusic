package com.darkmusic.client.pip;

import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * One helper, one receiver, bounded latest frames. No process or pipe waits on Minecraft's thread.
 */
public final class PipSession implements AutoCloseable {
    public final PipFrames frames = new PipFrames();
    public final PipAudio audio;
    private final String pipe = "darkmusic-pip-" + UUID.randomUUID();
    private volatile boolean closed;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private volatile Process process;
    private volatile PipFrameReader reader;
    private volatile String status = "pip.starting";
    private volatile long focusFailureUntil;
    private volatile long focusSuccesses, focusFailures;
    private volatile long heartbeat = System.nanoTime();
    private final long started = System.nanoTime();

    public PipSession(Path exe, PipSource source, long minecraft, PipSettings settings, int frameRate) {
        if (frameRate < 15 || frameRate > PipSettings.MAX_FRAME_RATE)
            throw new IllegalArgumentException("Invalid capture frame rate");
        audio = settings.audioSpectrum ? new PipAudio() : null;
        var command = List.of(exe.toString(), "--capture", Long.toString(source.Handle()), Integer.toString(source.Pid()), Long.toString(source.CreatedTicks()), Long.toString(minecraft), Long.toString(ProcessHandle.current().pid()), pipe, Integer.toString(frameRate), Integer.toString(settings.focusKey), Integer.toString(settings.returnKey), audio == null ? "0" : "1");
        Thread.ofPlatform().daemon().name("darkmusic-pip-helper").start(() -> launch(command, exe));
    }

    private void launch(List<String> command, Path exe) {
        try {
            var builder = new ProcessBuilder(command).directory(exe.getParent().toFile()).redirectErrorStream(true);
            if ("4".equals(System.getProperty("darkmusic.pip.minIntervalMs")))
                builder.environment().put("DARKMUSIC_PIP_MIN_INTERVAL_MS", "4");
            else builder.environment().remove("DARKMUSIC_PIP_MIN_INTERVAL_MS");
            String trace = System.getProperty("darkmusic.pip.trace");
            if (trace != null && !trace.isBlank()) builder.environment().put("DARKMUSIC_PIP_TRACE", trace);
            Process p = builder.start();
            process = p;
            if (closed) {
                p.destroyForcibly();
                return;
            }
            Thread.ofPlatform().daemon().name("darkmusic-pip-receiver").start(this::receive);
            if (audio != null) Thread.ofPlatform().daemon().name("darkmusic-pip-audio").start(this::receiveAudio);
            // Bounded lines; never retain arbitrary subprocess output or window contents.
            try (var input = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                var line = new StringBuilder();
                int ch;
                while ((ch = input.read()) != -1 && !closed) {
                    if (ch == '\n') {
                        event(line.toString());
                        line.setLength(0);
                    } else if (line.length() < 4096) line.append((char) ch);
                    else throw new IOException("Oversized helper event");
                }
            }
            if (!closed && !terminal(status)) status = "pip.disconnected";
        } catch (Exception e) {
            if (!closed) status = "pip.capture_error";
        } finally {
            closeResources();
            var p = process;
            if (p != null) try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            terminated.complete(null);
        }
    }

    private void event(String line) {
        try {
            String kind = JsonParser.parseString(line).getAsJsonObject().get("kind").getAsString();
            heartbeat = System.nanoTime();
            switch (kind) {
                case "heartbeat", "ready" -> {
                    if (!terminal(status)) status = "pip.live";
                }
                case "minimized" -> {
                    if (!terminal(status)) status = "pip.minimized";
                }
                case "focus_denied" -> {
                    focusFailures++;
                    focusFailureUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
                case "focused" -> {
                    focusSuccesses++;
                    focusFailureUntil = 0;
                }
                case "audio_unsupported", "audio_error" -> {
                    if (audio != null) audio.failure("pip." + kind);
                }
                case "hotkey_conflict", "source_closed", "capture_error", "pipe_error", "too_large" ->
                    status = "pip." + kind;
                default -> {
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean terminal(String value) {
        return Set.of("pip.hotkey_conflict", "pip.source_closed", "pip.capture_error", "pip.pipe_error", "pip.disconnected", "pip.too_large").contains(value);
    }

    private void receiveAudio() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!closed && System.nanoTime() < deadline) {
            FileInputStream stream;
            try {
                stream = new FileInputStream("\\\\.\\pipe\\" + pipe + ".audio");
            } catch (IOException e) {
                java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
                continue;
            }
            try {
                audio.read(stream);
                if (!closed) audio.failure("pip.audio_error");
            } catch (IOException e) {
                if (!closed) audio.failure("pip.audio_error");
            }
            return;
        }
        if (!closed && !audio.status().equals("pip.audio_unsupported")) audio.failure("pip.audio_error");
    }

    private void receive() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!closed && System.nanoTime() < deadline) {
            FileInputStream input;
            try {
                input = new FileInputStream("\\\\.\\pipe\\" + pipe);
            } catch (FileNotFoundException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            try (input; var r = new PipFrameReader(input.getChannel(), frames)) {
                reader = r;
                if (closed) return;
                r.read();
                if (!closed && !terminal(status)) status = "pip.disconnected";
            } catch (IOException e) {
                if (!closed && !terminal(status)) status = "pip.pipe_error";
            }
            return;
        }
        if (!closed) {
            status = "pip.pipe_error";
            closeResources();
        }
    }

    public String status() {
        return !terminal(status) && System.nanoTime() < focusFailureUntil ? "pip.focus_denied" : status;
    }

    public CompletableFuture<Void> termination() {
        return terminated;
    }

    public long focusSuccesses() {
        return focusSuccesses;
    }

    public long focusFailures() {
        return focusFailures;
    }

    public boolean healthy() {
        return !closed && !terminal(status);
    }

    public void watchdog() {
        if (!closed && (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(10) || frames.published() == 0 && System.nanoTime() - started > TimeUnit.SECONDS.toNanos(25))) {
            status = "pip.disconnected";
            close();
        }
    }

    public void command(String command) {
        if (!Set.of("FOCUS", "RETURN").contains(command)) return;
        Thread.ofVirtual().name("darkmusic-pip-command").start(() -> write(command));
    }

    private synchronized void write(String command) {
        var p = process;
        if (p == null || !p.isAlive()) return;
        try {
            p.getOutputStream().write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            p.getOutputStream().flush();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        frames.close();
        Thread.ofPlatform().daemon().name("darkmusic-pip-stop").start(() -> {
            write("STOP");
            var p = process;
            if (p != null) try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            closeResources();
        });
    }

    private void closeResources() {
        if (audio != null) audio.close();
        frames.close();
        var p = process;
        if (p != null && p.isAlive()) p.destroy();
        var r = reader;
        if (r != null) try {
            r.close();
        } catch (IOException ignored) {
        }
    }
}
