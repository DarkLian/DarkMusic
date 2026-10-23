package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

/**
 * File selection is deliberately separate from playlist-entry removal.
 */
public final class LibraryManagerScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final SongSelection selection = new SongSelection();
    private List<Track> tracks = List.of();
    private RowList rows;
    private EditBox search;
    private Button delete;
    private String query = "";
    private long revision = -1;

    public LibraryManagerScreen(DarkMusicClient app, Screen parent) {
        super(Text.component("delete_audio.manage"), parent);
        this.app = app;
    }

    @Override
    protected void init() {
        backButton();
        var cleanup = addRenderableWidget(UiButton.builder(Text.component("cleanup.title"), b -> show(new SmartCleanupScreen(app, this))).bounds(width - 216, 4, 132, 20).build());
        UiButton.icon(cleanup, UiIcon.SEARCH, false);
        search = addRenderableWidget(new UiInput(font, 12, 34, width - 24, 20, Text.component("text.darkmusic.search_songs")));
        search.setMaxLength(512);
        search.setHint(Text.component("text.darkmusic.search_title_artist_album_or_path"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            refresh();
        });
        rows = addRenderableWidget(new RowList(12, 60, width - 24, Math.max(28, height - 132), 36,
            Text.get("delete_audio.select"), (i, activated) -> {
        }));
        rows.multiple(selection);
        rows.artwork(app.artwork, i -> i < tracks.size() ? tracks.get(i) : null);
        int w = (width - 32) / 3;
        button("text.darkmusic.select_all", 12, height - 66, w, selection::all);
        button("text.darkmusic.deselect_all", 16 + w, height - 66, w, selection::clear);
        button("text.darkmusic.invert_selection", 20 + w * 2, height - 66, w, selection::invert);
        delete = button("delete_audio.review", 12, height - 28, width - 24, () -> {
            var ids = selection.selected();
            var selected = app.library.snapshot().tracks().stream().filter(t -> ids.contains(t.id())).toList();
            if (!selected.isEmpty()) show(new AudioDeletionScreen(app, this, selected, this));
        });
        UiButton.style(delete, UiButton.Style.DESTRUCTIVE);
        refresh();
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(UiButton.builder(Text.component(key), b -> action.run()).bounds(x, y, w, 20).build());
    }

    private void refresh() {
        var snapshot = app.library.snapshot();
        if (!snapshot.scanning()) selection.retain(snapshot.tracks().stream().map(Track::id).toList());
        tracks = snapshot.tracks().stream().filter(t -> t.matches(query.strip().toLowerCase(Locale.ROOT))).toList();
        selection.visible(tracks.stream().map(Track::id).toList());
        var shown = tracks;
        rows.rows(shown.size(), i -> new RowList.Row(shown.get(i).title(), shown.get(i).file().toString()));
        revision = snapshot.revision();
    }

    @Override
    public void tick() {
        if (revision != app.library.snapshot().revision()) refresh();
        delete.active = !app.mediaBusy && !app.library.snapshot().scanning() && !selection.isEmpty();
        rows.active = !app.mediaBusy;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, title, UiIcon.TOOLS);
        longText.draw(g, font, "selection", selection.hidden() == 0 ? Text.get("format.darkmusic.selected", selection.size()) : Text.get("playlist.darkmusic.filtered_selection", selection.size(), selection.hidden()),
            12, height - 40, width - 24, UiTheme.SECONDARY);
        super.extractRenderState(g, x, y, delta);
    }
}
