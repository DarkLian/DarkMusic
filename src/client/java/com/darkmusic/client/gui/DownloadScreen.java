package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkmusic.client.download.DownloadModels.*;

public final class DownloadScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private final Map<String, Entry> known = new LinkedHashMap<>();
    private EditBox url, first, last;
    private RowList rows;
    private Button analyze, download, modeButton, previous, next, selectAll, deselect;
    private Preview displayed;
    private Mode mode = Mode.VIDEO;
    private boolean queueView;
    private String input = "", from = "10", to = "30", notice = "";
    private long selectedJob = -1;

    public DownloadScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("Downloads"), parent);
        this.app = app;
    }

    private static String key(Entry e) {
        return e.index() + ":" + e.key();
    }

    private Button button(String label, int x, int y, int w, Runnable action, String help) {
        return addRenderableWidget(Help.tip(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build(), help));
    }

    @Override
    protected void init() {
        button("Tools", width - 82, 6, 70, () -> show(new DownloadToolsScreen(app, this)), "Set executable paths and check yt-dlp, FFmpeg, FFprobe, and optional JavaScript runtime.");
        url = addRenderableWidget(new EditBox(font, 12, 30, width - 116, 20, Component.literal("Video or Collection URL")));
        url.setMaxLength(4096);
        url.setValue(input);
        url.setHint(Component.literal("Paste a video or collection URL"));
        url.setResponder(s -> {
            input = s;
            notice = "";
        });
        analyze = button("Analyze", width - 100, 30, 88, () -> {
            notice = "";
            selection.clear();
            known.clear();
            app.downloads.analyze(input, 0);
        }, "Identify the source and preview up to 100 entries. Nothing downloads automatically.");
        int half = (width - 28) / 2;
        modeButton = button("Mode: " + mode.label, 12, 54, half, () -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            updateButtons();
        }, "Choose This Video Only, selected preview entries, First N, a range, or explicitly All Entries. Mixes have no stable end.");
        first = addRenderableWidget(Help.tip(new EditBox(font, 16 + half, 54, (half - 4) / 2, 20, Component.literal("First N or Range Start")), "First N: number of entries. Range: first entry number, starting at 1."));
        first.setMaxLength(7);
        first.setValue(from);
        first.setResponder(s -> from = s);
        last = addRenderableWidget(Help.tip(new EditBox(font, 20 + half + (half - 4) / 2, 54, (half - 4) / 2, 20, Component.literal("Range End")), "Range: last entry number, inclusive. For 10–30, enter 10 and 30."));
        last.setMaxLength(7);
        last.setValue(to);
        last.setResponder(s -> to = s);
        download = button("Download", 12, 78, half, this::download, "Queue only the explicitly chosen selection into the current music folder. Up to 1,000 entries per batch; larger collections need separate ranges. Download only content you are authorized to save.");
        button("Show Queue / Preview", 16 + half, 78, half, () -> {
            queueView = !queueView;
            rows.multiple(queueView ? null : selection);
            refreshRows();
        }, "Switch between collection preview and download queue. Closing this screen does not cancel the queue.");
        int w = (width - 36) / 4;
        previous = button("Prev 100", 12, 102, w, () -> page(-1), "Preview the previous page. Checked entries are kept across pages.");
        next = button("Next 100", 16 + w, 102, w, () -> page(1), "Preview the next page; this does not download it.");
        selectAll = button("Select Page", 20 + 2 * w, 102, w, selection::all, "Check every entry on this preview page. Download Selected Preview includes checks kept from other pages.");
        deselect = button("Clear Checks", 24 + 3 * w, 102, w, selection::clear, "Clear all preview checks, including hidden pages.");
        rows = addRenderableWidget(new RowList(12, 128, width - 24, Math.max(28, height - 196), 32, "Preview or Queue", (i, activated) -> {
            if (queueView) {
                var items = app.downloads.snapshot().items();
                if (i < items.size()) selectedJob = items.get(i).id();
            }
        }));
        rows.multiple(queueView ? null : selection);
        int third = (width - 32) / 3;
        button("Cancel Selected", 12, height - 28, third, () -> {
            if (selectedJob >= 0) app.downloads.cancel(selectedJob);
        }, "Cancel the selected queued or active item; other downloads continue.");
        button("Cancel All", 16 + third, height - 28, third, app.downloads::cancelAll, "Cancel URL analysis, active work, and queued entries. Completed songs are kept.");
        button("Clear Finished", 20 + 2 * third, height - 28, third, app.downloads::clearFinished, "Remove saved, skipped, failed, and cancelled rows from the queue; saved audio is kept.");
        tick();
    }

    private void page(int delta) {
        var p = app.downloads.snapshot().preview();
        if (p != null) app.downloads.analyze(p.url(), Math.max(0, (p.first() - 1) / PAGE_SIZE + delta));
    }

    private void download() {
        try {
            var p = app.downloads.snapshot().preview();
            if (p == null || !p.url().equals(DownloadModels.url(input)))
                throw new IllegalArgumentException("Analyze this URL before downloading.");
            var destination = app.library.root();
            notice = "";
            switch (mode) {
                case VIDEO -> {
                    if (p.collection() && !p.videoAlso())
                        throw new IllegalArgumentException("This is a collection URL. Choose selected entries, First N, or a range.");
                    app.downloads.enqueueVideo(p.url(), destination);
                }
                case SELECTED -> {
                    var selected = known.entrySet().stream().filter(e -> selection.selected().contains(e.getKey())).map(Map.Entry::getValue).toList();
                    if (selected.isEmpty()) throw new IllegalArgumentException("Check at least one preview entry.");
                    app.downloads.enqueueSelected(selected, destination);
                }
                case FIRST -> app.downloads.enqueueRange(p, 1, positive(from), destination);
                case RANGE -> app.downloads.enqueueRange(p, positive(from), positive(to), destination);
                case ALL -> {
                    if (p.total() < 1)
                        throw new IllegalArgumentException("This collection/Mix has no known end. Choose First N or a range.");
                    app.downloads.enqueueRange(p, 1, p.total(), destination);
                }
            }
            queueView = true;
            rows.multiple(null);
            refreshRows();
        } catch (IllegalArgumentException e) {
            notice = e.getMessage();
        }
    }

    private int positive(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n > 0) return n;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Enter a positive entry number.");
    }

    private void updateButtons() {
        var snapshot = app.downloads.snapshot();
        var p = snapshot.preview();
        analyze.active = !snapshot.analyzing();
        download.active = !snapshot.analyzing() && p != null;
        modeButton.setMessage(Component.literal("Mode: " + mode.label));
        first.active = mode == Mode.FIRST || mode == Mode.RANGE;
        last.active = mode == Mode.RANGE;
        previous.active = !queueView && !snapshot.analyzing() && p != null && p.first() > 1;
        next.active = !queueView && !snapshot.analyzing() && p != null && p.hasMore();
        selectAll.active = deselect.active = !queueView && p != null;
        download.setMessage(Component.literal(mode == Mode.SELECTED ? "Download Checked (" + selection.selected().size() + ")" : mode == Mode.VIDEO ? "Download One Video" : mode == Mode.ALL ? "Download All Entries" : mode == Mode.FIRST ? "Download First N" : "Download Range"));
    }

    private void refreshRows() {
        var snapshot = app.downloads.snapshot();
        var p = snapshot.preview();
        if (queueView) {
            rows.rows(snapshot.items().stream().map(i -> new RowList.Row(i.title(), i.status() + (i.progress() >= 0 ? " " + Math.round(i.progress() * 100) + "%" : "") + " | " + i.message())).toList());
            rows.emptyMessage("No Downloads Queued");
        } else {
            var entries = p == null ? List.<Entry>of() : p.entries();
            selection.visible(entries.stream().map(DownloadScreen::key).toList());
            rows.rows(entries.stream().map(e -> new RowList.Row(Math.max(1, e.index()) + ". " + e.title(), e.available() ? p.source() : "Unavailable / Private / Deleted")).toList());
            rows.emptyMessage("Analyze a URL to Preview Entries");
        }
    }

    @Override
    public void tick() {
        var p = app.downloads.snapshot().preview();
        if (p != displayed) {
            if (displayed != null && p != null && !displayed.url().equals(p.url())) {
                selection.clear();
                known.clear();
            }
            displayed = p;
            if (p != null) p.entries().forEach(e -> known.put(key(e), e));
        }
        refreshRows();
        updateButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "Downloads", 12, 10, 0xFFB5B0FF);
        var s = app.downloads.snapshot();
        String detail = notice.isBlank() ? s.message() : notice;
        if (queueView) {
            var item = s.items().stream().filter(i -> i.id() == selectedJob).findFirst().orElse(null);
            if (item != null) detail = item.message() + " | " + item.directory();
        }
        g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, height - 62, 0xFFFFCC88);
        long done = s.items().stream().filter(i -> i.status().finished()).count(), failed = s.items().stream().filter(i -> i.status() == Status.FAILED).count();
        String summary = queueView ? done + "/" + s.items().size() + " Finished | " + failed + " Failed" : s.preview() == null ? "Only authorized public content. Tools are installed separately." : s.preview().source() + " | Entries " + s.preview().first() + "–" + (s.preview().first() + s.preview().entries().size() - 1) + (s.preview().total() >= 0 ? " of " + s.preview().total() : " | Total Unknown / Dynamic");
        g.text(font, font.plainSubstrByWidth(summary, width - 24), 12, height - 44, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, delta);
    }

    private enum Mode {
        VIDEO("This Video Only"), SELECTED("Selected Preview"), FIRST("First N Entries"), RANGE("Entry Range"), ALL("All Entries");
        final String label;

        Mode(String label) {
            this.label = label;
        }
    }
}
