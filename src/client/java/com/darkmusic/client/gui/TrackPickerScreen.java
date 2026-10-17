package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

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
        super(Text.component("text.darkmusic.add_songs"), parent);
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
        search = addRenderableWidget(new EditBox(font, 12, 32, width - 24, 20, Text.component("text.darkmusic.search_songs")));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            refresh();
        });
        rows = addRenderableWidget(new RowList(12, 60, width - 24, Math.max(28, height - 140), 32, Text.get("text.darkmusic.songs"), (i, activated) -> {
        }));
        {
            rows.multiple(selection);
            int w = (width - 32) / 3;
            button(Text.get("text.darkmusic.select_all"), 12, height - 52, w, selection::all, Text.get("text.darkmusic.select_every_visible_song_ctrl_cmd_a_works_when_the_list_is_focused"));
            button(Text.get("text.darkmusic.deselect_all"), 16 + w, height - 52, w, selection::clear, Text.get("text.darkmusic.clear_selections_including_songs_hidden_by_search"));
            button(Text.get("text.darkmusic.invert_selection"), 20 + w * 2, height - 52, w, selection::invert, Text.get("text.darkmusic.toggle_selection_of_visible_songs_hidden_selections_are_kept"));
        }
        choose = button(Text.get("text.darkmusic.add_selected"), 12, height - 28, (width - 28) / 2, this::choose, Text.get("text.darkmusic.add_selected_songs_in_library_order_songs_already_added_are_hidden"));
        button(Text.get("text.darkmusic.cancel"), 16 + (width - 28) / 2, height - 28, (width - 28) / 2, this::onClose, Text.get("text.darkmusic.return_without_changing_the_playlist_escape_also_cancels"));
        refresh();
    }

    private void refresh() {
        if (rows == null) return;
        var library = eligible();
        // Keep pending selections during partial scans; prune removed files only after a completed scan.
        if (!app.library.snapshot().scanning()) selection.retain(library.stream().map(Track::id).toList());
        tracks = library.stream().filter(t -> t.matches(query.toLowerCase(Locale.ROOT).strip())).toList();
        rows.emptyMessage(library.isEmpty() && !app.library.snapshot().tracks().isEmpty() ? Text.get("text.darkmusic.all_library_songs_are_already_added") : Text.get("text.darkmusic.no_matching_songs"));
        selection.visible(tracks.stream().map(Track::id).toList());
        rows.rows(tracks.stream().map(t -> new RowList.Row(t.title(), t.artist() + " | " + t.relativePath())).toList());
        if (choose != null)
            choose.active = app.playlists.get(playlistId) != null && !app.library.snapshot().scanning() && !selection.isEmpty();
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
        choose.active = app.playlists.get(playlistId) != null && !app.library.snapshot().scanning() && !selection.isEmpty();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, title, 12, 12, 0xFFB5B0FF);
        g.text(font, Text.get("format.darkmusic.selected_hidden", selection.size(), selection.hidden()), 12, height - 72, 0xFFCCCCDD);
        super.extractRenderState(g, x, y, d);
    }
}
