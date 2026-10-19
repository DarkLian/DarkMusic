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
    private final ImportOverlay importOverlay = new ImportOverlay();

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor g) {
        if (!importOverlay.visible(app)) super.extractBlurredBackground(g);
    }

    private String selectedId = "";
    private RowList lists, entries;
    private Button remove, up, down, add, rename, delete;
    private long revision = -1, libraryRevision = -1;
    private boolean browsing = true;
    private List<Track> entryTracks = List.of();
    private boolean wasCompact;
    private int listScroll, entryScroll;

    public PlaylistScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("text.darkmusic.manage_playlists"), parent);
        this.app = app;
    }

    private Button button(String text, int x, int y, int w, Runnable action, String help) {
        return addRenderableWidget(Help.tip(UiButton.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build(), help));
    }

    private PlaylistStore.Playlist selected() {
        return app.playlists.get(selectedId);
    }

    @Override
    public void onFilesDrop(List<java.nio.file.Path> files) {
        importOverlay.dropped();
        app.importAudio(files, "", selected() == null ? "" : selectedId);
    }

    @Override
    protected void init() {
        boolean compact = width < 480;
        if (lists != null) listScroll = lists.scrollOffset();
        if (entries != null) {
            entryScroll = entries.scrollOffset();
            entries.cancelDrag();
        }
        if (compact && !wasCompact && selected() != null) browsing = false;
        wasCompact = compact;
        if (compact && !browsing)
            UiButton.icon(button(Text.get("view.darkmusic.playlists"), width - 116, 6, 104, () -> {
                browsing = true;
                rebuildWidgets();
            }, Text.get("phase2.back")), UiIcon.BACK, false);
        button(Text.get("text.darkmusic.new"), 12, 32, 60, () -> show(new TextPromptScreen(this, Text.get("text.darkmusic.create_playlist"), "", text -> {
            selectedId = app.playlists.create(text);
            selection.clear();
        })), Text.get("text.darkmusic.create_a_local_playlist"));
        rename = button(Text.get("text.darkmusic.rename"), 76, 32, 72, () -> {
            var p = selected();
            if (p != null)
                show(new TextPromptScreen(this, Text.get("text.darkmusic.rename_playlist"), p.name(), text -> app.playlists.rename(p.id(), text)));
        }, Text.get("text.darkmusic.rename_the_selected_playlist"));
        delete = button(Text.get("text.darkmusic.delete"), 152, 32, 100, () -> {
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
        int sidebar = compact ? width - 24 : Math.clamp(width / 5, 128, 184);
        lists = addRenderableWidget(new RowList(12, 78, sidebar, height - (compact && browsing ? 92 : 168), 28, Text.get("view.darkmusic.playlists"), (i, activated) -> {
            selectedId = app.playlists.all().get(i).id();
            selection.clear();
            refreshEntries();
            if (compact) {
                browsing = false;
                rebuildWidgets();
            }
        }));
        entries = addRenderableWidget(new RowList(compact ? 12 : 20 + sidebar, 78, compact ? width - 24 : width - 32 - sidebar, height - 168, 36, Text.get("text.darkmusic.playlist_songs_space_selects"), (i, activated) -> {
        }));
        entries.multiple(selection);
        entries.artwork(app.artwork, i -> i < entryTracks.size() ? entryTracks.get(i) : null);
        entries.lyrics(app.lyrics, () -> app.settings.lyricsEnabled);
        lists.icons(i -> PlaylistStore.FAVORITES.equals(app.playlists.all().get(i).id()) ? UiIcon.HEART_FILLED : UiIcon.PLAYLIST);
        lists.visible = !compact || browsing;
        entries.visible = !compact || !browsing;
        entries.reorder(move -> {
            app.playlists.moveBlock(selectedId, move.start(), move.count(), move.insertion());
            refreshEntries();
        });
        int footerStart = children().size();
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
        UiButton.style(add, UiButton.Style.PRIMARY);
        UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
        lists.emptyMessage(Text.get("ui.darkmusic.no_playlists"));
        if (compact && browsing)
            for (int i = footerStart; i < children().size(); i++)
                if (children().get(i) instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                    widget.visible = false;
        refresh();
        lists.scrollOffset(listScroll);
        entries.scrollOffset(entryScroll);
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
        var library = app.library.snapshot().tracks();
        lists.rows(all.stream().map(p -> new RowList.Row(app.playlists.displayName(p) + " (" + app.playlists.resolve(p.id(), library).size() + ")", "")).toList());
        lists.select(java.util.stream.IntStream.range(0, all.size()).filter(i -> all.get(i).id().equals(selectedId)).findFirst().orElse(-1));
        refreshEntries();
    }

    private void refreshEntries() {
        var p = selected();
        if (entries == null) return;
        entries.emptyMessage(Text.get(p == null ? "ui.darkmusic.choose_playlist" : "ui.darkmusic.empty_playlist"));
        var keys = p == null ? List.<String>of() : p.entries().stream().map(PlaylistStore.Entry::key).toList();
        selection.retain(keys);
        selection.visible(keys);
        Map<String, Track> index = new HashMap<>();
        app.library.snapshot().tracks().forEach(t -> index.put(t.id(), t));
        entryTracks = p == null ? List.of() : p.entries().stream().map(e -> index.get(e.key())).toList();
        entries.rows(p == null ? List.of() : p.entries().stream().map(e -> {
            Track t = index.get(e.key());
            return t == null ? new RowList.Row(Text.get("format.darkmusic.missing_song", e.path()), Text.get("text.darkmusic.remove_this_entry_then_add_the_current_file_with_add_songs")) : new RowList.Row(t.title(), t.artist() + " | " + MusicScreen.time(t.duration()), e.path());
        }).toList());
    }

    @Override
    public void tick() {
        importOverlay.tick();
        entries.inspectVisibleLyrics();
        if (revision != app.playlists.revision() || libraryRevision != app.library.snapshot().revision()) {
            revision = app.playlists.revision();
            libraryRevision = app.library.snapshot().revision();
            refresh();
        }
        entries.tickDrag();
        add.active = selected() != null && !app.mediaBusy;
        rename.active = delete.active = selected() != null && !PlaylistStore.FAVORITES.equals(selectedId) && !app.mediaBusy;
        remove.active = up.active = down.active = !selection.isEmpty();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 256 && entries != null && entries.cancelDrag()) return true;
        if (event.key() == 256 && width < 480 && !browsing) {
            browsing = true;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.PLAYLIST);
        if (lists.visible) g.text(font, Text.get("view.darkmusic.playlists"), 14, 64, UiTheme.ACCENT);
        if (entries.visible)
            longText.draw(g, font, "selection", (selected() == null ? "" : app.playlists.displayName(selected()) + " | ") + Text.get("format.darkmusic.selected", selection.size()), entries.getX() + 2, 64, entries.getWidth() - 4, UiTheme.SECONDARY);
        super.extractRenderState(g, x, y, d);
        importOverlay.render(app, g, width, height, selected() == null ? Text.get("text.darkmusic.all_tracks") : app.playlists.displayName(selected()));
    }
}
