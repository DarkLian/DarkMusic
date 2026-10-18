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
 * Downloads into isolated scratch space; only verified, complete audio enters the library.
 */
public final class DownloadPipeline {
    private final ProcessRunner runner;
    private final Path data;
    private final Object historyLock = new Object();
    private DownloadWorkspace workspace;
    private int workspaceUsers;

    public DownloadPipeline(ProcessRunner runner, Path data) {
        this.runner = runner;
        this.data = data;
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
        return filename(title, identity, "mp3");
    }

    public static String filename(String title, String identity, String extension) {
        if (!java.util.Set.of("mp3", "m4a", "opus", "ogg", "flac", "wav").contains(extension))
            throw new IllegalArgumentException("Unsupported download extension");
        String safe = title.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip().replaceAll("^[. ]+|[. ]+$", "");
        if (safe.isBlank()) safe = "Audio";
        safe = safe.substring(0, safe.offsetByCodePoints(0, Math.min(100, safe.codePointCount(0, safe.length()))));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return safe + " [" + HexFormat.of().formatHex(hash, 0, 8) + "]." + extension;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path previous(JsonObject archive, String identity, Path destination) throws IOException {
        if (!archive.has(identity)) return null;
        Path prior = destination.resolve(archive.get(identity).getAsString()).normalize();
        return prior.getParent().equals(destination.toAbsolutePath().normalize()) && Files.isRegularFile(prior) && Files.size(prior) > 0 ? prior : null;
    }

    synchronized void prepare() throws IOException {
        if (workspace == null) workspace = new DownloadWorkspace(data, false);
        workspaceUsers++;
    }

    synchronized void close() {
        if (workspaceUsers == 0 || --workspaceUsers != 0) return;
        if (workspace != null) try {
            workspace.close();
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Cannot close workspace", e);
        } finally {
            workspace = null;
        }
    }

    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress) throws Exception {
        return execute(entry, destination, tools, token, progress, DownloadEncoding.Format.MP3);
    }

    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress, DownloadEncoding.Format format) throws Exception {
        return execute(entry, destination, tools, token, progress, format, () -> false);
    }

    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress, DownloadEncoding.Format format, java.util.function.BooleanSupplier lyricsEnabled) throws Exception {
        token.check();
        prepare();
        try {
            return download(entry, destination, tools, token, progress, format, lyricsEnabled);
        } finally {
            close();
        }
    }

    private Saved download(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress, DownloadEncoding.Format format, java.util.function.BooleanSupplier lyricsEnabled) throws Exception {
        token.check();
        Files.createDirectories(destination);
        if (!Files.isWritable(destination))
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.the_selected_music_folder_is_not_writable"));
        if (Files.getFileStore(destination).getUsableSpace() < 16 * 1024 * 1024)
            throw new IOException(com.darkmusic.client.text.Text.get("text.darkmusic.not_enough_disk_space_in_the_music_folder"));
        Path history = destination.resolve(".darkmusic-downloads.json");
        String identity = entry.key() + (format == DownloadEncoding.Format.ORIGINAL ? ":original" : "");
        synchronized (historyLock) {
            Path prior = previous(readArchive(history), identity, destination);
            if (prior != null) return new Saved(prior, true, "Already downloaded");
        }
        Path scratchRoot = data.resolve("downloads/work");
        Files.createDirectories(scratchRoot);
        Path work = Files.createTempDirectory(scratchRoot, "item-");
        Path incoming = null;
        try {
            var command = tools.base();
            command.addAll(List.of("--no-playlist", "--playlist-items", "1", "--no-overwrites", "--newline", "--progress", "--progress-delta", "1", "--progress-template", "download:DM_PROGRESS:%(progress._percent_str)s", "--format", "bestaudio/best", "--write-info-json", "--write-thumbnail", "--paths", work.toString(), "--output", "source.%(ext)s", "--", url(entry.url())));
            progress.accept(Status.DOWNLOADING, com.darkmusic.client.text.Text.get("download.darkmusic.starting"));
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
            String sourceCodec = DownloadEncoding.codec(runner, tools, source, work, token);
            var plan = format == DownloadEncoding.Format.MP3 ? new DownloadEncoding.Plan("mp3", "libmp3lame", "") : DownloadEncoding.plan(sourceCodec);
            Path converted = work.resolve("converted." + plan.extension());
            progress.accept(Status.CONVERTING, com.darkmusic.client.text.Text.get("download.darkmusic.converting"));
            String warning = cover == null ? "No thumbnail was available" : "";
            Path pictureTags = null;
            if (cover != null && java.util.Set.of("opus", "ogg").contains(plan.extension())) {
                try {
                    pictureTags = DownloadEncoding.pictureComment(runner, tools, cover, work, token);
                } catch (Exception unavailable) {
                    token.check();
                    org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Cannot prepare embedded artwork", unavailable);
                    warning = "Saved without artwork: thumbnail conversion failed";
                }
                cover = null;
            }
            boolean coverSupported = java.util.Set.of("mp3", "m4a", "flac").contains(plan.extension());
            if (!coverSupported && cover != null) {
                cover = null;
                warning = "Saved without artwork: this container's embedded artwork is not supported";
            }
            var conversion = format == DownloadEncoding.Format.MP3 ? convert(tools, source, converted, cover, metadata, entry) : DownloadEncoding.remux(tools, source, converted, cover, metadata, entry, plan);
            if (pictureTags != null) conversion = DownloadEncoding.withPictureComment(conversion, pictureTags);
            result = runner.run(conversion, work, token, Duration.ofMinutes(20), 64_000, s -> {
            });
            if (result.exit() != 0 && (cover != null || pictureTags != null)) {
                token.check();
                warning = "Saved without artwork: thumbnail embedding failed";
                result = runner.run(format == DownloadEncoding.Format.MP3 ? convert(tools, source, converted, null, metadata, entry) : DownloadEncoding.remux(tools, source, converted, null, metadata, entry, plan), work, token, Duration.ofMinutes(20), 64_000, s -> {
                });
            }
            if (result.exit() != 0) throw new IOException("Audio processing failed: " + friendly(result.output()));
            String expectedCodec = plan.codec().equals("copy") ? sourceCodec : plan.codec().equals("libmp3lame") ? "mp3" : plan.codec();
            if (!DownloadEncoding.codec(runner, tools, converted, work, token).equals(expectedCodec))
                throw new IOException("Completed audio failed codec validation");
            var validation = runner.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-v", "error", "-xerror", "-protocol_whitelist", "file,pipe", "-i", converted.toString(), "-map", "0:a:0", "-vn", "-f", "null", "-"), work, token, Duration.ofMinutes(20), 16_000, s -> {
            });
            if (validation.exit() != 0)
                throw new IOException("Completed audio failed decoding validation: " + friendly(validation.output()));
            if (!plan.warning().isBlank()) warning = plan.warning() + " " + warning;
            DownloadLyrics.Result lyrics = new DownloadLyrics.Result("", "");
            if (lyricsEnabled.getAsBoolean()) {
                progress.accept(Status.CONVERTING, com.darkmusic.client.text.Text.get("lyrics.darkmusic.downloading"));
                lyrics = DownloadLyrics.fetch(runner, tools, entry, metadata, work, token);
                if (!lyrics.warning().isBlank()) warning = (warning + "; " + lyrics.warning()).replaceFirst("^; ", "");
            }
            token.check();
            String title = text(metadata, "title");
            if (title.isBlank()) title = entry.title();
            String name = filename(title, identity, plan.extension());
            incoming = Files.createTempFile(destination, ".darkmusic-", ".tmp");
            try (var input = Files.newInputStream(converted); var output = Files.newOutputStream(incoming)) {
                byte[] buffer = new byte[32_768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    token.check();
                    output.write(buffer, 0, read);
                }
            }
            synchronized (historyLock) {
                token.check();
                // Merge the latest history only after conversion; other workers may have committed.
                JsonObject archive = readArchive(history);
                Path prior = previous(archive, identity, destination);
                if (prior != null) return new Saved(prior, true, "Already downloaded");
                Path target = destination.resolve(name);
                for (int suffix = 2; Files.exists(target) || Files.exists(DownloadLyrics.sidecar(target)); suffix++)
                    target = destination.resolve(name.substring(0, name.lastIndexOf('.')) + " (" + suffix + ")." + plan.extension());
                Files.move(incoming, target);
                incoming = null;
                if (lyricsEnabled.getAsBoolean() && !lyrics.text().isBlank()) {
                    Path temporaryLyrics = null;
                    try {
                        temporaryLyrics = Files.createTempFile(destination, ".darkmusic-lyrics-", ".tmp");
                        Files.writeString(temporaryLyrics, lyrics.text());
                        Files.move(temporaryLyrics, DownloadLyrics.sidecar(target));
                    } catch (IOException failure) {
                        warning = (warning + "; " + com.darkmusic.client.text.Text.get("lyrics.darkmusic.save_failed")).replaceFirst("^; ", "");
                        org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Could not save lyric sidecar", failure);
                    } finally {
                        if (temporaryLyrics != null) try {
                            Files.deleteIfExists(temporaryLyrics);
                        } catch (IOException cleanup) {
                            org.slf4j.LoggerFactory.getLogger("darkmusic-download").warn("Could not clean lyric scratch file", cleanup);
                        }
                    }
                }
                archive.addProperty(identity, target.getFileName().toString());
                try {
                    ConfigStore.atomicWrite(history, ConfigStore.JSON.toJson(archive));
                } catch (IOException e) {
                    warning = "Saved, but duplicate history could not be updated";
                }
                return new Saved(target, false, warning);
            }
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

    public record Saved(Path file, boolean skipped, String warning) {
    }
}
