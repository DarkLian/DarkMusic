package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;
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
    private long revision = -1, libraryRevision = -1;

    public PlaylistScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.manage_playlists"), parent);
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
        button(Text.get("text.darkmusic.new"), 12, 32, 60, () -> show(new TextPromptScreen(this, Text.get("text.darkmusic.create_playlist"), "", text -> {
            selectedId = app.playlists.create(text);
            selection.clear();
        })), Text.get("text.darkmusic.create_a_local_playlist"));
        button(Text.get("text.darkmusic.rename"), 76, 32, 72, () -> {
            var p = selected();
            if (p != null)
                show(new TextPromptScreen(this, Text.get("text.darkmusic.rename_playlist"), p.name(), text -> app.playlists.rename(p.id(), text)));
        }, Text.get("text.darkmusic.rename_the_selected_playlist"));
        button(Text.get("text.darkmusic.delete"), 152, 32, 100, () -> {
            var playlist = selected();
            if (playlist == null) return;
            show(new ConfirmationScreen(this, Text.component("confirm.darkmusic.delete_title"),
                Text.component("confirm.darkmusic.delete_body", playlist.name()), Text.component("text.darkmusic.delete"), () -> {
                app.playlists.delete(playlist.id());
                selectedId = "";
                selection.clear();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }));
        }, Text.get("confirm.darkmusic.delete_help"));
        lists = addRenderableWidget(new RowList(12, 60, 100, Math.max(28, height - 150), 22, "Playlists", (i, activated) -> {
            selectedId = app.playlists.all().get(i).id();
            selection.clear();
            refreshEntries();
        }));
        entries = addRenderableWidget(new RowList(118, 60, width - 130, Math.max(28, height - 150), 32, Text.get("text.darkmusic.playlist_songs_space_selects"), (i, activated) -> {
        }));
        entries.multiple(selection);
        entries.reorder(move -> {
            app.playlists.moveBlock(selectedId, move.start(), move.count(), move.insertion());
            refreshEntries();
        });
        int w = (width - 32) / 3;
        int half = (width - 28) / 2;
        add = button(Text.get("text.darkmusic.add_songs"), 12, height - 76, half, () -> {
            if (selected() != null) {
                String id = selectedId;
                show(new TrackPickerScreen(app, this, id, tracks -> app.playlists.addAll(id, tracks)));
            }
        }, Text.get("text.darkmusic.select_library_songs_not_already_added_to_this_playlist"));
        remove = button(Text.get("text.darkmusic.remove_selected"), 16 + half, height - 76, half, () -> {
            app.playlists.removeAll(selectedId, selection.selected());
            selection.clear();
            refreshEntries();
        }, Text.get("text.darkmusic.remove_all_checked_entries_from_this_playlist"));
        button(Text.get("text.darkmusic.select_all"), 12, height - 52, w, selection::all, Text.get("text.darkmusic.select_every_entry_including_missing_songs_ctrl_cmd_a_works_with_list_focus"));
        button(Text.get("text.darkmusic.deselect_all"), 16 + w, height - 52, w, selection::clear, Text.get("text.darkmusic.clear_every_selected_entry"));
        button(Text.get("text.darkmusic.invert_selection"), 20 + 2 * w, height - 52, w, selection::invert, Text.get("text.darkmusic.toggle_selection_of_every_entry"));
        up = button(Text.get("text.darkmusic.move_up"), 12, height - 28, (width - 28) / 2, () -> move(-1), Text.get("text.darkmusic.move_selected_songs_up_while_preserving_their_relative_order"));
        down = button(Text.get("text.darkmusic.move_down"), 16 + (width - 28) / 2, height - 28, (width - 28) / 2, () -> move(1), Text.get("text.darkmusic.move_selected_songs_down_while_preserving_their_relative_order"));
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
            return t == null ? new RowList.Row(Text.get("format.darkmusic.missing_song", e.path()), Text.get("text.darkmusic.remove_this_entry_then_add_the_current_file_with_add_songs")) : new RowList.Row(t.title(), t.artist() + " | " + e.path());
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
        remove.active = up.active = down.active = !selection.isEmpty();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 256 && entries != null && entries.cancelDrag()) return true;
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, Text.get("text.darkmusic.manage_playlists"), 12, 12, 0xFFB5B0FF);
        longText.draw(g, font, "selection", Text.get("format.darkmusic.selected", selection.size()), 264, 38, width - 276, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, d);
    }
}
