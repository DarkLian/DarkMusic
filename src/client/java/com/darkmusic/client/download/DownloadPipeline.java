package com.darkmusic.client.download;

import com.darkmusic.client.config.ConfigStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;

import static com.darkmusic.client.download.DownloadJson.friendly;
import static com.darkmusic.client.download.DownloadJson.text;
import static com.darkmusic.client.download.DownloadModels.*;

/**
 * Downloads into isolated scratch space; only verified, complete MP3s enter the library.
 */
public final class DownloadPipeline {
    private final ProcessRunner runner;
    private final Path data;
    private DownloadWorkspace workspace;

    public DownloadPipeline(ProcessRunner runner, Path data) {
        this.runner = runner;
        this.data = data;
    }

    synchronized void prepare() throws IOException {
        if (workspace == null) workspace = new DownloadWorkspace(data);
    }

    synchronized void close() {
        if (workspace != null) try {
            workspace.close();
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Cannot close workspace", e);
        } finally {
            workspace = null;
        }
    }

    private static JsonObject readArchive(Path history) throws IOException {
        if (!Files.exists(history)) return new JsonObject();
        if (Files.size(history) > 16_000_000)
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.download_history_is_too_large_preserve_it_and_choose_another_folder"));
        try (var reader = Files.newBufferedReader(history)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Download history could not be read; the original has been preserved.", e);
        }
    }

    static List<String> convert(Toolchain tools, Path source, Path target, Path cover, JsonObject metadata, Entry entry) {
        var args = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", source.toString()));
        if (cover != null) args.addAll(List.of("-protocol_whitelist", "file,pipe", "-i", cover.toString()));
        args.addAll(List.of("-map", "0:a:0", "-map_metadata", "-1", "-c:a", "libmp3lame", "-q:a", "0", "-id3v2_version", "3"));
        if (cover == null) args.add("-vn");
        else args.addAll(List.of("-map", "1:v:0", "-c:v", "mjpeg", "-disposition:v:0", "attached_pic"));
        tag(args, "title", text(metadata, "title"));
        String artist = text(metadata, "artist");
        if (artist.isBlank()) artist = text(metadata, "uploader");
        tag(args, "artist", artist);
        tag(args, "album", text(metadata, "album"));
        String track = text(metadata, "track_number");
        if (track.isBlank() && entry.index() > 0) track = Integer.toString(entry.index());
        tag(args, "track", track);
        args.add(target.toString());
        return args;
    }

    private static void tag(List<String> args, String key, String value) {
        value = value.replace("\u0000", "");
        if (!value.isBlank())
            args.addAll(List.of("-metadata", key + "=" + value.substring(0, Math.min(value.length(), 1024))));
    }

    public static String filename(String title, String identity) {
        String safe = title.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip().replaceAll("^[. ]+|[. ]+$", "");
        if (safe.isBlank()) safe = "Audio";
        safe = safe.substring(0, safe.offsetByCodePoints(0, Math.min(100, safe.codePointCount(0, safe.length()))));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return safe + " [" + HexFormat.of().formatHex(hash, 0, 8) + "].mp3";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress) throws Exception {
        token.check();
        Files.createDirectories(destination);
        if (!Files.isWritable(destination))
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.the_selected_music_folder_is_not_writable"));
        if (Files.getFileStore(destination).getUsableSpace() < 16 * 1024 * 1024)
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.not_enough_disk_space_in_the_music_folder"));
        Path history = destination.resolve(".darkmusic-downloads.json");
        JsonObject archive = readArchive(history);
        String identity = entry.key();
        if (archive.has(identity)) {
            Path prior = destination.resolve(archive.get(identity).getAsString()).normalize();
            if (prior.getParent().equals(destination.toAbsolutePath().normalize()) && Files.isRegularFile(prior) && Files.size(prior) > 0)
                return new Saved(prior, true, "Already downloaded");
        }
        Path scratchRoot = data.resolve("downloads/work");
        Files.createDirectories(scratchRoot);
        Path work = Files.createTempDirectory(scratchRoot, "item-");
        Path incoming = null;
        try {
            var command = tools.base();
            command.addAll(List.of("--no-playlist", "--playlist-items", "1", "--no-overwrites", "--newline", "--progress", "--progress-delta", "1", "--progress-template", "download:DM_PROGRESS:%(progress._percent_str)s", "--format", "bestaudio/best", "--write-info-json", "--write-thumbnail", "--paths", work.toString(), "--output", "source.%(ext)s", "--", url(entry.url())));
            progress.accept(Status.DOWNLOADING, "Starting download");
            var result = runner.run(command, work, token, Duration.ofMinutes(30), 128_000, s -> {
                if (s.startsWith("DM_PROGRESS:")) progress.accept(Status.DOWNLOADING, s.substring(12).strip());
                else if (!s.isBlank()) org.slf4j.LoggerFactory.getLogger("darkmusic-download").debug("yt-dlp: {}", s);
            });
            if (result.exit() != 0) throw new IOException(friendly(result.output()));
            Path source;
            try (var files = Files.list(work)) {
                source = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("source.")).filter(p -> !p.toString().matches("(?i).*\\.(json|jpg|jpeg|png|webp|avif|gif|part|ytdl)$")).findFirst().orElseThrow(() -> new IOException("yt-dlp did not produce a complete media file."));
            }
            JsonObject metadata = new JsonObject();
            Path info = work.resolve("source.info.json");
            if (Files.isRegularFile(info) && Files.size(info) < 16_000_000) {
                try (var reader = Files.newBufferedReader(info)) {
                    metadata = JsonParser.parseReader(reader).getAsJsonObject();
                } catch (RuntimeException ignored) { /* Audio can still be saved without optional metadata. */ }
            }
            Path cover;
            try (var files = Files.list(work)) {
                cover = files.filter(p -> p.toString().matches("(?i).*\\.(jpg|jpeg|png|webp|avif)$")).findFirst().orElse(null);
            }
            Path converted = work.resolve("converted.mp3");
            progress.accept(Status.CONVERTING, "Converting to MP3 and embedding available metadata");
            String warning = cover == null ? "No thumbnail was available" : "";
            result = runner.run(convert(tools, source, converted, cover, metadata, entry), work, token, Duration.ofMinutes(20), 64_000, s -> {
            });
            if (result.exit() != 0 && cover != null) {
                token.check();
                warning = "Saved without artwork: thumbnail embedding failed";
                result = runner.run(convert(tools, source, converted, null, metadata, entry), work, token, Duration.ofMinutes(20), 64_000, s -> {
                });
            }
            if (result.exit() != 0) throw new IOException("MP3 conversion failed: " + friendly(result.output()));
            verify(tools, converted, work, token);
            token.check();
            String title = text(metadata, "title");
            if (title.isBlank()) title = entry.title();
            String name = filename(title, identity);
            Path target = destination.resolve(name);
            for (int suffix = 2; Files.exists(target); suffix++)
                target = destination.resolve(name.substring(0, name.length() - 4) + " (" + suffix + ").mp3");
            incoming = Files.createTempFile(destination, ".darkmusic-", ".tmp");
            try (var input = Files.newInputStream(converted); var output = Files.newOutputStream(incoming)) {
                byte[] buffer = new byte[32_768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    token.check();
                    output.write(buffer, 0, read);
                }
            }
            token.check();
            Files.move(incoming, target);
            incoming = null;
            archive.addProperty(identity, target.getFileName().toString());
            try {
                ConfigStore.atomicWrite(history, ConfigStore.JSON.toJson(archive));
            } catch (IOException e) {
                warning = "Saved, but duplicate history could not be updated";
            }
            return new Saved(target, false, warning);
        } finally {
            try {
                if (incoming != null) Files.deleteIfExists(incoming);
                try (var files = Files.walk(work)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Could not clean download scratch files in {}", work, e);
            }
        }
    }

    private void verify(Toolchain tools, Path file, Path work, ProcessRunner.Token token) throws Exception {
        if (!Files.isRegularFile(file) || Files.size(file) == 0)
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.conversion_produced_no_mp3_file"));
        var r = runner.run(List.of(tools.ffprobe().toString(), "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "json", file.toString()), work, token, Duration.ofSeconds(30), 32_000, s -> {
        });
        if (r.exit() != 0 || !r.output().contains("\"mp3\""))
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.the_converted_file_failed_mp3_validation"));
    }

    public record Saved(Path file, boolean skipped, String warning) {
    }
}
