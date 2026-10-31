package com.darkmusic.client.gui;

import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsInfo;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.playlist.PlaylistDrag;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.LyricsStatusIcon;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.AbstractList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Only visible rows are extracted, even for large libraries.
 */
public final class RowList extends AbstractWidget {
    // Set once at client start; a failed reorder is reported where other client warnings appear.
    private static volatile Consumer<String> warnings = text -> {
    };

    public static void warnings(Consumer<String> warn) {
        warnings = warn;
    }

    @Override
    public void setTooltip(Tooltip tooltip) {
        super.setTooltip(tooltip);
        Help.remember(this, tooltip);
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor g, int x, int y) {
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
    private boolean lazyRows;
    private String emptyMessage = Text.get("text.darkmusic.no_entries");
    private SongSelection selection;
    private int selected = -1, scroll;
    private int clickedRow = -1;

    void resetClicks() {
        clickedRow = -1;
    }

    private double fractionalScroll;
    private Consumer<PlaylistDrag> reorder;
    private final RowDrag drag = new RowDrag();
    private IntPredicate playing = i -> false;
    private IntPredicate favorite = i -> false;

    public void favorites(IntPredicate favorite) {
        this.favorite = favorite;
    }

    private Supplier<UiIcon> playingIcon = () -> UiIcon.PLAY;

    public void playingIcon(Supplier<UiIcon> icon) {
        playingIcon = icon;
    }

    private IntUnaryOperator tint = i -> UiTheme.SECONDARY;
    private IntFunction<Track> artwork;
    private ArtworkCache artworkCache;
    private LyricsService lyricsService;
    private IntFunction<LyricsInfo> lyrics;
    private BooleanSupplier lyricsEnabled = () -> false;
    private LyricsInfo hoveredLyrics;

    public void lyrics(LyricsService service, BooleanSupplier enabled) {
        lyricsService = service;
        lyrics(i -> service.info(artwork == null ? null : artwork.apply(i)), enabled);
    }

    public void lyrics(IntFunction<LyricsInfo> info, BooleanSupplier enabled) {
        lyrics = info;
        lyricsEnabled = enabled;
    }

    public void inspectVisibleLyrics() {
        if (!visible || lyricsService == null || artwork == null || !lyricsEnabled.getAsBoolean()) return;
        if (selected >= 0 && selected < rows.size()) lyricsService.request(artwork.apply(selected));
        for (int i = Math.max(0, scroll / rowHeight), last = Math.min(rows.size(), (scroll + height) / rowHeight + 1); i < last; i++)
            lyricsService.inspect(artwork.apply(i));
    }

    private LyricsInfo lyricInfo(int index) {
        return lyrics != null && lyricsEnabled.getAsBoolean() && index >= 0 && index < rows.size() ? lyrics.apply(index) : null;
    }

    public int tooltipY() {
        return Math.clamp(getY() + (long) selected * rowHeight - scroll + rowHeight / 2, getY(), getBottom());
    }

    private IntFunction<UiIcon> icons;
    private IntUnaryOperator indent = i -> 0;
    private IntPredicate expanded = i -> false;

    public void indent(IntUnaryOperator indent) {
        this.indent = indent;
    }

    public void expanded(IntPredicate expanded) {
        this.expanded = expanded;
    }

    private IntToDoubleFunction progress;
    private int hoveredRow = -2;

    public void artwork(ArtworkCache cache, IntFunction<Track> tracks) {
        rowTooltips(false);
        artworkCache = cache;
        artwork = tracks;
    }

    public void icons(IntFunction<UiIcon> icons) {
        this.icons = icons;
    }

    public void progress(IntToDoubleFunction progress) {
        this.progress = progress;
    }

    public int scrollOffset() {
        return scroll;
    }

    public void scrollOffset(int value) {
        scroll = value;
        clamp();
    }

    public void playing(IntPredicate value) {
        playing = value;
    }

    public void tint(IntUnaryOperator value) {
        tint = value;
    }

    private IntConsumer disclosure;
    private IntUnaryOperator disclosureInset;

    public RowList(int x, int y, int width, int height, int rowHeight, String label, BiConsumer<Integer, Boolean> action) {
        super(x, y, width, height, Component.literal(label));
        this.rowHeight = rowHeight;
        this.action = action;
    }

    public void rows(List<Row> rows) {
        // Comparing against the lazy form would build every row through its factory.
        if (!lazyRows && this.rows.equals(rows)) {
            if (drag.dropping()) cancelDrag();
            return;
        }
        lazyRows = false;
        hoveredRow = -2;
        resetClicks();
        if (drag.lands(this.rows, rows)) {
            selected = drag.land(selected, rows.size(), reorder != null, getY(), scroll, rowHeight);
            this.rows = List.copyOf(rows);
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
    public void rows(int size, IntFunction<Row> factory) {
        if (size < 0) throw new IllegalArgumentException("Negative row count");
        resetClicks();
        cancelDrag();
        lazyRows = true;
        var cached = new LinkedHashMap<Integer, Row>(32, .75f, true);
        this.rows = new AbstractList<>() {
            @Override
            public Row get(int index) {
                Objects.checkIndex(index, size);
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

    public void disclosure(IntUnaryOperator inset, IntConsumer action) {
        disclosureInset = inset;
        disclosure = action;
    }

    public int selected() {
        return selected;
    }

    public void multiple(SongSelection value) {
        selection = value;
    }

    public void reorder(Consumer<PlaylistDrag> action) {
        reorder = action;
        resetOrder();
    }

    private void resetOrder() {
        drag.reset(rows.size(), reorder != null);
    }

    private void preview() {
        drag.preview(getY(), scroll, rowHeight, rows.size());
    }

    public void tickDrag() {
        if (!drag.dragging() || !drag.pointerWithin(getX(), getRight())) return;
        int change = drag.edgeScroll(getY(), getBottom());
        scroll += change;
        clamp();
        if (change != 0) preview();
    }

    public boolean cancelDrag() {
        scrollbar.cancel();
        return drag.cancel(rows.size(), reorder != null);
    }

    /**
     * The drag-and-drop state, for tests.
     */
    RowDrag dragState() {
        return drag;
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
        if (rowTooltips && (hover != hoveredRow || !Objects.equals(lyric, hoveredLyrics))) {
            hoveredRow = hover;
            hoveredLyrics = lyric;
            var row = hover >= 0 && hover < rows.size() ? rows.get(hover) : null;
            setTooltip(row == null ? null : Tooltip.create(Component.literal(row.text("\n") + (lyric == null || row.subtitle().contains(Text.get(lyric.key())) ? "" : "\n" + Text.get(lyric.key())))));
        }
        UiTheme.panel(g, getX(), getY(), width, height);
        g.enableScissor(getX(), getY(), getRight(), getBottom());
        for (int slot = Math.max(0, scroll / rowHeight - 1), last = Math.min(rows.size(), (scroll + height) / rowHeight + 2); slot < last; slot++) {
            int i = reorder == null ? slot : drag.rowAt(slot);
            if (drag.carries(i)) continue;
            int y = getY() + (int) Math.round(drag.position(i) * rowHeight) - scroll;
            drawRow(g, i, y, mx, my);
        }
        if (drag.dragging()) {
            var dragged = drag.current();
            int marker = getY() + dragged.insertion() * rowHeight - scroll;
            g.fill(getX(), marker - 1, getRight(), marker + 1, 0xFFDED8FF);
            int top = (int) Math.round(drag.carriedTop());
            int first = Math.max(0, (getY() - top) / rowHeight), last = Math.min(dragged.count(), (getBottom() - top) / rowHeight + 1);
            for (int offset = first; offset < last; offset++) {
                int y = top + offset * rowHeight;
                g.fill(getX(), y, getRight(), y + rowHeight, 0xEE62578B);
                drawRow(g, dragged.start() + offset, y, -1, -1);
            }
        }
        if (rows.isEmpty()) {
            UiIcon.MUSIC.draw(g, getX() + 8, getY() + 9, 16, UiTheme.MUTED);
            horizontal.draw(g, font, "empty", emptyMessage, getX() + 30, getY() + 13, Math.max(1, width - 40), UiTheme.SECONDARY);
        }
        int hidden = rows.size() * rowHeight - height;
        if (hidden > 0 && !drag.dragging())
            UiTheme.edgeFades(g, getX() + 1, getY() + 1, width - 11, height - 2, scroll > 0, scroll < hidden, UiTheme.SURFACE);
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
        int rightInset = statusIcons(g, font, i, y);
        Row row = rows.get(i);
        boolean twoLines = rowHeight >= 28 && (!row.subtitle().isBlank() || !row.status().isBlank());
        int lineGap = 4;
        int titleY = twoLines ? y + (rowHeight - (font.lineHeight * 2 + lineGap)) / 2 : y + (rowHeight - font.lineHeight) / 2;
        int inset = leadingIcons(g, font, i, y, titleY, twoLines);
        horizontal.draw(g, font, "title" + i, row.title(), getX() + inset, titleY, Math.max(1, width - inset - rightInset), UiTheme.TEXT);
        if (twoLines) {
            if (row.status().isBlank())
                horizontal.draw(g, font, "subtitle" + i, row.subtitle(), getX() + inset, titleY + font.lineHeight + lineGap, Math.max(1, width - inset - rightInset), tint.applyAsInt(i));
            else
                horizontal.drawStatus(g, font, "subtitle" + i, row.status(), row.subtitle(), getX() + inset, titleY + font.lineHeight + lineGap,
                    Math.max(1, width - inset - rightInset), UiStatus.color(row.tone()), UiTheme.SECONDARY);
        }
        if (progress != null) {
            double value = progress.applyAsDouble(i);
            if (value >= 0)
                g.fill(getX() + inset, y + rowHeight - 3, getX() + inset + (int) Math.round(Math.clamp(value, 0, 1) * Math.max(1, width - inset - 9)), y + rowHeight - 2, tint.applyAsInt(i));
        }
    }

    /**
     * The status icons at the row's right edge: playing, then the lyrics state and lyrics. Returns the text's right
     * inset.
     */
    private int statusIcons(GuiGraphicsExtractor g, Font font, int i, int y) {
        boolean current = playing.test(i);
        var lyric = lyricInfo(i);
        // Status icons fill 14-pixel slots from the right at equal gaps, centred on the row: playing, lyrics status,
        // then lyrics.
        int slot = 14, gap = 6, slotY = y + (rowHeight - slot) / 2;
        int left = getRight() - 12;
        if (current) {
            left -= slot;
            playingIcon.get().draw(g, left, slotY, slot, UiTheme.ACCENT);
        }
        if (lyric != null) {
            left -= (current ? gap : 0) + slot;
            LyricsStatusIcon.draw(g, font, lyric, left + 1, slotY + 1, slot - 2);
            left -= gap + slot;
            UiIcon.LYRICS.draw(g, left, slotY, slot, UiStatus.lyricsColor(lyric));
        }
        return current || lyric != null ? getRight() - left + 4 : 9;
    }

    /**
     * What precedes the title: the checkbox, the favorite heart, and the artwork or the folder chevron and icon.
     * Returns the title's left inset.
     */
    private int leadingIcons(GuiGraphicsExtractor g, Font font, int i, int y, int titleY, boolean twoLines) {
        int inset = selection == null ? 5 : 25;
        if (selection != null)
            UiTheme.checkbox(g, getX() + 6, y + (rowHeight - 11) / 2, 11, selection.checked(i), active);
        if (favorite.test(i)) {
            UiIcon.HEART_FILLED.draw(g, getX() + inset, y + (rowHeight - 12) / 2, 12, UiTheme.ACCENT);
            inset += 18;
        }
        if (artwork != null && artworkCache != null) {
            int size = Math.min(28, rowHeight - 6);
            var cover = artworkCache.thumbnail(artwork.apply(i));
            if (cover != null)
                ArtworkCache.drawMenu(g, cover, getX() + inset, y + 3, size);
            else UiIcon.MUSIC.draw(g, getX() + inset + 4, y + 7, size - 8, UiTheme.MUTED);
            inset += size + 7;
        } else if (icons != null) {
            inset += indent.applyAsInt(i);
            if (disclosureInset != null) {
                if (disclosureInset.applyAsInt(i) >= 0)
                    UiIcon.chevron(g, getX() + inset + 1, titleY, 8, UiTheme.ACCENT, expanded.test(i) ? 3 : 2);
                inset += 12;
            }
            int iconY = twoLines ? titleY + (font.lineHeight - 12) / 2 : y + (rowHeight - 12) / 2;
            icons.apply(i).draw(g, getX() + inset, iconY, 12, UiTheme.ACCENT);
            inset += 18;
        }
        return inset;
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
                drag.press(event.x(), event.y(), index);
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
        if (!drag.pressed()) return;
        drag.pointer(event.x(), event.y());
        if (drag.moved()) {
            int pressedIndex = drag.pressedIndex();
            if (!selection.checked(pressedIndex)) selection.choose(pressedIndex, false, false);
            selected = pressedIndex;
            drag.start(PlaylistDrag.connected(selection.keys(), selection.selected(), pressedIndex), getY(), scroll, rowHeight);
        }
        preview();
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (scrollbar.dragging()) {
            scrollbar.cancel();
            return;
        }
        if (!drag.pressed()) return;
        if (drag.dragging()) {
            drag.pointer(event.x(), event.y());
            preview();
            if (isMouseOver(event.x(), event.y()) && drag.current().insertion() != drag.current().start()) {
                finishDrop();
            } else cancelDrag();
        } else {
            selected = drag.pressedIndex();
            selection.choose(selected, false, false);
            action.accept(selected, false);
            drag.release();
        }
    }

    void finishDrop() {
        var dropped = drag.drop(rows.size());
        try {
            reorder.accept(dropped);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger("darkmusic").warn("Playlist reorder failed", failure);
            warnings.accept(Text.failure(failure, "playlist.status.save_failed"));
        } finally {
            if (drag.dropping()) cancelDrag();
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
        if (drag.dragging() || scrollbar.dragging()) return true;
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

    public record Row(String title, String subtitle, String details, String status, UiStatus.Tone tone) {
        public String text(String separator) {
            return Stream.of(title, status, subtitle, details).filter(value -> !value.isBlank())
                .collect(Collectors.joining(separator));
        }

        public Row(String title, String subtitle) {
            this(title, subtitle, "");
        }

        public Row(String title, String subtitle, String details) {
            this(title, subtitle, details, "", UiStatus.Tone.INFO);
        }

        public Row status(String value, UiStatus.Tone tone) {
            return new Row(title, subtitle, details, value, tone);
        }
    }
}
