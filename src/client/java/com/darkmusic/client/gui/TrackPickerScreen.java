package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class TrackPickerScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Screen parent;
    private final Consumer<List<Track>> accept;
    private final String playlistId;
    private final SongSelection selection = new SongSelection();
    private List<Track> tracks = List.of();
    private RowList rows;
    private EditBox search;
    private Button choose;
    private String query = "";
    private long revision = -1;
    private long playlistRevision = -1;

    public TrackPickerScreen(DarkMusicClient app, Screen parent, String playlistId, Consumer<List<Track>> accept) {
        super(Component.literal("Add Songs"), parent);
        this.app = app;
        this.parent = parent;
        this.accept = accept;
        this.playlistId = playlistId;
    }

    private List<Track> eligible() {
        return app.playlists.availableToAdd(playlistId, app.library.snapshot().tracks());
    }

    private Button button(String text, int x, int y, int width, Runnable action, String help) {
        return addRenderableWidget(Help.tip(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, width, 20).build(), help));
    }

    @Override
    protected void init() {
        search = addRenderableWidget(new EditBox(font, 12, 32, width - 24, 20, Component.literal("Search Songs")));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            refresh();
        });
        rows = addRenderableWidget(new RowList(12, 60, width - 24, Math.max(28, height - 140), 32, "Songs", (i, activated) -> {
        }));
        {
            rows.multiple(selection);
            int w = (width - 32) / 3;
            button("Select All", 12, height - 52, w, selection::all, "Select every visible song. Ctrl/Cmd+A works when the list is focused.");
            button("Deselect All", 16 + w, height - 52, w, selection::clear, "Clear selections, including songs hidden by search.");
            button("Invert Selection", 20 + w * 2, height - 52, w, selection::invert, "Toggle selection of visible songs; hidden selections are kept.");
        }
        choose = button("Add Selected", 12, height - 28, (width - 28) / 2, this::choose, "Add Selected songs in library order. Songs already added are hidden.");
        button("Cancel", 16 + (width - 28) / 2, height - 28, (width - 28) / 2, this::onClose, "Return without changing the playlist. Escape also cancels.");
        refresh();
    }

    private void refresh() {
        if (rows == null) return;
        var library = eligible();
        // Keep pending selections during partial scans; prune removed files only after a completed scan.
        if (!app.library.snapshot().scanning()) selection.retain(library.stream().map(Track::id).toList());
        tracks = library.stream().filter(t -> t.matches(query.toLowerCase(Locale.ROOT).strip())).toList();
        rows.emptyMessage(library.isEmpty() && !app.library.snapshot().tracks().isEmpty() ? "All Library Songs Are Already Added" : "No Matching Songs");
        selection.visible(tracks.stream().map(Track::id).toList());
        rows.rows(tracks.stream().map(t -> new RowList.Row(t.title(), t.artist() + " | " + t.relativePath())).toList());
        if (choose != null)
            choose.active = app.playlists.get(playlistId) != null && !app.library.snapshot().scanning() && !selection.selected().isEmpty();
    }

    private void choose() {
        var checked = selection.selected();
        List<Track> chosen = eligible().stream().filter(t -> checked.contains(t.id())).toList();
        if (!chosen.isEmpty()) {
            accept.accept(chosen);
            show(parent);
        }
    }

    @Override
    public void tick() {
        if (revision != app.library.snapshot().revision() || playlistRevision != app.playlists.revision()) {
            revision = app.library.snapshot().revision();
            playlistRevision = app.playlists.revision();
            refresh();
        }
        choose.active = app.playlists.get(playlistId) != null && !app.library.snapshot().scanning() && !selection.selected().isEmpty();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        g.text(font, selection.selected().size() + " Selected (" + selection.hidden() + " Hidden). Shift: Range; Ctrl/Cmd: Toggle", 12, height - 72, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, d);
    }
}
