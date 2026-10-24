package com.darkmusic.client.library;

import com.darkmusic.client.audio.AudioFormats;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;

/**
 * Explicit audio-file deletion only; no recursive operations or implicit permanent fallback.
 */
public final class AudioDeletion {
    public enum Mode {TRASH, PERMANENT}

    public record Item(Track track, Path file, Object fileKey, String hash) {
        public Item(Track track, Path file, Object fileKey) {
            this(track, file, fileKey, "");
        }
    }

    public record Companion(Track audio, Path file, long size, long modified, Object fileKey) {
    }

    public record Plan(Map<Path, String> roots, List<Item> items, boolean trashSupported, List<Companion> companions,
                       boolean trashCompanions) {
        public Plan(Map<Path, String> roots, List<Item> items, boolean trashSupported) {
            this(roots, items, trashSupported, List.of(), false);
        }

        public Plan {
            roots = Map.copyOf(roots);
            items = List.copyOf(items);
            companions = List.copyOf(companions);
        }

        public Plan trashCompanions(boolean enabled) {
            return new Plan(roots, items, trashSupported, companions, enabled);
        }
    }

    public record Failure(Track track, String message) {
    }

    public record Progress(int completed, int total, String filename) {
    }

    public record Result(List<Track> removed, List<Failure> failed, List<Track> cancelled,
                         List<Path> companionFailures) {
        public Result(List<Track> removed, List<Failure> failed, List<Track> cancelled) {
            this(removed, failed, cancelled, List.of());
        }

        public Result {
            removed = List.copyOf(removed);
            failed = List.copyOf(failed);
            cancelled = List.copyOf(cancelled);
            companionFailures = List.copyOf(companionFailures);
        }
    }

    interface Trash {
        boolean supported();

        boolean move(Item item);

        default void close() {
        }
    }

    private final Trash trash;
    private final AudioValidator audioValidator;

    @FunctionalInterface
    interface AudioValidator {
        boolean isAudio(Path file) throws IOException;
    }

    public AudioDeletion() {
        this(new DesktopTrash());
    }

    AudioDeletion(Trash trash) {
        this(trash, file -> AudioFormats.detect(file) != AudioFormats.Container.UNKNOWN);
    }

    AudioDeletion(Trash trash, AudioValidator audioValidator) {
        this.trash = trash;
        this.audioValidator = Objects.requireNonNull(audioValidator);
    }

    /**
     * Run on a file worker. The confirmation uses these exact files and identities.
     */
    public Plan prepare(Map<Path, String> roots, List<Track> selected) throws IOException {
        var items = new LinkedHashMap<Path, Item>();
        var companions = new ArrayList<Companion>();
        for (Track track : selected) {
            var attributes = validate(roots, track);
            Path file = track.file().toAbsolutePath().normalize();
            items.putIfAbsent(file, new Item(track, file, attributes.fileKey(), attributes.fileKey() == null ? hash(file) : ""));
            com.darkmusic.client.lyrics.LyricsFiles.recover(file);
            for (Path companion : com.darkmusic.client.lyrics.LyricsFiles.companions(file)) {
                if (!Files.exists(companion, LinkOption.NOFOLLOW_LINKS)) continue;
                if (Files.isSymbolicLink(companion) || !Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(Text.get("lyrics.unsafe_path"));
                var a = Files.readAttributes(companion, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                companions.add(new Companion(track, companion, a.size(), a.lastModifiedTime().toMillis(), a.fileKey()));
            }
        }
        if (items.isEmpty()) throw new IOException(Text.get("delete_audio.empty"));
        try {
            return new Plan(roots, List.copyOf(items.values()), trash.supported(), companions, false);
        } finally {
            trash.close();
        }
    }

    public Result delete(Plan plan, Mode mode, ProcessRunner.Token token, Consumer<Progress> progress) {
        Objects.requireNonNull(mode);
        var removed = new ArrayList<Track>();
        var failed = new ArrayList<Failure>();
        var cancelled = new ArrayList<Track>();
        var companionFailures = new ArrayList<Path>();
        int completed = 0;
        try {
            for (Item item : plan.items()) {
                try {
                    token.check();
                    var attributes = validate(plan.roots(), item.track());
                    if (!sameIdentity(item, attributes))
                        throw new IOException(Text.get("library.darkmusic.file_changed"));
                    if (mode == Mode.TRASH) {
                        if (!plan.trashSupported() || !trash.supported())
                            throw new IOException(Text.get("delete_audio.trash_failed"));
                        token.check();
                        if (!trash.move(item) && !Files.notExists(item.file(), LinkOption.NOFOLLOW_LINKS))
                            throw new IOException(Text.get("delete_audio.trash_failed"));
                    } else deletePermanently(plan, item, token);
                    if (!Files.notExists(item.file(), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException(Text.get("delete_audio.failed"));
                    removed.add(item.track());
                    if (plan.trashCompanions()) for (Companion companion : plan.companions()) {
                        if (!companion.audio().id().equals(item.track().id())) continue;
                        try {
                            token.check();
                            var a = Files.readAttributes(companion.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (!a.isRegularFile() || Files.isSymbolicLink(companion.file()) || a.size() != companion.size()
                                || a.lastModifiedTime().toMillis() != companion.modified() || !Objects.equals(a.fileKey(), companion.fileKey())
                                || !companion.file().toRealPath().getParent().equals(item.file().getParent().toRealPath()))
                                throw new IOException("Companion changed after confirmation");
                            var track = new Track(item.track().rootId(), item.track().relativePath(), companion.file(), "", "", "", 0, companion.size(), companion.modified(), "");
                            if (!plan.trashSupported() || !trash.supported() || !trash.move(new Item(track, companion.file(), companion.fileKey()))
                                || !Files.notExists(companion.file(), LinkOption.NOFOLLOW_LINKS))
                                throw new IOException("Companion Trash failed");
                        } catch (Exception failure) {
                            companionFailures.add(companion.file());
                            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Could not trash selected lyric companion {}", companion.file(), failure);
                        }
                    }
                } catch (java.util.concurrent.CancellationException cancellation) {
                    cancelled.add(item.track());
                } catch (Exception failure) {
                    org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Could not delete selected audio {}", item.file(), failure);
                    failed.add(new Failure(item.track(), Text.failure(failure, "delete_audio.failed")));
                }
                progress.accept(new Progress(++completed, plan.items().size(), item.file().getFileName().toString()));
            }
        } finally {
            trash.close();
        }
        return new Result(removed, failed, cancelled, companionFailures);
    }

    private BasicFileAttributes validate(Map<Path, String> roots, Track track) throws IOException {
        Path root = roots.entrySet().stream().filter(e -> e.getValue().equals(track.rootId())).map(Map.Entry::getKey).findFirst()
            .orElseThrow(() -> new IOException(Text.get("delete_audio.outside_library")));
        Path base = root.toAbsolutePath().normalize(), file = track.file().toAbsolutePath().normalize();
        if (!file.startsWith(base) || file.equals(base) || !base.resolve(track.relativePath()).normalize().equals(file)
            || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(base.toRealPath()))
            throw new IOException(Text.get("delete_audio.outside_library"));
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != track.size() || attributes.lastModifiedTime().toMillis() != track.modified())
            throw new IOException(Text.get("library.darkmusic.file_changed"));
        if (!audioValidator.isAudio(file))
            throw new IOException(Text.get("library.darkmusic.not_audio"));
        return attributes;
    }

    private static boolean sameIdentity(Item item, BasicFileAttributes attributes) throws IOException {
        return Objects.equals(item.fileKey(), attributes.fileKey())
            && (item.fileKey() != null || !item.hash().isEmpty() && item.hash().equals(hash(item.file())));
    }

    private static String hash(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void deletePermanently(Plan plan, Item item, ProcessRunner.Token token) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (true) {
            token.check();
            var attributes = validate(plan.roots(), item.track());
            if (!sameIdentity(item, attributes))
                throw new IOException(Text.get("library.darkmusic.file_changed"));
            try {
                Files.delete(item.file());
                return;
            } catch (FileSystemException failure) {
                if (failure instanceof NoSuchFileException || failure instanceof AccessDeniedException) throw failure;
                if (System.nanoTime() >= deadline)
                    throw new IOException(Text.get("library.darkmusic.file_busy"), failure);
                Thread.sleep(50);
            }
        }
    }
}
