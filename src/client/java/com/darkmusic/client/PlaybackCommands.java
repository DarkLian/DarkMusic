package com.darkmusic.client;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.ResumeTracker;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.gui.PlayerQueries;
import com.darkmusic.client.gui.QueueOperations;
import com.darkmusic.client.gui.TransportOperations;
import com.darkmusic.client.library.ListeningHistory;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.systemmedia.MediaControls;

import java.util.List;

/**
 * The player's controls as the menus, chat commands, shortcuts and media keys share them: transport, the queue,
 * volume, speed, shuffle and repeat saved with the settings, the songs of a collection, and Resume Where You Left Off.
 * Each change applies its own admission here, so the screens only decide what to show as enabled. Client thread.
 */
public final class PlaybackCommands implements QueueOperations, TransportOperations, PlayerQueries {
    public static final int RECENTLY_ADDED = 100;
    private final ClientServices services;
    private final ResumeTracker resume = new ResumeTracker();

    PlaybackCommands(ClientServices services) {
        this.services = services;
    }

    public boolean canEditQueue() {
        return services.state.accepting() && services.playback() != null;
    }

    @Override
    public boolean playNext(Track track) {
        return canEditQueue() && services.playback().playNext(track);
    }

    @Override
    public boolean addToQueue(Track track) {
        return canEditQueue() && services.playback().addToQueue(track);
    }

    @Override
    public boolean insert(Track track, int index) {
        return canEditQueue() && services.playback().insertQueued(track, index);
    }

    @Override
    public boolean reorder(List<Long> expectedIds, List<Integer> order) {
        return canEditQueue() && services.playback().reorderQueued(expectedIds, order);
    }

    @Override
    public boolean remove(List<Long> ids) {
        if (!canEditQueue()) return false;
        for (long id : ids) services.playback().removeQueued(id);
        return true;
    }

    @Override
    public boolean clear() {
        if (!canEditQueue()) return false;
        services.playback().clearQueued();
        return true;
    }

    // Starting a song, play/pause, skipping and seeking wait while a media operation owns the files.
    private boolean steering() {
        return services.state.idle() && services.playback() != null;
    }

    // Stop, pause and resume only need the client alive, as the screens that call them always allowed.
    private boolean running() {
        return services.state.alive() && services.playback() != null;
    }

    @Override
    public boolean play(List<Track> tracks, Track track, PlaybackController.Source source) {
        if (!steering()) return false;
        services.playback().play(tracks, track, source);
        return true;
    }

    @Override
    public boolean toggle() {
        if (!steering()) return false;
        services.playback().toggle();
        return true;
    }

    @Override
    public boolean next() {
        if (!steering()) return false;
        services.playback().next();
        return true;
    }

    @Override
    public boolean previous() {
        if (!steering()) return false;
        services.playback().previous();
        return true;
    }

    @Override
    public boolean seek(double seconds) {
        if (!steering()) return false;
        services.playback().seek(seconds);
        return true;
    }

    @Override
    public boolean stop() {
        if (!running()) return false;
        services.playback().stop();
        return true;
    }

    @Override
    public boolean pause() {
        if (!running()) return false;
        services.playback().pause();
        return true;
    }

    @Override
    public boolean resume() {
        if (!running()) return false;
        services.playback().resume();
        return true;
    }

    @Override
    public AudioBackend.State state() {
        return services.playback().state();
    }

    @Override
    public PlaybackController.Source playingSource() {
        return services.playback().playingSource();
    }

    @Override
    public PlaybackController.UpNextSnapshot upNextSnapshot() {
        return services.playback().upNextSnapshot();
    }

    @Override
    public boolean shuffle() {
        return services.playback().shuffle();
    }

    @Override
    public Repeat repeat() {
        return services.playback().repeat();
    }

    public void seekRelative(double seconds) {
        if (services.state.idle() && services.playback() != null) services.playback().seekRelative(seconds);
    }

    public void speed(double value) {
        services.update(s -> {
            s.speed(PcmRateConverter.clamp(value));
            services.playback().speed(s.speed);
        });
    }

    public void volume(double value) {
        services.update(s -> {
            s.volume(Settings.clampVolume(value));
            if (s.volume > 0) s.lastAudibleVolume = s.volume;
            services.playback().volume(s.volume);
        });
    }

    public void sleepTimer(int minutes) {
        services.sleep().start(minutes, services.playback(), services.settings() == null ? 1 : services.settings().volume);
    }

    /**
     * A media key, headset button or Windows media flyout button, on the client thread.
     */
    void mediaAction(MediaControls.Action action) {
        var playback = services.playback();
        if (!services.state.idle() || services.settings() == null || playback == null || services.library() == null
            || !services.settings().mediaControls) return;
        var state = playback.state();
        switch (action.button()) {
            case "play" -> {
                if (state.track() == null) startPlayback();
                else if (state.status() == AudioBackend.Status.PAUSED) playback.resume();
                else if (state.status() != AudioBackend.Status.PLAYING) playback.toggle();
            }
            case "pause" -> {
                if (state.status() == AudioBackend.Status.PLAYING) playback.pause();
            }
            case "next" -> playback.next();
            case "previous" -> playback.previous();
            case "stop" -> playback.stop();
            case "seek" -> {
                if (state.track() != null && state.duration() > 0)
                    playback.seek(Math.clamp(action.position(), 0, state.duration()));
            }
            default -> {
            }
        }
    }

    public void shuffle(boolean on) {
        services.update(s -> {
            s.shuffle = on;
            services.playback().shuffle(on);
        });
    }

    public void repeat(Repeat mode) {
        services.update(s -> {
            s.repeat = mode;
            services.playback().repeat(mode);
        });
    }

    public void toggleMute() {
        var settings = services.settings();
        volume(settings.volume > 0 ? 0 : settings.lastAudibleVolume);
        services.volumeFeedback().show(services.settings().volume);
    }

    /**
     * The songs of one player collection: a folder ("All" with a folder group), playlist, artist, album or history list.
     */
    public List<Track> collection(String mode, String group) {
        var tracks = services.library().snapshot().tracks();
        var history = services.listeningHistory();
        if (mode.equals("History")) return switch (group) {
            case "most" -> history.collection(tracks, true);
            case "added" -> ListeningHistory.recentlyAdded(tracks, RECENTLY_ADDED);
            case "never" -> {
                var playback = services.playback();
                var state = playback.state();
                var source = playback.playingSource();
                boolean active = state.status() != AudioBackend.Status.STOPPED && state.status() != AudioBackend.Status.ERROR
                    && source.mode().equals("History") && source.group().equals("never");
                yield history.neverPlayed(tracks, active ? state.track() : null);
            }
            default -> history.collection(tracks, false);
        };
        var source = mode.equals("Playlists") ? services.playlists().resolve(group, tracks) : tracks;
        return source.stream().filter(t -> switch (mode) {
            case "All" -> FolderTree.includes(group, t);
            case "Artists" -> t.artist().equals(group);
            case "Albums" -> t.album().equals(group);
            default -> true;
        }).toList();
    }

    /**
     * Resume Where You Left Off: plays the remembered song from its position, in the collection it came from, or in
     * the whole library when that collection no longer has it. Returns false when there is nothing to resume.
     */
    public boolean resumeLast(boolean paused) {
        var settings = services.settings();
        var point = settings == null || !settings.resumeLast ? null : settings.resume;
        var library = services.library();
        var playback = services.playback();
        if (point == null || library == null || playback == null || !services.state.idle()) return false;
        var tracks = collection(point.mode, point.group);
        var source = new PlaybackController.Source(point.mode, point.group, "", "");
        var track = tracks.stream().filter(t -> t.id().equals(point.track)).findFirst().orElse(null);
        if (track == null) {
            tracks = library.snapshot().tracks();
            source = new PlaybackController.Source("All", "", "", "");
            track = tracks.stream().filter(t -> t.id().equals(point.track)).findFirst().orElse(null);
        }
        if (track == null) return false;
        // Within the last few seconds the song would end at once; start it over instead.
        double position = track.duration() > 0 && point.position > track.duration() - 5 ? 0 : point.position;
        playback.play(tracks, track, source, position, paused);
        return true;
    }

    /**
     * Starts with nothing playing: the remembered song, or else the library's first. Returns the song, or null.
     */
    public Track startPlayback() {
        if (resumeLast(false)) return services.playback().state().track();
        var tracks = services.library().snapshot().tracks();
        if (tracks.isEmpty()) return null;
        services.playback().play(tracks, tracks.getFirst());
        return tracks.getFirst();
    }

    /**
     * Whether Play on this song should continue it from the remembered position.
     */
    public boolean resumes(Track track) {
        var settings = services.settings();
        var point = settings == null || !settings.resumeLast ? null : settings.resume;
        return point != null && track != null && point.track.equals(track.id());
    }

    void recordResume(boolean persist) {
        if (resume.record(services.settings(), services.playback(), persist) && persist)
            services.settingsStore().persist();
    }
}
