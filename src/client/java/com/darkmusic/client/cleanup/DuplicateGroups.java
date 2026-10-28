package com.darkmusic.client.cleanup;

import com.darkmusic.client.library.Track;

import java.util.*;

/**
 * Connected review groups contain only the original evidence edges, never inferred pairs.
 */
public final class DuplicateGroups {
    private DuplicateGroups() {
    }

    public record Group(String id, List<Track> tracks, List<DuplicateResult> pairs) {
        public Group {
            tracks = List.copyOf(tracks);
            pairs = List.copyOf(pairs);
        }
    }

    public static List<Group> build(List<DuplicateResult> pairs) {
        var parents = new HashMap<String, String>();
        for (var pair : pairs) {
            String a = root(parents, pair.a().id()), b = root(parents, pair.b().id());
            if (!a.equals(b)) parents.put(a.compareTo(b) < 0 ? b : a, a.compareTo(b) < 0 ? a : b);
        }
        var edges = new LinkedHashMap<String, List<DuplicateResult>>();
        var tracks = new HashMap<String, LinkedHashMap<String, Track>>();
        for (var pair : pairs) {
            String id = root(parents, pair.a().id());
            edges.computeIfAbsent(id, k -> new ArrayList<>()).add(pair);
            var members = tracks.computeIfAbsent(id, k -> new LinkedHashMap<>());
            members.putIfAbsent(pair.a().id(), pair.a());
            members.putIfAbsent(pair.b().id(), pair.b());
        }
        return edges.entrySet().stream().map(e -> new Group(e.getKey(), new ArrayList<>(tracks.get(e.getKey()).values()), e.getValue())).toList();
    }

    private static String root(Map<String, String> parents, String id) {
        parents.putIfAbsent(id, id);
        String root = id;
        while (!parents.get(root).equals(root)) root = parents.get(root);
        while (!id.equals(root)) {
            id = parents.put(id, root);
        }
        return root;
    }
}
