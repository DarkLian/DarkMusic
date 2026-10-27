package com.darkmusic.client.download;

import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.library.Track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A bounded temporary excerpt; never published to the library or download history.
 */
public record AuditionClip(Path directory, Track track) implements AutoCloseable {
    public static AuditionClip fetch(DownloadModels.Entry entry, DownloadModels.Tools selected, Path data,
                                     ProcessRunner runner, ProcessRunner.Token token) throws Exception {
        if (!entry.available()) throw new IOException("This preview entry is unavailable");
        Path directory = Files.createTempDirectory("darkmusic-audition-");
        try {
            var tools = Toolchain.resolve(selected, data, runner, token);
            var args = tools.base();
            args.addAll(List.of("--no-playlist", "--playlist-items", "1", "--format", "bestaudio/best",
                "--download-sections", "*0-30", "--max-filesize", "50M",
                "--no-write-thumbnail", "--no-write-info-json", "--output", "source.%(ext)s", "--", DownloadModels.url(entry.url())));
            var result = runner.run(args, directory, token, Duration.ofMinutes(2), 100_000, line -> {
            });
            token.check();
            if (result.exit() != 0) throw new IOException(DownloadJson.friendly(result.output()));
            Path source;
            try (var files = Files.list(directory)) {
                source = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("source.")).filter(AudioFormats::candidate).findFirst().orElseThrow(() -> new IOException("The site did not provide a usable short audio preview"));
            }
            Path clip = directory.resolve("preview.mp3");
            if (!Files.isRegularFile(source) || Files.size(source) > 64_000_000)
                throw new IOException("The site did not provide a usable short audio preview");
            // Enforce the playback limit locally as well; some extractors approximate section boundaries.
            var converted = runner.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe", "-i", source.toString(),
                "-t", "30", "-fs", "8388608", "-vn", "-map_metadata", "-1", "-c:a", "libmp3lame", "-q:a", "5", clip.toString()), directory, token, Duration.ofSeconds(45), 32000, line -> {
            });
            token.check();
            if (converted.exit() != 0 || !Files.isRegularFile(clip) || Files.size(clip) < 256)
                throw new IOException("Could not convert this audio preview");
            return new AuditionClip(directory, new Track("audition", "preview.mp3", clip, entry.title(), "", "", 30, Files.size(clip), 0, ""));
        } catch (Exception e) {
            DownloadWorkspace.remove(directory);
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        DownloadWorkspace.remove(directory);
    }
}
