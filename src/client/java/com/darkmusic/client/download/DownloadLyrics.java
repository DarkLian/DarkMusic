package com.darkmusic.client.download;

import com.darkmusic.client.lyrics.Lrc;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Optional source captions only; no lyric search, transcription, or authentication.
 */
public final class DownloadLyrics {
    public record Result(String text, String warning) {
    }

    private DownloadLyrics() {
    }

    public static String language(JsonObject metadata) {
        if (!metadata.has("subtitles") || !metadata.get("subtitles").isJsonObject()) return "";
        var subtitles = metadata.getAsJsonObject("subtitles");
        var keys = subtitles.keySet().stream().filter(k -> !k.equals("live_chat") && k.matches("[A-Za-z0-9_-]{1,35}"))
            .filter(k -> subtitles.get(k).isJsonArray() && !subtitles.getAsJsonArray(k).isEmpty()).sorted().toList();
        String original = DownloadJson.text(metadata, "language");
        for (String preferred : List.of(original, Locale.getDefault().toLanguageTag(), Locale.getDefault().getLanguage(), "en")) {
            for (String key : keys) if (!preferred.isBlank() && key.equalsIgnoreCase(preferred)) return key;
            for (String key : keys)
                if (!preferred.isBlank() && key.toLowerCase(Locale.ROOT).startsWith(preferred.toLowerCase(Locale.ROOT) + "-"))
                    return key;
        }
        return keys.isEmpty() ? "" : keys.getFirst();
    }

    public static Result fetch(ProcessRunner runner, Toolchain tools, DownloadModels.Entry entry, JsonObject metadata, Path work, ProcessRunner.Token token) throws Exception {
        token.check();
        String language = language(metadata);
        if (language.isEmpty()) return new Result("", com.darkmusic.client.text.Text.get("lyrics.darkmusic.no_captions"));
        try {
            var args = tools.base();
            args.addAll(List.of("--skip-download", "--no-playlist", "--playlist-items", "1", "--write-subs", "--no-write-auto-subs", "--sub-langs", language, "--sub-format", "lrc/vtt/srt/best", "--convert-subs", "lrc", "--paths", work.toString(), "--output", "lyrics.%(ext)s", "--", entry.url()));
            var result = runner.run(args, work, token, Duration.ofSeconds(90), 32_000, s -> {
            });
            if (result.exit() != 0) throw new java.io.IOException(DownloadJson.friendly(result.output()));
            try (var files = Files.list(work)) {
                Path lrc = files.filter(p -> p.getFileName().toString().startsWith("lyrics.") && p.toString().endsWith(".lrc")).filter(Files::isRegularFile).sorted().findFirst().orElse(null);
                if (lrc != null && Files.size(lrc) <= 1_000_000) {
                    String text = Files.readString(lrc);
                    if (!Lrc.parse(text).lines().isEmpty()) return new Result(text, "");
                }
            }
            return new Result("", com.darkmusic.client.text.Text.get("lyrics.darkmusic.no_timed_captions"));
        } catch (Exception failure) {
            token.check();
            if (failure instanceof InterruptedException) throw failure;
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Optional lyric captions could not be downloaded", failure);
            return new Result("", com.darkmusic.client.text.Text.get("lyrics.darkmusic.download_failed"));
        }
    }

    public static Path sidecar(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return audio.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".lrc");
    }
}
