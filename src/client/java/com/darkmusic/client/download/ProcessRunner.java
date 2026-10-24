package com.darkmusic.client.download;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * No shell, bounded output, and one cancellation owner per subprocess tree.
 */
public class ProcessRunner {
    public Result run(List<String> command, Path directory, Token token, Duration timeout, int maxOutput, Consumer<String> line) throws IOException, InterruptedException {
        token.check();
        if (maxOutput < 1)
            throw new IllegalArgumentException(com.darkmusic.client.text.Text.get("text.darkmusic.output_limit_must_be_positive"));
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command)).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.start();
        token.process = process;
        var output = new OutputTail(maxOutput);
        var reader = CompletableFuture.runAsync(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder current = new StringBuilder();
                int ch;
                while ((ch = input.read()) != -1) {
                    output.append((char) ch);
                    if (current.length() < Math.max(32_768, maxOutput)) current.append((char) ch);
                    else
                        throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.tool_output_exceeded_the_allowed_line_length"));
                    if (ch == '\n' || ch == '\r') {
                        line.accept(current.toString().strip());
                        current.setLength(0);
                    }
                }
                if (!current.isEmpty()) line.accept(current.toString());
            } catch (IOException e) {
                if (!token.cancelled()) throw new CompletionException(e);
            }
        }, task -> Thread.ofVirtual().name("darkmusic-tool-output").start(task));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process.getOutputStream().close();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                token.collect();
                token.check();
                if (reader.isCompletedExceptionally()) reader.join();
                if (System.nanoTime() > deadline)
                    throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.tool_timed_out_check_your_connection_or_try_a_smaller_selection"));
            }
            token.check();
            reader.get(5, TimeUnit.SECONDS);
            String captured = output.text();
            if (process.exitValue() != 0)
                org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("{} exited with code {}: {}", Path.of(command.getFirst()).getFileName(), process.exitValue(), captured);
            return new Result(process.exitValue(), captured);
        } catch (ExecutionException | TimeoutException | CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(com.darkmusic.client.text.Text.get("download.error.unknown"), cause);
        } finally {
            token.kill();
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            token.process = null;
        }
    }

    public static final class Token {
        private final Token owner;

        public Token() {
            this(null);
        }

        public Token(Token owner) {
            this.owner = owner;
        }

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        private volatile Process process;

        public boolean cancelled() {
            return cancelled.get() || owner != null && owner.cancelled();
        }

        public void check() {
            if (cancelled()) throw new CancellationException("Cancelled");
        }

        public void cancel() {
            cancelled.set(true);
        }

        private void collect() {
            Process p = process;
            if (p != null) p.descendants().forEach(descendants::add);
        }

        private void kill() {
            collect();
            descendants.forEach(ProcessHandle::destroy);
            Process p = process;
            if (p != null) p.destroy();
            descendants.forEach(h -> {
                if (h.isAlive()) h.destroyForcibly();
            });
            if (p != null && p.isAlive()) p.destroyForcibly();
            var exits = descendants.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::onExit).toList();
            try {
                CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(1, TimeUnit.SECONDS);
                if (p != null) p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Timed out waiting for tool process termination", e);
            }
            descendants.removeIf(h -> !h.isAlive());
        }
    }

    /**
     * Lazily grows, then retains only the bounded diagnostic tail. Reader-thread owned.
     */
    static final class OutputTail {
        private final int maximum;
        private char[] data;
        private int size, cursor;

        OutputTail(int maximum) {
            this.maximum = maximum;
            data = new char[Math.min(4096, maximum)];
        }

        void append(char value) {
            if (size == data.length && size < maximum)
                data = java.util.Arrays.copyOf(data, Math.min(maximum, data.length * 2));
            if (size < data.length) data[size++] = value;
            else {
                data[cursor] = value;
                cursor = (cursor + 1) % maximum;
            }
        }

        String text() {
            if (size < maximum || cursor == 0) return new String(data, 0, size);
            return new String(data, cursor, size - cursor) + new String(data, 0, cursor);
        }
    }

    public record Result(int exit, String output) {
    }
}
