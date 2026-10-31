package com.darkmusic.client.gui;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * What the player's library browser shows: the view mode (All, Playlists, Artists, Albums, History), the collection
 * chosen in it, the search, the songs that match and the selected one. MusicScreen draws it and hands it the
 * player's input; it holds no widgets. Read by the client thread only.
 */
public final class PlayerBrowser {
    /**
     * What the browser reads: the library, playlists and listening history, and the songs of a collection.
     */
    public interface Sources {
        MusicLibrary library();

        PlaylistStore playlists();

        boolean playlistsReady();

        ListeningHistory listeningHistory();

        List<Track> collection(String mode, String group);
    }

    /**
     * The song list: how many rows, and each row.
     */
    record SongRows(int size, IntFunction<RowList.Row> row) {
    }

    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final Sources app;
    private final Set<String> expandedFolders = new HashSet<>();
    private final Map<String, String> selectedGroups = new HashMap<>();
    private List<Track> visible = List.of();
    private List<String> groups = List.of();
    private List<FolderTree.Entry> folderRows = List.of();
    private FolderTree folders = new FolderTree(List.of());
    private long folderRevision = -1;
    private String folderRoot = "";
    private String mode = "All", group = "", query = "";
    private Track selected;
    private boolean awaitingEditedTrack;
    private String playlistNotice = "";

    PlayerBrowser(Sources app) {
        this.app = app;
    }

    /**
     * The view mode: "All", "Playlists", "Artists", "Albums" or "History".
     */
    public String mode() {
        return mode;
    }

    /**
     * The collection chosen in the mode: a folder ("" for all songs), playlist id, artist, album or history list.
     */
    public String group() {
        return group;
    }

    /**
     * The collections the mode lists, in order.
     */
    public List<String> groups() {
        return groups;
    }

    /**
     * The songs in the collection that match the search.
     */
    public List<Track> visible() {
        return visible;
    }

    public Track selected() {
        return selected;
    }

    /**
     * Why a playlist shows fewer songs than it lists, or "".
     */
    public String playlistNotice() {
        return playlistNotice;
    }

    String query() {
        return query;
    }

    void query(String text) {
        query = text;
        refreshSongs();
    }

    /**
     * Switches the view mode, returning to the collection last chosen in it.
     */
    void mode(String next) {
        awaitingEditedTrack = false;
        selectedGroups.put(mode, group);
        mode = next;
        group = selectedGroups.getOrDefault(next, "");
    }

    /**
     * The collections list's row i was chosen; true when it is a folder whose expansion toggled instead.
     */
    boolean chooseGroup(int i, boolean activated) {
        awaitingEditedTrack = false;
        group = groups.get(i);
        if (activated && expandable(i)) {
            toggleFolder(i);
            return true;
        }
        refreshSongs();
        return false;
    }

    void select(int i) {
        selected = visible.get(i);
        awaitingEditedTrack = false;
    }

    /**
     * The metadata editor replaced the selected song; keep it selected until the library rescans it.
     */
    void edited(Track before, Track updated) {
        if (selected != null && selected.id().equals(before.id())) {
            selected = updated;
            awaitingEditedTrack = true;
        }
    }

    List<Track> collectionTracks() {
        return app.collection(mode, group);
    }

    PlaybackController.Source source() {
        return new PlaybackController.Source(mode, group, "", "");
    }

    /**
     * Rebuilds the folder tree when the library changed and recomputes the collections and songs.
     */
    void refresh() {
        var snapshot = app.library().snapshot();
        var tracks = snapshot.tracks();
        String root = app.library().root().toString();
        if (folderRevision != snapshot.revision() || !root.equals(folderRoot)) {
            folderRevision = snapshot.revision();
            folderRoot = root;
            folders = new FolderTree(tracks, snapshot.folders(), app.library().roots());
            expandedFolders.removeIf(path -> !folders.contains(path));
        }
        folderRows = folders.visible(expandedFolders);
        groups = switch (mode) {
            case "History" -> List.of("recent", "most", "added", "never");
            case "Playlists" -> app.playlists().all().stream().map(p -> p.id()).toList();
            case "Artists" ->
                tracks.stream().map(Track::artist).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "Albums" ->
                tracks.stream().map(Track::album).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            default -> Stream.concat(Stream.of(""), folderRows.stream().map(FolderTree.Entry::path)).toList();
        };
        if (!mode.equals("All") && !groups.contains(group)) group = groups.isEmpty() ? "" : groups.getFirst();
        if (mode.equals("All") && !folders.contains(group)) group = "";
        refreshSongs();
    }

    /**
     * The collections list's rows, each with its song count.
     */
    List<RowList.Row> groupRows() {
        var tracks = app.library().snapshot().tracks();
        var counts = new HashMap<String, Integer>();
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
        return IntStream.range(0, groups.size()).mapToObj(i -> {
            String key = groups.get(i);
            String label = mode.equals("All") ? i == 0 ? Text.get("text.darkmusic.all_tracks") : folderRows.get(i - 1).name() : mode.equals("History") ? Text.get("history." + key) : mode.equals("Playlists") ? app.playlists().displayName(app.playlists().get(key)) : named(key);
            int count = mode.equals("History") ? app.collection("History", key).size() : mode.equals("Playlists") ? app.playlists().resolve(key, tracks).size() : counts.getOrDefault(key, 0);
            return new RowList.Row(label + " (" + count + ")", "");
        }).toList();
    }

    String groupsEmptyMessage() {
        return mode.equals("Playlists") ? (app.playlistsReady() ? Text.get("text.darkmusic.no_playlists") : Text.get("text.darkmusic.loading")) : Text.get("text.darkmusic.no_entries");
    }

    /**
     * Recomputes the songs that match, the playlist notice and the selection.
     */
    void refreshSongs() {
        String needle = query.toLowerCase(Locale.ROOT).strip();
        var library = app.library().snapshot().tracks();
        boolean playlists = mode.equals("Playlists");
        var source = songSource(library);
        playlistNotice = "";
        if (playlists) {
            var playlist = app.playlists().get(group);
            if (!app.playlistsReady()) playlistNotice = Text.get("text.darkmusic.playlists_are_still_loading");
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
        if (selected != null && (!awaitingEditedTrack || app.playlists().index(library).containsKey(selected.id()))) {
            selected = visible.stream().filter(t -> t.id().equals(selected.id())).findFirst().orElse(null);
            awaitingEditedTrack = false;
        }
        if (selected == null) selected = visible.isEmpty() ? null : visible.getFirst();
    }

    private List<Track> songSource(List<Track> library) {
        return mode.equals("History") ? collectionTracks() : mode.equals("Playlists") ? app.playlists().resolve(group, library) : library;
    }

    String songsEmptyMessage() {
        var library = app.library().snapshot().tracks();
        return library.isEmpty() ? Text.get("ui.darkmusic.library_empty") : mode.equals("Playlists") && songSource(library).isEmpty() ? Text.get("text.darkmusic.no_playable_songs") : Text.get("text.darkmusic.no_matching_songs");
    }

    SongRows songRows() {
        var stats = mode.equals("History") ? app.listeningHistory().snapshot() : Map.<String, ListeningHistory.Entry>of();
        var displayed = visible;
        boolean history = mode.equals("History");
        return new SongRows(displayed.size(), i -> {
            var t = displayed.get(i);
            return new RowList.Row(t.title(), history ? historyDetails(t, stats) : (t.artist().isBlank() ? Text.get("library.artist.unknown") : t.artist()) + " | " + MusicScreen.time(t.duration()) + " | " + t.album(), t.relativePath());
        });
    }

    private String historyDetails(Track track, Map<String, ListeningHistory.Entry> values) {
        var stats = values.get(track.id());
        if (stats == null) return "";
        String date = HISTORY_DATE.format(Instant.ofEpochMilli(stats.lastPlayed()));
        return Text.get("history.stats", stats.plays(), (long) (stats.seconds() / 60), date);
    }

    /**
     * Whether collections row i is a folder with subfolders, which opens and closes in place.
     */
    boolean expandable(int i) {
        return mode.equals("All") && i > 0 && i <= folderRows.size() && folderRows.get(i - 1).children();
    }

    /**
     * Collections row i's disclosure position, or -1 when it has none.
     */
    int disclosure(int i) {
        return expandable(i) ? 5 + folderRows.get(i - 1).depth() * 8 : -1;
    }

    int indent(int i) {
        return mode.equals("All") && i > 0 && i <= folderRows.size() ? folderRows.get(i - 1).depth() * 8 : 0;
    }

    boolean expanded(int i) {
        return mode.equals("All") && i > 0 && i <= folderRows.size() && folderRows.get(i - 1).expanded();
    }

    void toggleFolder(int index) {
        if (!expandable(index)) return;
        var folder = folderRows.get(index - 1);
        if (!expandedFolders.remove(folder.path())) expandedFolders.add(folder.path());
        refresh();
    }

    /**
     * An artist or album as shown; songs without that tag are grouped under a blank key.
     */
    String named(String key) {
        return key.isBlank() ? Text.get(mode.equals("Artists") ? "library.artist.unknown" : "library.album.unknown") : key;
    }

    /**
     * The collection's name, as the song list's heading.
     */
    String title() {
        return mode.equals("History") ? Text.get("history." + group) : mode.equals("All") ? group.isEmpty() ? Text.get("text.darkmusic.all_tracks") : folders.display(group) : mode.equals("Playlists") ? app.playlists().get(group) != null ? app.playlists().displayName(app.playlists().get(group)) : group : named(group);
    }

    /**
     * Shows the source's collection with an empty search and its folders open. Returns the warning key when the
     * source is outside the library or its playlist is gone, or "" when it can be shown.
     */
    String reveal(PlaybackController.Source source) {
        if (!source.rootId().isEmpty() && app.library().directory(source.rootId()) == null)
            return "player.darkmusic.outside_library";
        if (source.mode().equals("Playlists") && app.playlists().get(source.group()) == null)
            return "player.darkmusic.source_missing";
        selectedGroups.put(mode, group);
        mode = source.mode();
        group = source.group();
        if (mode.equals("All") && !group.isEmpty() && !group.startsWith("@") && !source.rootId().isEmpty())
            group = "@" + source.rootId() + "/" + group;
        query = "";
        if (mode.equals("All")) {
            for (int slash = group.indexOf('/'); slash >= 0; slash = group.indexOf('/', slash + 1))
                expandedFolders.add(group.substring(0, slash));
        }
        return "";
    }

    /**
     * Selects the song with this id when it is listed; its index, or -1.
     */
    int selectListed(String id) {
        for (int i = 0; i < visible.size(); i++)
            if (visible.get(i).id().equals(id)) {
                selected = visible.get(i);
                return i;
            }
        return -1;
    }

    /**
     * Whether the selected song is the one playing, from this collection.
     */
    boolean selectionIsPlaying(Track current, PlaybackController.Source source) {
        return selected != null && current != null && selected.id().equals(current.id())
            && mode.equals(source.mode()) && group.equals(source.group())
            && (source.rootId().isEmpty() || app.library().directory(source.rootId()) != null);
    }

    /**
     * Where dropped files go: the open folder in All, and the open playlist.
     */
    String dropFolder() {
        return mode.equals("All") ? group : "";
    }

    String dropPlaylist() {
        return mode.equals("Playlists") && app.playlists().get(group) != null ? group : "";
    }

    /**
     * The player follows the playing song only while no search is typed or being typed.
     */
    static boolean canFollowPlayback(String query, boolean editingText) {
        return query.isEmpty() && !editingText;
    }
}
