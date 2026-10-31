package com.darkmusic.client.lyrics;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.LyricsLanguages;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The saved lyric choices on disk: one JSON record per song under tracks/, named by the hash of the song's id. File
 * work only, with no state of its own; the lookup runs it on its storage thread. The Saved record's field names are
 * the file format.
 */
final class LyricChoiceStore {
    record Saved(int schema, LyricsDocument selected, int offset, String language, long size, long modified,
                 boolean pinned, boolean reviewRequired, String rootId, String trackId, String encoding) {
    }

    private final Path directory;

    /**
     * @param directory the lyrics folder, or null to keep choices in memory only
     */
    LyricChoiceStore(Path directory) {
        this.directory = directory;
    }

    Path preference(Track track) {
        return file(track.id());
    }

    private Path file(String id) {
        return directory.resolve("tracks").resolve(hash(id) + ".json");
    }

    void write(Track track, Saved saved) throws IOException {
        ConfigStore.atomicWrite(preference(track), ConfigStore.JSON.toJson(saved));
    }

    void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    /**
     * Deletes the records of songs no longer in the library, but only in roots whose scan completed, and never one in
     * a folder that could not be read: an unknown legacy root or unregistered folder has no evidence of absence.
     */
    void prune(List<Track> tracks, Set<String> registeredRoots, Set<String> completeRoots, Map<String, List<String>> unreadableScopes) throws Exception {
        Path folder = directory.resolve("tracks");
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return;
        var present = new HashSet<String>();
        for (Track track : tracks) present.add(hash(track.id()) + ".json");
        try (var files = Files.list(folder)) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                if (present.contains(path.getFileName().toString()) || Files.isSymbolicLink(path)) continue;
                Saved saved;
                try {
                    saved = read(path, Saved.class);
                } catch (Exception invalid) {
                    continue;
                }
                // Unknown legacy roots and unregistered folders have no positive absence evidence.
                if (saved != null && protectedScope(saved.rootId(), saved.trackId(), unreadableScopes)) continue;
                if (saved != null && saved.rootId() != null && registeredRoots.contains(saved.rootId()) && completeRoots.contains(saved.rootId()))
                    Files.deleteIfExists(path);
            }
        }
    }

    private static boolean protectedScope(String root, String id, Map<String, List<String>> scopes) {
        if (root == null) return !scopes.isEmpty();
        var unreadable = scopes.getOrDefault(root, List.of());
        if (unreadable.isEmpty()) return false;
        if (id == null || !id.startsWith(root + ":")) return true;
        String relative = id.substring(root.length() + 1).replace('\\', '/');
        return unreadable.stream().anyMatch(scope -> scope.isEmpty() || relative.equals(scope) || relative.startsWith(scope + "/"));
    }

    static LyricsDocument validated(LyricsDocument d) {
        return LyricsDocument.of(d.provider(), d.recordId(), d.title(), d.artist(), d.album(), d.duration(), d.language(), d.variant(), d.generated(), d.instrumental(), d.text());
    }

    static String language(String value) {
        if (value == null || value.strip().equalsIgnoreCase("auto")) return "auto";
        String result = LyricsLanguages.normalize(value);
        if (result.contains(","))
            throw new IllegalArgumentException(Text.get("lyrics.darkmusic.language_invalid"));
        return LyricsLanguages.normalizeLanguage(result);
    }

    static String validEncoding(String value) {
        return List.of("Big5", "GB18030", "windows-31j", "EUC-KR").contains(value) ? value : "Auto";
    }

    static String hash(String value) {
        return Hashes.sha256(value);
    }

    static <T> T read(Path path, Class<T> type) throws Exception {
        if (Files.size(path) > 8_000_000) throw new IOException("Lyric record too large");
        return ConfigStore.JSON.fromJson(Files.readString(path), type);
    }
}
