package com.darkmusic.client.gui;

import com.darkmusic.client.playlist.PlaylistDrag;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RowListReliabilityTest {
    @Test
    void activationRequiresTwoConsecutiveClicksOnTheSameRow() {
        var activated = new ArrayList<Integer>();
        var list = new RowList(0, 0, 200, 200, 36, "Songs", (i, activate) -> {
            if (activate) activated.add(i);
        });
        list.rows(List.of(new RowList.Row("A", ""), new RowList.Row("B", "")));
        var a = new MouseButtonEvent(40, 12, new MouseButtonInfo(0, 0));
        var b = new MouseButtonEvent(40, 48, new MouseButtonInfo(0, 0));
        list.onClick(a, false);
        list.onClick(b, true);
        list.onClick(a, true);
        assertTrue(activated.isEmpty());
        list.onClick(a, true);
        assertEquals(List.of(0), activated);
        list.onClick(a, true);
        assertEquals(List.of(0), activated, "A completed pair must not arm a third click");
        list.resetClicks(); // Another screen element was clicked.
        list.onClick(a, true);
        assertEquals(List.of(0), activated);
        list.setFocused(false);
        list.onClick(a, true);
        assertEquals(List.of(0), activated);
        list.rows(List.of(new RowList.Row("C", ""), new RowList.Row("D", "")));
        list.onClick(a, true);
        assertEquals(List.of(0), activated, "Changing row contents must invalidate an old click");
    }

    @Test
    void fractionalScrollingAccumulatesInBothDirectionsAndRecoversAtTheBoundary() {
        var list = new RowList(0, 0, 200, 400, 36, "Songs", (i, activated) -> {
        });
        list.rows(100, i -> new RowList.Row("Song", ""));
        for (int i = 0; i < 4; i++) list.scrollBy(.25);
        assertEquals(1, list.scrollOffset());
        for (int i = 0; i < 4; i++) list.scrollBy(-.25);
        assertEquals(0, list.scrollOffset());
        list.scrollBy(-1.5);
        for (int i = 0; i < 4; i++) list.scrollBy(.25);
        assertEquals(1, list.scrollOffset());
    }

    @Test
    void unchangedAndFailedDropsReleaseTheirDragState() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            var list = new RowList(0, 0, 200, 400, 36, "Songs", (i, activated) -> {
            });
            list.reorder(move -> {
                if (fail) throw new IllegalStateException("Rejected write");
            });
            list.rows(List.of(new RowList.Row("A", ""), new RowList.Row("B", "")));
            var drag = RowList.class.getDeclaredField("drag");
            drag.setAccessible(true);
            drag.set(list, new PlaylistDrag(0, 1, 1));
            var finish = RowList.class.getDeclaredMethod("finishDrop");
            finish.setAccessible(true);
            assertDoesNotThrow(() -> finish.invoke(list));
            assertNull(drag.get(list));
            var dropping = RowList.class.getDeclaredField("dropping");
            dropping.setAccessible(true);
            assertNull(dropping.get(list));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void hundredThousandRowsAreLazyAndCacheOnlyTheViewport() throws Exception {
        var calls = new AtomicInteger();
        var list = new RowList(0, 0, 200, 400, 36, "Songs", (i, activated) -> {
        });
        list.rows(100_000, i -> {
            calls.incrementAndGet();
            return new RowList.Row("Track " + i, "Artist");
        });
        assertEquals(0, calls.get());
        var rows = RowList.class.getDeclaredField("rows");
        rows.setAccessible(true);
        var values = (List<RowList.Row>) rows.get(list);
        for (int i = 0; i < 15; i++) values.get(i);
        for (int i = 0; i < 15; i++) values.get(i);
        assertEquals(15, calls.get());
        for (int i = 100; i < 200; i++) values.get(i);
        values.get(0);
        assertEquals(116, calls.get(), "Old viewport rows should be evicted");
        var order = RowList.class.getDeclaredField("order");
        order.setAccessible(true);
        assertTrue(((List<?>) order.get(list)).isEmpty(), "Non-draggable lists do not need a full ordering array");
    }
}
