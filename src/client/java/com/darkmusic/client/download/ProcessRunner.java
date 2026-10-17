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
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command)).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("PYTHONUTF8", "1");
        Process process = builder.start();
        token.process = process;
        process.getOutputStream().close();
        char[] output = new char[maxOutput];
        long[] written = {0};
        var reader = CompletableFuture.runAsync(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder current = new StringBuilder();
                int ch;
                while ((ch = input.read()) != -1) {
                    output[(int) (written[0]++ % maxOutput)] = (char) ch;
                    if (current.length() < Math.max(32_768, maxOutput)) current.append((char) ch);
                    else throw new IOException("Tool output exceeded the allowed line length");
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
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                token.collect();
                token.check();
                if (reader.isCompletedExceptionally()) reader.join();
                if (System.nanoTime() > deadline)
                    throw new IOException("Tool timed out. Check your connection or try a smaller selection.");
            }
            token.check();
            reader.get(5, TimeUnit.SECONDS);
            int length = (int) Math.min(written[0], maxOutput);
            int beginning = written[0] < maxOutput ? 0 : (int) (written[0] % maxOutput);
            int first = Math.min(length, maxOutput - beginning);
            String captured = new String(output, beginning, first) + new String(output, 0, length - first);
            if (process.exitValue() != 0)
                org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("{} exited with code {}: {}", Path.of(command.getFirst()).getFileName(), process.exitValue(), captured);
            return new Result(process.exitValue(), captured);
        } catch (ExecutionException | TimeoutException | CompletionException e) {
            throw new IOException("Could not read tool output", e);
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
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        private volatile Process process;

        public boolean cancelled() {
            return cancelled.get();
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
        }
    }

    public record Result(int exit, String output) {
    }
}
