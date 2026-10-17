package com.darkmusic.client.gui;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Selection follows stable file references, independently of filtering and row positions.
 */
public final class SongSelection {
    private final Set<String> checked = new HashSet<>();
    private List<String> visible = List.of();
    private Set<String> visibleKeys = Set.of();
    private String anchor;

    public void visible(List<String> keys) {
        visible = List.copyOf(keys);
        visibleKeys = new HashSet<>(keys);
    }

    public void retain(Collection<String> available) {
        checked.retainAll(new HashSet<>(available));
    }

    public Set<String> selected() {
        return Set.copyOf(checked);
    }

    public List<String> keys() {
        return visible;
    }

    public boolean checked(int index) {
        return index >= 0 && index < visible.size() && checked.contains(visible.get(index));
    }

    public int hidden() {
        return (int) checked.stream().filter(k -> !visibleKeys.contains(k)).count();
    }

    public void clear() {
        checked.clear();
        anchor = null;
    }

    public void all() {
        checked.addAll(visible);
    }

    public void invert() {
        for (String key : visible) if (!checked.remove(key)) checked.add(key);
    }

    public void choose(int index, boolean toggle, boolean range) {
        if (index < 0 || index >= visible.size()) return;
        String key = visible.get(index);
        int from = anchor == null ? -1 : visible.indexOf(anchor);
        if (range && from >= 0) {
            if (!toggle) checked.clear();
            for (int i = Math.min(from, index); i <= Math.max(from, index); i++) checked.add(visible.get(i));
        } else {
            if (!toggle) checked.clear();
            if (!toggle || !checked.remove(key)) checked.add(key);
            anchor = key;
        }
    }
}
