package com.darkmusic.client.library;

import com.darkmusic.client.config.Settings;

import java.nio.file.Path;
import java.util.*;

/**
 * Registered scan roots and a separate write destination. Called on the client thread with canonical paths.
 */
public final class MusicFolders {
    private MusicFolders() {
    }

    public static Map<Path, String> resolve(Settings settings, Path builtIn) {
        Path destination = Path.of(settings.musicDirectory.isBlank() ? builtIn.toString() : settings.musicDirectory).toAbsolutePath().normalize();
        if (settings.musicFolders == null) settings.musicFolders = List.of(destination.toString());
        var paths = new LinkedHashSet<String>();
        paths.addAll(settings.musicFolders);
        paths.add(destination.toString());
        var roots = new LinkedHashMap<Path, String>();
        for (String name : paths) {
            Path path;
            try {
                path = Path.of(name).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException invalid) {
                org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Ignoring invalid saved music folder", invalid);
                continue;
            }
            if (!path.equals(destination) && overlaps(List.of(destination), path)) continue;
            if (overlaps(roots.keySet(), path)) continue;
            String id = settings.roots.get(path.toString());
            if (id == null) for (var remembered : settings.roots.entrySet()) {
                try {
                    if (Path.of(remembered.getKey()).toAbsolutePath().normalize().equals(path)) {
                        id = remembered.getValue();
                        break;
                    }
                } catch (java.nio.file.InvalidPathException ignored) {
                }
            }
            if (id == null) id = UUID.randomUUID().toString();
            settings.roots.putIfAbsent(path.toString(), id);
            roots.put(path, id);
        }
        settings.musicFolders = roots.keySet().stream().map(Path::toString).toList();
        return Collections.unmodifiableMap(roots);
    }

    public static boolean overlaps(Collection<Path> existing, Path candidate) {
        return existing.stream().anyMatch(p -> p.startsWith(candidate) || candidate.startsWith(p));
    }

    public static Path browseDirectory(Map<Path, String> roots, Path fallback, String mode, String group) {
        if (!mode.equals("All") || !group.startsWith("@")) return fallback;
        int slash = group.indexOf('/');
        String id = group.substring(1, slash < 0 ? group.length() : slash);
        Path root = roots.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
        if (root == null) return fallback;
        Path selected = slash < 0 ? root : root.resolve(group.substring(slash + 1)).normalize();
        return selected.startsWith(root) ? selected : fallback;
    }

    public static String label(Path path, Collection<Path> roots) {
        for (int depth = 1; depth <= path.getNameCount(); depth++) {
            Path suffix = path.subpath(path.getNameCount() - depth, path.getNameCount());
            int count = depth;
            if (roots.stream().filter(other -> other.getNameCount() >= count && other.subpath(other.getNameCount() - count, other.getNameCount()).equals(suffix)).count() == 1)
                return suffix.toString();
        }
        return path.toString();
    }
}
