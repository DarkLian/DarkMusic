package com.darkmusic.client.gui;

import com.darkmusic.client.playlist.PlaylistDrag;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A list's drag-and-drop reordering: the press, the dragged rows and where they would land, the animated positions
 * of the other rows, and the drop until the list's owner shows the new order. Positions are in rows; the list passes
 * its geometry (top, scroll, row height) in pixels.
 */
final class RowDrag {
    private static final long ANIMATION_NANOS = 150_000_000L;
    private PlaylistDrag drag;
    private boolean pressed;
    private double pressedX, pressedY;
    private int pressedIndex;
    private double pointerX, pointerY, grabOffset;
    // Display order while dragging (empty when the list cannot be reordered), and the order a drop asked for.
    private List<Integer> order = List.of(), dropping;
    private double[] from = new double[0], targets = new double[0];
    private long animation;

    /**
     * Every row back in its own place, without animation. Lists that cannot be reordered keep no order.
     */
    void reset(int size, boolean reorderable) {
        if (!reorderable) {
            order = List.of();
            from = targets = new double[0];
            return;
        }
        order = IntStream.range(0, size).boxed().toList();
        from = new double[size];
        targets = new double[size];
        for (int i = 0; i < size; i++) from[i] = targets[i] = i;
    }

    /**
     * Row index's position in rows, easing towards its target.
     */
    double position(int index) {
        if (from.length == 0) return index;
        double t = Math.clamp((System.nanoTime() - animation) / (double) ANIMATION_NANOS, 0, 1), ease = 1 - Math.pow(1 - t, 3);
        return from[index] + (targets[index] - from[index]) * ease;
    }

    /**
     * The row shown in slot.
     */
    int rowAt(int slot) {
        return order.get(slot);
    }

    /**
     * The order while dragging; empty for lists that cannot be reordered.
     */
    List<Integer> order() {
        return order;
    }

    PlaylistDrag current() {
        return drag;
    }

    boolean dragging() {
        return drag != null;
    }

    /**
     * Whether row index is one of the dragged rows, which are drawn under the pointer instead of in place.
     */
    boolean carries(int index) {
        return drag != null && index >= drag.start() && index < drag.start() + drag.count();
    }

    /**
     * Where the dragged rows' top edge is drawn, in pixels.
     */
    double carriedTop() {
        return pointerY - grabOffset;
    }

    void press(double x, double y, int index) {
        pressed = true;
        pressedX = x;
        pressedY = y;
        pressedIndex = index;
    }

    boolean pressed() {
        return pressed;
    }

    int pressedIndex() {
        return pressedIndex;
    }

    /**
     * A press released without dragging.
     */
    void release() {
        pressed = false;
    }

    void pointer(double x, double y) {
        pointerX = x;
        pointerY = y;
    }

    /**
     * Whether the press has moved far enough to start dragging.
     */
    boolean moved() {
        return drag == null && Math.hypot(pointerX - pressedX, pointerY - pressedY) >= 4;
    }

    void start(PlaylistDrag dragged, int top, int scroll, int rowHeight) {
        drag = dragged;
        grabOffset = pressedY - top - drag.start() * rowHeight + scroll;
    }

    /**
     * Moves the insertion point to the row boundary nearest the pointer and animates the other rows there.
     */
    void preview(int top, int scroll, int rowHeight, int size) {
        if (drag == null) return;
        int boundary = Math.clamp((int) Math.floor((pointerY - top + scroll) / rowHeight + .5), 0, size);
        var next = drag.atBoundary(boundary, size);
        if (next.equals(drag)) return;
        for (int i = 0; i < size; i++) from[i] = position(i);
        drag = next;
        order = drag.order(size);
        for (int slot = 0; slot < order.size(); slot++) targets[order.get(slot)] = slot;
        animation = System.nanoTime();
    }

    boolean pointerWithin(int left, int right) {
        return pointerX >= left && pointerX < right;
    }

    /**
     * Near the list's top or bottom edge, how far the list scrolls this tick, in pixels.
     */
    int edgeScroll(int top, int bottom) {
        return pointerY < top + 20 ? -8 : pointerY >= bottom - 20 ? 8 : 0;
    }

    /**
     * Ends any press or drag; false when there was none.
     */
    boolean cancel(int size, boolean reorderable) {
        if (drag == null && !pressed) return false;
        drag = null;
        pressed = false;
        dropping = null;
        reset(size, reorderable);
        return true;
    }

    /**
     * Records the order the drop asks for; the dragged rows stay where they were dropped until the new rows arrive.
     */
    PlaylistDrag drop(int size) {
        dropping = drag.order(size);
        return drag;
    }

    boolean dropping() {
        return dropping != null;
    }

    /**
     * Whether next is current reordered as the drop asked, so the rows can glide into place.
     */
    <T> boolean lands(List<T> current, List<T> next) {
        return dropping != null && dropping.stream().map(current::get).toList().equals(next);
    }

    /**
     * The dropped rows arrived: every row continues from where it was drawn to its new place. Returns where the
     * selected row moved.
     */
    int land(int selected, int size, boolean reorderable, int top, int scroll, int rowHeight) {
        var prior = dropping;
        double[] nextFrom = new double[size];
        for (int i = 0; i < prior.size(); i++) {
            int old = prior.get(i);
            nextFrom[i] = carries(old) ? (pointerY - grabOffset - top + scroll) / rowHeight + old - drag.start() : position(old);
        }
        int moved = prior.indexOf(selected);
        reset(size, reorderable);
        from = nextFrom;
        animation = System.nanoTime();
        drag = null;
        pressed = false;
        dropping = null;
        return moved;
    }
}
