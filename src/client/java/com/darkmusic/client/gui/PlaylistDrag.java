package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Index-only preview; insertion is measured in the list after removing the block.
 */
public record PlaylistDrag(int start, int count, int insertion) {
    public static PlaylistDrag connected(List<String> keys, Set<String> checked, int index) {
        int first = index, last = index;
        if (checked.contains(keys.get(index))) {
            while (first > 0 && checked.contains(keys.get(first - 1))) first--;
            while (last + 1 < keys.size() && checked.contains(keys.get(last + 1))) last++;
        }
        return new PlaylistDrag(first, last - first + 1, first);
    }

    public PlaylistDrag atBoundary(int boundary, int size) {
        int target = boundary <= start ? boundary : boundary >= start + count ? boundary - count : start;
        return new PlaylistDrag(start, count, Math.clamp(target, 0, size - count));
    }

    public List<Integer> order(int size) {
        if (start < 0 || count < 1 || start + count > size || insertion < 0 || insertion > size - count)
            throw new IllegalArgumentException(Text.get("text.darkmusic.invalid_block_move"));
        var remaining = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) if (i < start || i >= start + count) remaining.add(i);
        for (int i = 0; i < count; i++) remaining.add(insertion + i, start + i);
        return List.copyOf(remaining);
    }
}
