package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MusicScreen extends LocalScreen {
    private final DarkMusicClient app;
    private final Screen parent;
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private EditBox search;
    private RowList navigation, songs;
    private List<Track> visible = List.of();
    private List<String> groups = List.of();
    private String mode = "All", group = "", query = "";
    private Track selected;
    private long revision = -1, playlistRevision = -1;
    private String playlistNotice = "";
    private Button play, shuffle, repeat;
    private ValueSlider progress;
    private ValueSlider speed, volume;

    public MusicScreen(DarkMusicClient app, Screen parent) {
        super(Component.literal("DarkMusic"), parent);
        this.app = app;
        this.parent = parent;
    }

    public static String time(double seconds) {
        long value = (long) Math.max(0, Double.isFinite(seconds) ? seconds : 0);
        return "%d:%02d".formatted(value / 60, value % 60);
    }

    public static String label(String constant) {
        return constant.charAt(0) + constant.substring(1).toLowerCase(Locale.ROOT);
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        Help.tip(button("Settings", width - 88, 6, 76, () -> show(new SettingsScreen(app, this))), "Music folder, autoplay, background music, and HUD layout. Assign shortcuts in Options > Controls > Key Binds.");
        Help.tip(button("Manage Playlists", width - 216, 6, 124, () -> {
            if (app.playlistsReady) show(new PlaylistScreen(app, this));
            else app.warn("Playlists are still loading.");
        }), "Create and edit playlists. To listen, use the Playlists view below. Escape closes the player.");
        boolean compact = height < 320;
        search = addRenderableWidget(new EditBox(font, 12, 32, compact ? width - 144 : width - 24, 20, Component.literal("Search Songs")));
        search.setMaxLength(512);
        search.setHint(Component.literal("Search title, artist, album or path"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            refresh();
        });
        String[] modes = {"All", "Playlists", "Artists", "Albums"};
        int tab = (width - 24) / modes.length;
        if (compact) button("View: " + mode, width - 126, 32, 114, () -> {
            mode = modes[(Arrays.asList(modes).indexOf(mode) + 1) % modes.length];
            group = "";
            rebuildWidgets();
        });
        else for (int i = 0; i < modes.length; i++) {
            String next = modes[i];
            button(next, 12 + i * tab, 56, tab - 3, () -> {
                mode = next;
                group = "";
                refresh();
            });
        }
        int navWidth = width >= 480 ? 120 : 86;
        int listY = compact ? 58 : 82;
        boolean narrow = width < 640;
        int extra = narrow ? 24 : 0;
        int listHeight = Math.max(28, height - 144 - extra - listY);
        navigation = addRenderableWidget(new RowList(12, listY, navWidth, listHeight, 22, "Library navigation", (i, activated) -> {
            group = groups.get(i);
            refreshSongs();
        }));
        int art = width >= 640 ? 106 : 0;
        songs = addRenderableWidget(new RowList(18 + navWidth, listY, width - 30 - navWidth - art, listHeight, compact ? 28 : 32, "Songs; Enter or double click to play", (i, activated) -> {
            selected = visible.get(i);
            if (activated) app.playback.play(visible, selected);
        }));
        progress = addRenderableWidget(Help.tip(new ValueSlider(12, height - 102 - extra, width - 24, 0,
            v -> time(app.playback.state().position()) + " / " + time(app.playback.state().duration()),
            v -> app.playback.seek(v * app.playback.state().duration()), () -> {
        }), "Seek in the original song's timeline. Compressed files may take a moment to seek."));
        int gap = 4, x = 12, control = (width - 24 - gap * (narrow ? 2 : 5)) / (narrow ? 3 : 6), y = height - 78 - extra;
        Help.tip(button("Previous", x, y, control, () -> app.playback.previous()), "Previous song in playback history, or restart the current song.");
        x += control + gap;
        play = Help.tip(button("Play", x, y, control, this::playPause), "Start the selected song when stopped; otherwise pause or resume current playback.");
        x += control + gap;
        Help.tip(button("Next", x, y, control, () -> app.playback.next()), "Next song in the playback queue.");
        x += control + gap;
        if (narrow) {
            x = 12;
            y += 24;
        }
        Help.tip(button("Stop", x, y, control, () -> app.playback.stop()), "Stop playback and reset its position.");
        x += control + gap;
        shuffle = Help.tip(button("Shuffle", x, y, control, () -> {
            app.settings.shuffle = !app.playback.shuffle();
            app.playback.shuffle(app.settings.shuffle);
            app.save();
        }), "Shuffle: play the queue in randomized order. Off restores its normal order.");
        x += control + gap;
        repeat = Help.tip(button("Repeat", x, y, width - 12 - x, () -> {
            var values = PlaybackController.Repeat.values();
            var next = values[(app.playback.repeat().ordinal() + 1) % values.length];
            app.settings.repeat = next.name();
            app.playback.repeat(next);
            app.save();
        }), "Repeat OFF stops at the queue's end; ONE repeats the current song; ALL repeats the queue. Click to cycle.");
        int half = (width - 28) / 2;
        volume = addRenderableWidget(Help.tip(new ValueSlider(12, height - 54, half, app.settings.volume,
            v -> "Volume " + Math.round(v * 100) + "%", v -> {
            app.settings.volume = v;
            app.playback.volume(v);
        }, app::save), "DarkMusic volume, independent of Minecraft sound sliders."));
        speed = addRenderableWidget(Help.tip(new ValueSlider(16 + half, height - 54, half - 44, (app.settings.speed - .5) / 2.5,
            v -> String.format(Locale.ROOT, "Speed %.1fx", app.settings.speed), v -> app.speed(.5 + v * 2.5), app::save, 25), "Playback speed: 0.5x to 3.0x in 0.1x steps. Faster raises pitch; slower lowers it."));
        Help.tip(button("1.0x", width - 52, height - 54, 40, () -> {
            app.speed(1);
            speed.syncNow((1 - .5) / 2.5);
        }), "Reset playback speed to normal.");
        int third = (width - 32) / 3;
        Help.tip(button("Open Folder", 12, height - 30, third, app::openFolder), "Open the selected music folder in your file manager.");
        Help.tip(button("Downloads", 16 + third, height - 30, third, () -> show(new DownloadScreen(app, this))), "Paste a video/collection URL, choose entries, and queue MP3 downloads into your music folder.");
        Help.tip(button("Refresh Library", 20 + 2 * third, height - 30, width - 32 - 2 * third, () -> app.library.rescan()), "Songs are discovered automatically. Refresh now if changes were missed or a drive was reconnected.");
        refresh();
    }

    private void refresh() {
        if (songs == null) return;
        var tracks = app.library.snapshot().tracks();
        groups = switch (mode) {
            case "Playlists" -> app.playlists.all().stream().map(p -> p.id()).toList();
            case "Artists" ->
                tracks.stream().map(Track::artist).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            case "Albums" ->
                tracks.stream().map(Track::album).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            default -> List.of("All Tracks");
        };
        if (mode.equals("Playlists") && !groups.contains(group)) group = groups.isEmpty() ? "" : groups.getFirst();
        navigation.emptyMessage(mode.equals("Playlists") ? (app.playlistsReady ? "No Playlists" : "Loading...") : "No Entries");
        navigation.rows(groups.stream().map(s -> new RowList.Row(mode.equals("Playlists") ? app.playlists.get(s).name() : s, "")).toList());
        navigation.select(groups.indexOf(group));
        refreshSongs();
    }

    private void refreshSongs() {
        String needle = query.toLowerCase(Locale.ROOT).strip();
        var library = app.library.snapshot().tracks();
        boolean playlists = mode.equals("Playlists");
        var source = playlists ? app.playlists.resolve(group, library) : library;
        playlistNotice = "";
        if (playlists) {
            var playlist = app.playlists.get(group);
            if (!app.playlistsReady) playlistNotice = "Playlists are still loading.";
            else if (playlist == null) playlistNotice = "Create a playlist with Manage Playlists.";
            else if (playlist.entries().isEmpty())
                playlistNotice = "This playlist is empty. Add Songs in Manage Playlists.";
            else if (source.size() < playlist.entries().size())
                playlistNotice = (playlist.entries().size() - source.size()) + " unavailable song(s); select missing entries in Manage Playlists.";
        }
        visible = source.stream().filter(t -> t.matches(needle)).filter(t -> group.isEmpty() || switch (mode) {
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
        songs.emptyMessage(playlists && source.isEmpty() ? "No Playable Songs" : "No Matching Songs");
        songs.rows(visible.stream().map(t -> new RowList.Row(t.title(), t.artist() + " | " + time(t.duration()) + " | " + t.relativePath())).toList());
        if (selected == null || !visible.contains(selected)) selected = visible.isEmpty() ? null : visible.getFirst();
        songs.select(visible.indexOf(selected));
    }

    private void playPause() {
        var status = app.playback.state().status();
        if (status == AudioBackend.Status.STOPPED || status == AudioBackend.Status.ERROR) {
            if (selected != null) app.playback.play(visible, selected);
        } else app.playback.toggle();
    }

    @Override
    public void tick() {
        var state = app.library.snapshot();
        if (revision != state.revision() || playlistRevision != app.playlists.revision()) {
            revision = state.revision();
            playlistRevision = app.playlists.revision();
            refresh();
        }
        var audio = app.playback.state();
        volume.sync(app.settings.volume);
        speed.sync((app.settings.speed - .5) / 2.5);
        progress.active = audio.track() != null && audio.duration() > 0 && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.sync(audio.duration() > 0 ? audio.position() / audio.duration() : 0);
        play.setMessage(Component.literal(audio.status() == AudioBackend.Status.PLAYING ? "Pause" : audio.status() == AudioBackend.Status.PAUSED ? "Resume" : "Play"));
        shuffle.setMessage(Component.literal(app.playback.shuffle() ? "Shuffle: On" : "Shuffle: Off"));
        repeat.setMessage(Component.literal("Repeat: " + label(app.playback.repeat().name())));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "DarkMusic", 12, 12, 0xFFB5B0FF);
        var audio = app.playback.state();
        int extra = width < 640 ? 24 : 0;
        String status = label(audio.status().name()) + (audio.track() == null ? "" : "  |  " + audio.track().title() + " - " + audio.track().artist());
        Track now = audio.track() != null ? audio.track() : selected;
        var thumbnail = app.artwork.get(now);
        g.fill(12, height - 123 - extra, 28, height - 107 - extra, 0xFF292F44);
        if (thumbnail != null)
            g.blit(RenderPipelines.GUI_TEXTURED, thumbnail, 12, height - 123 - extra, 0, 0, 16, 16, 256, 256, 256, 256);
        int spectrumY = (height < 320 ? 58 : 82) + 104;
        int spectrumHeight = Math.min(app.settings.visualizerHeight, height - 144 - spectrumY);
        boolean sidebarSpectrum = width >= 640 && spectrumHeight >= 16;
        g.text(font, font.plainSubstrByWidth(status, width - 46 - (app.settings.playerVisualizer && !sidebarSpectrum ? 108 : 0)), 34, height - 118 - extra, 0xFFEEEEFF);
        if (app.settings.playerVisualizer)
            spectrum.render(app, g, width - 108, sidebarSpectrum ? spectrumY : height - 123 - extra, 96, sidebarSpectrum ? spectrumHeight : 16);
        String message = !app.warning.isBlank() ? app.warning : !app.library.snapshot().message().isBlank() ? app.library.snapshot().message() : audio.status() == AudioBackend.Status.ERROR ? audio.error() : playlistNotice;
        if (!message.isBlank())
            g.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 138 - extra, 0xFFFFBC80);
        if (width >= 640 && selected != null) {
            int x = width - 108, y = height < 320 ? 58 : 82, size = Math.min(96, Math.max(16, height - 144 - y));
            var art = app.artwork.get(selected);
            g.fill(x, y, x + size, y + size, 0xFF292F44);
            if (art != null) g.blit(RenderPipelines.GUI_TEXTURED, art, x, y, 0, 0, size, size, 256, 256, 256, 256);
            else g.centeredText(font, "Music", x + size / 2, y + size / 2 - 4, 0xFFB5B0FF);
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
