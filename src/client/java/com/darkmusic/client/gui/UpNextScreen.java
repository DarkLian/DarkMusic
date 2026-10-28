package com.darkmusic.client.gui;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
import java.util.Locale;

/**
 * Three bounded, lazy lists backed by the controller's immutable session snapshot.
 */
public final class UpNextScreen extends LocalScreen {
    /**
     * The queue, the songs it shows, and the playlists songs can be added to.
     */
    public interface Sources extends TrackThumbnails {
        /**
         * Null until startup creates the player.
         */
        PlaybackController playback();

        boolean canEditQueue();

        /**
         * Null until startup creates the library.
         */
        MusicLibrary library();

        ArtworkCache artwork();

        PlaylistStore playlists();
    }

    private final Sources app;
    private PlaybackController.UpNextSnapshot snapshot;
    private RowList now, manual, automatic;
    private Button remove, clear;
    private final SongSelection selection = new SongSelection();
    private Track collectionDrag;
    private double dragX, dragY;
    private boolean draggingCollection;
    private long selectedId = -1;
    // The right-hand list: the collection's upcoming tracks or the whole library, filtered by the search box.
    private boolean allSongs;
    private String query = "";
    private List<Track> source = List.of();
    private Object sourceBasis;
    private int manualTitleY, automaticTitleY, automaticX, listWidth;

    public UpNextScreen(Sources app, Screen parent) {
        super(Text.component("upnext.title"), parent);
        this.app = app;
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(UiButton.builder(Text.component(key), b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        collectionDrag = null;
        draggingCollection = false;
        backButton();
        boolean wide = width >= 640;
        listWidth = wide ? (width - 32) / 2 : width - 24;
        manualTitleY = 102;
        int listHeight = wide ? Math.max(28, height - 166) : Math.max(24, (height - 206) / 2);
        automaticX = wide ? 20 + listWidth : 12;
        automaticTitleY = wide ? manualTitleY : manualTitleY + 18 + listHeight + 4;
        now = addRenderableWidget(new RowList(12, 48, width - 24, 32, 32, Text.get("upnext.now"), (i, a) -> {
        }));
        manual = addRenderableWidget(new RowList(12, manualTitleY + 18, listWidth, listHeight, 28, Text.get("upnext.manual"), (i, a) -> {
            if (snapshot != null && i < snapshot.manual().size()) selectedId = snapshot.manual().get(i).id();
            controls();
        }));
        manual.multiple(selection);
        manual.reorder(move -> {
            selectedId = snapshot.manual().get(move.start()).id();
            if (app.canEditQueue())
                app.playback().reorderQueued(snapshot.manual().stream().map(PlaybackController.Queued::id).toList(), move.order(snapshot.manual().size()));
            refresh();
        });
        var search = addRenderableWidget(new UiInput(font, automaticX, automaticTitleY + 18, listWidth - 112, 20, Text.component("text.darkmusic.search_songs")));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            refresh();
        });
        var view = button("format.darkmusic.view", automaticX + listWidth - 108, automaticTitleY + 18, 108, () -> {
            allSongs = !allSongs;
            rebuildWidgets();
        });
        view.setMessage(Text.component("format.darkmusic.view", Text.get(allSongs ? "upnext.view.all" : "upnext.view.collection")));
        UiButton.selected(view, () -> allSongs);
        Help.tip(view, Text.get("upnext.view.help"));
        automatic = addRenderableWidget(new RowList(automaticX, automaticTitleY + 42, listWidth,
            wide ? listHeight - 24 : Math.max(18, height - 32 - automaticTitleY - 42), 28, Text.get("upnext.automatic"), (i, activated) -> {
            // Double-click or Enter adds the song after the other queued songs.
            if (activated && i < source.size() && app.canEditQueue()) {
                var resolved = app.playlists().index(app.library().snapshot().tracks()).get(source.get(i).id());
                if (app.playback().insertQueued(resolved, snapshot.manual().size())) {
                    refresh();
                    manual.reveal(snapshot.manual().size() - 1);
                }
            }
        }));
        now.artwork(app.artwork(), i -> snapshot.current());
        manual.artwork(app.artwork(), i -> snapshot.manual().get(i).track());
        automatic.artwork(app.artwork(), i -> i < source.size() ? source.get(i) : null);
        now.rowTooltips(false);
        manual.rowTooltips(false);
        automatic.rowTooltips(false);
        now.emptyMessage(Text.get("upnext.idle"));
        manual.emptyMessage(Text.get("upnext.empty"));
        automatic.emptyMessage(Text.get("upnext.no_source"));
        addRenderableWidget(new InfoHint(12, manualTitleY - 4, listWidth, Text.component("upnext.reorder.help")));
        addRenderableWidget(new InfoHint(automaticX, automaticTitleY - 4, listWidth, Text.component("upnext.collection.help")));
        int w = (width - 28) / 2, y = height - 28;
        remove = button("upnext.remove", 12, y, w, () -> {
            // Checked rows win over the single highlighted row, matching the playlist manager.
            if (app.canEditQueue()) {
                if (selection.isEmpty()) app.playback().removeQueued(selectedId);
                else for (var key : selection.selected()) app.playback().removeQueued(Long.parseLong(key));
                selection.clear();
            }
            refresh();
        });
        clear = button("upnext.clear", 16 + w, y, w, () -> {
            if (app.canEditQueue()) app.playback().clearQueued();
            refresh();
        });
        Help.tip(remove, Text.get("upnext.remove.help"));
        UiButton.style(clear, UiButton.Style.DESTRUCTIVE);
        Help.tip(clear, Text.get("upnext.clear.help"));
        snapshot = null;
        sourceBasis = null;
        refresh();
    }

    private int selectedIndex() {
        if (snapshot != null) for (int i = 0; i < snapshot.manual().size(); i++)
            if (snapshot.manual().get(i).id() == selectedId) return i;
        return -1;
    }

    private static RowList.Row row(Track track) {
        return new RowList.Row(track.title(), track.artist());
    }

    private void refresh() {
        var next = app.playback().upNextSnapshot();
        if (snapshot == null || next.revision() != snapshot.revision()) {
            int scroll = manual.scrollOffset();
            snapshot = next;
            now.rows(next.current() == null ? 0 : 1, i -> row(snapshot.current()));
            var keys = next.manual().stream().map(q -> Long.toString(q.id())).toList();
            selection.visible(keys);
            selection.retain(keys);
            // The queue is bounded to 1,000; concrete rows preserve RowList's drop animation.
            manual.rows(next.manual().stream().map(q -> row(q.track())).toList());
            manual.select(selectedIndex());
            manual.scrollOffset(scroll);
        }
        // Rebuild the right-hand list only when its view, query or underlying tracks change.
        var tracks = allSongs ? app.library().snapshot().tracks() : snapshot.automatic();
        var basis = List.of(allSongs, query, tracks);
        if (!basis.equals(sourceBasis)) {
            sourceBasis = basis;
            String needle = query.toLowerCase(Locale.ROOT).strip();
            source = needle.isEmpty() ? tracks : tracks.stream().filter(track -> track.matches(needle)).toList();
            automatic.rows(source.size(), i -> row(source.get(i)));
            automatic.emptyMessage(Text.get(!needle.isEmpty() ? "text.darkmusic.no_matching_songs"
                : allSongs ? "upnext.no_songs"
                : snapshot.shuffle() && snapshot.repeat() == Repeat.ALL && snapshot.sourceSize() > 0 ? "upnext.shuffle_pending" : "upnext.no_source"));
        }
        controls();
    }

    private void controls() {
        int index = selectedIndex();
        boolean editable = app.canEditQueue();
        manual.active = editable;
        remove.active = editable && (index >= 0 || !selection.isEmpty());
        clear.active = editable && !snapshot.manual().isEmpty();
    }

    @Override
    public void tick() {
        refresh();
        manual.tickDrag();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        collectionDrag = null;
        draggingCollection = false;
        if (event.button() == 0 && app.canEditQueue() && automatic.isMouseOver(event.x(), event.y()) && event.x() < automatic.getRight() - 10) {
            int index = (int) (event.y() - automatic.getY() + automatic.scrollOffset()) / 28;
            if (index >= 0 && index < source.size()) {
                collectionDrag = source.get(index);
                dragX = event.x();
                dragY = event.y();
            }
        }
        return super.mouseClicked(event, twice);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (collectionDrag != null) {
            if (Math.hypot(event.x() - dragX, event.y() - dragY) >= 4) draggingCollection = true;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (collectionDrag != null) {
            if (draggingCollection && manual.isMouseOver(event.x(), event.y()) && app.canEditQueue()) {
                int insertion = Math.clamp((int) (event.y() - manual.getY() + manual.scrollOffset() + 14) / 28, 0, snapshot.manual().size());
                var resolved = app.playlists().index(app.library().snapshot().tracks()).get(collectionDrag.id());
                if (app.playback().insertQueued(resolved, insertion)) {
                    var entries = app.playback().upNextSnapshot().manual();
                    selectedId = entries.get(Math.min(insertion, entries.size() - 1)).id();
                    refresh();
                    manual.reveal(selectedIndex());
                }
            }
            collectionDrag = null;
            draggingCollection = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        UiTheme.header(g, font, getTitle(), UiIcon.PLAYLIST);
        g.text(font, Text.get("upnext.now"), 14, 34, UiTheme.ACCENT);
        longText.draw(g, font, "queue_scope", Text.get(snapshot.repeat() == Repeat.ONE ? "upnext.hold" : "upnext.temporary"), 14, 86, width - 28, UiTheme.MUTED);
        longText.draw(g, font, "manual_heading", Text.get("upnext.count", snapshot.manual().size()), 14, manualTitleY, listWidth - 4, UiTheme.ACCENT);
        longText.draw(g, font, "source_heading", allSongs ? Text.get("upnext.all_songs", source.size()) : Text.get("upnext.automatic"), automaticX + 2, automaticTitleY, listWidth - 4, UiTheme.SECONDARY);
        super.extractRenderState(g, x, y, delta);
        if (draggingCollection && collectionDrag != null) {
            g.outline(manual.getX(), manual.getY(), manual.getWidth(), manual.getHeight(), UiTheme.ACCENT);
            if (manual.isMouseOver(x, y)) {
                int insertion = Math.clamp((y - manual.getY() + manual.scrollOffset() + 14) / 28, 0, snapshot.manual().size());
                int lineY = Math.clamp(manual.getY() + insertion * 28 - manual.scrollOffset(), manual.getY(), manual.getBottom() - 2);
                g.fill(manual.getX() + 2, lineY, manual.getRight() - 2, lineY + 2, UiTheme.ACCENT);
            }
            int ghostWidth = Math.min(width - 8, Math.clamp(listWidth, 240, 480));
            int ghostX = Math.clamp(x + 12, 4, width - ghostWidth - 4);
            int ghostY = y + 12 + SongCard.HEIGHT <= height - 4 ? y + 12 : Math.max(4, y - SongCard.HEIGHT - 12);
            SongCard.draw(app, g, font, longText, collectionDrag, "collection_drag", ghostX, ghostY, ghostWidth);
        }
    }
}
