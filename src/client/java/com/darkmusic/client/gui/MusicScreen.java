package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MusicScreen extends LocalScreen {
    private static final java.time.format.DateTimeFormatter HISTORY_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
    private final DarkMusicClient app;
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private final ImportOverlay importOverlay = new ImportOverlay();

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor g) {
        if (!importOverlay.visible(app)) super.extractBlurredBackground(g);
    }

    private final java.util.Set<String> expandedFolders = new java.util.HashSet<>();
    private EditBox search;
    private RowList navigation, songs;
    private List<Track> visible = List.of();
    private List<String> groups = List.of();
    private List<FolderTree.Entry> folderRows = List.of();
    private FolderTree folders = new FolderTree(List.of());
    private long folderRevision = -1;
    private String folderRoot = "";
    private String mode = "All", group = "", query = "";
    private final java.util.Map<String, String> selectedGroups = new java.util.HashMap<>();

    private void mode(String next) {
        awaitingEditedTrack = false;
        selectedGroups.put(mode, group);
        mode = next;
        group = selectedGroups.getOrDefault(next, "");
        rebuildWidgets();
    }

    private Track selected, seekTrack;
    private boolean awaitingEditedTrack;
    private boolean seekDragging;
    private long revision = -1, playlistRevision = -1, historyRevision = -1, refreshed;
    private String playlistNotice = "";
    private Button play, shuffle, repeat, jump, openFolder, queueActions;
    private Button seekBack, seekForward;
    private boolean spaceDown;
    private boolean keyboardNavigation;
    private FavoriteButton favorite;
    private Button edit, sourceLink, copySource, lyricsButton;
    private long copiedUntil;
    private String copiedTrack = "";
    private Button mute;
    private ValueSlider progress;
    private ValueSlider speed, volume;
    private String playingId = "";
    private String openFolderTarget = "";
    private String followedId = "";
    private String lyricsSelectionId = "";
    private long lyricsSelectedAt;
    private PlaybackController.Source followedSource;
    private UiIcon playingIcon = UiIcon.PLAY;

    public MusicScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("DarkMusic"), parent);
        this.app = app;
    }

    public static String time(double seconds) {
        long value = (long) Math.max(0, Double.isFinite(seconds) ? seconds : 0);
        return "%d:%02d".formatted(value / 60, value % 60);
    }

    public static String label(String constant) {
        return Text.get("state.darkmusic." + constant.toLowerCase(Locale.ROOT));
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
            case PLAY -> app.toggle;
            case PREVIOUS -> app.previous;
            case NEXT -> app.next;
            case SEEK_BACK -> app.seekBack;
            case SEEK_FORWARD -> app.seekForward;
            case STOP -> app.stop;
            case LOCATE -> app.jump;
            default -> null;
        };
        return binding == null ? button : UiButton.shortcuts(button, binding);
    }

    private void pane(Pane next) {
        pane = next;
        rebuildWidgets();
    }

    private java.nio.file.Path browseDirectory() {
        return com.darkmusic.client.library.MusicFolders.browseDirectory(app.library.roots(), app.library.root(), mode, group);
    }

    private Button transportIcon(String key, UiIcon icon, int x, int y, int width, Runnable action) {
        return UiButton.style(icon(key, icon, x, y, width, true, action), UiButton.Style.TRANSPORT);
    }

    private void shuffle() {
        if (app.mediaBusy) return;
        app.settings.shuffle = !app.playback.shuffle();
        app.playback.shuffle(app.settings.shuffle);
        app.save();
    }

    private void repeat() {
        if (app.mediaBusy) return;
        var values = PlaybackController.Repeat.values();
        var next = values[(app.playback.repeat().ordinal() + 1) % values.length];
        app.settings.repeat = next;
        app.playback.repeat(next);
        app.save();
    }

    @Override
    protected void init() {
        if (songs != null) songScroll = songs.scrollOffset();
        if (navigation != null) collectionScroll = navigation.scrollOffset();
        seekDragging = false;
        seekTrack = null;
        if (width < 320 || height < 240) {
            icon("phase2.back", UiIcon.BACK, 4, 4, Math.max(1, Math.min(80, width - 8)), false, this::onClose);
            return;
        }
        layout = PlayerLayout.of(width, height);
        openFolder = null;
        openFolderTarget = "";
        shuffle = repeat = null;
        int contextY = height < 300 ? 64 : 70;
        boolean compactNavigation = width < 640;
        int navigationWidth = compactNavigation ? 24 : 112;
        int navigationX = width - 12 - 3 * navigationWidth - 8;
        Help.tip(icon("upnext.title", UiIcon.PLAYLIST, navigationX, 4, navigationWidth, compactNavigation,
            () -> show(new UpNextScreen(app, this))), Text.get("upnext.title.help"));
        icon("text.darkmusic.downloads", UiIcon.DOWNLOAD, navigationX + navigationWidth + 4, 4, navigationWidth, compactNavigation,
            () -> show(new DownloadScreen(app, this)));
        icon("text.darkmusic.settings", UiIcon.SETTINGS, navigationX + 2 * (navigationWidth + 4), 4, navigationWidth, compactNavigation,
            () -> show(new SettingsScreen(app, this)));
        String[] modes = {"All", "Playlists", "Artists", "Albums"};
        int searchX;
        if (width >= 800) {
            for (int i = 0; i < modes.length; i++) {
                String next = modes[i];
                UiButton.selected(button(Text.get("view.darkmusic." + next.toLowerCase(Locale.ROOT)), 12 + i * 68, 38, 64, () -> {
                    mode(next);
                }), () -> mode.equals(next));
            }
            searchX = 292;
        } else {
            button(Text.get("format.darkmusic.view", Text.get("view.darkmusic." + mode.toLowerCase(Locale.ROOT))), 12, 38, 108, () -> {
                mode(modes[(Arrays.asList(modes).indexOf(mode) + 1) % modes.length]);
            });
            searchX = 128;
        }
        search = addRenderableWidget(new UiInput(font, searchX, 38, width - searchX - 12, 20, Text.component("text.darkmusic.search_songs")));
        search.setMaxLength(512);
        search.setHint(Text.component("text.darkmusic.search_title_artist_album_or_path"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            refreshSongs();
        });
        boolean library = pane == Pane.LIBRARY;
        boolean drawer = pane == Pane.COLLECTIONS;
        if (pane != Pane.LIBRARY) icon("phase2.back", UiIcon.BACK, 12, contextY, 68, false, () -> pane(Pane.LIBRARY));
        else if (layout.singleColumn())
            icon("ui.darkmusic.collections", UiIcon.FOLDER, 12, contextY, 110, false, () -> pane(Pane.COLLECTIONS));
        else {
            int utilityWidth = Math.min(24, (layout.navigation().width() - 20) / 6);
            openFolder = icon("text.darkmusic.open_folder", UiIcon.FOLDER, 12, contextY, utilityWidth, true, () -> app.openFolder(browseDirectory()));
            icon("import.darkmusic.history", UiIcon.PLAYLIST, 16 + utilityWidth, contextY, utilityWidth, true, () -> show(new ImportHistoryScreen(app, this)));
            icon("text.darkmusic.refresh_library", UiIcon.REFRESH, 20 + utilityWidth * 2, contextY, utilityWidth, true, () -> app.library.rescan());
            icon("delete_audio.manage", UiIcon.TOOLS, 24 + utilityWidth * 3, contextY, utilityWidth, true, () -> show(new LibraryManagerScreen(app, this)));
        }
        if (library || drawer) {
            int utilityWidth = drawer || layout.singleColumn() ? 24 : Math.min(24, (layout.navigation().width() - 20) / 6);
            int manageX = drawer ? width - 40 : layout.singleColumn() ? width - 64 : layout.navigation().right() - utilityWidth;
            UiButton.selected(icon("view.darkmusic.history", UiIcon.HISTORY, manageX - utilityWidth - 4, contextY, utilityWidth, true,
                () -> mode("History")), () -> mode.equals("History"));
            var manage = icon("text.darkmusic.manage_playlists", UiIcon.MUSIC, manageX, contextY, utilityWidth, true, () -> {
                if (app.playlistsReady) show(new PlaylistScreen(app, this));
                else app.warn(Text.get("text.darkmusic.playlists_are_still_loading"));
            });
            manage.active = app.playlistsReady;
        }
        if (layout.inspector().width() == 0 && library)
            icon("phase2.details", UiIcon.ALBUM, width - 36, contextY, 24, true, () -> pane(Pane.DETAILS));
        var list = layout.tracks();
        var nav = layout.navigation();
        navigation = addRenderableWidget(new RowList(drawer ? 12 : nav.x(), nav.y(), drawer ? width - 24 : Math.max(1, nav.width()), nav.height(), 24,
            Text.get("text.darkmusic.library_navigation"), (i, activated) -> {
            awaitingEditedTrack = false;
            group = groups.get(i);
            if (activated && mode.equals("All") && i > 0 && folderRows.get(i - 1).children()) toggleFolder(i);
            else refreshSongs();
            if (drawer && !(activated && mode.equals("All") && i > 0 && folderRows.get(i - 1).children()))
                pane(Pane.LIBRARY);
        }));
        navigation.visible = drawer || library && !layout.singleColumn();
        navigation.disclosure(i -> mode.equals("All") && i > 0 && i <= folderRows.size() && folderRows.get(i - 1).children() ? 5 + folderRows.get(i - 1).depth() * 8 : -1, this::toggleFolder);
        navigation.icons(i -> mode.equals("All") ? i == 0 ? UiIcon.MUSIC : UiIcon.FOLDER : mode.equals("Artists") ? UiIcon.ARTIST : mode.equals("Albums") ? UiIcon.ALBUM : mode.equals("History") ? UiIcon.MUSIC : PlaylistStore.FAVORITES.equals(groups.get(i)) ? UiIcon.HEART_FILLED : UiIcon.PLAYLIST);
        navigation.indent(i -> mode.equals("All") && i > 0 && i <= folderRows.size() ? folderRows.get(i - 1).depth() * 8 : 0);
        navigation.expanded(i -> mode.equals("All") && i > 0 && i <= folderRows.size() && folderRows.get(i - 1).expanded());
        songs = addRenderableWidget(new RowList(list.x(), list.y(), list.width(), list.height(), 36,
            Text.get("text.darkmusic.songs_enter_or_double_click_to_play"), (i, activated) -> {
            selected = visible.get(i);
            awaitingEditedTrack = false;
            if (activated && !app.mediaBusy)
                app.playback.play(collectionTracks(), selected, new PlaybackController.Source(mode, group, "", ""));
        }));
        songs.visible = library;
        songs.artwork(app.artwork, i -> i < visible.size() ? visible.get(i) : null);
        songs.lyrics(app.lyrics, () -> app.settings.lyricsEnabled);
        songs.playing(i -> i < visible.size() && visible.get(i).id().equals(playingId));
        songs.playingIcon(() -> playingIcon);
        songs.favorites(i -> i < visible.size() && app.playlists.favorite(visible.get(i)));
        var inspector = pane == Pane.DETAILS ? new PlayerLayout.Rect(12, 96, width - 24, layout.dock().y() - 108) : layout.inspector();
        queueActions = icon("upnext.actions", UiIcon.MORE, inspector.right() - 28, inspector.y() + 4, 22, true,
            () -> show(new SongActionsScreen(app, this, selected)));
        queueActions.visible = selected != null && (pane == Pane.DETAILS || library && inspector.width() > 0);
        queueActions.active = selected != null && app.canEditQueue();
        Help.tip(queueActions, Text.get("upnext.actions.help"));
        favorite = addRenderableWidget(new FavoriteButton(inspector.x() + 6, inspector.y() + 4,
            () -> app.playlists.favorite(selected), () -> {
            if (selected != null && app.playlistsReady) app.playlists.toggleFavorite(selected);
        }));
        edit = icon("library.darkmusic.edit_metadata", UiIcon.EDIT, inspector.x() + 30, inspector.y() + 4, 22, true, () -> {
            if (selected != null && !app.mediaBusy) {
                var before = selected;
                show(new MetadataScreen(app, this, before, updated -> {
                    if (selected != null && selected.id().equals(before.id())) {
                        selected = updated;
                        awaitingEditedTrack = true;
                    }
                }));
            }
        });
        lyricsButton = app.settings.lyricsEnabled
            ? icon("lyrics.darkmusic.title", UiIcon.LYRICS, inspector.x() + 54, inspector.y() + 4, 22, true, () -> show(new LyricsScreen(app, this, selected))) : null;
        int sourceX = inspector.x() + (lyricsButton == null ? 54 : 78);
        sourceLink = icon("library.darkmusic.open_source", UiIcon.LINK, sourceX, inspector.y() + 4, 22, true, () -> {
            String url = app.sourceLinks.get(selected);
            if (!url.isEmpty()) net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(url));
        });
        copySource = icon("library.darkmusic.copy_source", UiIcon.CHAIN, sourceX + 24, inspector.y() + 4, 22, true, () -> {
            String url = app.sourceLinks.get(selected);
            if (url.isEmpty()) return;
            minecraft.keyboardHandler.setClipboard(url);
            if (url.equals(minecraft.keyboardHandler.getClipboard())) {
                copiedUntil = System.nanoTime() + 2_000_000_000L;
                copiedTrack = selected.id();
            } else app.warn(Text.get("library.darkmusic.copy_failed"));
        });
        favorite.visible = edit.visible = sourceLink.visible = copySource.visible = selected != null && (pane == Pane.DETAILS || library && inspector.width() > 0);
        if (lyricsButton != null) lyricsButton.visible = copySource.visible;
        int transportY = layout.transportY();
        int x = layout.transportX();
        int sliderWidth = layout.controlWidth();
        int buttonWidth = layout.transportButtonWidth();
        int gap = PlayerLayout.CONTROL_GAP;
        speed = addRenderableWidget(Help.tip(new ValueSlider(x, transportY, sliderWidth, (app.settings.speed - .5) / 2.5,
                v -> Text.get("format.darkmusic.speed", String.format(Locale.ROOT, "%.1f", app.settings.speed)),
                v -> app.speed(.5 + v * 2.5), app::save, 25),
            Help.withKeys(Text.get("text.darkmusic.playback_speed_0_5x_to_3_0x_in_0_1x_steps_faster_raises_pitch_slower_lowers_it"), app.slower, app.faster)));
        speed.media(true, v -> String.format(Locale.ROOT, "%.1f×", .5 + v * 2.5));
        speed.mediaLabelLeft(true);
        x += sliderWidth + gap;
        UiButton.shortcuts(transportIcon("key.darkmusic.normal_speed", UiIcon.REFRESH, x, transportY, buttonWidth, () -> app.speed(1)), app.normalSpeed);
        x += buttonWidth + gap;
        shuffle = transportIcon("text.darkmusic.shuffle", UiIcon.SHUFFLE, x, transportY, buttonWidth, this::shuffle);
        x += buttonWidth + gap;
        jump = transportIcon("player.darkmusic.jump", UiIcon.LOCATE, x, transportY, buttonWidth, this::jumpToPlaying);
        x += buttonWidth + gap;
        transportIcon("text.darkmusic.previous", UiIcon.PREVIOUS, x, transportY, buttonWidth, () -> {
            if (!app.mediaBusy) app.playback.previous();
        });
        x += buttonWidth + gap;
        seekBack = transportIcon("key.darkmusic.seek_back", UiIcon.SEEK_BACK, x, transportY, buttonWidth, () -> app.seekRelative(-5));
        x += buttonWidth + gap;
        play = transportIcon("text.darkmusic.play", UiIcon.PLAY, x, transportY - 4, buttonWidth + 8, this::playPause);
        play.setHeight(28);
        UiButton.playOutline(play);
        x += buttonWidth + 8 + gap;
        seekForward = transportIcon("key.darkmusic.seek_forward", UiIcon.SEEK_FORWARD, x, transportY, buttonWidth, () -> app.seekRelative(5));
        x += buttonWidth + gap;
        transportIcon("text.darkmusic.next", UiIcon.NEXT, x, transportY, buttonWidth, () -> {
            if (!app.mediaBusy) app.playback.next();
        });
        x += buttonWidth + gap;
        transportIcon("text.darkmusic.stop", UiIcon.STOP, x, transportY, buttonWidth, () -> app.playback.stop());
        x += buttonWidth + gap;
        repeat = transportIcon("text.darkmusic.repeat", UiIcon.REPEAT, x, transportY, buttonWidth, this::repeat);
        x += buttonWidth + gap;
        mute = UiButton.shortcuts(transportIcon("key.darkmusic.mute", app.settings.volume == 0 ? UiIcon.MUTED : UiIcon.VOLUME, x, transportY, buttonWidth, app::toggleMute), app.mute);
        UiButton.selected(mute, () -> app.settings.volume == 0);
        x += buttonWidth + gap;
        volume = addRenderableWidget(Help.tip(new ValueSlider(x, transportY, sliderWidth, app.settings.volume,
                v -> Text.get("format.darkmusic.volume", Math.round(v * 100)), app::volume, app::save),
            Help.withKeys(Text.get("text.darkmusic.darkmusic_volume_independent_of_minecraft_sound_sliders"), app.volumeDown, app.volumeUp)));
        volume.media(true, v -> Math.round(v * 100) + "%");
        progress = addRenderableWidget(new ValueSlider(layout.dock().x() + 4, layout.seekY(), layout.dock().width() - 8, 0,
            v -> time(progress != null && progress.editing() ? v * app.playback.state().duration() : app.playback.state().position()) + " / " + time(app.playback.state().duration()),
            v -> {
                if (!seekDragging) seekTrack = app.playback.state().track();
                seekDragging = progress != null && progress.editing();
            }, this::commitSeek));
        progress.trackOnly(true);
        progress.simpleProgress(true);
        progress.colors(() -> app.settings.progressColors);
        if (drawer) {
            icon("text.darkmusic.open_folder", UiIcon.FOLDER, 84, contextY, 24, true, app::openFolder);
            icon("import.darkmusic.history", UiIcon.PLAYLIST, 112, contextY, 24, true, () -> show(new ImportHistoryScreen(app, this)));
            icon("text.darkmusic.refresh_library", UiIcon.REFRESH, 140, contextY, 24, true, () -> app.library.rescan());
        }
        if (shuffle != null) UiButton.selected(shuffle, () -> app.playback.shuffle());
        if (repeat != null) UiButton.selected(repeat, () -> app.playback.repeat() != PlaybackController.Repeat.OFF);
        refresh();
        songs.scrollOffset(songScroll);
        navigation.scrollOffset(collectionScroll);
        if (revealOnEntry) {
            revealOnEntry = false;
            if (app.playback.state().track() != null) jumpToPlaying();
        }
    }

    private void refresh() {
        if (songs == null) return;
        var snapshot = app.library.snapshot();
        var tracks = snapshot.tracks();
        String root = app.library.root().toString();
        if (folderRevision != snapshot.revision() || !root.equals(folderRoot)) {
            folderRevision = snapshot.revision();
            folderRoot = root;
            folders = new FolderTree(tracks, snapshot.folders(), app.library.roots());
            expandedFolders.removeIf(path -> !folders.contains(path));
        }
        folderRows = folders.visible(expandedFolders);
        groups = switch (mode) {
            case "History" -> List.of("recent", "most");
            case "Playlists" -> app.playlists.all().stream().map(p -> p.id()).toList();
            case "Artists" ->
                tracks.stream().map(Track::artist).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "Albums" ->
                tracks.stream().map(Track::album).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            default ->
                java.util.stream.Stream.concat(java.util.stream.Stream.of(""), folderRows.stream().map(FolderTree.Entry::path)).toList();
        };
        if (!mode.equals("All") && !groups.contains(group)) group = groups.isEmpty() ? "" : groups.getFirst();
        if (mode.equals("All") && !folders.contains(group)) group = "";
        navigation.emptyMessage(mode.equals("Playlists") ? (app.playlistsReady ? Text.get("text.darkmusic.no_playlists") : Text.get("text.darkmusic.loading")) : Text.get("text.darkmusic.no_entries"));
        var counts = new java.util.HashMap<String, Integer>();
        for (Track track : tracks) {
            if (mode.equals("Artists")) counts.merge(track.artist(), 1, Integer::sum);
            else if (mode.equals("Albums")) counts.merge(track.album(), 1, Integer::sum);
            else if (mode.equals("All")) {
                counts.merge("", 1, Integer::sum);
                String path = "@" + track.rootId() + "/" + track.relativePath().replace('\\', '/');
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                    counts.merge(path.substring(0, slash), 1, Integer::sum);
            }
        }
        navigation.rows(java.util.stream.IntStream.range(0, groups.size()).mapToObj(i -> {
            String key = groups.get(i);
            String label = mode.equals("All") ? i == 0 ? Text.get("text.darkmusic.all_tracks") : folderRows.get(i - 1).name() : mode.equals("History") ? Text.get("history." + key) : mode.equals("Playlists") ? app.playlists.displayName(app.playlists.get(key)) : key;
            int count = mode.equals("History") ? app.listeningHistory.collection(tracks, key.equals("most")).size() : mode.equals("Playlists") ? app.playlists.resolve(key, tracks).size() : counts.getOrDefault(key, 0);
            return new RowList.Row(label + " (" + count + ")", "");
        }).toList());
        navigation.select(groups.indexOf(group));
        refreshSongs();
    }

    private List<Track> collectionTracks() {
        var tracks = app.library.snapshot().tracks();
        if (mode.equals("History")) return app.listeningHistory.collection(tracks, group.equals("most"));
        var source = mode.equals("Playlists") ? app.playlists.resolve(group, tracks) : tracks;
        return source.stream().filter(t -> switch (mode) {
            case "All" -> FolderTree.includes(group, t);
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
    }

    private void refreshSongs() {
        String needle = query.toLowerCase(Locale.ROOT).strip();
        var library = app.library.snapshot().tracks();
        boolean playlists = mode.equals("Playlists");
        var source = mode.equals("History") ? collectionTracks() : playlists ? app.playlists.resolve(group, library) : library;
        playlistNotice = "";
        if (playlists) {
            var playlist = app.playlists.get(group);
            if (!app.playlistsReady) playlistNotice = Text.get("text.darkmusic.playlists_are_still_loading");
            else if (playlist == null)
                playlistNotice = Text.get("text.darkmusic.create_a_playlist_with_manage_playlists");
            else if (playlist.entries().isEmpty())
                playlistNotice = Text.get("text.darkmusic.this_playlist_is_empty_add_songs_in_manage_playlists");
            else if (source.size() < playlist.entries().size())
                playlistNotice = Text.get("format.darkmusic.missing_entries", playlist.entries().size() - source.size());
        }
        visible = source.stream().filter(t -> t.matches(needle)).filter(t -> switch (mode) {
            case "All" -> FolderTree.includes(group, t);
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
        songs.emptyMessage(app.library.snapshot().tracks().isEmpty() ? Text.get("ui.darkmusic.library_empty") : playlists && source.isEmpty() ? Text.get("text.darkmusic.no_playable_songs") : Text.get("text.darkmusic.no_matching_songs"));
        var stats = mode.equals("History") ? app.listeningHistory.snapshot() : java.util.Map.<String, com.darkmusic.client.library.ListeningHistory.Entry>of();
        var displayed = visible;
        boolean history = mode.equals("History");
        songs.rows(displayed.size(), i -> {
            var t = displayed.get(i);
            return new RowList.Row(t.title(), history ? historyDetails(t, stats) : t.artist() + " | " + time(t.duration()) + " | " + t.album(), t.relativePath());
        });
        if (selected != null && (!awaitingEditedTrack || app.playlists.index(library).containsKey(selected.id()))) {
            selected = visible.stream().filter(t -> t.id().equals(selected.id())).findFirst().orElse(null);
            awaitingEditedTrack = false;
        }
        if (selected == null) selected = visible.isEmpty() ? null : visible.getFirst();
        songs.select(visible.indexOf(selected));
    }

    private String historyDetails(Track track, java.util.Map<String, com.darkmusic.client.library.ListeningHistory.Entry> values) {
        var stats = values.get(track.id());
        if (stats == null) return "";
        String date = HISTORY_DATE.format(java.time.Instant.ofEpochMilli(stats.lastPlayed()));
        return Text.get("history.stats", stats.plays(), (long) (stats.seconds() / 60), date);
    }

    private void toggleFolder(int index) {
        if (!mode.equals("All") || index <= 0 || index > folderRows.size()) return;
        var folder = folderRows.get(index - 1);
        if (!folder.children()) return;
        if (!expandedFolders.remove(folder.path())) expandedFolders.add(folder.path());
        refresh();
    }

    public void jumpToPlaying() {
        var current = app.playback.state().track();
        if (current == null) return;
        var source = app.playback.playingSource();
        if (!source.rootId().isEmpty() && app.library.directory(source.rootId()) == null) {
            app.warn(Text.get("player.darkmusic.outside_library"));
            return;
        }
        if (source.mode().equals("Playlists") && app.playlists.get(source.group()) == null) {
            app.warn(Text.get("player.darkmusic.source_missing"));
            return;
        }
        selectedGroups.put(mode, group);
        mode = source.mode();
        pane = Pane.LIBRARY;
        group = source.group();
        if (mode.equals("All") && !group.isEmpty() && !group.startsWith("@") && !source.rootId().isEmpty())
            group = "@" + source.rootId() + "/" + group;
        query = "";
        if (mode.equals("All")) {
            for (int slash = group.indexOf('/'); slash >= 0; slash = group.indexOf('/', slash + 1))
                expandedFolders.add(group.substring(0, slash));
        }
        rebuildWidgets();
        int index = indexOfPlaying(current.id());
        if (index < 0 && !query.isEmpty()) {
            query = "";
            search.setValue("");
            refreshSongs();
            index = indexOfPlaying(current.id());
        }
        if (index >= 0) {
            selected = visible.get(index);
            songs.reveal(index);
        } else app.warn(Text.get("player.darkmusic.outside_library"));
    }

    private int indexOfPlaying(String id) {
        for (int i = 0; i < visible.size(); i++) if (visible.get(i).id().equals(id)) return i;
        return -1;
    }

    private void commitSeek() {
        var state = app.playback.state();
        if (seekTrack != null && state.track() != null && seekTrack.id().equals(state.track().id()) && progress.active)
            app.playback.seek(progress.fraction() * state.duration());
        seekDragging = false;
        seekTrack = null;
    }

    private void playPause() {
        if (app.mediaBusy) return;
        if (!selectionIsPlaying()) {
            if (selected != null)
                app.playback.play(collectionTracks(), selected, new PlaybackController.Source(mode, group, "", ""));
        } else app.playback.toggle();
    }

    private boolean selectionIsPlaying() {
        var current = app.playback.state().track();
        var source = app.playback.playingSource();
        return selected != null && current != null && selected.id().equals(current.id())
            && mode.equals(source.mode()) && group.equals(source.group())
            && (source.rootId().isEmpty() || app.library.directory(source.rootId()) != null);
    }

    @Override
    public void tick() {
        if (app.settings.lyricsEnabled && app.settings.lyricsConsentVersion < 1) {
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
        var state = app.library.snapshot();
        if (mode.equals("History") && historyRevision != app.listeningHistory.revision() || playlistRevision != app.playlists.revision() || revision != state.revision() && (!state.scanning() || System.nanoTime() - refreshed > 250_000_000L)) {
            refreshed = System.nanoTime();
            revision = state.revision();
            playlistRevision = app.playlists.revision();
            historyRevision = app.listeningHistory.revision();
            refresh();
        }
        var audio = app.playback.state();
        String currentId = audio.track() == null ? "" : audio.track().id();
        if (!currentId.equals(followedId) || !app.playback.playingSource().equals(followedSource)) {
            followedId = currentId;
            followedSource = app.playback.playingSource();
            if (audio.track() != null && canFollowPlayback(query, getFocused() instanceof EditBox)) jumpToPlaying();
        }
        favorite.active = selected != null && app.playlistsReady && !app.mediaBusy;
        queueActions.active = selected != null && app.canEditQueue();
        edit.active = selected != null && !app.mediaBusy;
        sourceLink.active = selected != null && !app.sourceLinks.get(selected).isEmpty();
        copySource.active = sourceLink.active;
        songs.active = !app.mediaBusy;
        playingId = audio.track() != null && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR ? audio.track().id() : "";
        playingIcon = audio.status() == AudioBackend.Status.PAUSED ? UiIcon.PAUSE : UiIcon.PLAY;
        jump.active = audio.track() != null;
        if (app.settings.lyricsEnabled) {
            app.lyrics.request(audio.track());
            String selectedId = selected == null ? "" : selected.id();
            long now = System.nanoTime();
            if (!selectedId.equals(lyricsSelectionId)) {
                lyricsSelectionId = selectedId;
                lyricsSelectedAt = now;
            }
            app.lyrics.request(selected, app.settings.lyricsAutomaticLookup && now - lyricsSelectedAt >= 750_000_000L);
            songs.inspectVisibleLyrics();
        }
        volume.sync(app.settings.volume);
        UiButton.icon(mute, app.settings.volume == 0 ? UiIcon.MUTED : UiIcon.VOLUME, true);
        speed.sync((app.settings.speed - .5) / 2.5);
        play.active = !app.mediaBusy;
        seekBack.active = seekForward.active = !app.mediaBusy && audio.track() != null && audio.duration() > 0
            && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.active = !app.mediaBusy && audio.track() != null && audio.duration() > 0 && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.sync(audio.duration() > 0 ? audio.position() / audio.duration() : 0);
        boolean controlling = selectionIsPlaying();
        play.setMessage(Component.literal(controlling && audio.status() == AudioBackend.Status.PLAYING ? Text.get("text.darkmusic.pause") : controlling && audio.status() == AudioBackend.Status.PAUSED ? Text.get("text.darkmusic.resume") : Text.get("text.darkmusic.play")));
        UiButton.icon(play, controlling && audio.status() == AudioBackend.Status.PLAYING ? UiIcon.PAUSE : UiIcon.PLAY, true);
        if (shuffle != null)
            shuffle.setMessage(Component.literal(app.playback.shuffle() ? Text.get("text.darkmusic.shuffle_on") : Text.get("text.darkmusic.shuffle_off")));
        if (repeat != null) {
            repeat.setMessage(Text.component("format.darkmusic.repeat", label(app.playback.repeat().name())));
            UiButton.icon(repeat, app.playback.repeat() == PlaybackController.Repeat.ONE ? UiIcon.REPEAT_ONE : app.playback.repeat() == PlaybackController.Repeat.ALL ? UiIcon.REPEAT_ALL : UiIcon.REPEAT, true);
        }
    }

    @Override
    public void onFilesDrop(java.util.List<java.nio.file.Path> files) {
        String folder = mode.equals("All") ? group : "";
        String playlist = mode.equals("Playlists") && app.playlists.get(group) != null ? group : "";
        app.importAudio(files, folder, playlist);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean twice) {
        keyboardNavigation = false;
        boolean handled = super.mouseClicked(event, twice);
        if (!handled) setFocused(null);
        return handled;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 256 && pane != Pane.LIBRARY) {
            pane(Pane.LIBRARY);
            return true;
        }
        var focused = getFocused();
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            keyboardNavigation = true;
            return super.keyPressed(event);
        }
        if (focused instanceof EditBox) return super.keyPressed(event);
        // Mouse selection must not capture playback keys until the user navigates with Tab.
        if (keyboardNavigation && focused instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible && widget.active) {
            boolean navigation = event.isLeft() || event.isRight() || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_UP
                || event.key() >= org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP && event.key() <= org.lwjgl.glfw.GLFW.GLFW_KEY_END;
            boolean activation = event.isSelection() && (focused instanceof Button || focused instanceof ValueSlider);
            if (navigation || activation) return super.keyPressed(event);
        }
        if ((event.modifiers() & (org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL | org.lwjgl.glfw.GLFW.GLFW_MOD_ALT | org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER)) == 0) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
                if (!spaceDown && !app.mediaBusy) {
                    spaceDown = true;
                    playPause();
                }
                return true;
            }
            for (var binding : app.bindings())
                if (binding.matches(event)) {
                    while (binding.consumeClick()) {
                    }
                    if (binding == app.open) {
                        if (!app.conflicts(binding)) onClose();
                    } else if (binding == app.toggle) {
                        if (!app.conflicts(binding)) playPause();
                    } else if (binding == app.jump) {
                        if (!app.conflicts(binding)) jumpToPlaying();
                    } else app.playbackShortcut(binding);
                    return true;
                }
        }
        return super.keyPressed(event);
    }

    static boolean canFollowPlayback(String query, boolean editingText) {
        return query.isEmpty() && !editingText;
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE && spaceDown) {
            spaceDown = false;
            return true;
        }
        return super.keyReleased(event);
    }

    private void details(GuiGraphicsExtractor g, PlayerLayout.Rect area, boolean standalone) {
        UiTheme.panel(g, area.x(), area.y(), area.width(), area.height());
        int size = standalone ? Math.min(area.height() - 36, Math.min(180, area.width() / 3)) : layout.artworkSize() - 20;
        size = Math.max(16, size);
        int artX = area.x() + (standalone ? 8 : (area.width() - size) / 2), artY = area.y() + 28;
        if (selected == null) {
            UiIcon.MUSIC.draw(g, artX + size / 4, artY + size / 4, size / 2, UiTheme.MUTED);
            return;
        }
        var cover = app.artwork.get(selected);
        if (cover != null) {
            boolean landscape = cover.contentWidth() - cover.menuInset() * 2 > cover.contentHeight();
            com.darkmusic.client.library.ArtworkCache.drawMenu(g, cover,
                !standalone && landscape ? area.x() + 8 : artX, artY,
                !standalone && landscape ? area.width() - 16 : size, size);
        } else UiIcon.MUSIC.draw(g, artX + size / 4, artY + size / 4, size / 2, UiTheme.MUTED);
        int x = standalone ? artX + size + 12 : area.x() + 8;
        int y = standalone ? artY : artY + size + 8;
        int textWidth = area.right() - x - 8;
        longText.draw(g, font, "selected_title", selected.title(), x, y, textWidth, UiTheme.TEXT);
        longText.draw(g, font, "selected_artist", selected.artist(), x, y + 13, textWidth, UiTheme.SECONDARY);
        UiIcon.ALBUM.draw(g, x, y + 26, 10, UiTheme.MUTED);
        longText.draw(g, font, "selected_album", selected.album(), x + 15, y + 27, textWidth - 15, UiTheme.SECONDARY);
        UiIcon.SPEED.draw(g, x, y + 38, 10, UiTheme.MUTED);
        g.text(font, time(selected.duration()), x + 15, y + 39, UiTheme.SECONDARY);
        UiIcon.FOLDER.draw(g, x, y + 50, 10, UiTheme.MUTED);
        longText.draw(g, font, "selected_path", selected.relativePath(), x + 15, y + 51, textWidth - 15, UiTheme.MUTED);
        if (app.settings.lyricsEnabled) {
            var info = app.lyrics.info(selected);
            UiIcon.LYRICS.draw(g, x, y + 63, 10, info.color());
            LyricsStatusIcon.draw(g, font, info, x + 15, y + 62, 12);
            longText.drawUpdating(g, font, "selected_lyrics:" + selected.id(), app.lyrics.status(selected), x + 30, y + 64, textWidth - 30, info.color());
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
        var audio = app.playback.state();
        boolean library = pane == Pane.LIBRARY;
        queueActions.visible = favorite.visible = edit.visible = sourceLink.visible = copySource.visible = selected != null && (pane == Pane.DETAILS || library && layout.inspector().width() > 0);
        if (lyricsButton != null) lyricsButton.visible = copySource.visible;
        if (library) {
            String collection = mode.equals("History") ? Text.get("history." + group) : mode.equals("All") ? group.isEmpty() ? Text.get("text.darkmusic.all_tracks") : folders.display(group) : mode.equals("Playlists") && app.playlists.get(group) != null ? app.playlists.displayName(app.playlists.get(group)) : group;

            int titleX = layout.tracks().x() + (layout.singleColumn() ? 116 : 0);
            int titleRight = layout.singleColumn() ? width - 96 : layout.tracks().right() - (mode.equals("Playlists") ? 150 : 32);
            longText.draw(g, font, "collection_title", collection + " (" + visible.size() + ")", titleX, height < 300 ? 70 : 76,
                Math.max(1, titleRight - titleX), UiTheme.ACCENT);
            if (layout.inspector().width() > 0) {
                details(g, layout.inspector(), false);
                if (app.settings.hudVisualizer && selectionIsPlaying()) {
                    spectrum.render(app, g, layout.inspector().x() + 8, layout.spectrumY(), layout.inspector().width() - 16, layout.spectrumHeight());
                }
            }
        } else if (pane == Pane.DETAILS)
            details(g, new PlayerLayout.Rect(12, 96, width - 24, layout.dock().y() - 108), true);
        {
            var dock = layout.dock();
            String message = !app.warning.isBlank() ? app.warning : !app.library.snapshot().message().isBlank() ? app.library.snapshot().message() : audio.status() == AudioBackend.Status.ERROR ? audio.error() : playlistNotice;
            if (!message.isBlank()) {
                UiIcon.WARNING.draw(g, 12, dock.y() - 14, 10, UiTheme.WARNING);
                longText.draw(g, font, "message", message, 28, dock.y() - 13, width - 40, UiTheme.WARNING);
            }
        }
        super.extractRenderState(g, mx, my, delta);
        if (copySource.visible && selected != null && copiedTrack.equals(selected.id()) && System.nanoTime() < copiedUntil) {
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
