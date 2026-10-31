package com.darkmusic.client.download;

import com.darkmusic.client.config.LyricsLanguages;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.CaptionUrls;
import com.darkmusic.client.lyrics.LyricsDocument;
import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.ToolPaths;
import com.darkmusic.client.tools.Toolchain;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import static com.darkmusic.client.download.DownloadModels.Entry;
import static com.darkmusic.client.download.DownloadModels.url;

/**
 * Lyrics from a song's source video, for music already in the library: the captions yt-dlp finds at the song's
 * recorded link. Uses the configured tools and installs nothing. Runs on the lyric lookup's own threads.
 */
final class CaptionFetcher {
    /**
     * The service's toolchain for the paths, resolved through its cache.
     */
    @FunctionalInterface
    interface Tools {
        Toolchain resolve(ToolPaths selected, ProcessRunner.Token token) throws Exception;
    }

    private final Path data;
    private final ProcessRunner runner;
    private final Supplier<ToolPaths> settings;
    private final Tools tools;

    CaptionFetcher(Path data, ProcessRunner runner, Supplier<ToolPaths> settings, Tools tools) {
        this.data = data;
        this.runner = runner;
        this.settings = settings;
        this.tools = tools;
    }

    /**
     * Explicit lyric lookup for existing music, using configured tools without installing anything.
     */
    LyricsDocument fetchLyrics(Track track, String preferences, boolean generated, ProcessRunner.Token token) throws Exception {
        String link = CaptionUrls.automatic(SourceLinks.read(track.file()));
        return fetchLyrics(track, link, preferences, generated, token);
    }

    LyricsDocument fetchSourceLyrics(Track track, String preferences, boolean generated, ProcessRunner.Token token) throws Exception {
        String source = SourceLinks.resolve(track.file());
        String canonical = CaptionUrls.automatic(source);
        if (!source.isBlank() && canonical.isBlank())
            throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.UNSUPPORTED_URL, DownloadDiagnostics.Stage.OPTIONAL,
                DownloadDiagnostics.Tool.YT_DLP, null, "", null);
        return fetchLyrics(track, canonical, preferences, generated, token);
    }

    private LyricsDocument fetchLyrics(Track track, String link, String preferences, boolean generated, ProcessRunner.Token token) throws Exception {
        if (link.isBlank()) return null;
        try {
            link = CaptionUrls.publicUrl(link);
        } catch (UnknownHostException network) {
            throw network;
        } catch (IOException rejected) {
            throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.UNSUPPORTED_URL, DownloadDiagnostics.Stage.OPTIONAL,
                DownloadDiagnostics.Tool.YT_DLP, null, "", rejected);
        }
        ToolPaths selected = settings.get();
        var chain = tools.resolve(selected, token);
        Files.createDirectories(data);
        Path work = Files.createTempDirectory(data, ".lyrics-");
        try {
            var args = chain.base();
            args.addAll(List.of("--dump-single-json", "--skip-download", "--no-playlist", "--playlist-items", "1", "--", url(link)));
            var result = runner.run(args, work, token, Duration.ofSeconds(90), 2_000_000);
            if (result.exit() != 0)
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Stage.ANALYSIS, DownloadDiagnostics.Tool.YT_DLP, result.exit(), result.output());
            var metadata = DownloadJson.object(result.output());
            var entry = new Entry(1, track.title(), link, "", "", true);
            try {
                var tag = AudioFileIO.read(track.file().toFile()).getTag();
                if (tag != null)
                    preferences = LyricsLanguages.preferSongLanguage(preferences, tag.getFirst(FieldKey.LANGUAGE));
            } catch (
                Exception unavailable) { /* Unknown song language remains Auto; video/UI language is not substituted. */ }
            var lyrics = DownloadLyrics.fetch(runner, chain, entry, metadata, work, token, LyricsLanguages.resolve(preferences), generated);
            if (lyrics.failure() != null)
                throw new DownloadDiagnostics.Failure(lyrics.failure(), DownloadDiagnostics.Stage.OPTIONAL, DownloadDiagnostics.Tool.YT_DLP, null, "", null);
            return lyrics.text().isBlank() ? null : DownloadLyrics.document(lyrics);
        } finally {
            try (var files = Files.walk(work)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
