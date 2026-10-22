package com.darkmusic.client.gui;

import com.darkmusic.client.library.Track;

import java.util.*;

/**
 * A library-snapshot tree; browsing never enumerates the filesystem on the render thread.
 */
public final class FolderTree {
    private static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
    private final Node root = new Node("", "");
    private final Set<String> paths = new HashSet<>();

    public FolderTree(List<Track> tracks) {
        this(tracks, List.of());
    }

    public FolderTree(List<Track> tracks, List<String> folders) {
        folders.stream().map(folder -> {
            if (!folder.startsWith("@")) return folder;
            int slash = folder.indexOf('/');
            return slash < 0 ? "" : folder.substring(slash + 1);
        }).filter(folder -> !folder.isEmpty()).forEach(this::addFolder);
        for (Track track : tracks) {
            String path = track.relativePath().replace('\\', '/');
            int end = path.lastIndexOf('/');
            if (end > 0) addFolder(path.substring(0, end));
        }
    }

    public FolderTree(List<Track> tracks, List<String> folders, Map<java.nio.file.Path, String> roots) {
        for (var entry : roots.entrySet()) {
            String path = "@" + entry.getValue();
            String name = com.darkmusic.client.library.MusicFolders.label(entry.getKey(), roots.keySet());
            root.children.put(path, new Node(path, name));
            paths.add(path);
        }
        folders.forEach(this::addFolder);
        for (Track track : tracks) {
            int end = track.relativePath().lastIndexOf('/');
            if (end > 0) addFolder("@" + track.rootId() + "/" + track.relativePath().substring(0, end));
        }
    }

    public static boolean includes(String folder, Track track) {
        if (folder.startsWith("@")) {
            String root = "@" + track.rootId();
            return folder.equals(root) || (root + "/" + track.relativePath()).startsWith(folder + "/");
        }
        return folder.isEmpty() || track.relativePath().replace('\\', '/').startsWith(folder + "/");
    }

    private void addFolder(String folder) {
        Node node = root;
        for (String name : folder.replace('\\', '/').split("/")) {
            if (name.isEmpty() || name.equals(".") || name.equals("..")) return;
            String path = node.path.isEmpty() ? name : node.path + "/" + name;
            node = node.children.computeIfAbsent(name, key -> new Node(path, name));
            paths.add(path);
        }
    }

    public List<Entry> visible(Set<String> expanded) {
        List<Entry> rows = new ArrayList<>();
        append(root, expanded, 0, rows);
        return List.copyOf(rows);
    }

    public boolean contains(String path) {
        return paths.contains(path);
    }

    public String display(String path) {
        int slash = path.indexOf('/');
        var folder = root.children.get(slash < 0 ? path : path.substring(0, slash));
        return folder == null ? path : folder.name + (slash < 0 ? "" : path.substring(slash));
    }

    private void append(Node parent, Set<String> expanded, int depth, List<Entry> rows) {
        for (Node child : parent.children.values()) {
            boolean open = expanded.contains(child.path);
            rows.add(new Entry(child.path, child.name, depth, !child.children.isEmpty(), open));
            if (open) append(child, expanded, depth + 1, rows);
        }
    }

    public record Entry(String path, String name, int depth, boolean children, boolean expanded) {
        public String label() {
            return "  ".repeat(depth) + (children ? expanded ? "v " : "> " : "  ") + name;
        }
    }

    private static final class Node {
        final String path, name;
        final Map<String, Node> children = new TreeMap<>(ORDER);

        Node(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }
}
