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

    public record Item(Track track, Path file, Object fileKey) {
    }

    public record Plan(Map<Path, String> roots, List<Item> items, boolean trashSupported) {
        public Plan {
            roots = Map.copyOf(roots);
            items = List.copyOf(items);
        }
    }

    public record Failure(Track track, String message) {
    }

    public record Progress(int completed, int total, String filename) {
    }

    public record Result(List<Track> removed, List<Failure> failed) {
        public Result {
            removed = List.copyOf(removed);
            failed = List.copyOf(failed);
        }
    }

    interface Trash {
        boolean supported();

        boolean move(Item item);

        default void close() {
        }
    }

    private final Trash trash;

    public AudioDeletion() {
        this(new DesktopTrash());
    }

    AudioDeletion(Trash trash) {
        this.trash = trash;
    }

    /**
     * Run on a file worker. The confirmation uses these exact files and identities.
     */
    public Plan prepare(Map<Path, String> roots, List<Track> selected) throws IOException {
        var items = new LinkedHashMap<Path, Item>();
        for (Track track : selected) {
            var attributes = validate(roots, track);
            Path file = track.file().toAbsolutePath().normalize();
            items.putIfAbsent(file, new Item(track, file, attributes.fileKey()));
        }
        if (items.isEmpty()) throw new IOException(Text.get("delete_audio.empty"));
        try {
            return new Plan(roots, List.copyOf(items.values()), trash.supported());
        } finally {
            trash.close();
        }
    }

    public Result delete(Plan plan, Mode mode, ProcessRunner.Token token, Consumer<Progress> progress) {
        Objects.requireNonNull(mode);
        var removed = new ArrayList<Track>();
        var failed = new ArrayList<Failure>();
        int completed = 0;
        try {
            for (Item item : plan.items()) {
                try {
                    token.check();
                    var attributes = validate(plan.roots(), item.track());
                    if (!Objects.equals(item.fileKey(), attributes.fileKey()))
                        throw new IOException(Text.get("library.darkmusic.file_changed"));
                    if (mode == Mode.TRASH) {
                        if (!plan.trashSupported() || !trash.supported() || !trash.move(item))
                            throw new IOException(Text.get("delete_audio.trash_failed"));
                    } else deletePermanently(plan, item, token);
                    if (!Files.notExists(item.file(), LinkOption.NOFOLLOW_LINKS))
                        throw new IOException(Text.get("delete_audio.failed"));
                    removed.add(item.track());
                } catch (Exception failure) {
                    org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Could not delete selected audio {}", item.file(), failure);
                    failed.add(new Failure(item.track(), Text.failure(failure, "delete_audio.failed")));
                }
                progress.accept(new Progress(++completed, plan.items().size(), item.file().getFileName().toString()));
            }
        } finally {
            trash.close();
        }
        return new Result(removed, failed);
    }

    private static BasicFileAttributes validate(Map<Path, String> roots, Track track) throws IOException {
        Path root = roots.entrySet().stream().filter(e -> e.getValue().equals(track.rootId())).map(Map.Entry::getKey).findFirst()
            .orElseThrow(() -> new IOException(Text.get("delete_audio.outside_library")));
        Path base = root.toAbsolutePath().normalize(), file = track.file().toAbsolutePath().normalize();
        if (!file.startsWith(base) || file.equals(base) || !base.resolve(track.relativePath()).normalize().equals(file)
            || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(base.toRealPath()))
            throw new IOException(Text.get("delete_audio.outside_library"));
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != track.size() || attributes.lastModifiedTime().toMillis() != track.modified())
            throw new IOException(Text.get("library.darkmusic.file_changed"));
        if (AudioFormats.detect(file) == AudioFormats.Container.UNKNOWN)
            throw new IOException(Text.get("library.darkmusic.not_audio"));
        return attributes;
    }

    private static void deletePermanently(Plan plan, Item item, ProcessRunner.Token token) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (true) {
            token.check();
            var attributes = validate(plan.roots(), item.track());
            if (!Objects.equals(item.fileKey(), attributes.fileKey()))
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
