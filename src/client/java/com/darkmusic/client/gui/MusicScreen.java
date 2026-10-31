package com.darkmusic.client.gui;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.library.ArtworkCache;
import com.darkmusic.client.library.MusicFolders;
import com.darkmusic.client.library.SourceLinks;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.render.LyricsStatusIcon;
import com.darkmusic.client.render.SpectrumRenderer;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MusicScreen extends LocalScreen {
    /**
     * The player: playback controls and the library browser. The screens it opens come from navigation.
     */
    public interface Sources extends ImportOverlay.Sources, PlayerProgress.Sources, PlayerBrowser.Sources {
        Navigation navigation();

        /**
         * Null until startup creates the player, library and playlists. Read only, for drawing the spectrum: changes go
         * through transport().
         */
        PlaybackController playback();

        TransportOperations transport();

        PlayerQueries player();

        PlaylistOperations playlistOperations();

        LyricsService lyrics();

        ArtworkCache artwork();

        boolean mediaBusy();

        boolean canEditQueue();

        void importAudio(List<Path> paths, String folder, String playlistId);

        void openFolder(Path directory);

        void warn(String text);

        /**
         * The warning on screen, or "" when there is none.
         */
        String warning();

        ShortcutKeys shortcuts();

        SourceLinks sourceLinks();

        boolean resumes(Track track);

        boolean resumeLast(boolean paused);

        void seekRelative(double seconds);

        void speed(double value);

        void volume(double value);

        void shuffle(boolean on);

        void repeat(Repeat mode);

        void toggleMute();

        void openMusicFolder();
    }

    private final Sources app;
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private final ImportOverlay importOverlay = new ImportOverlay();
    private final PlayerBrowser browser;
    private final TransportBar transport;

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor g) {
        if (!importOverlay.visible(app)) super.extractBlurredBackground(g);
    }

    private EditBox search;
    private RowList navigation, songs;

    private void mode(String next) {
        browser.mode(next);
        rebuildWidgets();
    }

    private String historyPlaying = "";
    private long revision = -1, playlistRevision = -1, historyRevision = -1, refreshed;
    private Button openFolder, queueActions;
    private boolean spaceDown;
    private boolean keyboardNavigation;
    private FavoriteButton favorite;
    private Button edit, sourceLink, copySource, lyricsButton;
    private long copiedUntil;
    private String copiedTrack = "";
    private String playingId = "";
    private String openFolderTarget = "";
    private String followedId = "";
    private String lyricsSelectionId = "";
    private long lyricsSelectedAt;
    private PlaybackController.Source followedSource;
    private UiIcon playingIcon = UiIcon.PLAY;

    public MusicScreen(Sources app, Screen parent) {
        super(Component.literal("DarkMusic"), parent);
        this.app = app;
        browser = new PlayerBrowser(app);
        transport = new TransportBar(app);
    }

    /**
     * The library browser's state: the view mode, collection, matching songs and selection. Read only.
     */
    public PlayerBrowser browser() {
        return browser;
    }

    /**
     * Whether the library (rather than the collections drawer or the song details) fills the screen.
     */
    public boolean showingLibrary() {
        return pane == Pane.LIBRARY;
    }

    public static String time(double seconds) {
        long value = (long) Math.max(0, Double.isFinite(seconds) ? seconds : 0);
        return "%d:%02d".formatted(value / 60, value % 60);
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        var button = addRenderableWidget(UiButton.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
        if (y >= height - 32) UiButton.style(button, UiButton.Style.SUBTLE);
        return button;
    }

    private enum Pane {LIBRARY, COLLECTIONS, DETAILS}

    private Pane pane = Pane.LIBRARY;
    private PlayerLayout layout;
    private int songScroll, collectionScroll;
    private boolean revealOnEntry = true;

    @Override
    public void removed() {
        revealOnEntry = true;
        spaceDown = false;
        super.removed();
    }

    private Button icon(String key, UiIcon icon, int x, int y, int w, boolean only, Runnable action) {
        var button = UiButton.icon(button(Text.get(key), x, y, w, action), icon, only);
        if (only) UiButton.style(button, UiButton.Style.TRANSPORT);
        var binding = switch (icon) {
            case PLAY -> app.shortcuts().toggle();
            case PREVIOUS -> app.shortcuts().previous();
            case NEXT -> app.shortcuts().next();
            case SEEK_BACK -> app.shortcuts().seekBack();
            case SEEK_FORWARD -> app.shortcuts().seekForward();
            case STOP -> app.shortcuts().stop();
            case LOCATE -> app.shortcuts().jump();
            default -> null;
        };
        return binding == null ? button : UiButton.shortcuts(button, binding);
    }

    private void pane(Pane next) {
        pane = next;
        rebuildWidgets();
    }

    private Path browseDirectory() {
        return MusicFolders.browseDirectory(app.library().roots(), app.library().root(), browser.mode(), browser.group());
    }

    private Button transportIcon(String key, UiIcon icon, int x, int y, int width, Runnable action) {
        return UiButton.style(icon(key, icon, x, y, width, true, action), UiButton.Style.TRANSPORT);
    }

    @Override
    protected void init() {
        if (songs != null) songScroll = songs.scrollOffset();
        if (navigation != null) collectionScroll = navigation.scrollOffset();
        transport.reset();
        if (width < 320 || height < 240) {
            icon("phase2.back", UiIcon.BACK, 4, 4, Math.max(1, Math.min(80, width - 8)), false, this::onClose);
            return;
        }
        layout = PlayerLayout.of(width, height);
        openFolder = null;
        openFolderTarget = "";
        int contextY = height < 300 ? 64 : 70;
        header();
        boolean library = pane == Pane.LIBRARY;
        boolean drawer = pane == Pane.COLLECTIONS;
        paneTools(library, drawer, contextY);
        lists(library, drawer);
        detailButtons(library);
        transport.build(layout, new TransportBar.Controls() {
            @Override
            public Button icon(String key, UiIcon icon, int x, int y, int width, Runnable action) {
                return transportIcon(key, icon, x, y, width, action);
            }

            @Override
            public ValueSlider slider(ValueSlider slider) {
                return addRenderableWidget(slider);
            }
        }, this::playPause, this::jumpToPlaying);
        if (drawer) {
            icon("text.darkmusic.open_folder", UiIcon.FOLDER, 84, contextY, 24, true, app::openMusicFolder);
            icon("import.darkmusic.history", UiIcon.PLAYLIST, 112, contextY, 24, true, () -> show(app.navigation().importHistory(this)));
            icon("text.darkmusic.refresh_library", UiIcon.REFRESH, 140, contextY, 24, true, () -> app.library().rescan());
        }
        refresh();
        songs.scrollOffset(songScroll);
        navigation.scrollOffset(collectionScroll);
        if (revealOnEntry) {
            revealOnEntry = false;
            if (app.player().state().track() != null) jumpToPlaying();
            else revealResume();
        }
    }

    /**
     * The top row: Up Next, Downloads and Settings, the view modes, and the search field.
     */
    private void header() {
        boolean compactNavigation = width < 640;
        int navigationWidth = compactNavigation ? 24 : 112;
        int navigationX = width - 12 - 3 * navigationWidth - 8;
        Help.tip(icon("upnext.title", UiIcon.PLAYLIST, navigationX, 4, navigationWidth, compactNavigation,
            () -> show(app.navigation().upNext(this))), Text.get("upnext.title.help"));
        icon("text.darkmusic.downloads", UiIcon.DOWNLOAD, navigationX + navigationWidth + 4, 4, navigationWidth, compactNavigation,
            () -> show(app.navigation().downloads(this)));
        icon("text.darkmusic.settings", UiIcon.SETTINGS, navigationX + 2 * (navigationWidth + 4), 4, navigationWidth, compactNavigation,
            () -> show(app.navigation().settings(this)));
        String[] modes = {"All", "Playlists", "Artists", "Albums"};
        int searchX;
        if (width >= 800) {
            for (int i = 0; i < modes.length; i++) {
                String next = modes[i];
                UiButton.selected(button(Text.get("view.darkmusic." + next.toLowerCase(Locale.ROOT)), 12 + i * 68, 38, 64, () -> {
                    mode(next);
                }), () -> browser.mode().equals(next));
            }
            searchX = 292;
        } else {
            button(Text.get("format.darkmusic.view", Text.get("view.darkmusic." + browser.mode().toLowerCase(Locale.ROOT))), 12, 38, 108, () -> {
                mode(modes[(Arrays.asList(modes).indexOf(browser.mode()) + 1) % modes.length]);
            });
            searchX = 128;
        }
        search = addRenderableWidget(new UiInput(font, searchX, 38, width - searchX - 12, 20, Text.component("text.darkmusic.search_songs")));
        search.setMaxLength(512);
        search.setHint(Text.component("text.darkmusic.search_title_artist_album_or_path"));
        search.setValue(browser.query());
        search.setResponder(text -> {
            browser.query(text);
            showSongs();
        });
    }

    /**
     * The row below the header, which depends on the pane: back or collections, library tools, history and playlists.
     */
    private void paneTools(boolean library, boolean drawer, int contextY) {
        if (pane != Pane.LIBRARY) icon("phase2.back", UiIcon.BACK, 12, contextY, 68, false, () -> pane(Pane.LIBRARY));
        else if (layout.singleColumn())
            icon("ui.darkmusic.collections", UiIcon.FOLDER, 12, contextY, 110, false, () -> pane(Pane.COLLECTIONS));
        else {
            int utilityWidth = layout.navigationUtilityWidth();
            openFolder = icon("text.darkmusic.open_folder", UiIcon.FOLDER, 12, contextY, utilityWidth, true, () -> app.openFolder(browseDirectory()));
            icon("import.darkmusic.history", UiIcon.PLAYLIST, 16 + utilityWidth, contextY, utilityWidth, true, () -> show(app.navigation().importHistory(this)));
            icon("text.darkmusic.refresh_library", UiIcon.REFRESH, 20 + utilityWidth * 2, contextY, utilityWidth, true, () -> app.library().rescan());
            icon("delete_audio.manage", UiIcon.TOOLS, 24 + utilityWidth * 3, contextY, utilityWidth, true, () -> show(app.navigation().libraryManager(this)));
            Help.tip(icon("pip.title", UiIcon.PIP, 28 + utilityWidth * 4, contextY, utilityWidth, true, () -> show(app.navigation().pip(this))), Text.get("pip.settings.help"));
        }
        if (drawer || layout.singleColumn() && library) {
            int filesX = drawer ? 84 : 128;
            icon("delete_audio.manage", UiIcon.TOOLS, filesX, contextY, 24, true, () -> show(app.navigation().libraryManager(this)));
            Help.tip(icon("pip.title", UiIcon.PIP, filesX + 28, contextY, 24, true, () -> show(app.navigation().pip(this))), Text.get("pip.settings.help"));
        }
        if (library || drawer) {
            int utilityWidth = drawer || layout.singleColumn() ? 24 : layout.navigationUtilityWidth();
            int manageX = drawer ? width - 40 : layout.singleColumn() ? width - 64 : layout.navigation().right() - utilityWidth;
            UiButton.selected(icon("view.darkmusic.history", UiIcon.HISTORY, manageX - utilityWidth - 4, contextY, utilityWidth, true,
                () -> mode("History")), () -> browser.mode().equals("History"));
            var manage = icon("text.darkmusic.manage_playlists", UiIcon.MUSIC, manageX, contextY, utilityWidth, true, () -> {
                if (app.playlistsReady()) show(app.navigation().playlists(this));
                else app.warn(Text.get("text.darkmusic.playlists_are_still_loading"));
            });
            manage.active = app.playlistsReady();
        }
        if (layout.inspector().width() == 0 && library)
            icon("phase2.details", UiIcon.ALBUM, width - 36, contextY, 24, true, () -> pane(Pane.DETAILS));
    }

    /**
     * The collections list and the song list with their row decorations.
     */
    private void lists(boolean library, boolean drawer) {
        var list = layout.tracks();
        var nav = layout.navigation();
        navigation = addRenderableWidget(new RowList(drawer ? 12 : nav.x(), nav.y(), drawer ? width - 24 : Math.max(1, nav.width()), nav.height(), 24,
            Text.get("text.darkmusic.library_navigation"), (i, activated) -> {
            boolean folder = browser.chooseGroup(i, activated);
            if (folder) showLists();
            else showSongs();
            if (drawer && !folder) pane(Pane.LIBRARY);
        }));
        navigation.visible = drawer || library && !layout.singleColumn();
        navigation.disclosure(browser::disclosure, this::toggleFolder);
        navigation.icons(i -> {
            String mode = browser.mode();
            return mode.equals("All") ? i == 0 ? UiIcon.MUSIC : UiIcon.FOLDER : mode.equals("Artists") ? UiIcon.ARTIST : mode.equals("Albums") ? UiIcon.ALBUM : mode.equals("History") ? UiIcon.MUSIC : PlaylistStore.FAVORITES.equals(browser.groups().get(i)) ? UiIcon.HEART_FILLED : UiIcon.PLAYLIST;
        });
        navigation.indent(browser::indent);
        navigation.expanded(browser::expanded);
        songs = addRenderableWidget(new RowList(list.x(), list.y(), list.width(), list.height(), 36,
            Text.get("text.darkmusic.songs_enter_or_double_click_to_play"), (i, activated) -> {
            browser.select(i);
            if (activated)
                app.transport().play(browser.collectionTracks(), browser.selected(), browser.source());
        }));
        songs.visible = library;
        songs.artwork(app.artwork(), i -> i < browser.visible().size() ? browser.visible().get(i) : null);
        songs.lyrics(app.lyrics(), () -> app.settings().lyricsEnabled);
        songs.playing(i -> i < browser.visible().size() && browser.visible().get(i).id().equals(playingId));
        songs.playingIcon(() -> playingIcon);
        songs.favorites(i -> i < browser.visible().size() && app.playlists().favorite(browser.visible().get(i)));
    }

    /**
     * The selected song's buttons above its details: actions, favorite, edit, lyrics and its source link.
     */
    private void detailButtons(boolean library) {
        var inspector = pane == Pane.DETAILS ? new PlayerLayout.Rect(12, 96, width - 24, layout.dock().y() - 108) : layout.inspector();
        queueActions = icon("upnext.actions", UiIcon.MORE, inspector.right() - 28, inspector.y() + 4, 22, true,
            () -> show(app.navigation().songActions(this, browser.selected())));
        queueActions.visible = browser.selected() != null && (pane == Pane.DETAILS || library && inspector.width() > 0);
        queueActions.active = browser.selected() != null && app.canEditQueue();
        Help.tip(queueActions, Text.get("upnext.actions.help"));
        favorite = addRenderableWidget(new FavoriteButton(inspector.x() + 6, inspector.y() + 4,
            () -> app.playlists().favorite(browser.selected()), () -> {
            if (browser.selected() != null) app.playlistOperations().toggleFavorite(browser.selected());
        }));
        edit = icon("library.darkmusic.edit_metadata", UiIcon.EDIT, inspector.x() + 30, inspector.y() + 4, 22, true, () -> {
            if (browser.selected() != null && !app.mediaBusy()) {
                var before = browser.selected();
                show(app.navigation().metadata(this, before, updated -> browser.edited(before, updated)));
            }
        });
        lyricsButton = app.settings().lyricsEnabled
            ? icon("lyrics.darkmusic.title", UiIcon.LYRICS, inspector.x() + 54, inspector.y() + 4, 22, true, () -> show(app.navigation().lyrics(this, browser.selected()))) : null;
        int sourceX = inspector.x() + (lyricsButton == null ? 54 : 78);
        sourceLink = icon("library.darkmusic.open_source", UiIcon.LINK, sourceX, inspector.y() + 4, 22, true, () -> {
            String url = app.sourceLinks().get(browser.selected());
            if (!url.isEmpty()) Util.getPlatform().openUri(URI.create(url));
        });
        copySource = icon("library.darkmusic.copy_source", UiIcon.CHAIN, sourceX + 24, inspector.y() + 4, 22, true, () -> {
            var selected = browser.selected();
            String url = app.sourceLinks().get(selected);
            if (url.isEmpty()) return;
            minecraft.keyboardHandler.setClipboard(url);
            if (url.equals(minecraft.keyboardHandler.getClipboard())) {
                copiedUntil = System.nanoTime() + 2_000_000_000L;
                copiedTrack = selected.id();
            } else app.warn(Text.get("library.darkmusic.copy_failed"));
        });
        favorite.visible = edit.visible = sourceLink.visible = copySource.visible = browser.selected() != null && (pane == Pane.DETAILS || library && inspector.width() > 0);
        if (lyricsButton != null) lyricsButton.visible = copySource.visible;
    }

    private void refresh() {
        if (songs == null) return;
        browser.refresh();
        showLists();
    }

    /**
     * Shows the browser's collections and songs.
     */
    private void showLists() {
        navigation.emptyMessage(browser.groupsEmptyMessage());
        navigation.rows(browser.groupRows());
        navigation.select(browser.groups().indexOf(browser.group()));
        showSongs();
    }

    private void showSongs() {
        songs.emptyMessage(browser.songsEmptyMessage());
        var rows = browser.songRows();
        songs.rows(rows.size(), rows.row());
        songs.select(browser.visible().indexOf(browser.selected()));
    }

    private void toggleFolder(int index) {
        if (!browser.expandable(index)) return;
        browser.toggleFolder(index);
        showLists();
    }

    public void jumpToPlaying() {
        var current = app.player().state().track();
        if (current == null) return;
        reveal(current.id(), app.player().playingSource(), true);
    }

    /**
     * With nothing playing, opens on the song Resume Where You Left Off would continue, in its collection.
     */
    private void revealResume() {
        var point = app.settings().resumeLast ? app.settings().resume : null;
        if (point != null) reveal(point.track, new PlaybackController.Source(point.mode, point.group, "", ""), false);
    }

    private void reveal(String id, PlaybackController.Source source, boolean warn) {
        String problem = browser.reveal(source);
        if (!problem.isEmpty()) {
            if (warn) app.warn(Text.get(problem));
            return;
        }
        pane = Pane.LIBRARY;
        rebuildWidgets();
        int index = browser.selectListed(id);
        if (index >= 0) songs.reveal(index);
        else if (warn) app.warn(Text.get("player.darkmusic.outside_library"));
    }

    private void playPause() {
        // The remembered song continues from where it stopped rather than starting over.
        var selected = browser.selected();
        if (app.player().state().track() == null && selected != null && app.resumes(selected) && app.resumeLast(false))
            return;
        if (!selectionIsPlaying()) {
            if (selected != null)
                app.transport().play(browser.collectionTracks(), selected, browser.source());
        } else app.transport().toggle();
    }

    private boolean selectionIsPlaying() {
        return browser.selectionIsPlaying(app.player().state().track(), app.player().playingSource());
    }

    @Override
    public void tick() {
        if (app.settings().lyricsEnabled && app.settings().lyricsConsentVersion < 1) {
            show(LyricsConsent.request(app, this));
            return;
        }
        if (width < 320 || height < 240) return;
        if (openFolder != null) {
            String target = browseDirectory().toString();
            if (!target.equals(openFolderTarget)) {
                openFolderTarget = target;
                Help.tip(openFolder, Text.get("delete_audio.open_folder", target));
            }
        }
        var state = app.library().snapshot();
        var playing = app.player().state();
        String historyNow = (playing.track() == null ? "" : playing.track().id()) + playing.status();
        if (browser.mode().equals("History") && browser.group().equals("never") && !historyNow.equals(historyPlaying))
            refresh();
        historyPlaying = historyNow;
        if (browser.mode().equals("History") && historyRevision != app.listeningHistory().revision() || playlistRevision != app.playlists().revision() || revision != state.revision() && (!state.scanning() || System.nanoTime() - refreshed > 250_000_000L)) {
            refreshed = System.nanoTime();
            revision = state.revision();
            playlistRevision = app.playlists().revision();
            historyRevision = app.listeningHistory().revision();
            refresh();
        }
        var audio = app.player().state();
        String currentId = audio.track() == null ? "" : audio.track().id();
        if (!currentId.equals(followedId) || !app.player().playingSource().equals(followedSource)) {
            followedId = currentId;
            followedSource = app.player().playingSource();
            if (audio.track() != null && PlayerBrowser.canFollowPlayback(browser.query(), getFocused() instanceof EditBox))
                jumpToPlaying();
        }
        var selected = browser.selected();
        favorite.active = selected != null && app.playlistsReady() && !app.mediaBusy();
        queueActions.active = selected != null && app.canEditQueue();
        edit.active = selected != null && !app.mediaBusy();
        sourceLink.active = selected != null && !app.sourceLinks().get(selected).isEmpty();
        copySource.active = sourceLink.active;
        songs.active = !app.mediaBusy();
        playingId = audio.track() != null && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR ? audio.track().id() : "";
        playingIcon = audio.status() == AudioBackend.Status.PAUSED ? UiIcon.PAUSE : UiIcon.PLAY;
        requestLyrics(audio);
        transport.sync(audio, selectionIsPlaying());
    }

    /**
     * Lyrics for the playing song, and for the selected one once it has stayed selected briefly.
     */
    private void requestLyrics(AudioBackend.State audio) {
        if (app.settings().lyricsEnabled) {
            app.lyrics().request(audio.track());
            var selected = browser.selected();
            String selectedId = selected == null ? "" : selected.id();
            long now = System.nanoTime();
            if (!selectedId.equals(lyricsSelectionId)) {
                lyricsSelectionId = selectedId;
                lyricsSelectedAt = now;
            }
            app.lyrics().request(selected, app.settings().lyricsAutomaticLookup && now - lyricsSelectedAt >= 750_000_000L);
            songs.inspectVisibleLyrics();
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        app.importAudio(files, browser.dropFolder(), browser.dropPlaylist());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        keyboardNavigation = false;
        boolean handled = super.mouseClicked(event, twice);
        if (!handled) setFocused(null);
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256 && pane != Pane.LIBRARY) {
            pane(Pane.LIBRARY);
            return true;
        }
        var focused = getFocused();
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            keyboardNavigation = true;
            return super.keyPressed(event);
        }
        if (focused instanceof EditBox) return super.keyPressed(event);
        // Mouse selection must not capture playback keys until the user navigates with Tab.
        if (keyboardNavigation && focused instanceof AbstractWidget widget && widget.visible && widget.active) {
            boolean navigation = event.isLeft() || event.isRight() || event.key() == GLFW.GLFW_KEY_DOWN || event.key() == GLFW.GLFW_KEY_UP
                || event.key() >= GLFW.GLFW_KEY_PAGE_UP && event.key() <= GLFW.GLFW_KEY_END;
            boolean activation = event.isSelection() && (focused instanceof Button || focused instanceof ValueSlider);
            if (navigation || activation) return super.keyPressed(event);
        }
        if ((event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0) {
            if (event.key() == GLFW.GLFW_KEY_SPACE) {
                if (!spaceDown && !app.mediaBusy()) {
                    spaceDown = true;
                    playPause();
                }
                return true;
            }
            for (var binding : app.shortcuts().bindings())
                if (binding.matches(event)) {
                    while (binding.consumeClick()) {
                    }
                    if (binding == app.shortcuts().open()) {
                        if (!app.shortcuts().conflicts(binding)) onClose();
                    } else if (binding == app.shortcuts().toggle()) {
                        if (!app.shortcuts().conflicts(binding)) playPause();
                    } else if (binding == app.shortcuts().jump()) {
                        if (!app.shortcuts().conflicts(binding)) jumpToPlaying();
                    } else app.shortcuts().perform(binding);
                    return true;
                }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_SPACE && spaceDown) {
            spaceDown = false;
            return true;
        }
        return super.keyReleased(event);
    }

    static int fittedHeight(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return maxHeight;
        double ratio = Math.min((double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight);
        return Math.clamp((int) Math.round(sourceHeight * ratio), 1, maxHeight);
    }

    private static int fittedHeight(ArtworkCache.Cover cover, int maxWidth, int maxHeight) {
        return fittedHeight(cover.contentWidth() - 2 * cover.menuInset(), cover.contentHeight(), maxWidth, maxHeight);
    }

    private void details(GuiGraphicsExtractor g, PlayerLayout.Rect area, boolean standalone) {
        UiTheme.panel(g, area.x(), area.y(), area.width(), area.height());
        int size = standalone ? Math.min(area.height() - 36, Math.min(180, area.width() / 3)) : layout.artworkSize() - 20;
        size = Math.max(16, size);
        int artX = area.x() + (standalone ? 8 : (area.width() - size) / 2), artY = area.y() + 28;
        var selected = browser.selected();
        if (selected == null) {
            UiIcon.MUSIC.draw(g, artX + size / 4, artY + size / 4, size / 2, UiTheme.MUTED);
            return;
        }
        var cover = app.artwork().get(selected);
        int drawnHeight = size;
        if (cover != null) {
            boolean landscape = cover.contentWidth() - cover.menuInset() * 2 > cover.contentHeight();
            int maxWidth = !standalone && landscape ? area.width() - 16 : size;
            // Wide covers keep their aspect ratio without leaving an empty band above or below.
            if (!standalone) drawnHeight = fittedHeight(cover, maxWidth, size);
            ArtworkCache.drawMenu(g, cover,
                !standalone && landscape ? area.x() + 8 : artX, artY, maxWidth, drawnHeight);
        } else UiIcon.MUSIC.draw(g, artX + size / 4, artY + size / 4, size / 2, UiTheme.MUTED);
        int x = standalone ? artX + size + 12 : area.x() + 8;
        int y = standalone ? artY : artY + drawnHeight + 8;
        int textWidth = area.right() - x - 8;
        longText.draw(g, font, "selected_title", selected.title(), x, y, textWidth, UiTheme.TEXT);
        longText.draw(g, font, "selected_artist", selected.artist(), x, y + 13, textWidth, UiTheme.SECONDARY);
        UiIcon.ALBUM.draw(g, x, y + 26, 10, UiTheme.MUTED);
        longText.draw(g, font, "selected_album", selected.album(), x + 15, y + 27, textWidth - 15, UiTheme.SECONDARY);
        UiIcon.SPEED.draw(g, x, y + 38, 10, UiTheme.MUTED);
        g.text(font, time(selected.duration()), x + 15, y + 39, UiTheme.SECONDARY);
        UiIcon.FOLDER.draw(g, x, y + 50, 10, UiTheme.MUTED);
        longText.draw(g, font, "selected_path", selected.relativePath(), x + 15, y + 51, textWidth - 15, UiTheme.MUTED);
        if (app.settings().lyricsEnabled) {
            var info = app.lyrics().info(selected);
            UiIcon.LYRICS.draw(g, x, y + 63, 10, UiStatus.lyricsColor(info));
            LyricsStatusIcon.draw(g, font, info, x + 15, y + 62, 12);
            longText.drawUpdating(g, font, "selected_lyrics:" + selected.id(), app.lyrics().status(selected), x + 30, y + 64, textWidth - 30, UiStatus.lyricsColor(info));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        longText.begin();
        UiTheme.background(g, width, height);
        if (width < 320 || height < 240) {
            g.text(font, Text.get("phase2.minimum_size"), 8, 34, UiTheme.WARNING);
            super.extractRenderState(g, mx, my, delta);
            return;
        }
        UiIcon.MUSIC.draw(g, 12, 10, 12, UiTheme.ACCENT);
        g.text(font, "DarkMusic", 30, 12, UiTheme.TEXT);
        var audio = app.player().state();
        boolean library = pane == Pane.LIBRARY;
        queueActions.visible = favorite.visible = edit.visible = sourceLink.visible = copySource.visible = browser.selected() != null && (pane == Pane.DETAILS || library && layout.inspector().width() > 0);
        if (lyricsButton != null) lyricsButton.visible = copySource.visible;
        if (library) {
            String collection = browser.title();

            int titleX = layout.tracks().x() + (layout.singleColumn() ? 116 : 0);
            int titleRight = layout.singleColumn() ? width - 96 : layout.tracks().right() - (browser.mode().equals("Playlists") ? 150 : 32);
            longText.draw(g, font, "collection_title", collection + " (" + browser.visible().size() + ")", titleX, height < 300 ? 70 : 76,
                Math.max(1, titleRight - titleX), UiTheme.ACCENT);
            if (layout.inspector().width() > 0) {
                details(g, layout.inspector(), false);
                if (app.settings().hudVisualizer && selectionIsPlaying()) {
                    spectrum.render(app.settings(), app.playback(), g, layout.inspector().x() + 8, layout.spectrumY(), layout.inspector().width() - 16, layout.spectrumHeight());
                }
            }
        } else if (pane == Pane.DETAILS)
            details(g, new PlayerLayout.Rect(12, 96, width - 24, layout.dock().y() - 108), true);
        {
            var dock = layout.dock();
            String message = !app.warning().isBlank() ? app.warning() : !app.library().snapshot().message().isBlank() ? app.library().snapshot().message() : audio.status() == AudioBackend.Status.ERROR ? audio.error() : browser.playlistNotice();
            if (!message.isBlank()) {
                UiIcon.WARNING.draw(g, 12, dock.y() - 14, 10, UiTheme.WARNING);
                longText.draw(g, font, "message", message, 28, dock.y() - 13, width - 40, UiTheme.WARNING);
            }
        }
        if (audio.track() != null && audio.duration() > 0) {
            var dock = layout.dock();
            double position = transport.shownPosition(audio);
            int color = transport.seekable() ? UiTheme.SECONDARY : UiTheme.MUTED;
            String total = time(audio.duration());
            g.text(font, time(position), dock.x() + 4, layout.timeY(), color);
            g.text(font, total, dock.right() - 4 - font.width(total), layout.timeY(), color);
        }
        // Utility icons and view toggles are separate groups; the divider makes the gap intentional.
        if (library && !layout.singleColumn() && openFolder != null) {
            int utility = layout.navigationUtilityWidth(), contextY = height < 300 ? 64 : 70;
            int utilitiesEnd = 28 + utility * 5, togglesStart = layout.navigation().right() - utility * 2 - 4;
            int dividerX = (utilitiesEnd + togglesStart) / 2;
            if (togglesStart - utilitiesEnd >= 9)
                g.fill(dividerX, contextY + 4, dividerX + 1, contextY + 20, UiTheme.BORDER);
        }
        super.extractRenderState(g, mx, my, delta);
        if (copySource.visible && browser.selected() != null && copiedTrack.equals(browser.selected().id()) && System.nanoTime() < copiedUntil) {
            var message = Text.component("library.darkmusic.copied");
            int right = queueActions.getX();
            if (font.width(message) <= right - copySource.getRight() - 12)
                g.text(font, message, copySource.getRight() + 6, copySource.getY() + 6, UiTheme.SUCCESS);
            else
                g.setTooltipForNextFrame(font, List.of(message.getVisualOrderText()), Help::position, copySource.getX(), copySource.getBottom(), true);
        }
        importOverlay.render(app, g, width, height);
    }
}
