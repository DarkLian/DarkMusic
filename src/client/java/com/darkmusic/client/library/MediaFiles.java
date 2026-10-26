package com.darkmusic.client.library;

import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.audio.ExternalAudio;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.text.Text;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * File work only. Callers own playback coordination and client-thread playlist updates.
 */
public final class MediaFiles {
    public record ImportEntry(String source, String destination, boolean success, String message) {
    }

    public record Imported(List<Track> tracks, List<String> errors, List<ImportEntry> entries) {
    }

    public record Fields(String title, String artist, String album, String filename, String extension) {
    }

    private final MetadataReader metadata = new MetadataReader();
    private final Runnable beforeCommit;

    public MediaFiles() {
        this(() -> {
        });
    }

    MediaFiles(Runnable beforeCommit) {
        this.beforeCommit = beforeCommit;
    }

    public Imported importFiles(Path root, String rootId, String folder, List<Path> sources, ProcessRunner.Token token) throws Exception {
        Path base = root.toRealPath(), destination = base.resolve(folder).normalize();
        if (!destination.startsWith(base) || !Files.isDirectory(destination) || !destination.toRealPath().startsWith(base))
            destination = base;
        var imported = new ArrayList<Track>();
        var errors = new ArrayList<String>();
        var entries = new ArrayList<ImportEntry>();
        for (Path source : sources) {
            token.check();
            Path scratch = null;
            try {
                if (!Files.isRegularFile(source) || AudioFormats.detect(source) == AudioFormats.Container.UNKNOWN)
                    throw new IOException(Text.get("library.darkmusic.not_audio"));
                String name = source.getFileName().toString();
                Path target = destination.resolve(name);
                if (!Files.exists(target) || !Files.isSameFile(source, target)) {
                    scratch = Files.createTempFile(destination, ".darkmusic-import-", ".tmp");
                    copy(source, scratch, token);
                    if (AudioFormats.detect(scratch) == AudioFormats.Container.UNKNOWN)
                        throw new IOException(Text.get("library.darkmusic.not_audio"));
                    int dot = name.lastIndexOf('.');
                    String stem = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
                    for (int number = 2; Files.exists(target) || com.darkmusic.client.lyrics.LyricsFiles.companions(target).stream().anyMatch(p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS)); number++) {
                        if (number > 10000) throw new IOException(Text.get("library.darkmusic.name_collision"));
                        target = destination.resolve(stem + " (" + number + ")" + extension);
                    }
                    token.check();
                    Files.move(scratch, target); // Never replace an existing song.
                    scratch = null;
                }
                SourceLinks.record(target, SourceLinks.read(source));
                imported.add(metadata.read(rootId, base.relativize(target).toString().replace('\\', '/'), target,
                    Files.size(target), Files.getLastModifiedTime(target).toMillis()));
                entries.add(new ImportEntry(source.toAbsolutePath().toString(), target.toString(), true, ""));
            } catch (Exception e) {
                token.check();
                errors.add(source.getFileName() + ": " + e.getMessage());
                entries.add(new ImportEntry(source.toAbsolutePath().toString(), "", false, String.valueOf(e.getMessage())));
            } finally {
                if (scratch != null) Files.deleteIfExists(scratch);
            }
        }
        return new Imported(List.copyOf(imported), List.copyOf(errors), List.copyOf(entries));
    }

    public Fields fields(Track track) throws Exception {
        String title = "", artist = "", album = "";
        try {
            if (AudioFormats.detect(track.file()) == AudioFormats.Container.OPUS) throw new IOException();
            var tag = AudioFileIO.read(track.file().toFile()).getTag();
            if (tag != null) {
                title = tag.getFirst(FieldKey.TITLE);
                artist = tag.getFirst(FieldKey.ARTIST);
                album = tag.getFirst(FieldKey.ALBUM);
            }
        } catch (Exception unavailable) {
            var info = ExternalAudio.probe(track.file());
            var tags = new HashMap<String, String>();
            if (info.has("format") && info.getAsJsonObject("format").has("tags"))
                info.getAsJsonObject("format").getAsJsonObject("tags").entrySet().forEach(e -> tags.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
            var streams = info.getAsJsonArray("streams");
            if (streams != null && !streams.isEmpty() && streams.get(0).getAsJsonObject().has("tags"))
                streams.get(0).getAsJsonObject().getAsJsonObject("tags").entrySet().forEach(e -> tags.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
            title = tags.getOrDefault("title", "");
            artist = tags.getOrDefault("artist", "");
            album = tags.getOrDefault("album", "");
        }
        String name = track.file().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return new Fields(title, artist, album, dot > 0 ? name.substring(0, dot) : name, dot > 0 ? name.substring(dot) : "");
    }

    public Track edit(Track track, Fields original, Fields requested, ProcessRunner.Token token) throws Exception {
        String filename = requested.filename().strip();
        if (filename.isEmpty() || filename.length() > 180 || filename.matches(".*[\\p{Cntrl}<>:\"/\\\\|?*].*")
            || filename.endsWith(".") || filename.startsWith(".") || filename.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
            throw new IOException(Text.get("library.darkmusic.invalid_filename"));
        for (String value : List.of(requested.title(), requested.artist(), requested.album()))
            if (value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl))
                throw new IOException(Text.get("library.darkmusic.invalid_tags"));
        Path source = track.file().toAbsolutePath();
        String sourceName = source.getFileName().toString();
        int dot = sourceName.lastIndexOf('.');
        String extension = dot > 0 ? sourceName.substring(dot) : "";
        Path target = source.resolveSibling(filename + extension);
        checkSource(track);
        var sourceIdentity = AudioReplacement.Identity.read(source);
        String verifiedSource = SourceLinks.read(source);
        boolean rename = !source.getFileName().toString().equals(target.getFileName().toString());
        boolean caseAlias = rename && source.getFileName().toString().equalsIgnoreCase(target.getFileName().toString()) && Files.exists(target) && Files.isSameFile(source, target);
        if (rename && Files.exists(target) && !caseAlias)
            throw new IOException(Text.get("library.darkmusic.name_collision"));
        com.darkmusic.client.lyrics.LyricsFiles.recover(source);
        var companions = com.darkmusic.client.lyrics.LyricsFiles.companions(source);
        var nextCompanions = com.darkmusic.client.lyrics.LyricsFiles.companions(target);
        for (int i = 0; rename && i < companions.size(); i++) {
            Path old = companions.get(i), next = nextCompanions.get(i);
            if (Files.isSymbolicLink(old) || Files.isSymbolicLink(next)
                || Files.exists(next, LinkOption.NOFOLLOW_LINKS) && (!Files.exists(old) || !Files.isSameFile(old, next)))
                throw new IOException(Text.get("library.darkmusic.lyric_collision"));
        }
        requireEditSpace(Files.size(source), Files.getFileStore(source.getParent()).getUsableSpace());
        Path work = Files.createTempDirectory(source.getParent(), ".darkmusic-work-");
        Path staged = work.resolve("edited" + extension);
        var movedCompanions = new ArrayList<Integer>();
        var copiedCompanions = new HashMap<Integer, AudioReplacement.Identity>();
        Path retained = work.resolve("retained" + extension);
        var edit = new AudioReplacement.Edit(source, target, work.resolve("original" + extension), sourceIdentity);
        boolean preserveWork = false;
        try {
            boolean tagsChanged = !original.title().equals(requested.title()) || !original.artist().equals(requested.artist()) || !original.album().equals(requested.album());
            copy(source, staged, token);
            if (!sourceIdentity.hash().equals(SourceLinks.contentHash(staged)))
                throw new AudioReplacement.RecoveryRequired(staged, new IOException(Text.get("library.darkmusic.file_changed")));
            Files.copy(staged, retained, StandardCopyOption.COPY_ATTRIBUTES);
            if (tagsChanged) {
                var container = AudioFormats.detect(source);
                if (container == AudioFormats.Container.OPUS || container == AudioFormats.Container.WEBM)
                    remux(source, staged, work, requested, container, token);
                else {
                    var audio = AudioFileIO.read(staged.toFile());
                    var tag = audio.getTagOrCreateAndSetDefault();
                    if (tag instanceof org.jaudiotagger.tag.wav.WavTag wav) {
                        if (wav.getID3Tag() == null)
                            wav.setID3Tag(org.jaudiotagger.tag.wav.WavTag.createDefaultID3Tag());
                        writeTags(wav.getID3Tag(), requested);
                        if (wav.getInfoTag() != null) writeTags(wav.getInfoTag(), requested);
                    } else writeTags(tag, requested);
                    audio.commit();
                }
                Fields actual = fields(new Track(track.rootId(), track.relativePath(), staged, "", "", "", 0, Files.size(staged), 0, ""));
                if (!actual.title().equals(requested.title()) || !actual.artist().equals(requested.artist()) || !actual.album().equals(requested.album()))
                    throw new IOException(Text.get("library.darkmusic.tags_not_saved"));
                if (container != AudioFormats.Container.WEBM && !Arrays.equals(metadata.artwork(source), metadata.artwork(staged)))
                    throw new IOException(Text.get("library.darkmusic.cover_not_preserved"));
            }
            if (AudioFormats.detect(source) != AudioFormats.detect(staged))
                throw new IOException(Text.get("library.darkmusic.format_changed"));
            if (Files.getLastModifiedTime(staged).toMillis() == track.modified())
                Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(track.modified() + 1));
            token.check();
            checkSource(track);
            beforeCommit.run();
            try {
                edit.claim();
                boolean sharedLyrics = rename && com.darkmusic.client.lyrics.LyricsFiles.shared(source);
                for (int i = 0; rename && i < companions.size(); i++) {
                    Path old = companions.get(i), next = nextCompanions.get(i);
                    if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (sharedLyrics) {
                        if (Files.exists(next) && Files.isSameFile(old, next)) continue;
                        Files.copy(old, next, StandardCopyOption.COPY_ATTRIBUTES);
                        copiedCompanions.put(i, AudioReplacement.Identity.read(next));
                    } else moveCaseAware(old, next, work.resolve("companion-" + i));
                    movedCompanions.add(i);
                }
                if (rename)
                    com.darkmusic.client.download.DownloadPipeline.renameRecorded(source, target, () -> edit.install(staged));
                else edit.install(staged);
                try {
                    SourceLinks.renamed(source, target, verifiedSource, edit.installedHash());
                } catch (IOException provenanceFailure) {
                    if (rename)
                        com.darkmusic.client.download.DownloadPipeline.renameRecorded(target, source, edit::rollback);
                    else edit.rollback();
                    throw provenanceFailure;
                }
            } catch (Exception failure) {
                try {
                    edit.rollback();
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                    preserveWork = true;
                }
                for (int i = movedCompanions.size() - 1; i >= 0; i--) {
                    int index = movedCompanions.get(i);
                    try {
                        if (copiedCompanions.containsKey(index))
                            AudioReplacement.claim(nextCompanions.get(index), work.resolve("copied-rollback-" + index), copiedCompanions.get(index));
                        else
                            moveCaseAware(nextCompanions.get(index), companions.get(index), work.resolve("companion-rollback-" + index));
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                        preserveWork = true;
                    }
                }
                throw failure;
            }
            String relative = track.relativePath();
            int slash = relative.lastIndexOf('/');
            relative = (slash < 0 ? "" : relative.substring(0, slash + 1)) + target.getFileName();
            return metadata.read(track.rootId(), relative, target, Files.size(target), Files.getLastModifiedTime(target).toMillis());
        } catch (AudioReplacement.RecoveryRequired failure) {
            preserveWork = true;
            throw failure;
        } finally {
            if (preserveWork) {
                org.slf4j.LoggerFactory.getLogger(MediaFiles.class).error("Original audio retained for recovery in {}", work);
            } else
                try {
                    try (var paths = Files.list(work)) {
                        for (Path file : paths.toList()) Files.deleteIfExists(file);
                    }
                    Files.deleteIfExists(work);
                } catch (IOException cleanup) {
                    org.slf4j.LoggerFactory.getLogger(MediaFiles.class).warn("Could not remove metadata staging directory {}", work, cleanup);
                }
        }
    }

    static void requireEditSpace(long size, long usable) throws IOException {
        if (size < 0 || size > (Long.MAX_VALUE - 4_194_304) / 3 || usable < size * 3 + 4_194_304)
            throw new IOException(Text.get("library.darkmusic.edit_space"));
    }

    private static void moveCaseAware(Path source, Path target, Path temporary) throws IOException {
        if (!Files.exists(target) || !Files.isSameFile(source, target)) {
            Files.move(source, target);
            return;
        }
        Files.move(source, temporary);
        try {
            Files.move(temporary, target);
        } catch (IOException failure) {
            try {
                Files.move(temporary, source);
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
                throw new AudioReplacement.RecoveryRequired(temporary, failure);
            }
            throw failure;
        }
    }

    private static void writeTags(org.jaudiotagger.tag.Tag tag, Fields fields) throws Exception {
        tag.setField(FieldKey.TITLE, fields.title());
        tag.setField(FieldKey.ARTIST, fields.artist());
        tag.setField(FieldKey.ALBUM, fields.album());
    }

    private void remux(Path source, Path staged, Path work, Fields fields, AudioFormats.Container container, ProcessRunner.Token token) throws Exception {
        var args = new ArrayList<>(List.of(ExternalAudio.tool(false).toString(), "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", source.toString()));
        byte[] artwork = container == AudioFormats.Container.OPUS ? metadata.artwork(source) : null;
        if (artwork != null) {
            int width, height;
            try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(artwork))) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException(Text.get("library.darkmusic.cover_unreadable"));
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 32768 || height > 32768 || (long) width * height > 268435456)
                        throw new IOException(Text.get("library.darkmusic.cover_unreadable"));
                } finally {
                    reader.dispose();
                }
            }
            String mime = artwork.length > 3 && artwork[0] == (byte) 0x89 && artwork[1] == 'P' ? "image/png" : "image/jpeg";
            Path picture = work.resolve("picture.txt");
            Files.writeString(picture, ";FFMETADATA1\nMETADATA_BLOCK_PICTURE=" + OggArtwork.picture(artwork, width, height, mime) + "\n");
            args.addAll(List.of("-f", "ffmetadata", "-i", picture.toString()));
        }
        args.addAll(List.of("-map", container == AudioFormats.Container.OPUS ? "0:a:0" : "0", "-c", "copy", "-map_metadata", "0", "-map_metadata:s:a:0", "0:s:a:0"));
        if (artwork != null) args.addAll(List.of("-map_metadata:s:a:0", "1:g"));
        for (String scope : List.of("-metadata", "-metadata:s:a:0"))
            args.addAll(List.of(scope, "title=" + fields.title(), scope, "artist=" + fields.artist(), scope, "album=" + fields.album()));
        args.addAll(List.of("-f", container == AudioFormats.Container.OPUS ? "opus" : "webm", staged.toString()));
        var result = new ProcessRunner().run(args, work, token, Duration.ofMinutes(10), 64000, line -> {
        });
        if (result.exit() != 0)
            throw new IOException(Text.get("library.darkmusic.edit_failed"), new IOException(result.output()));
        if (artwork != null && !Arrays.equals(artwork, metadata.artwork(staged)))
            throw new IOException(Text.get("library.darkmusic.cover_not_preserved"));
    }

    private static void checkSource(Track track) throws IOException {
        if (Files.isSymbolicLink(track.file()) || !Files.isRegularFile(track.file()) || Files.size(track.file()) != track.size()
            || Files.getLastModifiedTime(track.file()).toMillis() != track.modified())
            throw new IOException(Text.get("library.darkmusic.file_changed"));
    }

    private static void copy(Path source, Path target, ProcessRunner.Token token) throws Exception {
        try (var input = Files.newInputStream(source); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                token.check();
                output.write(buffer, 0, read);
            }
        }
    }
}
