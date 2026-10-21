package com.darkmusic.client.gui;

import java.util.List;

/**
 * Logical Tab order includes off-screen rows, without focusing disabled controls.
 */
public final class FocusRows {
    private FocusRows() {
    }

    public static int next(List<Boolean> enabled, int current, boolean backwards, boolean wrap) {
        int direction = backwards ? -1 : 1;
        int start = current < 0 ? backwards ? enabled.size() : -1 : current;
        for (int step = 1; step <= enabled.size(); step++) {
            int index = start + step * direction;
            if (!wrap && (index < 0 || index >= enabled.size())) return -1;
            index = Math.floorMod(index, enabled.size());
            if (enabled.get(index)) return index;
        }
        return -1;
    }
}
