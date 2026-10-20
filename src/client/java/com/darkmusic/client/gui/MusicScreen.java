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
    private long revision = -1, playlistRevision = -1, refreshed;
    private String playlistNotice = "";
    private Button play, shuffle, repeat, jump;
    private FavoriteButton favorite;
    private Button edit, sourceLink, copySource;
    private long copiedUntil;
    private String copiedTrack = "";
    private Button mute;
    private ValueSlider progress;
    private ValueSlider speed, volume;
    private String playingId = "";
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

    private Button icon(String key, UiIcon icon, int x, int y, int w, boolean only, Runnable action) {
        var button = UiButton.icon(button(Text.get(key), x, y, w, action), icon, only);
        var binding = switch (icon) {
            case PLAY -> app.toggle;
            case PREVIOUS -> app.previous;
            case NEXT -> app.next;
            default -> null;
        };
        return binding == null ? button : UiButton.shortcuts(button, binding);
    }

    private void pane(Pane next) {
        pane = next;
        rebuildWidgets();
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
        shuffle = repeat = null;
        int contextY = height < 300 ? 64 : 70;
        icon("text.darkmusic.downloads", UiIcon.DOWNLOAD, width - 180, 6, 96, false, () -> show(new DownloadScreen(app, this)));
        icon("text.darkmusic.settings", UiIcon.SETTINGS, width - 80, 6, 68, false, () -> show(new SettingsScreen(app, this)));
        if (width >= 480 && app.settings.lyricsEnabled)
            icon("lyrics.darkmusic.title", UiIcon.LYRICS, width - 208, 6, 24, true, () -> show(new LyricsScreen(app, this)));
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
            icon("text.darkmusic.open_folder", UiIcon.FOLDER, 12, contextY, 24, true, app::openFolder);
            icon("import.darkmusic.history", UiIcon.PLAYLIST, 40, contextY, 24, true, () -> show(new ImportHistoryScreen(app, this)));
            icon("text.darkmusic.refresh_library", UiIcon.REFRESH, 68, contextY, 24, true, () -> app.library.rescan());
        }
        if (mode.equals("Playlists")) {
            var manage = icon("text.darkmusic.manage_playlists", UiIcon.PLAYLIST, width - (drawer ? 132 : 160), contextY, 120, false, () -> {
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
        navigation.icons(i -> mode.equals("All") ? i == 0 ? UiIcon.MUSIC : UiIcon.FOLDER : mode.equals("Artists") ? UiIcon.ARTIST : mode.equals("Albums") ? UiIcon.ALBUM : PlaylistStore.FAVORITES.equals(groups.get(i)) ? UiIcon.HEART_FILLED : UiIcon.PLAYLIST);
        navigation.indent(i -> mode.equals("All") && i > 0 && i <= folderRows.size() ? folderRows.get(i - 1).depth() * 8 : 0);
        navigation.expanded(i -> mode.equals("All") && i > 0 && i <= folderRows.size() && folderRows.get(i - 1).expanded());
        songs = addRenderableWidget(new RowList(list.x(), list.y(), list.width(), list.height(), 36,
            Text.get("text.darkmusic.songs_enter_or_double_click_to_play"), (i, activated) -> {
            selected = visible.get(i);
            awaitingEditedTrack = false;
            if (activated && !app.mediaBusy)
                app.playback.play(collectionTracks(), selected, new PlaybackController.Source(mode, group, "", app.library.rootId()));
        }));
        songs.visible = library;
        songs.artwork(app.artwork, i -> i < visible.size() ? visible.get(i) : null);
        songs.lyrics(app.lyrics, () -> app.settings.lyricsEnabled);
        songs.playing(i -> i < visible.size() && visible.get(i).id().equals(playingId));
        songs.playingIcon(() -> playingIcon);
        songs.favorites(i -> i < visible.size() && app.playlists.favorite(visible.get(i)));
        var inspector = pane == Pane.DETAILS ? new PlayerLayout.Rect(12, 96, width - 24, layout.dock().y() - 108) : layout.inspector();
        favorite = addRenderableWidget(new FavoriteButton(inspector.x() + 6, inspector.y() + 4,
            () -> app.playlists.favorite(selected), () -> {
            if (selected != null && app.playlistsReady) app.playlists.toggleFavorite(selected);
        }));
        edit = icon("library.darkmusic.edit_metadata", UiIcon.EDIT, inspector.x() + 32, inspector.y() + 4, 22, true, () -> {
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
        sourceLink = icon("library.darkmusic.open_source", UiIcon.LINK, inspector.x() + 58, inspector.y() + 4, 22, true, () -> {
            String url = app.sourceLinks.get(selected);
            if (!url.isEmpty()) net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(url));
        });
        copySource = icon("library.darkmusic.copy_source", UiIcon.CHAIN, inspector.x() + 84, inspector.y() + 4, 22, true, () -> {
            String url = app.sourceLinks.get(selected);
            if (url.isEmpty()) return;
            minecraft.keyboardHandler.setClipboard(url);
            if (url.equals(minecraft.keyboardHandler.getClipboard())) {
                copiedUntil = System.nanoTime() + 2_000_000_000L;
                copiedTrack = selected.id();
            } else app.warn(Text.get("library.darkmusic.copy_failed"));
        });
        favorite.visible = edit.visible = sourceLink.visible = copySource.visible = selected != null && (pane == Pane.DETAILS || library && inspector.width() > 0);
        int transportY = layout.transportY();
        int x = layout.transportX();
        int sliderWidth = layout.controlWidth();
        int buttonWidth = layout.transportButtonWidth();
        UiButton.shortcuts(icon("key.darkmusic.normal_speed", UiIcon.REFRESH, x, transportY, buttonWidth, true, () -> app.speed(1)), app.normalSpeed);
        x += buttonWidth + 4;
        speed = addRenderableWidget(Help.tip(new ValueSlider(x, transportY, sliderWidth, (app.settings.speed - .5) / 2.5,
                v -> Text.get("format.darkmusic.speed", String.format(Locale.ROOT, "%.1f", app.settings.speed)),
                v -> app.speed(.5 + v * 2.5), app::save, 25),
            Help.withKeys(Text.get("text.darkmusic.playback_speed_0_5x_to_3_0x_in_0_1x_steps_faster_raises_pitch_slower_lowers_it"), app.slower, app.faster)));
        speed.plain(true);
        x += sliderWidth + 8;
        shuffle = icon("text.darkmusic.shuffle", UiIcon.SHUFFLE, x, transportY, buttonWidth, true, this::shuffle);
        x += buttonWidth + 4;
        jump = icon("player.darkmusic.jump", UiIcon.LOCATE, x, transportY, buttonWidth, true, this::jumpToPlaying);
        x += buttonWidth + 4;
        icon("text.darkmusic.previous", UiIcon.PREVIOUS, x, transportY, buttonWidth, true, () -> {
            if (!app.mediaBusy) app.playback.previous();
        });
        x += buttonWidth + 4;
        play = icon("text.darkmusic.play", UiIcon.PLAY, x, transportY - 4, buttonWidth + 8, true, this::playPause);
        play.setHeight(28);
        x += buttonWidth + 12;
        icon("text.darkmusic.next", UiIcon.NEXT, x, transportY, buttonWidth, true, () -> {
            if (!app.mediaBusy) app.playback.next();
        });
        x += buttonWidth + 4;
        icon("text.darkmusic.stop", UiIcon.STOP, x, transportY, buttonWidth, true, () -> app.playback.stop());
        x += buttonWidth + 4;
        repeat = icon("text.darkmusic.repeat", UiIcon.REPEAT, x, transportY, buttonWidth, true, this::repeat);
        x += buttonWidth + 8;
        volume = addRenderableWidget(Help.tip(new ValueSlider(x, transportY, sliderWidth, app.settings.volume,
                v -> Text.get("format.darkmusic.volume", Math.round(v * 100)), app::volume, app::save),
            Help.withKeys(Text.get("text.darkmusic.darkmusic_volume_independent_of_minecraft_sound_sliders"), app.volumeDown, app.volumeUp)));
        volume.plain(true);
        x += sliderWidth + 4;
        mute = UiButton.shortcuts(icon("key.darkmusic.mute", UiIcon.VOLUME, x, transportY, buttonWidth, true, app::toggleMute), app.mute);
        UiButton.selected(mute, () -> app.settings.volume == 0);
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
        UiButton.style(play, UiButton.Style.PRIMARY);
        if (shuffle != null) UiButton.selected(shuffle, () -> app.playback.shuffle());
        if (repeat != null) UiButton.selected(repeat, () -> app.playback.repeat() != PlaybackController.Repeat.OFF);
        refresh();
        songs.scrollOffset(songScroll);
        navigation.scrollOffset(collectionScroll);
    }

    private void refresh() {
        if (songs == null) return;
        var snapshot = app.library.snapshot();
        var tracks = snapshot.tracks();
        String root = app.library.root().toString();
        if (folderRevision != snapshot.revision() || !root.equals(folderRoot)) {
            folderRevision = snapshot.revision();
            folderRoot = root;
            folders = new FolderTree(tracks, snapshot.folders());
            expandedFolders.removeIf(path -> !folders.contains(path));
        }
        folderRows = folders.visible(expandedFolders);
        groups = switch (mode) {
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
                String path = track.relativePath().replace('\\', '/');
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                    counts.merge(path.substring(0, slash), 1, Integer::sum);
            }
        }
        navigation.rows(java.util.stream.IntStream.range(0, groups.size()).mapToObj(i -> {
            String key = groups.get(i);
            String label = mode.equals("All") ? i == 0 ? Text.get("text.darkmusic.all_tracks") : folderRows.get(i - 1).name() : mode.equals("Playlists") ? app.playlists.displayName(app.playlists.get(key)) : key;
            int count = mode.equals("Playlists") ? app.playlists.resolve(key, tracks).size() : counts.getOrDefault(key, 0);
            return new RowList.Row(label + " (" + count + ")", "");
        }).toList());
        navigation.select(groups.indexOf(group));
        refreshSongs();
    }

    private List<Track> collectionTracks() {
        var tracks = app.library.snapshot().tracks();
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
        var source = playlists ? app.playlists.resolve(group, library) : library;
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
        songs.rows(visible.stream().map(t -> new RowList.Row(t.title(), t.artist() + " | " + time(t.duration()) + " | " + t.album(), t.relativePath())).toList());
        if (selected != null && (!awaitingEditedTrack || library.stream().anyMatch(t -> t.id().equals(selected.id())))) {
            selected = visible.stream().filter(t -> t.id().equals(selected.id())).findFirst().orElse(null);
            awaitingEditedTrack = false;
        }
        if (selected == null) selected = visible.isEmpty() ? null : visible.getFirst();
        songs.select(visible.indexOf(selected));
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
        var source = app.playback.source();
        if (!source.rootId().isEmpty() && !source.rootId().equals(app.library.rootId())) {
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
        var status = app.playback.state().status();
        if (status == AudioBackend.Status.STOPPED || status == AudioBackend.Status.ERROR) {
            if (selected != null)
                app.playback.play(collectionTracks(), selected, new PlaybackController.Source(mode, group, "", app.library.rootId()));
        } else app.playback.toggle();
    }

    @Override
    public void tick() {
        if (width < 320 || height < 240) return;
        var state = app.library.snapshot();
        if (playlistRevision != app.playlists.revision() || revision != state.revision() && (!state.scanning() || System.nanoTime() - refreshed > 250_000_000L)) {
            refreshed = System.nanoTime();
            revision = state.revision();
            playlistRevision = app.playlists.revision();
            refresh();
        }
        var audio = app.playback.state();
        favorite.active = selected != null && app.playlistsReady && !app.mediaBusy;
        edit.active = selected != null && !app.mediaBusy;
        sourceLink.active = selected != null && !app.sourceLinks.get(selected).isEmpty();
        copySource.active = sourceLink.active;
        songs.active = !app.mediaBusy;
        playingId = audio.track() != null && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR ? audio.track().id() : "";
        playingIcon = audio.status() == AudioBackend.Status.PAUSED ? UiIcon.PAUSE : UiIcon.PLAY;
        jump.active = audio.track() != null;
        if (app.settings.lyricsEnabled) {
            app.lyrics.request(audio.track());
            app.lyrics.request(selected);
            songs.inspectVisibleLyrics();
        }
        volume.sync(app.settings.volume);
        speed.sync((app.settings.speed - .5) / 2.5);
        play.active = !app.mediaBusy;
        progress.active = !app.mediaBusy && audio.track() != null && audio.duration() > 0 && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.sync(audio.duration() > 0 ? audio.position() / audio.duration() : 0);
        play.setMessage(Component.literal(audio.status() == AudioBackend.Status.PLAYING ? Text.get("text.darkmusic.pause") : audio.status() == AudioBackend.Status.PAUSED ? Text.get("text.darkmusic.resume") : Text.get("text.darkmusic.play")));
        UiButton.icon(play, audio.status() == AudioBackend.Status.PLAYING ? UiIcon.PAUSE : UiIcon.PLAY, true);
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
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 256 && pane != Pane.LIBRARY) {
            pane(Pane.LIBRARY);
            return true;
        }
        return super.keyPressed(event);
    }

    private void details(GuiGraphicsExtractor g, PlayerLayout.Rect area, boolean standalone) {
        UiTheme.panel(g, area.x(), area.y(), area.width(), area.height());
        if (selected != null && copiedTrack.equals(selected.id()) && System.nanoTime() < copiedUntil)
            g.text(font, font.plainSubstrByWidth(Text.get("library.darkmusic.copied"), Math.max(1, area.right() - copySource.getRight() - 12)),
                copySource.getRight() + 6, copySource.getY() + 6, UiTheme.SUCCESS);
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
            longText.draw(g, font, "selected_lyrics", info.mark() + " " + Text.get(info.key()), x + 15, y + 64, textWidth - 15, info.color());
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
        favorite.visible = edit.visible = sourceLink.visible = copySource.visible = selected != null && (pane == Pane.DETAILS || library && layout.inspector().width() > 0);
        if (library) {
            String collection = mode.equals("All") ? group.isEmpty() ? Text.get("text.darkmusic.all_tracks") : group : mode.equals("Playlists") && app.playlists.get(group) != null ? app.playlists.displayName(app.playlists.get(group)) : group;

            longText.draw(g, font, "collection_title", collection + " (" + visible.size() + ")", layout.tracks().x() + (layout.singleColumn() ? 116 : 0), height < 300 ? 70 : 76,
                Math.max(1, layout.tracks().width() - (layout.singleColumn() ? 150 : mode.equals("Playlists") ? 150 : 32)), UiTheme.ACCENT);
            if (layout.inspector().width() > 0) {
                details(g, layout.inspector(), false);
                if (app.settings.hudVisualizer) {
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
        importOverlay.render(app, g, width, height);
    }
}
