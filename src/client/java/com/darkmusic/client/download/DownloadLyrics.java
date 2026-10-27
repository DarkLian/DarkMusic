package com.darkmusic.client.download;

import com.darkmusic.client.config.LyricsLanguages;
import com.darkmusic.client.lyrics.Lrc;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Optional source captions only; no lyric search, transcription, or authentication.
 */
public final class DownloadLyrics {
    public record Result(String text, String warning, String language, boolean generated,
                         DownloadDiagnostics.Category failure) {
        public Result(String text, String warning) {
            this(text, warning, "und", false, null);
        }

        public Result(String text, String warning, String language, boolean generated) {
            this(text, warning, language, generated, null);
        }
    }

    private DownloadLyrics() {
    }

    public static String language(JsonObject metadata) {
        return LyricsLanguages.select(metadata, LyricsLanguages.resolve("auto"));
    }

    public static Result fetch(ProcessRunner runner, Toolchain tools, DownloadModels.Entry entry, JsonObject metadata, Path work, ProcessRunner.Token token) throws Exception {
        return fetch(runner, tools, entry, metadata, work, token, LyricsLanguages.resolve("auto"));
    }

    public static Result fetch(ProcessRunner runner, Toolchain tools, DownloadModels.Entry entry, JsonObject metadata, Path work, ProcessRunner.Token token, List<String> preferences) throws Exception {
        return fetch(runner, tools, entry, metadata, work, token, preferences, false);
    }

    public static Result fetch(ProcessRunner runner, Toolchain tools, DownloadModels.Entry entry, JsonObject metadata, Path work, ProcessRunner.Token token, List<String> preferences, boolean allowGenerated) throws Exception {
        token.check();
        String link = DownloadModels.url(entry.url());
        String language = LyricsLanguages.select(metadata, preferences);
        boolean generated = false;
        if (language.isEmpty() && allowGenerated) {
            language = LyricsLanguages.select(metadata, preferences, true);
            generated = !language.isEmpty();
        }
        if (language.isEmpty())
            return new Result("", Text.get("lyrics.darkmusic.no_preferred_captions"));
        try {
            var args = tools.base();
            args.addAll(List.of("--skip-download", "--no-playlist", "--playlist-items", "1", generated ? "--no-write-subs" : "--write-subs", generated ? "--write-auto-subs" : "--no-write-auto-subs", "--extractor-args", "youtube:skip=translated_subs", "--sub-langs", "^" + language + "$", "--sub-format", "lrc/vtt/srt/best", "--convert-subs", "lrc", "--paths", work.toString(), "--output", "lyrics.%(ext)s", "--", link));
            var result = runner.run(args, work, token, Duration.ofSeconds(90), 32_000, s -> {
            });
            if (result.exit() != 0)
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Stage.OPTIONAL, DownloadDiagnostics.Tool.YT_DLP, result.exit(), result.output());
            try (var files = Files.list(work)) {
                Path lrc = files.filter(p -> p.getFileName().toString().startsWith("lyrics.") && p.toString().endsWith(".lrc")).filter(Files::isRegularFile).sorted().findFirst().orElse(null);
                if (lrc != null && Files.size(lrc) <= 1_000_000) {
                    String text = Files.readString(lrc);
                    if (!Lrc.parse(text).lines().isEmpty()) return new Result(text, "", language, generated);
                }
            }
            return new Result("", Text.get("lyrics.darkmusic.no_timed_captions"));
        } catch (Exception failure) {
            token.check();
            if (failure instanceof InterruptedException) throw failure;
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Optional lyric captions could not be downloaded", failure);
            var category = DownloadDiagnostics.classify(failure, DownloadDiagnostics.Stage.OPTIONAL, link, 0, 1, "").category();
            return new Result("", Text.get("lyrics.darkmusic.download_failed"), "und", false, category);
        }
    }

    public static LyricsDocument document(Result result) {
        return LyricsDocument.of("captions", "", "", "", "", 0, result.language(),
            LyricsDocument.Variant.UNKNOWN, result.generated(), false, result.text());
    }
}
