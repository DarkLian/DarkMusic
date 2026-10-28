package com.darkmusic.client.lyrics;

import com.darkmusic.client.tools.ProcessRunner;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Anonymous, bounded requests to LRCLIB only. No uploads or provider-controlled redirects.
 */
public final class LrclibProvider implements LyricsProvider, AutoCloseable {
    public enum Problem {NETWORK, RATE_LIMITED, PROVIDER, INVALID, REJECTED}

    public static final class Failure extends IOException {
        public final Problem problem;
        public final int status;
        public final long retryAfterMillis;

        public Failure(Problem problem) {
            this(problem, 0, 0);
        }

        public Failure(Problem problem, int status, long retryAfterMillis) {
            super(problem.name());
            this.problem = problem;
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    public record Reply(int status, String body, String retryAfter) {
    }

    @FunctionalInterface
    public interface Transport {
        Reply get(URI uri, ProcessRunner.Token token) throws Exception;
    }

    private final HttpClient client;
    private final Transport transport;
    private final AtomicLong requestCount = new AtomicLong();

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    private long nextRequest, cooldownUntil;
    private final ReentrantLock requests = new ReentrantLock(true);
    private Problem cooldownProblem = Problem.RATE_LIMITED;
    private int cooldownStatus;

    private static String userAgent() {
        String version = "development";
        try {
            version = FabricLoader.getInstance().getModContainer("darkmusic").orElseThrow().getMetadata().getVersion().getFriendlyString();
        } catch (RuntimeException ignored) {
        }
        return "DarkMusic/" + version.replaceAll("[^A-Za-z0-9._+-]", "") + " (https://github.com/DarkLian/DarkMusic)";
    }

    public LrclibProvider() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        transport = this::http;
    }

    public LrclibProvider(Transport transport) {
        this.client = null;
        this.transport = transport;
    }

    @Override
    public String id() {
        return "lrclib";
    }

    @Override
    public List<Candidate> search(Query query, ProcessRunner.Token token) throws Exception {
        return lockedSearch(query, token, false);
    }

    @Override
    public List<Candidate> alternatives(Query query, ProcessRunner.Token token) throws Exception {
        return lockedSearch(query, token, true);
    }

    private List<Candidate> lockedSearch(Query query, ProcessRunner.Token token, boolean alternatives) throws Exception {
        while (!requests.tryLock(50, TimeUnit.MILLISECONDS)) token.check();
        try {
            token.check();
            return search(query, token, alternatives);
        } finally {
            requests.unlock();
        }
    }

    private List<Candidate> search(Query query, ProcessRunner.Token token, boolean alternatives) throws Exception {
        if (query.title().isBlank()) return List.of();
        String params = "track_name=" + encode(query.title()) + "&artist_name=" + encode(query.artist());
        if (!query.album().isBlank()) params += "&album_name=" + encode(query.album());
        // LRCLIB accepts duration only within 1–3600 seconds; it is optional.
        if (Double.isFinite(query.duration()) && query.duration() >= 1 && query.duration() <= 3600)
            params += "&duration=" + Math.round(query.duration());
        var found = new LinkedHashMap<String, Candidate>();
        if (!query.artist().isBlank()) try {
            add(found, request("/api/get?" + params, token), query);
        } catch (Failure failure) {
            // A rejected signature or failed exact-lookup backend need not prevent a valid search.
            if (failure.status != 400 && failure.status != 500) throw failure;
        }
        // A precise endpoint can still return a weak match. Search for alternatives rather than applying it blindly.
        if (alternatives || found.values().stream().noneMatch(Candidate::reliable)) {
            String search = "track_name=" + encode(query.title());
            if (!query.artist().isBlank()) search += "&artist_name=" + encode(query.artist());
            add(found, request("/api/search?" + search, token), query);
        }
        return found.values().stream().sorted(Comparator.comparing(Candidate::reliable).reversed()).limit(20).toList();
    }

    private JsonElement request(String path, ProcessRunner.Token token) throws Exception {
        if (System.nanoTime() < cooldownUntil)
            throw new Failure(cooldownProblem, cooldownStatus, Math.max(1, TimeUnit.NANOSECONDS.toMillis(cooldownUntil - System.nanoTime())));
        while (System.nanoTime() < nextRequest) {
            token.check();
            Thread.sleep(50);
        }
        token.check();
        Reply reply;
        try {
            requestCount.incrementAndGet();
            reply = transport.get(URI.create("https://lrclib.net" + path), token);
        } catch (CancellationException | InterruptedException e) {
            throw e;
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw new Failure(Problem.NETWORK);
        } finally {
            // Identify the client and leave headroom below its current per-IP allowance.
            nextRequest = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
        }
        if (reply.status == 404) return JsonNull.INSTANCE;
        if (reply.status == 429 || reply.status == 503) {
            // Missing Retry-After on a 503 is not proof of a provider-wide rate limit.
            long seconds = reply.status == 429 ? 60 : 0;
            try {
                seconds = Long.parseLong(reply.retryAfter);
            } catch (Exception ignored) {
                try {
                    seconds = Duration.between(Instant.now(), ZonedDateTime.parse(reply.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
                } catch (Exception alsoIgnored) {
                }
            }
            long waitMillis = TimeUnit.SECONDS.toMillis(Math.clamp(seconds, reply.status == 429 ? 1 : 0, TimeUnit.HOURS.toSeconds(24)));
            try {
                cooldownUntil = Math.addExact(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(waitMillis));
            } catch (ArithmeticException excessiveWait) {
                cooldownUntil = Long.MAX_VALUE;
            }
            cooldownProblem = reply.status == 429 ? Problem.RATE_LIMITED : Problem.PROVIDER;
            cooldownStatus = reply.status;
            throw new Failure(cooldownProblem, reply.status, waitMillis);
        }
        if (reply.status != 200)
            throw new Failure(reply.status == 400 ? Problem.REJECTED : Problem.PROVIDER, reply.status, 0);
        if (reply.body.length() > 2_000_000) throw new Failure(Problem.INVALID);
        try {
            JsonElement parsed = JsonParser.parseString(reply.body);
            if (!parsed.isJsonObject() && !parsed.isJsonArray()) throw new Failure(Problem.INVALID);
            return parsed;
        } catch (RuntimeException e) {
            throw new Failure(Problem.INVALID);
        }
    }

    private static void add(Map<String, Candidate> found, JsonElement data, Query query) throws Failure {
        if (data.isJsonNull()) return;
        var records = new ArrayList<JsonElement>();
        if (data.isJsonArray()) data.getAsJsonArray().forEach(records::add);
        else records.add(data);
        if (records.size() > 100) throw new Failure(Problem.INVALID);
        for (var value : records)
            try {
                var o = value.getAsJsonObject();
                String id = string(o, "id"), synced = string(o, "syncedLyrics"), plain = string(o, "plainLyrics");
                boolean instrumental = o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean();
                if (id.isBlank()) continue;
                if (plain.isBlank() && !synced.isBlank() && Lrc.parse(synced).lines().isEmpty()) continue;
                var document = LyricsDocument.of("lrclib", id, string(o, "trackName"), string(o, "artistName"), string(o, "albumName"),
                    o.has("duration") ? o.get("duration").getAsDouble() : 0, "und", LyricsDocument.Variant.UNKNOWN, false, instrumental,
                    !synced.isBlank() && !Lrc.parse(synced).lines().isEmpty() ? synced : plain);
                if (document.available())
                    found.putIfAbsent(id, new Candidate(document, LyricsMatcher.reliable(query, document), "lyrics.match.metadata"));
            } catch (RuntimeException invalid) { /* An invalid result does not invalidate its valid neighbours. */ }
    }

    private static String string(JsonObject o, String key) {
        return !o.has(key) || o.get(key).isJsonNull() ? "" : o.get(key).getAsString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    Reply http(URI uri, ProcessRunner.Token token) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("User-Agent", userAgent()).header("Lrclib-Client", userAgent())
            .header("Accept", "application/json").GET().build();
        var future = client.sendAsync(request, info -> new BoundedBody());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        try {
            for (; ; ) {
                token.check();
                if (System.nanoTime() >= deadline) throw new Failure(Problem.NETWORK);
                try {
                    var response = future.get(100, TimeUnit.MILLISECONDS);
                    return new Reply(response.statusCode(), StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString(), response.headers().firstValue("Retry-After").orElse(null));
                } catch (TimeoutException waiting) {
                }
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 2_000_000 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new Failure(Problem.INVALID));
                    return;
                }
                while (buffer.hasRemaining()) bytes.write(buffer.get());
            }
            subscription.request(1);
        }

        public void onError(Throwable e) {
            result.completeExceptionally(e);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    @Override
    public void close() {
        if (client != null) client.shutdownNow();
    }
}
