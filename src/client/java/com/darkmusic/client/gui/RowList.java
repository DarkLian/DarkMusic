package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Only visible rows are extracted, even for large libraries.
 */
public final class RowList extends AbstractWidget {
    private final int rowHeight;
    private final BiConsumer<Integer, Boolean> action;
    private List<Row> rows = List.of();
    private String emptyMessage = Text.get("text.darkmusic.no_entries");
    private SongSelection selection;
    private int selected = -1, scroll;
    private java.util.function.Consumer<PlaylistDrag> reorder;
    private MouseButtonEvent pressed;
    private int pressedIndex;
    private PlaylistDrag drag;
    private double pointerX, pointerY, grabOffset;
    private List<Integer> order = List.of(), dropping;
    private double[] from = new double[0], targets = new double[0];
    private long animation;

    public RowList(int x, int y, int width, int height, int rowHeight, String label, BiConsumer<Integer, Boolean> action) {
        super(x, y, width, height, Component.literal(label));
        this.rowHeight = rowHeight;
        this.action = action;
    }

    public void rows(List<Row> rows) {
        if (this.rows.equals(rows)) return;
        if (dropping != null && dropping.stream().map(this.rows::get).toList().equals(rows)) {
            var prior = dropping;
            double[] nextFrom = new double[rows.size()];
            for (int i = 0; i < prior.size(); i++) {
                int old = prior.get(i);
                nextFrom[i] = drag != null && old >= drag.start() && old < drag.start() + drag.count()
                    ? (pointerY - grabOffset - getY() + scroll) / rowHeight + old - drag.start() : position(old);
            }
            selected = prior.indexOf(selected);
            this.rows = List.copyOf(rows);
            resetOrder();
            from = nextFrom;
            animation = System.nanoTime();
            drag = null;
            pressed = null;
            dropping = null;
        } else {
            cancelDrag();
            this.rows = List.copyOf(rows);
            resetOrder();
        }
        selected = Math.min(selected, rows.size() - 1);
        clamp();
    }

    public void select(int index) {
        selected = index >= 0 && index < rows.size() ? index : -1;
    }

    public int selected() {
        return selected;
    }

    public void multiple(SongSelection value) {
        selection = value;
    }

    public void reorder(java.util.function.Consumer<PlaylistDrag> action) {
        reorder = action;
    }

    private void resetOrder() {
        order = java.util.stream.IntStream.range(0, rows.size()).boxed().toList();
        from = new double[rows.size()];
        targets = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) from[i] = targets[i] = i;
    }

    private double position(int index) {
        double t = Math.clamp((System.nanoTime() - animation) / 150_000_000.0, 0, 1), ease = 1 - Math.pow(1 - t, 3);
        return from[index] + (targets[index] - from[index]) * ease;
    }

    private void preview() {
        if (drag == null) return;
        int boundary = Math.clamp((int) Math.floor((pointerY - getY() + scroll) / rowHeight + .5), 0, rows.size());
        var next = drag.atBoundary(boundary, rows.size());
        if (next.equals(drag)) return;
        for (int i = 0; i < rows.size(); i++) from[i] = position(i);
        drag = next;
        order = drag.order(rows.size());
        for (int slot = 0; slot < order.size(); slot++) targets[order.get(slot)] = slot;
        animation = System.nanoTime();
    }

    public void tickDrag() {
        if (drag == null || pointerX < getX() || pointerX >= getRight()) return;
        int change = pointerY < getY() + 20 ? -8 : pointerY >= getBottom() - 20 ? 8 : 0;
        scroll += change;
        clamp();
        if (change != 0) preview();
    }

    public boolean cancelDrag() {
        boolean had = drag != null || pressed != null;
        if (!had) return false;
        drag = null;
        pressed = null;
        dropping = null;
        resetOrder();
        return had;
    }

    public void emptyMessage(String message) {
        emptyMessage = message;
    }

    private void clamp() {
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() * rowHeight - height));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        var font = Minecraft.getInstance().font;
        g.fill(getX(), getY(), getRight(), getBottom(), 0xFF191D2B);
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        for (int slot = Math.max(0, scroll / rowHeight - 1), last = Math.min(rows.size(), (scroll + height) / rowHeight + 2); slot < last; slot++) {
            int i = order.get(slot);
            if (drag != null && i >= drag.start() && i < drag.start() + drag.count()) continue;
            int y = getY() + (int) Math.round(position(i) * rowHeight) - scroll;
            drawRow(g, i, y, mx, my);
        }
        if (drag != null) {
            int marker = getY() + drag.insertion() * rowHeight - scroll;
            g.fill(getX(), marker - 1, getRight(), marker + 1, 0xFFDED8FF);
            int top = (int) Math.round(pointerY - grabOffset);
            int first = Math.max(0, (getY() - top) / rowHeight), last = Math.min(drag.count(), (getBottom() - top) / rowHeight + 1);
            for (int offset = first; offset < last; offset++) {
                int y = top + offset * rowHeight;
                g.fill(getX(), y, getRight(), y + rowHeight, 0xEE62578B);
                drawRow(g, drag.start() + offset, y, -1, -1);
            }
        }
        if (rows.isEmpty())
            g.text(font, font.plainSubstrByWidth(emptyMessage, Math.max(1, width - 14)), getX() + 5, getY() + 6, 0xFFAAAABD);
        if (rows.size() * rowHeight > height) {
            int bar = Math.max(8, height * height / (rows.size() * rowHeight));
            int top = getY() + (height - bar) * scroll / Math.max(1, rows.size() * rowHeight - height);
            g.fill(getRight() - 3, top, getRight(), top + bar, 0xFF9993D9);
        }
        g.disableScissor();
        if (isFocused()) g.outline(getX(), getY(), width, height, 0xFFB8B2FF);
    }

    private void drawRow(GuiGraphicsExtractor g, int i, int y, int mx, int my) {
        var font = Minecraft.getInstance().font;
        if (selection != null ? selection.checked(i) : selected == i)
            g.fill(getX(), y, getRight(), y + rowHeight - 1, 0xFF49436D);
        else if (mx >= getX() && mx < getRight() && my >= y && my < y + rowHeight)
            g.fill(getX(), y, getRight(), y + rowHeight - 1, 0xFF292F44);
        Row row = rows.get(i);
        int inset = selection == null ? 5 : 25;
        if (selection != null) g.text(font, selection.checked(i) ? "[x]" : "[ ]", getX() + 4, y + 4, 0xFFB5B0FF);
        g.text(font, font.plainSubstrByWidth(row.title(), Math.max(1, width - inset - 9)), getX() + inset, y + 4, 0xFFF0EEFF);
        if (rowHeight >= 28)
            g.text(font, font.plainSubstrByWidth(row.subtitle(), Math.max(1, width - inset - 9)), getX() + inset, y + 16, 0xFFAAAABD);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        int index = (int) (event.y() - getY() + scroll) / rowHeight;
        if (index >= 0 && index < rows.size()) {
            if (reorder != null && selection != null && event.x() >= getX() + 24 && !event.hasShiftDown() && !event.hasControlDownWithQuirk()) {
                pressed = event;
                pressedIndex = index;
                return;
            }
            selected = index;
            if (selection != null)
                selection.choose(index, event.hasControlDownWithQuirk() || event.x() < getX() + 24, event.hasShiftDown());
            action.accept(index, selection == null && twice);
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (pressed == null) return;
        pointerX = event.x();
        pointerY = event.y();
        if (drag == null && Math.hypot(event.x() - pressed.x(), event.y() - pressed.y()) >= 4) {
            if (!selection.checked(pressedIndex)) selection.choose(pressedIndex, false, false);
            selected = pressedIndex;
            drag = PlaylistDrag.connected(selection.keys(), selection.selected(), pressedIndex);
            grabOffset = pressed.y() - getY() - drag.start() * rowHeight + scroll;
        }
        preview();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (pressed == null) return;
        if (drag != null) {
            pointerX = event.x();
            pointerY = event.y();
            preview();
            if (isMouseOver(event.x(), event.y()) && drag.insertion() != drag.start()) {
                dropping = drag.order(rows.size());
                var move = drag;
                reorder.accept(move);
            } else cancelDrag();
        } else {
            selected = pressedIndex;
            selection.choose(selected, false, false);
            action.accept(selected, false);
            pressed = null;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!isMouseOver(x, y)) return false;
        if (drag != null) return true;
        scroll -= (int) (dy * rowHeight * 2);
        clamp();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isFocused() || rows.isEmpty()) return false;
        if (selection != null && event.isSelectAll()) {
            selection.all();
            return true;
        }
        if (selection != null && (event.key() == 32 || event.isConfirmation())) {
            if (selected >= 0) selection.choose(selected, true, event.hasShiftDown());
            return true;
        }
        int old = selected;
        switch (event.key()) {
            case 265 -> selected = Math.max(0, selected - 1);
            case 264 -> selected = Math.min(rows.size() - 1, selected + 1);
            case 268 -> selected = 0;
            case 269 -> selected = rows.size() - 1;
            case 266 -> selected = Math.max(0, selected - Math.max(1, height / rowHeight));
            case 267 -> selected = Math.min(rows.size() - 1, selected + Math.max(1, height / rowHeight));
            case 257, 335 -> {
                if (selected >= 0) action.accept(selected, true);
                return true;
            }
            default -> {
                return false;
            }
        }
        if (selected * rowHeight < scroll) scroll = selected * rowHeight;
        if ((selected + 1) * rowHeight > scroll + height) scroll = (selected + 1) * rowHeight - height;
        clamp();
        if (selected != old) {
            if (selection != null && event.hasShiftDown())
                selection.choose(selected, event.hasControlDownWithQuirk(), true);
            action.accept(selected, false);
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected >= 0 && selected < rows.size())
            setMessage(Component.literal(rows.get(selected).title() + ", " + rows.get(selected).subtitle()));
        defaultButtonNarrationText(output);
    }

    public record Row(String title, String subtitle) {
    }
}
