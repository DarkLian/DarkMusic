package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.library.Track;
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
    private final Screen parent;
    private final SpectrumRenderer spectrum = new SpectrumRenderer();
    private EditBox search;
    private RowList navigation, songs;
    private List<Track> visible = List.of();
    private List<String> groups = List.of();
    private String mode = "All", group = "", query = "";
    private Track selected, seekTrack;
    private boolean seekDragging;
    private long revision = -1, playlistRevision = -1, refreshed;
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
        return Text.get("state.darkmusic." + constant.toLowerCase(Locale.ROOT));
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        Help.tip(button(Text.get("text.darkmusic.settings"), width - 88, 6, 76, () -> show(new SettingsScreen(app, this))), Text.get("text.darkmusic.music_folder_autoplay_background_music_and_hud_layout_assign_shortcuts_in_options"));
        Help.tip(button(Text.get("text.darkmusic.manage_playlists"), width - 216, 6, 124, () -> {
            if (app.playlistsReady) show(new PlaylistScreen(app, this));
            else app.warn(Text.get("text.darkmusic.playlists_are_still_loading"));
        }), Text.get("text.darkmusic.create_and_edit_playlists_to_listen_use_the_playlists_view_below_escape_closes_the"));
        boolean compact = height < 320;
        search = addRenderableWidget(new EditBox(font, 12, 32, compact ? width - 144 : width - 24, 20, Text.component("text.darkmusic.search_songs")));
        search.setMaxLength(512);
        search.setHint(Text.component("text.darkmusic.search_title_artist_album_or_path"));
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            refresh();
        });
        String[] modes = {"All", "Playlists", "Artists", "Albums"};
        int tab = (width - 24) / modes.length;
        if (compact)
            button(Text.get("format.darkmusic.view", Text.get("view.darkmusic." + mode.toLowerCase(Locale.ROOT))), width - 126, 32, 114, () -> {
                mode = modes[(Arrays.asList(modes).indexOf(mode) + 1) % modes.length];
                group = "";
                rebuildWidgets();
            });
        else for (int i = 0; i < modes.length; i++) {
            String next = modes[i];
            button(Text.get("view.darkmusic." + next.toLowerCase(Locale.ROOT)), 12 + i * tab, 56, tab - 3, () -> {
                mode = next;
                group = "";
                refresh();
            });
        }
        var layout = PlayerLayout.of(width, height);
        int navWidth = layout.navigationWidth();
        int listY = compact ? 58 : 82;
        boolean narrow = width < 640;
        int extra = narrow ? 24 : 0;
        int listHeight = Math.max(28, height - 144 - extra - listY);
        navigation = addRenderableWidget(new RowList(12, listY, navWidth, listHeight, 22, Text.get("text.darkmusic.library_navigation"), (i, activated) -> {
            group = groups.get(i);
            refreshSongs();
        }));
        int art = layout.panelWidth() == 0 ? 0 : layout.panelWidth() + 8;
        songs = addRenderableWidget(new RowList(18 + navWidth, listY, width - 30 - navWidth - art, listHeight, compact ? 28 : 32, Text.get("text.darkmusic.songs_enter_or_double_click_to_play"), (i, activated) -> {
            selected = visible.get(i);
            if (activated) app.playback.play(visible, selected);
        }));
        progress = addRenderableWidget(Help.tip(new ValueSlider(12, height - 102 - extra, width - 24, 0,
            v -> time(progress != null && progress.editing() ? v * app.playback.state().duration() : app.playback.state().position()) + " / " + time(app.playback.state().duration()),
            v -> {
                if (!seekDragging) seekTrack = app.playback.state().track();
                seekDragging = progress != null && progress.editing();
            }, this::commitSeek), Text.get("text.darkmusic.seek_in_the_original_song_s_timeline_compressed_files_may_take_a_moment_to_seek")));
        int gap = 4, x = 12, control = (width - 24 - gap * (narrow ? 2 : 5)) / (narrow ? 3 : 6), y = height - 78 - extra;
        Help.tip(button(Text.get("text.darkmusic.previous"), x, y, control, () -> app.playback.previous()), Text.get("text.darkmusic.previous_song_in_playback_history_or_restart_the_current_song"));
        x += control + gap;
        play = Help.tip(button(Text.get("text.darkmusic.play"), x, y, control, this::playPause), Text.get("text.darkmusic.start_the_selected_song_when_stopped_otherwise_pause_or_resume_current_playback"));
        x += control + gap;
        Help.tip(button(Text.get("text.darkmusic.next"), x, y, control, () -> app.playback.next()), Text.get("text.darkmusic.next_song_in_the_playback_queue"));
        x += control + gap;
        if (narrow) {
            x = 12;
            y += 24;
        }
        Help.tip(button(Text.get("text.darkmusic.stop"), x, y, control, () -> app.playback.stop()), Text.get("text.darkmusic.stop_playback_and_reset_its_position"));
        x += control + gap;
        shuffle = Help.tip(button(Text.get("text.darkmusic.shuffle"), x, y, control, () -> {
            app.settings.shuffle = !app.playback.shuffle();
            app.playback.shuffle(app.settings.shuffle);
            app.save();
        }), Text.get("text.darkmusic.shuffle_play_the_queue_in_randomized_order_off_restores_its_normal_order"));
        x += control + gap;
        repeat = Help.tip(button(Text.get("text.darkmusic.repeat"), x, y, width - 12 - x, () -> {
            var values = PlaybackController.Repeat.values();
            var next = values[(app.playback.repeat().ordinal() + 1) % values.length];
            app.settings.repeat = next;
            app.playback.repeat(next);
            app.save();
        }), Text.get("text.darkmusic.repeat_off_stops_at_the_queue_s_end_one_repeats_the_current_song_all_repeats_the_q"));
        int half = (width - 28) / 2;
        volume = addRenderableWidget(Help.tip(new ValueSlider(12, height - 54, half, app.settings.volume,
            v -> Text.get("format.darkmusic.volume", Math.round(v * 100)), v -> {
            app.volume(v);
        }, app::save), Text.get("text.darkmusic.darkmusic_volume_independent_of_minecraft_sound_sliders")));
        speed = addRenderableWidget(Help.tip(new ValueSlider(16 + half, height - 54, half - 44, (app.settings.speed - .5) / 2.5,
            v -> Text.get("format.darkmusic.speed", String.format(Locale.ROOT, "%.1f", app.settings.speed)), v -> app.speed(.5 + v * 2.5), app::save, 25), Text.get("text.darkmusic.playback_speed_0_5x_to_3_0x_in_0_1x_steps_faster_raises_pitch_slower_lowers_it")));
        Help.tip(button("1.0x", width - 52, height - 54, 40, () -> {
            app.speed(1);
            speed.syncNow((1 - .5) / 2.5);
        }), Text.get("text.darkmusic.reset_playback_speed_to_normal"));
        int third = (width - 32) / 3;
        Help.tip(button(Text.get("text.darkmusic.open_folder"), 12, height - 30, third, app::openFolder), Text.get("text.darkmusic.open_the_selected_music_folder_in_your_file_manager"));
        Help.tip(button(Text.get("text.darkmusic.downloads"), 16 + third, height - 30, third, () -> show(new DownloadScreen(app, this))), Text.get("text.darkmusic.paste_a_video_collection_url_choose_entries_and_queue_mp3_downloads_into_your_musi"));
        Help.tip(button(Text.get("text.darkmusic.refresh_library"), 20 + 2 * third, height - 30, width - 32 - 2 * third, () -> app.library.rescan()), Text.get("text.darkmusic.songs_are_discovered_automatically_refresh_now_if_changes_were_missed_or_a_drive_w"));
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
            default -> List.of(Text.get("text.darkmusic.all_tracks"));
        };
        if (mode.equals("Playlists") && !groups.contains(group)) group = groups.isEmpty() ? "" : groups.getFirst();
        navigation.emptyMessage(mode.equals("Playlists") ? (app.playlistsReady ? Text.get("text.darkmusic.no_playlists") : Text.get("text.darkmusic.loading")) : Text.get("text.darkmusic.no_entries"));
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
            if (!app.playlistsReady) playlistNotice = Text.get("text.darkmusic.playlists_are_still_loading");
            else if (playlist == null)
                playlistNotice = Text.get("text.darkmusic.create_a_playlist_with_manage_playlists");
            else if (playlist.entries().isEmpty())
                playlistNotice = Text.get("text.darkmusic.this_playlist_is_empty_add_songs_in_manage_playlists");
            else if (source.size() < playlist.entries().size())
                playlistNotice = Text.get("format.darkmusic.missing_entries", playlist.entries().size() - source.size());
        }
        visible = source.stream().filter(t -> t.matches(needle)).filter(t -> group.isEmpty() || switch (mode) {
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
        songs.emptyMessage(playlists && source.isEmpty() ? Text.get("text.darkmusic.no_playable_songs") : Text.get("text.darkmusic.no_matching_songs"));
        songs.rows(visible.stream().map(t -> new RowList.Row(t.title(), t.artist() + " | " + time(t.duration()) + " | " + t.relativePath())).toList());
        if (selected == null || !visible.contains(selected)) selected = visible.isEmpty() ? null : visible.getFirst();
        songs.select(visible.indexOf(selected));
    }

    private void commitSeek() {
        var state = app.playback.state();
        if (seekTrack != null && state.track() != null && seekTrack.id().equals(state.track().id()) && progress.active)
            app.playback.seek(progress.fraction() * state.duration());
        seekDragging = false;
        seekTrack = null;
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
        if (playlistRevision != app.playlists.revision() || revision != state.revision() && (!state.scanning() || System.nanoTime() - refreshed > 250_000_000L)) {
            refreshed = System.nanoTime();
            revision = state.revision();
            playlistRevision = app.playlists.revision();
            refresh();
        }
        var audio = app.playback.state();
        volume.sync(app.settings.volume);
        speed.sync((app.settings.speed - .5) / 2.5);
        progress.active = audio.track() != null && audio.duration() > 0 && audio.status() != AudioBackend.Status.STOPPED && audio.status() != AudioBackend.Status.ERROR;
        progress.sync(audio.duration() > 0 ? audio.position() / audio.duration() : 0);
        play.setMessage(Component.literal(audio.status() == AudioBackend.Status.PLAYING ? Text.get("text.darkmusic.pause") : audio.status() == AudioBackend.Status.PAUSED ? Text.get("text.darkmusic.resume") : Text.get("text.darkmusic.play")));
        shuffle.setMessage(Component.literal(app.playback.shuffle() ? Text.get("text.darkmusic.shuffle_on") : Text.get("text.darkmusic.shuffle_off")));
        repeat.setMessage(Text.component("format.darkmusic.repeat", label(app.playback.repeat().name())));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        longText.begin();
        g.fill(0, 0, width, height, 0xFF111420);
        g.text(font, "DarkMusic", 12, 12, 0xFFB5B0FF);
        var audio = app.playback.state();
        int extra = width < 640 ? 24 : 0;
        String status = label(audio.status().name()) + (audio.track() == null ? "" : "  |  " + audio.track().title() + " - " + audio.track().artist());
        Track now = audio.track() != null ? audio.track() : selected;
        var thumbnail = app.artwork.get(now);
        g.fill(12, height - 123 - extra, 28, height - 107 - extra, 0xFF292F44);
        if (thumbnail != null)
            com.darkmusic.client.library.ArtworkCache.draw(g, thumbnail, 12, height - 123 - extra, 16);
        var layout = PlayerLayout.of(width, height);
        boolean sidebarSpectrum = layout.panelWidth() > 0 && layout.spectrumY() + layout.spectrumHeight() <= layout.listY() + layout.listHeight();
        longText.draw(g, font, "status", status, 34, height - 118 - extra, width - 46 - (app.settings.hudVisualizer && !sidebarSpectrum ? 108 : 0), 0xFFEEEEFF);
        if (app.settings.hudVisualizer)
            spectrum.render(app, g, sidebarSpectrum ? layout.panelX() : width - 108, sidebarSpectrum ? layout.spectrumY() : height - 123 - extra, sidebarSpectrum ? layout.panelWidth() : 96, sidebarSpectrum ? layout.spectrumHeight() : 16);
        String message = !app.warning.isBlank() ? app.warning : !app.library.snapshot().message().isBlank() ? app.library.snapshot().message() : audio.status() == AudioBackend.Status.ERROR ? audio.error() : playlistNotice;
        if (!message.isBlank())
            longText.draw(g, font, "message", message, 12, height - 138 - extra, width - 24, 0xFFFFBC80);
        if (width >= 640 && selected != null) {
            int size = layout.artworkSize(), x = layout.panelX() + (layout.panelWidth() - size) / 2, y = layout.listY();
            var art = app.artwork.get(selected);
            g.fill(x, y, x + size, y + size, 0xFF292F44);
            if (art != null) com.darkmusic.client.library.ArtworkCache.draw(g, art, x, y, size);
            else g.centeredText(font, Text.get("text.darkmusic.music"), x + size / 2, y + size / 2 - 4, 0xFFB5B0FF);
        }
        super.extractRenderState(g, mx, my, delta);
    }
}
