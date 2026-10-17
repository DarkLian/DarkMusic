package com.darkmusic.client.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.darkmusic.client.download.DownloadModels.*;

/**
 * Instance-scoped worker queues. UI callers only exchange immutable snapshots.
 */
public final class DownloadService implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("darkmusic-download");
    private final Path data;
    private final ProcessRunner runner;
    private final DownloadPipeline pipeline;
    private final Runnable refresh;
    private final ExecutorService analysis = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-url-analysis").factory());
    private final ExecutorService downloads = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-download-queue").factory());
    private final BlockingQueue<Work> pending = new LinkedBlockingQueue<>();
    private final List<Work> work = new ArrayList<>();
    private long ids;
    private Snapshot published;
    private final java.util.Set<ActiveKey> activeKeys = new java.util.HashSet<>();
    private boolean analyzing;
    private volatile boolean closed;
    private String message = "Paste a URL and Analyze. Nothing downloads until you choose Download.", toolStatus = "Tools not checked";
    private Preview preview;
    private ProcessRunner.Token analysisToken = new ProcessRunner.Token();
    private Tools settings = new Tools("", "", "", "");
    private volatile Cache cache;

    public DownloadService(Path data, Runnable refresh) {
        this(data, refresh, new ProcessRunner());
    }

    public DownloadService(Path data, Runnable refresh, ProcessRunner runner) {
        this.data = data.toAbsolutePath().normalize();
        this.refresh = refresh;
        this.runner = runner;
        pipeline = new DownloadPipeline(runner, this.data);
        downloads.execute(this::drain);
    }

    public synchronized void configure(Tools value) {
        settings = value;
    }

    public synchronized Snapshot snapshot() {
        if (published == null)
            published = new Snapshot(analyzing, message, toolStatus, preview, work.stream().map(Work::view).toList());
        return published;
    }

    public void checkTools() {
        start("Checking tools…", token -> {
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            synchronized (this) {
                cache = null;
                toolStatus = "Checking tools";
                published = null;
            }
            try {
                tools(selected, token);
            } catch (Exception e) {
                synchronized (this) {
                    cache = null;
                    toolStatus = "Tool check failed: " + e.getMessage();
                    published = null;
                }
                throw e;
            }
        });
    }

    private Toolchain tools(Tools selected, ProcessRunner.Token token) throws Exception {
        Cache cached = cache;
        token.check();
        if (cached != null && selected.equals(cached.settings())) return cached.tools();
        Files.createDirectories(data);
        Toolchain result = Toolchain.resolve(selected, data, runner, token);
        token.check();
        cache = new Cache(selected, result);
        synchronized (this) {
            toolStatus = result.versions();
            published = null;
        }
        return result;
    }

    public void analyze(String value, int page) {
        start("Analyzing URL…", token -> {
            String url = url(value);
            int first = Math.addExact(Math.multiplyExact(Math.max(0, page), PAGE_SIZE), 1);
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            Preview result = inspect(url, first, first + PAGE_SIZE - 1, tools(selected, token), token);
            token.check();
            synchronized (this) {
                token.check();
                preview = result;
                published = null;
                message = result.source() + (result.collection() ? " collection. Choose an explicit selection." : " single video.");
            }
        });
    }

    private Preview inspect(String value, int first, int last, Toolchain tools, ProcessRunner.Token token) throws Exception {
        var args = tools.base();
        args.addAll(List.of("--flat-playlist", "--dump-single-json", "--skip-download", "--ignore-errors", "--yes-playlist", "--playlist-items", first + ":" + last, "--", url(value)));
        var r = runner.run(args, data, token, Duration.ofMinutes(3), 16_000_000, s -> {
            if (!s.startsWith("{") && !s.isBlank()) LOG.debug("Analysis: {}", s);
        });
        if (r.exit() != 0) throw new IOException(DownloadJson.friendly(r.output()));
        return DownloadJson.preview(value, first, DownloadJson.object(r.output()));
    }

    public void enqueueVideo(String value, Path destination) {
        start("Preparing one video…", token -> {
            String link = url(value);
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            var args = tools(selected, token).base();
            args.addAll(List.of("--dump-single-json", "--skip-download", "--no-playlist", "--playlist-items", "1", "--", link));
            var result = runner.run(args, data, token, Duration.ofMinutes(3), 16_000_000, s -> {
            });
            if (result.exit() != 0) throw new IOException(DownloadJson.friendly(result.output()));
            Preview one = DownloadJson.preview(link, 1, DownloadJson.object(result.output()));
            if (one.entries().isEmpty())
                throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.no_video_was_found_at_this_url"));
            token.check();
            add(List.of(one.entries().getFirst()), destination, selected, token);
        });
    }

    public void enqueueRange(Preview expected, int first, int last, Path destination) {
        start("Preparing selected collection entries…", token -> {
            range(first, last);
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            Toolchain chain = tools(selected, token);
            List<Entry> entries = new ArrayList<>();
            for (long position = first; position <= last; position += PAGE_SIZE) {
                token.check();
                int from = (int) position;
                Preview page = inspect(expected.url(), from, (int) Math.min(last, position + PAGE_SIZE - 1), chain, token);
                if (!page.collection())
                    throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.this_url_is_not_a_supported_collection_choose_this_video_only"));
                entries.addAll(page.entries());
                if (page.entries().isEmpty() || !page.hasMore()) break;
            }
            token.check();
            add(entries, destination, selected, token);
        });
    }

    public void enqueueSelected(List<Entry> entries, Path destination) {
        Tools selected;
        synchronized (this) {
            selected = settings;
        }
        start("Preparing selected entries…", token -> {
            tools(selected, token);
            token.check();
            add(entries, destination, selected, token);
        });
    }

    private synchronized void add(List<Entry> entries, Path destination, Tools selected, ProcessRunner.Token token) throws IOException {
        token.check();
        if (closed || token != analysisToken) throw new CancellationException();
        if (entries.isEmpty())
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.there_are_no_entries_in_that_selection"));
        if (entries.size() > MAX_BATCH || work.size() + entries.size() > MAX_BATCH)
            throw new IOException("The queue holds up to " + MAX_BATCH + " entries. Clear finished items or use a smaller range.");
        Path target = destination.toAbsolutePath().normalize();
        int added = 0;
        for (Entry entry : entries) {
            var key = new ActiveKey(target, entry.key());
            if (!activeKeys.add(key)) continue;
            Work item = new Work(++ids, entry, target, selected);
            work.add(item);
            if (!entry.available()) {
                item.status = Status.FAILED;
                activeKeys.remove(key);
                item.message = "Unavailable, private, or deleted entry";
            } else pending.add(item);
            added++;
        }
        message = added + " entries queued for " + target;
        published = null;
    }

    public synchronized void cancel(long id) {
        for (Work item : work)
            if (item.id == id && !item.status.finished()) {
                item.token.cancel();
                if (item.status == Status.QUEUED) {
                    pending.remove(item);
                    item.status = Status.CANCELLED;
                    activeKeys.remove(new ActiveKey(item.destination, item.entry.key()));
                    item.message = "Cancelled";
                } else item.message = "Cancelling…";
            }
    }

    public synchronized void cancelAll() {
        work.forEach(w -> cancel(w.id));
        cancelAnalysis();
    }

    public synchronized void clearFinished() {
        work.removeIf(w -> w.status.finished());
        published = null;
    }

    public synchronized void cancelAnalysis() {
        analysisToken.cancel();
    }

    private synchronized void start(String text, Task task) {
        if (closed) return;
        if (analyzing) {
            message = "Already busy. Wait for analysis or cancel it first.";
            published = null;
            return;
        }
        analyzing = true;
        published = null;
        message = text;
        ProcessRunner.Token token = new ProcessRunner.Token();
        analysisToken = token;
        analysis.execute(() -> {
            try {
                task.run(token);
                synchronized (this) {
                    if (message.equals(text)) message = "Ready";
                }
            } catch (CancellationException | InterruptedException e) {
                synchronized (this) {
                    message = "Analysis cancelled";
                }
            } catch (Exception e) {
                LOG.warn("Download analysis failed", e);
                synchronized (this) {
                    message = e instanceof IllegalArgumentException ? e.getMessage() : DownloadJson.friendly(String.valueOf(e.getMessage()));
                }
            } finally {
                synchronized (this) {
                    analyzing = false;
                    published = null;
                }
            }
        });
    }

    private void drain() {
        while (!closed) {
            Work item;
            try {
                item = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                item.token.check();
                update(item, Status.DOWNLOADING, -1, "Checking tools");
                Toolchain chain = tools(item.tools, item.token);
                var result = pipeline.execute(item.entry, item.destination, chain, item.token, (status, detail) -> {
                    double percent = -1;
                    try {
                        if (detail.endsWith("%"))
                            percent = Math.clamp(Double.parseDouble(detail.replace("%", "").strip()) / 100, 0, 1);
                    } catch (NumberFormatException ignored) {
                    }
                    update(item, status, percent, detail);
                });
                update(item, result.skipped() ? Status.SKIPPED : Status.SAVED, 1, result.file().getFileName() + (result.warning().isBlank() ? "" : " — " + result.warning()));
                if (!result.skipped()) refresh.run();
            } catch (CancellationException | InterruptedException e) {
                update(item, Status.CANCELLED, -1, "Cancelled");
            } catch (Exception e) {
                LOG.warn("Download failed for entry {}", item.entry.mediaId(), e);
                update(item, Status.FAILED, -1, DownloadJson.friendly(String.valueOf(e.getMessage())));
            }
        }
    }

    private synchronized void update(Work item, Status status, double progress, String detail) {
        if (status.finished()) activeKeys.remove(new ActiveKey(item.destination, item.entry.key()));
        published = null;
        item.status = status;
        item.progress = progress;
        item.message = detail;
    }

    @Override
    public void close() {
        closed = true;
        cancelAll();
        analysis.shutdownNow();
        downloads.shutdownNow();
        try {
            analysis.awaitTermination(2, TimeUnit.SECONDS);
            downloads.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Task {
        void run(ProcessRunner.Token token) throws Exception;
    }

    private static final class Work {
        final long id;
        final Entry entry;
        final Path destination;
        final Tools tools;
        final ProcessRunner.Token token = new ProcessRunner.Token();
        Status status = Status.QUEUED;
        double progress = -1;
        String message = "Waiting";

        Work(long id, Entry entry, Path destination, Tools tools) {
            this.id = id;
            this.entry = entry;
            this.destination = destination;
            this.tools = tools;
        }

        Item view() {
            return new Item(id, entry.title(), destination, status, progress, message);
        }
    }

    private record ActiveKey(Path directory, String media) {
    }

    private record Cache(Tools settings, Toolchain tools) {
    }
}
