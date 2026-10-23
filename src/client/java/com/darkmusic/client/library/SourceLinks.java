package com.darkmusic.client.library;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Provenance belongs to the music, not a disposable player cache. File access is off-thread.
 */
public final class SourceLinks {
    private static final Object LOCK = new Object();
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Executor executor;

    public SourceLinks(Executor executor) {
        this.executor = executor;
    }

    public static String safe(String value) {
        try {
            if (value == null || value.length() > 8192) return "";
            URI uri = URI.create(value.strip());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    public String get(Track track) {
        if (track == null) return "";
        String key = track.file().toAbsolutePath() + ":" + track.size() + ":" + track.modified();
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
            if (cache.size() >= 128) cache.remove(cache.keySet().iterator().next());
            cache.put(key, "");
        }
        try {
            executor.execute(() -> {
                String url = read(track.file());
                if (url.isEmpty()) try {
                    var info = com.darkmusic.client.audio.ExternalAudio.probe(track.file());
                    if (info.has("format")) url = tags(info.getAsJsonObject("format"));
                    if (url.isEmpty() && info.has("streams")) for (var stream : info.getAsJsonArray("streams")) {
                        url = tags(stream.getAsJsonObject());
                        if (!url.isEmpty()) break;
                    }
                } catch (Exception ignored) {
                }
                synchronized (cache) {
                    if (cache.containsKey(key)) cache.put(key, url);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            cache.remove(key);
        }
        return "";
    }

    private static String tags(JsonObject object) {
        if (!object.has("tags")) return "";
        for (var entry : object.getAsJsonObject("tags").entrySet())
            if (Set.of("purl", "webpage_url", "source_url", "url", "comment").contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                String url = safe(entry.getValue().getAsString());
                if (!url.isEmpty()) return url;
            }
        return "";
    }

    private static JsonObject json(Path path) throws Exception {
        if (!Files.exists(path)) return new JsonObject();
        if (Files.size(path) > 16_000_000) throw new java.io.IOException("Source history is too large");
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    public static String read(Path audio) {
        synchronized (LOCK) {
            try {
                String name = audio.getFileName().toString();
                var sources = json(audio.resolveSibling(".darkmusic-sources.json"));
                if (sources.has(name)) {
                    String url = safe(sources.get(name).getAsString());
                    if (!url.isEmpty()) return url;
                }
                // Recover provenance for downloads made before source URLs were stored explicitly.
                var history = json(audio.resolveSibling(".darkmusic-downloads.json"));
                for (var entry : history.entrySet())
                    if (name.equals(entry.getValue().getAsString())) {
                        String[] id = entry.getKey().split(":");
                        if (id.length >= 2 && id[0].equalsIgnoreCase("youtube") && id[1].matches("[A-Za-z0-9_-]{11}"))
                            return "https://www.youtube.com/watch?v=" + id[1];
                        if (id.length >= 2 && id[0].toLowerCase(Locale.ROOT).startsWith("bilibili") && id[1].matches("BV[A-Za-z0-9]+|av[0-9]+"))
                            return "https://www.bilibili.com/video/" + id[1];
                    }
            } catch (Exception ignored) {
            }
            return "";
        }
    }

    /**
     * Background bulk readers load each directory's provenance once, rather than once per song.
     */
    public static Map<String, String> readDirectory(Path directory) {
        synchronized (LOCK) {
            var result = new HashMap<String, String>();
            try {
                for (var e : json(directory.resolve(".darkmusic-sources.json")).entrySet()) {
                    String url = safe(e.getValue().getAsString());
                    if (!url.isEmpty()) result.put(e.getKey(), url);
                }
                for (var e : json(directory.resolve(".darkmusic-downloads.json")).entrySet()) {
                    String[] id = e.getKey().split(":");
                    String url = "";
                    if (id.length >= 2 && id[0].equalsIgnoreCase("youtube") && id[1].matches("[A-Za-z0-9_-]{11}"))
                        url = "https://www.youtube.com/watch?v=" + id[1];
                    else if (id.length >= 2 && id[0].toLowerCase(Locale.ROOT).startsWith("bilibili") && id[1].matches("BV[A-Za-z0-9]+|av[0-9]+"))
                        url = "https://www.bilibili.com/video/" + id[1];
                    if (!url.isEmpty()) result.putIfAbsent(e.getValue().getAsString(), url);
                }
            } catch (Exception ignored) {
            }
            return Map.copyOf(result);
        }
    }

    public static void record(Path audio, String url) {
        url = safe(url);
        if (url.isEmpty()) return;
        synchronized (LOCK) {
            try {
                Path file = audio.resolveSibling(".darkmusic-sources.json");
                var data = json(file);
                data.addProperty(audio.getFileName().toString(), url);
                ConfigStore.atomicWrite(file, ConfigStore.JSON.toJson(data));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(SourceLinks.class).warn("Could not save source link for {}", audio, e);
            }
        }
    }
}
