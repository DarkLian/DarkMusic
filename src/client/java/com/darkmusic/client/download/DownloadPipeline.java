package com.darkmusic.client.download;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.library.DownloadArchive;
import com.darkmusic.client.library.LibraryFiles;
import com.darkmusic.client.library.LyricsFiles;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.DownloadDiagnostics;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

import static com.darkmusic.client.download.DownloadJson.text;
import static com.darkmusic.client.download.DownloadModels.*;

/**
 * Downloads into isolated scratch space; only verified, complete audio enters the library.
 */
public final class DownloadPipeline {
    private final ProcessRunner runner;
    private final Path data;
    private DownloadWorkspace workspace;
    private int workspaceUsers;

    public DownloadPipeline(ProcessRunner runner, Path data) {
        this.runner = runner;
        this.data = data;
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
        if (!Set.of("mp3", "m4a", "opus", "ogg", "flac", "wav").contains(extension))
            throw new IllegalArgumentException("Unsupported download extension");
        String safe = title.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip().replaceAll("^[. ]+|[. ]+$", "");
        if (safe.isBlank()) safe = "Audio";
        safe = safe.substring(0, safe.offsetByCodePoints(0, Math.min(100, safe.codePointCount(0, safe.length()))));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return safe + " [" + HexFormat.of().formatHex(hash, 0, 8) + "]." + extension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path previous(JsonObject archive, String identity, Path destination) throws IOException {
        Path recovered = DownloadCommit.recover(destination, identity, archive);
        if (recovered != null) return recovered;
        if (!archive.has(identity)) return null;
        Path prior = destination.resolve(archive.get(identity).getAsString()).normalize();
        return prior.getParent().equals(destination.toAbsolutePath().normalize()) && Files.isRegularFile(prior) && Files.size(prior) > 0
            && SourceLinks.matchesArchive(prior, identity) ? prior : null;
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
            LoggerFactory.getLogger("darkmusic-download").warn("Cannot close workspace", e);
        } finally {
            workspace = null;
        }
    }

    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress) throws Exception {
        return execute(entry, destination, tools, token, progress, DownloadFormat.MP3);
    }

    /**
     * Saves the audio only. Lyrics are looked up afterwards, once the saved file is reported to lyric lookup.
     */
    public Saved execute(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress, DownloadFormat format) throws Exception {
        token.check();
        DownloadDiagnostics.at(DownloadDiagnostics.Stage.COMMIT, DownloadDiagnostics.Tool.NONE, () -> {
            prepare();
            return null;
        });
        try {
            return download(entry, destination, tools, token, progress, format);
        } finally {
            close();
        }
    }

    private Saved download(Entry entry, Path destination, Toolchain tools, ProcessRunner.Token token, BiConsumer<Status, String> progress, DownloadFormat format) throws Exception {
        DownloadDiagnostics.Stage stage = DownloadDiagnostics.Stage.COMMIT;
        DownloadDiagnostics.Tool tool = DownloadDiagnostics.Tool.NONE;
        try {
            token.check();
            Files.createDirectories(destination);
            if (!Files.isWritable(destination))
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.STORAGE_FAILURE, stage, tool, null, "Permission denied", null);
            if (Files.getFileStore(destination).getUsableSpace() < 16 * 1024 * 1024)
                throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.STORAGE_FAILURE, stage, tool, null, "Insufficient disk space", null);
            Path history = destination.resolve(".darkmusic-downloads.json");
            String identity = entry.key() + (format == DownloadFormat.ORIGINAL ? ":original" : "");
            synchronized (DownloadArchive.LOCK) {
                Path prior = previous(DownloadArchive.read(history), identity, destination);
                if (prior != null)
                    return new Saved(prior, true, Text.get("download.already_saved"));
            }
            Path scratchRoot = data.resolve("downloads/work");
            Files.createDirectories(scratchRoot);
            Path work = Files.createTempDirectory(scratchRoot, "item-");
            Path incoming = null;
            try {
                stage = DownloadDiagnostics.Stage.DOWNLOAD;
                tool = DownloadDiagnostics.Tool.YT_DLP;
                var command = tools.base();
                command.addAll(List.of("--no-playlist", "--playlist-items", "1", "--no-overwrites", "--newline", "--progress", "--progress-delta", "1", "--progress-template", "download:DM_PROGRESS:%(progress._percent_str)s", "--format", "bestaudio/best", "--write-info-json", "--write-thumbnail", "--paths", work.toString(), "--output", "source.%(ext)s", "--", url(entry.url())));
                progress.accept(Status.DOWNLOADING, Text.get("download.darkmusic.starting"));
                var result = runner.run(command, work, token, Duration.ofMinutes(30), 128_000, s -> {
                    if (s.startsWith("DM_PROGRESS:")) progress.accept(Status.DOWNLOADING, s.substring(12).strip());
                    else if (!s.isBlank())
                        LoggerFactory.getLogger("darkmusic-download").debug("yt-dlp: {}", s);
                });
                if (result.exit() != 0)
                    throw new DownloadDiagnostics.Failure(stage, tool, result.exit(), result.output());
                stage = DownloadDiagnostics.Stage.VALIDATION;
                tool = DownloadDiagnostics.Tool.NONE;
                Path source;
                try (var files = Files.list(work)) {
                    source = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("source.")).filter(p -> !p.toString().matches("(?i).*\\.(json|jpg|jpeg|png|webp|avif|gif|part|ytdl)$")).findFirst().orElseThrow(() -> new IOException(Text.get("download.media_missing")));
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
                stage = DownloadDiagnostics.Stage.VALIDATION;
                tool = DownloadDiagnostics.Tool.FFPROBE;
                String sourceCodec = DownloadEncoding.codec(runner, tools, source, work, token);
                var plan = format == DownloadFormat.MP3 ? new DownloadEncoding.Plan("mp3", "libmp3lame", "") : DownloadEncoding.plan(sourceCodec);
                Path converted = work.resolve("converted." + plan.extension());
                progress.accept(Status.CONVERTING, Text.get("download.darkmusic.converting"));
                String warning = cover == null ? Text.get("download.no_thumbnail") : "";
                stage = DownloadDiagnostics.Stage.PROCESSING;
                tool = DownloadDiagnostics.Tool.FFMPEG;
                Path pictureTags = null;
                if (cover != null && Set.of("opus", "ogg").contains(plan.extension())) {
                    try {
                        pictureTags = DownloadEncoding.pictureComment(runner, tools, cover, work, token);
                    } catch (Exception unavailable) {
                        token.check();
                        LoggerFactory.getLogger("darkmusic-download").warn("Cannot prepare embedded artwork", unavailable);
                        warning = Text.get("download.thumbnail_failed");
                    }
                    cover = null;
                }
                boolean coverSupported = Set.of("mp3", "m4a", "flac").contains(plan.extension());
                if (!coverSupported && cover != null) {
                    cover = null;
                    warning = Text.get("download.artwork_unsupported");
                }
                var conversion = format == DownloadFormat.MP3 ? convert(tools, source, converted, cover, metadata, entry) : DownloadEncoding.remux(tools, source, converted, cover, metadata, entry, plan);
                if (pictureTags != null) conversion = DownloadEncoding.withPictureComment(conversion, pictureTags);
                result = runner.run(conversion, work, token, Duration.ofMinutes(20), 64_000);
                if (result.exit() != 0 && (cover != null || pictureTags != null)) {
                    token.check();
                    warning = Text.get("download.thumbnail_failed");
                    result = runner.run(format == DownloadFormat.MP3 ? convert(tools, source, converted, null, metadata, entry) : DownloadEncoding.remux(tools, source, converted, null, metadata, entry, plan), work, token, Duration.ofMinutes(20), 64_000);
                }
                if (result.exit() != 0)
                    throw new DownloadDiagnostics.Failure(stage, tool, result.exit(), result.output());
                String expectedCodec = plan.codec().equals("copy") ? sourceCodec : plan.codec().equals("libmp3lame") ? "mp3" : plan.codec();
                stage = DownloadDiagnostics.Stage.VALIDATION;
                tool = DownloadDiagnostics.Tool.FFPROBE;
                if (!DownloadEncoding.codec(runner, tools, converted, work, token).equals(expectedCodec))
                    throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.OUTPUT_VALIDATION_FAILURE, stage, tool, null, "", null);
                tool = DownloadDiagnostics.Tool.FFMPEG;
                var validation = runner.run(List.of(tools.ffmpeg().toString(), "-nostdin", "-v", "error", "-xerror", "-protocol_whitelist", "file,pipe", "-i", converted.toString(), "-map", "0:a:0", "-vn", "-f", "null", "-"), work, token, Duration.ofMinutes(20), 16_000);
                if (validation.exit() != 0)
                    throw new DownloadDiagnostics.Failure(DownloadDiagnostics.Category.OUTPUT_VALIDATION_FAILURE, stage, tool, validation.exit(), validation.output(), null);
                if (!plan.warning().isBlank()) warning = plan.warning() + " " + warning;
                token.check();
                stage = DownloadDiagnostics.Stage.COMMIT;
                tool = DownloadDiagnostics.Tool.NONE;
                String title = text(metadata, "title");
                if (title.isBlank()) title = entry.title();
                String name = filename(title, identity, plan.extension());
                incoming = LibraryFiles.incoming(destination, ".darkmusic-");
                try (var input = Files.newInputStream(converted); var output = Files.newOutputStream(incoming)) {
                    byte[] buffer = new byte[32_768];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        token.check();
                        output.write(buffer, 0, read);
                    }
                }
                synchronized (DownloadArchive.LOCK) {
                    token.check();
                    // Merge the latest history only after conversion; other workers may have committed.
                    JsonObject archive = DownloadArchive.read(history);
                    Path prior = previous(archive, identity, destination);
                    if (prior != null)
                        return new Saved(prior, true, Text.get("download.already_saved"));
                    Path target = destination.resolve(name);
                    for (int suffix = 2; Files.exists(target) || LyricsFiles.companions(target).stream().anyMatch(p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS)); suffix++)
                        target = destination.resolve(name.substring(0, name.lastIndexOf('.')) + " (" + suffix + ")." + plan.extension());
                    DownloadCommit.prepare(incoming, target, identity, entry.url());
                    Files.move(incoming, target);
                    incoming = null;
                    if (!SourceLinks.record(target, entry.url(), identity))
                        warning = (warning + "; " + Text.get("download.provenance_write")).replaceFirst("^; ", "");
                    archive.addProperty(identity, target.getFileName().toString());
                    try {
                        ConfigStore.atomicWrite(history, ConfigStore.JSON.toJson(archive));
                        DownloadCommit.finish(target, identity);
                    } catch (IOException e) {
                        LoggerFactory.getLogger("darkmusic-download").warn("Could not save download history {}", history, e);
                        warning = (warning + "; " + Text.get("download.history_write")).replaceFirst("^; ", "");
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
                    LoggerFactory.getLogger("darkmusic-download").warn("Could not clean download scratch files in {}", work, e);
                }
            }
        } catch (DownloadDiagnostics.Failure | CancellationException |
                 InterruptedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DownloadDiagnostics.Failure(null, stage, tool, null, failure.getMessage(), failure);
        }
    }

    boolean repairBookkeeping(Path destination, String identity) throws IOException {
        synchronized (DownloadArchive.LOCK) {
            DownloadCommit.recover(destination, identity, DownloadArchive.read(destination.resolve(".darkmusic-downloads.json")));
            return !DownloadCommit.pending(destination, identity);
        }
    }

    public record Saved(Path file, boolean skipped, String warning) {
    }
}
