package com.darkmusic.client.download;

import com.darkmusic.client.text.Text;

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
    public static final int PARALLEL_DOWNLOADS = 3;
    public static final int MAX_RETRIES = 3;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("darkmusic-download");
    private final Path data;
    private final ProcessRunner runner;
    private final DownloadPipeline pipeline;
    private final Runnable refresh;
    private final ExecutorService analysis = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-url-analysis").factory());
    private final Object toolResolutionLock = new Object();
    private final ExecutorService downloads = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, Thread.ofPlatform().daemon().name("darkmusic-download-", 1).factory());
    private final BlockingQueue<Work> pending = new LinkedBlockingQueue<>();
    private final List<Work> work = new ArrayList<>();
    private final java.util.Set<ActiveKey> activeKeys = new java.util.HashSet<>();
    private long ids;
    private long analysisIds;
    private DownloadDiagnostics.Diagnostic analysisDiagnostic;
    private DownloadDiagnostics.Diagnostic toolDiagnostic;
    private Snapshot published;
    private boolean analyzing, toolOperation;
    private volatile boolean closed;
    private String message = "", toolStatus = Text.get("download.darkmusic.tools_unchecked");
    private Preview preview;
    private String lastAnalyzedUrl = "", inputUrl = "";
    private long previewRevision;
    private ProcessRunner.Token previewToken;
    private ProcessRunner.Token analysisToken = new ProcessRunner.Token();
    private Tools settings = new Tools("", "", "", "");
    private DownloadEncoding.Format format = DownloadEncoding.Format.ORIGINAL;
    private volatile com.darkmusic.client.cleanup.LibrarySourceIndex awareness;

    public record DuplicateChoice(long id, List<Entry> entries, Path destination, Tools tools,
                                  DownloadEncoding.Format format,
                                  java.util.Map<String, List<com.darkmusic.client.cleanup.LibrarySourceIndex.Existing>> matches) {
    }

    private DuplicateChoice duplicateChoice;
    private long choiceIds;

    public void awareness(com.darkmusic.client.cleanup.LibrarySourceIndex value) {
        awareness = value;
    }

    public synchronized DuplicateChoice duplicateChoice() {
        return duplicateChoice;
    }

    public synchronized void cancelDuplicateChoice(long id) {
        if (duplicateChoice != null && duplicateChoice.id() == id) {
            duplicateChoice = null;
            message = Text.get("download.darkmusic.cancelled");
            published = null;
        }
    }

    public void resolveDuplicateChoice(long id, boolean skip) {
        DuplicateChoice choice;
        synchronized (this) {
            choice = duplicateChoice;
            if (closed || analyzing || choice == null || choice.id() != id) return;
            duplicateChoice = null;
        }
        start(Text.get("download.darkmusic.preparing_selected"), token -> {
            var skipped = new java.util.HashSet<String>();
            var index = awareness;
            if (skip && index != null) for (Entry e : choice.entries())
                if (index.verified(e).stream().anyMatch(v -> v.sameFormat(choice.format()))) skipped.add(e.key());
            commit(choice.entries(), choice.destination(), choice.tools(), token, choice.format(), skipped);
        });
    }

    private volatile boolean lyricsEnabled = true;
    private volatile List<String> lyricsLanguages = LyricsLanguages.resolve("auto", java.util.Locale.getDefault().toLanguageTag());

    public void lyrics(boolean enabled) {
        lyricsEnabled = enabled;
    }

    public void lyrics(boolean enabled, String preferences, String automatic) {
        lyricsLanguages = LyricsLanguages.resolve(preferences, automatic);
        lyricsEnabled = enabled;
    }

    public synchronized void format(DownloadEncoding.Format value) {
        format = java.util.Objects.requireNonNull(value);
    }

    private volatile Cache cache;

    public DownloadService(Path data, Runnable refresh) {
        this(data, refresh, new ProcessRunner());
    }

    public DownloadService(Path data, Runnable refresh, ProcessRunner runner) {
        this.data = data.toAbsolutePath().normalize();
        this.refresh = refresh;
        this.runner = runner;
        pipeline = new DownloadPipeline(runner, this.data);
        for (int i = 0; i < PARALLEL_DOWNLOADS; i++) downloads.execute(this::drain);
    }

    public synchronized void configure(Tools value) {
        settings = value;
    }

    public synchronized Snapshot snapshot() {
        if (published == null)
            published = new Snapshot(analyzing, message, toolStatus, preview, work.stream().map(Work::view).toList());
        return published;
    }

    public synchronized DownloadDiagnostics.Diagnostic analysisDiagnostic() {
        return analysisDiagnostic;
    }

    public synchronized DownloadDiagnostics.Diagnostic toolDiagnostic() {
        return toolDiagnostic;
    }

    public synchronized DownloadDiagnostics.Diagnostic diagnostic(long id) {
        return work.stream().filter(w -> w.id == id).map(w -> w.diagnostic).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public void installTools() {
        start(Text.get("download.darkmusic.tools_installing"), true, token -> {
            synchronized (this) {
                if (!activeKeys.isEmpty())
                    throw new IOException(Text.get("download.darkmusic.finish_queue_before_update"));
            }
            new ManagedTools(data).install(runner, token, text -> {
                synchronized (this) {
                    toolStatus = text;
                    published = null;
                }
            });
            cache = null;
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            tools(selected, token);
            synchronized (this) {
                message = Text.get("download.darkmusic.tools_ready");
                published = null;
            }
        });
    }

    public void checkTools() {
        start(Text.get("download.darkmusic.tools_checking"), true, token -> {
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            synchronized (this) {
                cache = null;
                toolStatus = Text.get("download.darkmusic.tools_checking");
                published = null;
            }
            try {
                tools(selected, token);
            } catch (Exception e) {
                synchronized (this) {
                    cache = null;
                    toolStatus = Text.get("download.darkmusic.tool_check_failed", DownloadJson.friendly(String.valueOf(e.getMessage())));
                    published = null;
                }
                throw e;
            }
        });
    }

    private Toolchain tools(Tools selected, ProcessRunner.Token token) throws Exception {
        synchronized (toolResolutionLock) {
            Cache cached = cache;
            token.check();
            if (cached != null && selected.equals(cached.settings())) return cached.tools();
            Files.createDirectories(data);
            Toolchain result = DownloadDiagnostics.at(DownloadDiagnostics.Stage.TOOLS, DownloadDiagnostics.Tool.TOOLS,
                () -> Toolchain.resolve(selected, data, runner, token));
            token.check();
            cache = new Cache(selected, result);
            synchronized (this) {
                toolStatus = result.versions();
                published = null;
            }
            return result;
        }
    }

    public synchronized List<String> toolVersions() {
        Cache resolved = cache;
        return resolved == null || !resolved.settings().equals(settings) ? List.of() : List.of(resolved.tools().versions().split(" \\| ", -1));
    }

    public synchronized String lastAnalyzedUrl() {
        return lastAnalyzedUrl;
    }

    public synchronized String inputUrl() {
        return inputUrl;
    }

    public synchronized void rememberInput(String value) {
        inputUrl = value;
        if (value.isBlank()) {
            analysisDiagnostic = null;
            published = null;
        }
    }

    /**
     * Forget UI-only results without cancelling queued downloads or tool installation.
     */
    public synchronized void clearPreview() {
        analysisDiagnostic = null;
        previewRevision++;
        if (previewToken != null) previewToken.cancel();
        preview = null;
        if (!analyzing) message = "";
        published = null;
    }

    public synchronized void analyze(String value, int page) {
        if (closed) return;
        if (analyzing) {
            message = Text.get("download.darkmusic.busy");
            published = null;
            return;
        }
        final String link;
        try {
            link = url(value);
        } catch (IllegalArgumentException e) {
            preview = null;
            message = e.getMessage();
            analysisDiagnostic = DownloadDiagnostics.classify(new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.UNSUPPORTED_URL,
                DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.NONE, null, "", e), DownloadDiagnostics.Stage.ANALYSIS, value, -++analysisIds, 1, "");
            published = null;
            return;
        }
        lastAnalyzedUrl = link;
        inputUrl = link;
        long ticket = ++previewRevision;
        preview = null;
        published = null;
        start(Text.get("download.darkmusic.analyzing"), token -> {
            token.check();
            int first = Math.addExact(Math.multiplyExact(Math.max(0, page), PAGE_SIZE), 1);
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            Preview result = inspect(link, first, first + PAGE_SIZE - 1, tools(selected, token), token,
                page > 0 ? DownloadDiagnostics.Stage.COLLECTION : DownloadDiagnostics.Stage.ANALYSIS);
            synchronized (this) {
                token.check();
                if (ticket != previewRevision) return;
                preview = result;
                published = null;
                message = Text.get(result.collection() ? "download.analysis_collection" : "download.analysis_video", result.source());
            }
        });
        previewToken = analysisToken;
    }

    private Preview inspect(String value, int first, int last, Toolchain tools, ProcessRunner.Token token, DownloadDiagnostics.Stage stage) throws Exception {
        var args = tools.base();
        args.addAll(List.of("--flat-playlist", "--dump-single-json", "--skip-download", "--ignore-errors", "--yes-playlist", "--playlist-items", first + ":" + last, "--", url(value)));
        var r = DownloadDiagnostics.at(stage, DownloadDiagnostics.Tool.YT_DLP, () -> runner.run(args, data, token, Duration.ofMinutes(3), 16_000_000, s -> {
            if (!s.startsWith("{") && !s.isBlank()) LOG.debug("Analysis: {}", s);
        }));
        if (r.exit() != 0)
            throw new DownloadDiagnostics.Failure(stage, DownloadDiagnostics.Tool.YT_DLP, r.exit(), r.output()).versions(tools.versions());
        return DownloadDiagnostics.at(stage, DownloadDiagnostics.Tool.YT_DLP, () -> DownloadJson.preview(value, first, DownloadJson.object(r.output())));
    }

    public void enqueueVideo(String value, Path destination) {
        start(Text.get("download.darkmusic.preparing_video"), token -> {
            String link = url(value);
            Tools selected;
            synchronized (this) {
                selected = settings;
            }
            var verified = tools(selected, token);
            var args = verified.base();
            args.addAll(List.of("--dump-single-json", "--skip-download", "--no-playlist", "--playlist-items", "1", "--", link));
            var result = DownloadDiagnostics.at(DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.YT_DLP,
                () -> runner.run(args, data, token, Duration.ofMinutes(3), 16_000_000, s -> {
                }));
            if (result.exit() != 0)
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.YT_DLP, result.exit(), result.output()).versions(verified.versions());
            Preview one = DownloadJson.preview(link, 1, DownloadJson.object(result.output()));
            if (one.entries().isEmpty())
                throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.no_video_was_found_at_this_url"));
            token.check();
            add(List.of(one.entries().getFirst()), destination, selected, token);
        });
    }

    public void enqueueRange(Preview expected, int first, int last, Path destination) {
        start(Text.get("download.darkmusic.preparing_collection"), token -> {
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
                Preview page = inspect(expected.url(), from, (int) Math.min(last, position + PAGE_SIZE - 1), chain, token, DownloadDiagnostics.Stage.COLLECTION);
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
        start(Text.get("download.darkmusic.preparing_selected"), token -> {
            tools(selected, token);
            token.check();
            add(entries, destination, selected, token);
        });
    }

    private void add(List<Entry> entries, Path destination, Tools selected, ProcessRunner.Token token) throws IOException {
        if (entries.size() > MAX_BATCH) throw new IOException(Text.get("download.queue_capacity", MAX_BATCH));
        var index = awareness;
        DownloadEncoding.Format requested;
        synchronized (this) {
            requested = format;
        }
        if (index != null) {
            var found = new java.util.LinkedHashMap<String, List<com.darkmusic.client.cleanup.LibrarySourceIndex.Existing>>();
            for (Entry entry : entries) {
                token.check();
                var matches = index.verified(entry);
                if (!matches.isEmpty()) found.put(entry.key(), matches);
            }
            if (!found.isEmpty()) synchronized (this) {
                token.check();
                if (duplicateChoice == null)
                    duplicateChoice = new DuplicateChoice(++choiceIds, List.copyOf(entries), destination, selected, requested, java.util.Map.copyOf(found));
                message = Text.get("download.library.review_needed", found.size());
                published = null;
                return;
            }
        }
        commit(entries, destination, selected, token, requested, java.util.Set.of());
    }

    private synchronized void commit(List<Entry> entries, Path destination, Tools selected, ProcessRunner.Token token, DownloadEncoding.Format requested, java.util.Set<String> skipped) throws IOException {
        token.check();
        if (closed || token != analysisToken) throw new CancellationException();
        if (entries.isEmpty())
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.there_are_no_entries_in_that_selection"));
        if (entries.size() > MAX_BATCH || work.size() + entries.size() > MAX_BATCH)
            throw new IOException(Text.get("download.queue_capacity", MAX_BATCH));
        Path target = destination.toAbsolutePath().normalize();
        int added = 0, skippedCount = 0;
        for (Entry entry : entries) {
            var key = new ActiveKey(target, entry.key(), requested);
            if (!activeKeys.add(key)) continue;
            Work item = new Work(++ids, entry, target, selected);
            item.format = requested;
            work.add(item);
            if (skipped.contains(entry.key())) {
                item.status = Status.SKIPPED;
                activeKeys.remove(key);
                item.message = Text.get("download.library.skipped");
                item.progress = 1;
                skippedCount++;
            } else if (!entry.available()) {
                item.status = Status.FAILED;
                activeKeys.remove(key);
                item.message = Text.get("download.entry_unavailable");
                item.diagnostic = DownloadDiagnostics.classify(new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.CONTENT_UNAVAILABLE,
                    DownloadDiagnostics.Stage.DOWNLOAD, DownloadDiagnostics.Tool.NONE, null, "", null), DownloadDiagnostics.Stage.DOWNLOAD, entry.url(), item.id, 0, "");
            } else pending.add(item);
            if (item.status != Status.SKIPPED) added++;
        }
        message = skippedCount == 0 ? Text.get("download.queued_count", added, target) : Text.get("download.library.queued", added, skippedCount);
        published = null;
    }

    public synchronized void cancel(long id) {
        for (Work item : work)
            if (item.id == id && !item.status.finished()) {
                item.token.cancel();
                if (item.status == Status.QUEUED) {
                    pending.remove(item);
                    item.status = Status.CANCELLED;
                    activeKeys.remove(new ActiveKey(item.destination, item.entry.key(), item.format));
                    item.message = Text.get("download.darkmusic.cancelled");
                } else item.message = Text.get("download.cancelling");
            }
        published = null;
    }

    public synchronized void cancelAll() {
        duplicateChoice = null;
        work.forEach(w -> {
            w.retryAllowed = false;
            cancel(w.id);
        });
        cancelAnalysis();
    }

    public synchronized void clearFinished() {
        work.removeIf(w -> w.status.finished() && w.status != Status.FAILED);
        published = null;
    }

    public synchronized void clearFailed() {
        work.removeIf(w -> w.status == Status.FAILED);
        published = null;
    }

    public synchronized void retry(long id) {
        if (closed) return;
        for (Work item : work)
            if (item.id == id && item.status == Status.FAILED) {
                if (item.entry.url().isBlank()) {
                    message = Text.get("download.darkmusic.retry_missing_url");
                    published = null;
                    return;
                }
                item.tools = settings;
                item.format = format;
                item.attempts = 0;
                requeue(item);
            }
    }

    public synchronized boolean failedEntry(Entry entry, Path destination) {
        return work.stream().anyMatch(w -> w.status == Status.FAILED && w.entry.key().equals(entry.key()) && w.destination.equals(destination.toAbsolutePath().normalize()));
    }

    public synchronized void retryEntry(Entry entry, Path destination) {
        for (Work item : work)
            if (item.status == Status.FAILED && item.entry.key().equals(entry.key()) && item.destination.equals(destination.toAbsolutePath().normalize())) {
                retry(item.id);
                return;
            }
    }

    public synchronized void retryFailed() {
        for (Work item : work) if (item.status == Status.FAILED) retry(item.id);
    }

    private void requeue(Work item) {
        var key = new ActiveKey(item.destination, item.entry.key(), item.format);
        if (!activeKeys.add(key)) return;
        item.token = new ProcessRunner.Token();
        item.diagnostic = null;
        item.retryAllowed = true;
        item.status = Status.QUEUED;
        item.progress = -1;
        item.message = Text.get("download.darkmusic.retry_waiting", Math.max(0, item.attempts - 1), MAX_RETRIES);
        pending.add(item);
        published = null;
    }

    /**
     * Retry only after every queued/in-flight entry has finished its current pass.
     */
    private void retryAfterQueue() {
        if (closed || analyzing || work.stream().anyMatch(w -> !w.status.finished())) return;
        for (Work item : work)
            if (item.status == Status.FAILED && item.retryAllowed && item.attempts > 0 && item.attempts <= MAX_RETRIES && !item.token.cancelled())
                requeue(item);
    }

    public synchronized void cancelAnalysis() {
        analysisToken.cancel();
    }

    public synchronized boolean toolsBusy() {
        return analyzing && toolOperation;
    }

    public synchronized void cancelTools() {
        if (toolsBusy()) cancelAnalysis();
    }

    private synchronized void start(String text, Task task) {
        start(text, false, task);
    }

    private synchronized void start(String text, boolean tools, Task task) {
        if (closed) return;
        if (analyzing) {
            message = Text.get("download.darkmusic.busy");
            published = null;
            return;
        }
        analyzing = true;
        if (tools) toolDiagnostic = null;
        else analysisDiagnostic = null;
        long operation = -++analysisIds;
        String operationUrl = inputUrl;
        toolOperation = tools;
        published = null;
        message = text;
        ProcessRunner.Token token = new ProcessRunner.Token();
        analysisToken = token;
        analysis.execute(() -> {
            try {
                task.run(token);
                synchronized (this) {
                    if (message.equals(text)) message = Text.get("download.darkmusic.ready");
                }
            } catch (CancellationException | InterruptedException e) {
                synchronized (this) {
                    message = tools ? Text.get("download.darkmusic.tools_cancelled") : Text.get("download.darkmusic.cancelled");
                }
            } catch (Exception e) {
                LOG.warn("Download operation failed", e);
                synchronized (this) {
                    var diagnostic = DownloadDiagnostics.classify(e, tools ? DownloadDiagnostics.Stage.TOOLS : DownloadDiagnostics.Stage.ANALYSIS,
                        operationUrl, operation, 1, "");
                    if (tools) toolDiagnostic = diagnostic;
                    else analysisDiagnostic = diagnostic;
                    message = e instanceof IllegalArgumentException || Text.isMessage(e.getMessage()) ? e.getMessage() : diagnostic.summary();
                }
            } finally {
                synchronized (this) {
                    analyzing = false;
                    toolOperation = false;
                    published = null;
                    retryAfterQueue();
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
            String verifiedVersions = "";
            try {
                synchronized (this) {
                    item.attempts++;
                }
                item.token.check();
                update(item, Status.DOWNLOADING, -1, Text.get("download.darkmusic.tools_checking"));
                Toolchain chain = tools(item.tools, item.token);
                verifiedVersions = chain.versions();
                var result = pipeline.execute(item.entry, item.destination, chain, item.token, (status, detail) -> {
                    double percent = -1;
                    try {
                        if (detail.endsWith("%"))
                            percent = Math.clamp(Double.parseDouble(detail.replace("%", "").strip()) / 100, 0, 1);
                    } catch (NumberFormatException ignored) {
                    }
                    update(item, status, percent, detail);
                }, item.format, () -> lyricsEnabled, () -> lyricsLanguages);
                update(item, result.skipped() ? Status.SKIPPED : Status.SAVED, 1, result.file().getFileName() + (result.warning().isBlank() ? "" : " — " + result.warning()));
                if (awareness != null) awareness.saved(item.entry, result.file(), item.format);
                if (!result.skipped()) try {
                    refresh.run();
                } catch (RuntimeException e) {
                    LOG.warn("Saved download could not trigger a library refresh", e);
                }
            } catch (CancellationException | InterruptedException e) {
                update(item, Status.CANCELLED, -1, Text.get("download.darkmusic.cancelled"));
            } catch (Exception e) {
                LOG.warn("Download failed for entry {}", item.entry.mediaId(), e);
                synchronized (this) {
                    item.retryAllowed &= RetryPolicy.automatic(e);
                    item.diagnostic = DownloadDiagnostics.classify(e, DownloadDiagnostics.Stage.DOWNLOAD, item.entry.url(), item.id,
                        item.attempts, verifiedVersions);
                }
                update(item, Status.FAILED, -1, item.diagnostic.summary());
            }
        }
    }

    private synchronized void update(Work item, Status status, double progress, String detail) {
        if (status.finished()) activeKeys.remove(new ActiveKey(item.destination, item.entry.key(), item.format));
        published = null;
        item.status = status;
        item.progress = progress;
        item.message = detail;
        if (status == Status.SAVED || status == Status.SKIPPED || status == Status.CANCELLED) item.diagnostic = null;
        if (status.finished()) retryAfterQueue();
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

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return analysis.awaitTermination(seconds, TimeUnit.SECONDS) && downloads.awaitTermination(seconds, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface Task {
        void run(ProcessRunner.Token token) throws Exception;
    }

    private static final class Work {
        final long id;
        final Entry entry;
        final Path destination;
        Tools tools;
        DownloadEncoding.Format format = DownloadEncoding.Format.ORIGINAL;
        ProcessRunner.Token token = new ProcessRunner.Token();
        int attempts;
        boolean retryAllowed = true;
        Status status = Status.QUEUED;
        double progress = -1;
        String message = Text.get("download.waiting");
        DownloadDiagnostics.Diagnostic diagnostic;

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

    private record ActiveKey(Path directory, String media, DownloadEncoding.Format format) {
    }

    private record Cache(Tools settings, Toolchain tools) {
    }
}
