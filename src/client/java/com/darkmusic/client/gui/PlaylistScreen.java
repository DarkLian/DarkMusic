package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlaylistScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private String selectedId = "";
    private RowList lists, entries;
    private Button remove, up, down, add;
    private boolean confirmDelete;
    private long revision = -1, libraryRevision = -1;

    public PlaylistScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("Manage Playlists"), parent);
        this.app = app;
    }

    private Button button(String text, int x, int y, int w, Runnable action, String help) {
        return addRenderableWidget(Help.tip(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build(), help));
    }

    private PlaylistStore.Playlist selected() {
        return app.playlists.get(selectedId);
    }

    @Override
    protected void init() {
        button("New", 12, 32, 60, () -> show(new TextPromptScreen(this, "Create Playlist", "", text -> {
            selectedId = app.playlists.create(text);
            selection.clear();
        })), "Create a local playlist.");
        button("Rename", 76, 32, 72, () -> {
            var p = selected();
            if (p != null)
                show(new TextPromptScreen(this, "Rename Playlist", p.name(), text -> app.playlists.rename(p.id(), text)));
        }, "Rename the selected playlist.");
        button(confirmDelete ? "Confirm Delete" : "Delete", 152, 32, 100, () -> {
            if (selected() == null) return;
            if (!confirmDelete) {
                confirmDelete = true;
                rebuildWidgets();
            } else {
                app.playlists.delete(selectedId);
                selectedId = "";
                selection.clear();
                confirmDelete = false;
                rebuildWidgets();
            }
        }, "Click twice to delete the selected playlist.");
        lists = addRenderableWidget(new RowList(12, 60, 100, Math.max(28, height - 150), 22, "Playlists", (i, activated) -> {
            selectedId = app.playlists.all().get(i).id();
            selection.clear();
            confirmDelete = false;
            refreshEntries();
        }));
        entries = addRenderableWidget(new RowList(118, 60, width - 130, Math.max(28, height - 150), 32, "Playlist songs; Space selects", (i, activated) -> {
        }));
        entries.multiple(selection);
        entries.reorder(move -> {
            app.playlists.moveBlock(selectedId, move.start(), move.count(), move.insertion());
            refreshEntries();
        });
        int w = (width - 32) / 3;
        int half = (width - 28) / 2;
        add = button("Add Songs", 12, height - 76, half, () -> {
            if (selected() != null) {
                String id = selectedId;
                show(new TrackPickerScreen(app, this, id, tracks -> app.playlists.addAll(id, tracks)));
            }
        }, "Select library songs not already added to this playlist.");
        remove = button("Remove Selected", 16 + half, height - 76, half, () -> {
            app.playlists.removeAll(selectedId, selection.selected());
            selection.clear();
            refreshEntries();
        }, "Remove all checked entries from this playlist.");
        button("Select All", 12, height - 52, w, selection::all, "Select every entry, including missing songs. Ctrl/Cmd+A works with list focus.");
        button("Deselect All", 16 + w, height - 52, w, selection::clear, "Clear every selected entry.");
        button("Invert Selection", 20 + 2 * w, height - 52, w, selection::invert, "Toggle selection of every entry.");
        up = button("Move Up", 12, height - 28, (width - 28) / 2, () -> move(-1), "Move selected songs up while preserving their relative order.");
        down = button("Move Down", 16 + (width - 28) / 2, height - 28, (width - 28) / 2, () -> move(1), "Move selected songs down while preserving their relative order.");
        refresh();
    }

    private void move(int delta) {
        app.playlists.moveAll(selectedId, selection.selected(), delta);
        refreshEntries();
    }

    private void refresh() {
        if (lists == null) return;
        var all = app.playlists.all();
        if (selected() == null) {
            selectedId = all.isEmpty() ? "" : all.getFirst().id();
            selection.clear();
        }
        lists.rows(all.stream().map(p -> new RowList.Row(p.name(), "")).toList());
        lists.select(java.util.stream.IntStream.range(0, all.size()).filter(i -> all.get(i).id().equals(selectedId)).findFirst().orElse(-1));
        refreshEntries();
    }

    private void refreshEntries() {
        var p = selected();
        if (entries == null) return;
        var keys = p == null ? List.<String>of() : p.entries().stream().map(PlaylistStore.Entry::key).toList();
        selection.retain(keys);
        selection.visible(keys);
        Map<String, Track> index = new HashMap<>();
        app.library.snapshot().tracks().forEach(t -> index.put(t.id(), t));
        entries.rows(p == null ? List.of() : p.entries().stream().map(e -> {
            Track t = index.get(e.key());
            return t == null ? new RowList.Row("[Missing] " + e.path(), "Remove this entry, then add the current file with Add Songs") : new RowList.Row(t.title(), t.artist() + " | " + e.path());
        }).toList());
    }

    @Override
    public void tick() {
        if (revision != app.playlists.revision() || libraryRevision != app.library.snapshot().revision()) {
            revision = app.playlists.revision();
            libraryRevision = app.library.snapshot().revision();
            refresh();
        }
        entries.tickDrag();
        add.active = selected() != null;
        remove.active = up.active = down.active = !selection.selected().isEmpty();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 256 && entries != null && entries.cancelDrag()) return true;
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "Manage Playlists", 12, 12, 0xFFB5B0FF);
        g.text(font, selection.selected().size() + " selected", 264, 38, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, d);
    }
}
