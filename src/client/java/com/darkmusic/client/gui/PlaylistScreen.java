package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

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
    private Button remove, add, rename, delete, selectAll, deselect, invert;
    private long revision = -1, libraryRevision = -1;
    private boolean browsing = true;
    private List<Track> entryTracks = List.of();
    private List<String> playlistIds = List.of();
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
    public void onFilesDrop(List<Path> files) {
        app.media.importAudio(files, "", selected() == null ? "" : selectedId);
    }

    @Override
    protected void init() {
        backButton();
        boolean compact = width < 480;
        if (lists != null) listScroll = lists.scrollOffset();
        if (entries != null) {
            entryScroll = entries.scrollOffset();
            entries.cancelDrag();
        }
        if (compact && !wasCompact && selected() != null) browsing = false;
        wasCompact = compact;
        if (compact && !browsing)
            UiButton.icon(button(Text.get("view.darkmusic.playlists"), width - 188, 6, 104, () -> {
                browsing = true;
                rebuildWidgets();
            }, Text.get("phase2.back")), UiIcon.BACK, false);
        int sidebar = compact ? width - 24 : Math.clamp(width / 5, 128, 184);
        int entryX = compact ? 12 : 20 + sidebar;
        int entryWidth = compact ? width - 24 : width - 32 - sidebar;
        var create = UiButton.icon(button(Text.get("text.darkmusic.new"), 12, 38, sidebar - 56, () -> show(new TextPromptScreen(this, Text.get("text.darkmusic.create_playlist"), "", text -> {
            selectedId = app.playlists.create(text);
            selection.clear();
        })), Text.get("text.darkmusic.create_a_local_playlist")), UiIcon.ADD, false);
        rename = UiButton.icon(button(Text.get("text.darkmusic.rename"), 16 + sidebar - 56, 38, 24, () -> {
            var p = selected();
            if (p != null)
                show(new TextPromptScreen(this, Text.get("text.darkmusic.rename_playlist"), p.name(), text -> app.playlists.rename(p.id(), text)));
        }, Text.get("text.darkmusic.rename_the_selected_playlist")), UiIcon.EDIT, true);
        delete = UiButton.icon(button(Text.get("text.darkmusic.delete"), 12 + sidebar - 24, 38, 24, () -> {
            var playlist = selected();
            if (playlist == null) return;
            show(new ConfirmationScreen(this, Text.component("confirm.darkmusic.delete_title"),
                Text.component("confirm.darkmusic.delete_body", playlist.name()), Text.component("text.darkmusic.delete"), () -> {
                app.playlists.delete(playlist.id());
                selectedId = "";
                selection.clear();
                return CompletableFuture.completedFuture(null);
            }));
        }, Text.get("confirm.darkmusic.delete_help")), UiIcon.DELETE, true);
        create.visible = rename.visible = delete.visible = !compact || browsing;
        lists = addRenderableWidget(new RowList(12, 86, sidebar, height - 98, 28, Text.get("view.darkmusic.playlists"), (i, activated) -> {
            if (i < 0 || i >= playlistIds.size()) return;
            String id = playlistIds.get(i);
            if (app.playlists.get(id) == null) {
                refresh();
                return;
            }
            selectedId = id;
            selection.clear();
            refreshEntries();
            if (compact) {
                browsing = false;
                rebuildWidgets();
            }
        }));
        entries = addRenderableWidget(new RowList(entryX, 86, entryWidth, height - 128, 36, Text.get("text.darkmusic.playlist_songs_space_selects"), (i, activated) -> {
        }));
        entries.multiple(selection);
        entries.artwork(app.artwork, i -> i < entryTracks.size() ? entryTracks.get(i) : null);
        entries.lyrics(app.lyrics, () -> app.settings.lyricsEnabled);
        lists.icons(i -> i >= 0 && i < playlistIds.size() && PlaylistStore.FAVORITES.equals(playlistIds.get(i)) ? UiIcon.HEART_FILLED : UiIcon.PLAYLIST);
        lists.visible = !compact || browsing;
        entries.visible = !compact || !browsing;
        entries.reorder(move -> {
            app.playlists.moveBlock(selectedId, move.start(), move.count(), move.insertion());
            refreshEntries();
        });
        int footerStart = children().size();
        int actionWidth = Math.min(164, (entryWidth - 4) / 2);
        add = button(Text.get("text.darkmusic.add_songs"), entryX, 38, actionWidth, () -> {
            if (selected() != null) {
                String id = selectedId;
                show(new TrackPickerScreen(app, this, id, tracks -> app.playlists.addAll(id, tracks)));
            }
        }, Text.get("text.darkmusic.select_library_songs_not_already_added_to_this_playlist"));
        remove = button(Text.get("text.darkmusic.remove_selected"), entryX + entryWidth - actionWidth, 38, actionWidth, () -> {
            String id = selectedId;
            var keys = Set.copyOf(selection.selected());
            Runnable removeEntries = () -> {
                app.playlists.removeAll(id, keys);
                selection.clear();
                refreshEntries();
            };
            if (keys.size() > 1) show(new ConfirmationScreen(this, Text.component("text.darkmusic.remove_selected"),
                Text.component("playlist.confirm_remove_entries", keys.size()), Text.component("text.darkmusic.remove_selected"), () -> {
                removeEntries.run();
                return CompletableFuture.completedFuture(null);
            }));
            else removeEntries.run();
        }, Text.get("text.darkmusic.remove_all_checked_entries_from_this_playlist"));
        int w = (entryWidth - 8) / 3;
        selectAll = button(Text.get("text.darkmusic.select_all"), entryX, height - 32, w, selection::all, Text.get("text.darkmusic.select_every_entry_including_missing_songs_ctrl_cmd_a_works_with_list_focus"));
        deselect = button(Text.get("text.darkmusic.deselect_all"), entryX + w + 4, height - 32, w, selection::clear, Text.get("text.darkmusic.clear_every_selected_entry"));
        invert = button(Text.get("text.darkmusic.invert_selection"), entryX + (w + 4) * 2, height - 32, entryWidth - (w + 4) * 2, selection::invert, Text.get("text.darkmusic.toggle_selection_of_every_entry"));
        UiButton.style(add, UiButton.Style.PRIMARY);
        UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
        UiButton.style(remove, UiButton.Style.DESTRUCTIVE);
        lists.emptyMessage(Text.get("ui.darkmusic.no_playlists"));
        if (compact && browsing)
            for (int i = footerStart; i < children().size(); i++)
                if (children().get(i) instanceof AbstractWidget widget)
                    widget.visible = false;
        refresh();
        lists.scrollOffset(listScroll);
        entries.scrollOffset(entryScroll);
    }

    private void refresh() {
        if (lists == null) return;
        var all = app.playlists.all();
        playlistIds = all.stream().map(PlaylistStore.Playlist::id).toList();
        if (selected() == null) {
            selectedId = all.isEmpty() ? "" : all.getFirst().id();
            selection.clear();
        }
        var library = app.library.snapshot().tracks();
        lists.rows(all.stream().map(p -> new RowList.Row(app.playlists.displayName(p) + " (" + app.playlists.resolve(p.id(), library).size() + ")", "")).toList());
        lists.select(IntStream.range(0, all.size()).filter(i -> all.get(i).id().equals(selectedId)).findFirst().orElse(-1));
        refreshEntries();
    }

    private void refreshEntries() {
        var p = selected();
        if (entries == null) return;
        entries.emptyMessage(Text.get(p == null ? "ui.darkmusic.choose_playlist" : "ui.darkmusic.empty_playlist"));
        var keys = p == null ? List.<String>of() : p.entries().stream().map(PlaylistStore.Entry::key).toList();
        selection.retain(keys);
        selection.visible(keys);
        var index = app.playlists.index(app.library.snapshot().tracks());
        entryTracks = p == null ? List.of() : p.entries().stream().map(e -> index.get(e.key())).toList();
        entries.rows(p == null ? List.of() : p.entries().stream().map(e -> {
            Track t = index.get(e.key());
            return t == null ? new RowList.Row(Text.get("format.darkmusic.missing_song", e.path()), Text.get("text.darkmusic.remove_this_entry_then_add_the_current_file_with_add_songs")) : new RowList.Row(t.title(), t.artist() + " | " + MusicScreen.time(t.duration()), e.path());
        }).toList());
    }

    @Override
    public void tick() {
        entries.inspectVisibleLyrics();
        if (revision != app.playlists.revision() || libraryRevision != app.library.snapshot().revision()) {
            revision = app.playlists.revision();
            libraryRevision = app.library.snapshot().revision();
            refresh();
        }
        entries.tickDrag();
        add.active = selected() != null && !app.mediaBusy();
        rename.active = delete.active = selected() != null && !PlaylistStore.FAVORITES.equals(selectedId) && !app.mediaBusy();
        remove.active = deselect.active = !selection.isEmpty() && !app.mediaBusy();
        selectAll.active = invert.active = selected() != null && !entryTracks.isEmpty() && !app.mediaBusy();
        entries.active = lists.active = !app.mediaBusy();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
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
        if (lists.visible) g.text(font, Text.get("view.darkmusic.playlists"), 14, 72, UiTheme.ACCENT);
        if (entries.visible)
            longText.draw(g, font, "selection", (selected() == null ? "" : app.playlists.displayName(selected()) + " | ") + Text.get("format.darkmusic.selected", selection.size()), entries.getX() + 2, 72, entries.getWidth() - 4, UiStatus.color(UiStatus.Tone.INFO));
        super.extractRenderState(g, x, y, d);
        importOverlay.render(app, g, width, height);
    }
}
