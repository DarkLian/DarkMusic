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
    @Override
    public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y) {
        if (rowTooltips) Help.extract(this, g, x, y);
    }

    private boolean rowTooltips = true;

    public void rowTooltips(boolean enabled) {
        rowTooltips = enabled;
        if (!enabled) setTooltip(null);
    }

    private final HorizontalText horizontal = new HorizontalText();
    private final ScrollBar scrollbar = new ScrollBar(() -> this.scroll, value -> {
        this.scroll = value;
        clamp();
    });
    private final int rowHeight;
    private final BiConsumer<Integer, Boolean> action;
    private List<Row> rows = List.of();
    private String emptyMessage = Text.get("text.darkmusic.no_entries");
    private SongSelection selection;
    private int selected = -1, scroll;
    private int clickedRow = -1;

    void resetClicks() {
        clickedRow = -1;
    }

    private double fractionalScroll;
    private java.util.function.Consumer<PlaylistDrag> reorder;
    private MouseButtonEvent pressed;
    private int pressedIndex;
    private PlaylistDrag drag;
    private double pointerX, pointerY, grabOffset;
    private List<Integer> order = List.of(), dropping;
    private double[] from = new double[0], targets = new double[0];
    private long animation;
    private java.util.function.IntPredicate playing = i -> false;
    private java.util.function.IntPredicate favorite = i -> false;

    public void favorites(java.util.function.IntPredicate favorite) {
        this.favorite = favorite;
    }

    private java.util.function.Supplier<UiIcon> playingIcon = () -> UiIcon.PLAY;

    public void playingIcon(java.util.function.Supplier<UiIcon> icon) {
        playingIcon = icon;
    }

    private java.util.function.IntUnaryOperator tint = i -> UiTheme.SECONDARY;
    private java.util.function.IntFunction<com.darkmusic.client.library.Track> artwork;
    private com.darkmusic.client.library.ArtworkCache artworkCache;
    private com.darkmusic.client.lyrics.LyricsService lyricsService;
    private java.util.function.IntFunction<com.darkmusic.client.lyrics.LyricsInfo> lyrics;
    private java.util.function.BooleanSupplier lyricsEnabled = () -> false;
    private com.darkmusic.client.lyrics.LyricsInfo hoveredLyrics;

    public void lyrics(com.darkmusic.client.lyrics.LyricsService service, java.util.function.BooleanSupplier enabled) {
        lyricsService = service;
        lyrics(i -> service.info(artwork == null ? null : artwork.apply(i)), enabled);
    }

    public void lyrics(java.util.function.IntFunction<com.darkmusic.client.lyrics.LyricsInfo> info, java.util.function.BooleanSupplier enabled) {
        lyrics = info;
        lyricsEnabled = enabled;
    }

    public void inspectVisibleLyrics() {
        if (!visible || lyricsService == null || artwork == null || !lyricsEnabled.getAsBoolean()) return;
        if (selected >= 0 && selected < rows.size()) lyricsService.request(artwork.apply(selected));
        for (int i = Math.max(0, scroll / rowHeight), last = Math.min(rows.size(), (scroll + height) / rowHeight + 1); i < last; i++)
            lyricsService.inspect(artwork.apply(i));
    }

    private com.darkmusic.client.lyrics.LyricsInfo lyricInfo(int index) {
        return lyrics != null && lyricsEnabled.getAsBoolean() && index >= 0 && index < rows.size() ? lyrics.apply(index) : null;
    }

    public int tooltipY() {
        return Math.clamp(getY() + (long) selected * rowHeight - scroll + rowHeight / 2, getY(), getBottom());
    }

    private java.util.function.IntFunction<UiIcon> icons;
    private java.util.function.IntUnaryOperator indent = i -> 0;
    private java.util.function.IntPredicate expanded = i -> false;

    public void indent(java.util.function.IntUnaryOperator indent) {
        this.indent = indent;
    }

    public void expanded(java.util.function.IntPredicate expanded) {
        this.expanded = expanded;
    }

    private java.util.function.IntToDoubleFunction progress;
    private int hoveredRow = -2;

    public void artwork(com.darkmusic.client.library.ArtworkCache cache, java.util.function.IntFunction<com.darkmusic.client.library.Track> tracks) {
        rowTooltips(false);
        artworkCache = cache;
        artwork = tracks;
    }

    public void icons(java.util.function.IntFunction<UiIcon> icons) {
        this.icons = icons;
    }

    public void progress(java.util.function.IntToDoubleFunction progress) {
        this.progress = progress;
    }

    public int scrollOffset() {
        return scroll;
    }

    public void scrollOffset(int value) {
        scroll = value;
        clamp();
    }

    public void playing(java.util.function.IntPredicate value) {
        playing = value;
    }

    public void tint(java.util.function.IntUnaryOperator value) {
        tint = value;
    }

    private java.util.function.IntConsumer disclosure;
    private java.util.function.IntUnaryOperator disclosureInset;

    public RowList(int x, int y, int width, int height, int rowHeight, String label, BiConsumer<Integer, Boolean> action) {
        super(x, y, width, height, Component.literal(label));
        this.rowHeight = rowHeight;
        this.action = action;
    }

    public void rows(List<Row> rows) {
        if (this.rows.equals(rows)) {
            if (dropping != null) cancelDrag();
            return;
        }
        hoveredRow = -2;
        resetClicks();
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

    /**
     * Build and retain only a small viewport of row presentation data.
     */
    public void rows(int size, java.util.function.IntFunction<Row> factory) {
        if (size < 0) throw new IllegalArgumentException("Negative row count");
        resetClicks();
        cancelDrag();
        var cached = new java.util.LinkedHashMap<Integer, Row>(32, .75f, true);
        this.rows = new java.util.AbstractList<>() {
            @Override
            public Row get(int index) {
                java.util.Objects.checkIndex(index, size);
                Row row = cached.computeIfAbsent(index, factory::apply);
                int limit = Math.max(32, height / rowHeight + 8);
                while (cached.size() > limit) cached.remove(cached.keySet().iterator().next());
                return row;
            }

            @Override
            public int size() {
                return size;
            }
        };
        hoveredRow = -2;
        selected = Math.min(selected, size - 1);
        resetOrder();
        clamp();
    }

    public void select(int index) {
        selected = index >= 0 && index < rows.size() ? index : -1;
    }

    public void reveal(int index) {
        select(index);
        if (selected < 0) return;
        if (selected * rowHeight < scroll) scroll = selected * rowHeight;
        if ((selected + 1) * rowHeight > scroll + height) scroll = (selected + 1) * rowHeight - height;
        clamp();
    }

    public void disclosure(java.util.function.IntUnaryOperator inset, java.util.function.IntConsumer action) {
        disclosureInset = inset;
        disclosure = action;
    }

    public int selected() {
        return selected;
    }

    public void multiple(SongSelection value) {
        selection = value;
    }

    public void reorder(java.util.function.Consumer<PlaylistDrag> action) {
        reorder = action;
        resetOrder();
    }

    private void resetOrder() {
        if (reorder == null) {
            order = List.of();
            from = targets = new double[0];
            return;
        }
        order = java.util.stream.IntStream.range(0, rows.size()).boxed().toList();
        from = new double[rows.size()];
        targets = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) from[i] = targets[i] = i;
    }

    private double position(int index) {
        if (from.length == 0) return index;
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
        scrollbar.cancel();
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
        scrollbar.configure(getRight() - 9, getY(), height, rows.size() * rowHeight, height);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        horizontal.begin();
        if (artworkCache != null) artworkCache.visibleRows((height + rowHeight - 1) / rowHeight);
        var font = Minecraft.getInstance().font;
        int hover = isMouseOver(mx, my) && mx < getRight() - 9 ? (my - getY() + scroll) / rowHeight : -1;
        if (hover < 0 && isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard()) hover = selected;
        var lyric = lyricInfo(hover);
        if (rowTooltips && (hover != hoveredRow || !java.util.Objects.equals(lyric, hoveredLyrics))) {
            hoveredRow = hover;
            hoveredLyrics = lyric;
            var row = hover >= 0 && hover < rows.size() ? rows.get(hover) : null;
            setTooltip(row == null ? null : net.minecraft.client.gui.components.Tooltip.create(Component.literal(row.text("\n") + (lyric == null || row.subtitle().contains(Text.get(lyric.key())) ? "" : "\n" + Text.get(lyric.key())))));
        }
        UiTheme.panel(g, getX(), getY(), width, height);
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        for (int slot = Math.max(0, scroll / rowHeight - 1), last = Math.min(rows.size(), (scroll + height) / rowHeight + 2); slot < last; slot++) {
            int i = reorder == null ? slot : order.get(slot);
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
        if (rows.isEmpty()) {
            UiIcon.MUSIC.draw(g, getX() + 8, getY() + 9, 16, UiTheme.MUTED);
            horizontal.draw(g, font, "empty", emptyMessage, getX() + 30, getY() + 13, Math.max(1, width - 40), UiTheme.SECONDARY);
        }
        scrollbar.configure(getRight() - 9, getY(), height, rows.size() * rowHeight, height);
        scrollbar.extractRenderState(g, mx, my, d);
        g.disableScissor();
        if (isFocused()) g.outline(getX(), getY(), width, height, UiTheme.ACCENT);
    }

    private void drawRow(GuiGraphicsExtractor g, int i, int y, int mx, int my) {
        var font = Minecraft.getInstance().font;
        if (selection != null ? selection.checked(i) : selected == i)
            g.fill(getX(), y, getRight(), y + rowHeight - 1, UiTheme.SELECTED);
        else if (mx >= getX() && mx < getRight() && my >= y && my < y + rowHeight)
            g.fill(getX(), y, getRight(), y + rowHeight - 1, UiTheme.HOVER);
        if (selection != null ? selection.checked(i) : selected == i)
            g.fill(getX() + 1, y + 2, getX() + 3, y + rowHeight - 3, UiTheme.ACCENT);
        boolean current = playing.test(i);
        var lyric = lyricInfo(i);
        int rightInset = (current ? 34 : 9) + (lyric != null ? 34 : 0);
        if (lyric != null) {
            int x = getRight() - rightInset + 2;
            UiIcon.LYRICS.draw(g, x, y + (rowHeight - 14) / 2, 14, lyric.color());
            LyricsStatusIcon.draw(g, font, lyric, x + 17, y + (rowHeight - 12) / 2, 12);
        }
        if (current) playingIcon.get().draw(g, getRight() - 26, y + (rowHeight - 14) / 2, 14, UiTheme.ACCENT);
        Row row = rows.get(i);
        boolean twoLines = rowHeight >= 28 && !row.subtitle().isBlank();
        int lineGap = 4;
        int titleY = twoLines ? y + (rowHeight - (font.lineHeight * 2 + lineGap)) / 2 : y + (rowHeight - font.lineHeight) / 2;
        int inset = selection == null ? 5 : 25;
        if (selection != null) g.text(font, selection.checked(i) ? "[x]" : "[ ]", getX() + 4, y + 4, UiTheme.ACCENT);
        if (favorite.test(i)) {
            UiIcon.HEART_FILLED.draw(g, getX() + inset, y + (rowHeight - 12) / 2, 12, UiTheme.ACCENT);
            inset += 18;
        }
        if (artwork != null && artworkCache != null) {
            int size = Math.min(28, rowHeight - 6);
            var cover = artworkCache.thumbnail(artwork.apply(i));
            if (cover != null)
                com.darkmusic.client.library.ArtworkCache.drawMenu(g, cover, getX() + inset, y + 3, size);
            else UiIcon.MUSIC.draw(g, getX() + inset + 4, y + 7, size - 8, UiTheme.MUTED);
            inset += size + 7;
        } else if (icons != null) {
            inset += indent.applyAsInt(i);
            if (disclosureInset != null) {
                if (disclosureInset.applyAsInt(i) >= 0)
                    g.text(font, expanded.test(i) ? "v" : ">", getX() + inset + 1, titleY, UiTheme.ACCENT);
                inset += 12;
            }
            int iconY = twoLines ? titleY + (font.lineHeight - 12) / 2 : y + (rowHeight - 12) / 2;
            icons.apply(i).draw(g, getX() + inset, iconY, 12, UiTheme.ACCENT);
            inset += 18;
        }
        horizontal.draw(g, font, "title" + i, row.title(), getX() + inset, titleY, Math.max(1, width - inset - rightInset), UiTheme.TEXT);
        if (twoLines)
            horizontal.draw(g, font, "subtitle" + i, row.subtitle(), getX() + inset, titleY + font.lineHeight + lineGap, Math.max(1, width - inset - rightInset), tint.applyAsInt(i));
        if (progress != null) {
            double value = progress.applyAsDouble(i);
            if (value >= 0)
                g.fill(getX() + inset, y + rowHeight - 3, getX() + inset + (int) Math.round(Math.clamp(value, 0, 1) * Math.max(1, width - inset - 9)), y + rowHeight - 2, tint.applyAsInt(i));
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean twice) {
        clamp();
        int previousClick = clickedRow;
        resetClicks();
        if (scrollbar.hit(event.x(), event.y())) {
            cancelDrag();
            scrollbar.onClick(event, twice);
            return;
        }
        int index = (int) (event.y() - getY() + scroll) / rowHeight;
        if (index >= 0 && index < rows.size()) {
            int inset = disclosureInset == null ? -1 : disclosureInset.applyAsInt(index);
            if (inset >= 0 && event.x() >= getX() + inset && event.x() < getX() + inset + 12) {
                disclosure.accept(index);
                return;
            }
            if (reorder != null && selection != null && event.x() >= getX() + 24 && !event.hasShiftDown() && !event.hasControlDownWithQuirk()) {
                pressed = event;
                pressedIndex = index;
                return;
            }
            selected = index;
            if (selection != null)
                selection.choose(index, event.hasControlDownWithQuirk() || event.x() < getX() + 24, event.hasShiftDown());
            boolean activate = selection == null && twice && previousClick == index;
            if (!activate) clickedRow = index;
            action.accept(index, activate);
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        if (scrollbar.dragging()) {
            scrollbar.drag(event.y());
            return;
        }
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
        if (scrollbar.dragging()) {
            scrollbar.cancel();
            return;
        }
        if (pressed == null) return;
        if (drag != null) {
            pointerX = event.x();
            pointerY = event.y();
            preview();
            if (isMouseOver(event.x(), event.y()) && drag.insertion() != drag.start()) {
                finishDrop();
            } else cancelDrag();
        } else {
            selected = pressedIndex;
            selection.choose(selected, false, false);
            action.accept(selected, false);
            pressed = null;
        }
    }

    private void finishDrop() {
        dropping = drag.order(rows.size());
        try {
            reorder.accept(drag);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic").warn("Playlist reorder failed", failure);
            var app = com.darkmusic.client.DarkMusicClient.INSTANCE;
            if (app != null) app.warn(Text.failure(failure, "playlist.status.save_failed"));
        } finally {
            if (dropping != null) cancelDrag();
        }
    }

    void scrollBy(double pixels) {
        fractionalScroll += pixels;
        int whole = (int) fractionalScroll;
        fractionalScroll -= whole;
        scroll += whole;
        int desired = scroll;
        clamp();
        if (scroll != desired) fractionalScroll = 0;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!isMouseOver(x, y)) return false;
        if (drag != null || scrollbar.dragging()) return true;
        if (horizontal.scroll(x, y, dx, dy, HorizontalText.shiftDown())) return true;
        if (HorizontalText.horizontalIntent(dx, dy)) return false;
        scrollBy(-dy * rowHeight * 2);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isFocused() || rows.isEmpty()) return false;
        resetClicks();
        if (selection != null && event.isSelectAll()) {
            selection.all();
            return true;
        }
        if (selection != null && (event.key() == 32 || event.isConfirmation())) {
            if (selected >= 0) selection.choose(selected, true, event.hasShiftDown());
            return true;
        }
        if (event.key() == 262 || event.key() == 263) {
            int index = Math.max(0, selected);
            double amount = event.key() == 262 ? 24 : -24;
            return horizontal.pan("title" + index, amount) | horizontal.pan("subtitle" + index, amount);
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
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            resetClicks();
            cancelDrag();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        if (selected >= 0 && selected < rows.size()) {
            var info = lyricInfo(selected);
            setMessage(Component.literal(rows.get(selected).text(", ") + (info == null || rows.get(selected).subtitle().contains(Text.get(info.key())) ? "" : ", " + Text.get(info.key()))
                + ". " + Text.get("accessibility.position", selected + 1, rows.size())
                + (selection == null ? "" : ". " + Text.get(selection.checked(selected) ? "accessibility.selected" : "accessibility.not_selected"))));
        }
        defaultButtonNarrationText(output);
    }

    public record Row(String title, String subtitle, String details) {
        public String text(String separator) {
            return java.util.stream.Stream.of(title, subtitle, details).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(separator));
        }

        public Row(String title, String subtitle) {
            this(title, subtitle, "");
        }
    }
}
