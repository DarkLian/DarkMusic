package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Queue mutations and completion callbacks are confined to the client thread.
 */
public final class PlaybackController implements AutoCloseable {
    private final AudioBackend audio;
    private final Consumer<String> warning;
    private final PlayOrder order = new PlayOrder();
    private final UpNextQueue upNext = new UpNextQueue();
    private Map<String, Integer> sourceIndex = Map.of();
    private Playing current;
    private Plan planned;
    public static final int QUEUE_LIMIT = 1000, PREVIEW_LIMIT = 25;
    private List<Track> tracks = List.of();
    private int failures;
    private boolean shuffle;
    private double speed = 1;
    private Repeat repeat = Repeat.OFF;
    private AudioSettings configuredAudio;
    private SoundEnvironmentSettings configuredEnvironment = new SoundEnvironmentSettings();
    private long sequence;
    private Source source = new Source("All", "", "", "");
    private BiConsumer<Source, List<Track>> sourceChanged = (source, tracks) -> {
    };

    public void onSourceChanged(BiConsumer<Source, List<Track>> listener) {
        sourceChanged = Objects.requireNonNull(listener);
    }

    public PlaybackController(AudioBackend audio, Consumer<String> warning) {
        this.audio = audio;
        this.warning = warning;
    }

    public double speed() {
        return speed;
    }

    public SpectrumAnalyzer spectrum() {
        return audio.spectrum();
    }

    public void speed(double value) {
        speed = PcmRateConverter.clamp(value);
        audio.speed(speed);
    }

    public AudioBackend.State state() {
        return audio.state();
    }

    public boolean shuffle() {
        return shuffle;
    }

    public Repeat repeat() {
        return repeat;
    }

    public void volume(double value) {
        audio.volume(value);
    }

    public void configure(AudioSettings settings) {
        configure(settings, configuredEnvironment);
    }

    public AudioDiagnostics diagnostics() {
        return audio.diagnostics();
    }

    public void comparison(AudioComparison comparison) {
        audio.comparison(comparison);
    }

    public LoudnessCache.Status normalization() {
        return audio.normalization();
    }

    public void configure(AudioSettings settings, SoundEnvironmentSettings environment) {
        if (configuredAudio != null && configuredAudio.samePlayback(settings) && configuredEnvironment.samePlayback(environment))
            return;
        var snapshot = settings.playbackCopy();
        boolean transitionsChanged = configuredAudio == null || configuredAudio.gapless != snapshot.gapless
            || configuredAudio.crossfade != snapshot.crossfade;
        var space = environment.playbackCopy();
        audio.configure(snapshot, space);
        configuredAudio = snapshot;
        configuredEnvironment = space;
        if (transitionsChanged) {
            invalidatePlan();
            refreshNext();
        }
    }

    private boolean stopAfter;

    public boolean stopAfterCurrent() {
        return stopAfter;
    }

    /**
     * Stops when the song playing now ends, instead of continuing. Applies to whichever song is playing when it
     * ends; stopping clears it.
     */
    public void stopAfterCurrent(boolean value) {
        stopAfter = value;
        invalidatePlan();
        refreshNext();
    }

    public void repeat(Repeat value) {
        invalidatePlan();
        repeat = Objects.requireNonNullElse(value, Repeat.OFF);
        order.bump();
        refreshNext();
    }

    public void shuffle(boolean value) {
        invalidatePlan();
        shuffle = value;
        order.rebuild(order.current(), tracks.size(), shuffle);
        refreshNext();
    }

    public void play(List<Track> queue, Track selected) {
        if (selected == null) return;
        play(queue, selected, new Source("All", "", "", selected.rootId()));
    }

    public Source source() {
        return source;
    }

    public void play(List<Track> queue, Track selected, Source origin) {
        play(queue, selected, origin, 0, false);
    }

    /**
     * Starts {@code selected} at {@code position} seconds, optionally paused there.
     */
    public void play(List<Track> queue, Track selected, Source origin, double position, boolean paused) {
        if (!queue.contains(selected)) return;
        invalidatePlan();
        source = Objects.requireNonNull(origin);
        tracks = List.copyOf(queue);
        indexSource();
        sourceChanged.accept(source, tracks);
        order.forget();
        failures = 0;
        int index = tracks.indexOf(selected);
        if (index < 0) return;
        order.rebuild(index, tracks.size(), shuffle);
        start(index, Double.isFinite(position) ? Math.max(0, position) : 0, paused);
    }

    public record Queued(long id, Track track) {
    }

    public record UpNextSnapshot(long revision, Track current, List<Queued> manual, List<Track> automatic,
                                 boolean shuffle, Repeat repeat, Source source, int sourceSize) {
        public UpNextSnapshot {
            manual = List.copyOf(manual);
            automatic = List.copyOf(automatic);
        }
    }

    private record Playing(Track track, boolean manual) {
    }

    private static final class Plan {
        final AudioBackend.Transition gate = new AudioBackend.Transition();
        final Playing from, target;
        final long ticket, version, manualId;
        final int destination;
        final List<Integer> order;
        final boolean hold;
        boolean applied;

        Plan(Playing from, Playing target, long ticket, long version, long manualId, int destination, List<Integer> order, boolean hold) {
            this.from = from;
            this.target = target;
            this.ticket = ticket;
            this.version = version;
            this.manualId = manualId;
            this.destination = destination;
            this.order = order;
            this.hold = hold;
        }
    }

    private void indexSource() {
        var index = new HashMap<String, Integer>();
        for (int i = 0; i < tracks.size(); i++) index.putIfAbsent(tracks.get(i).id(), i);
        sourceIndex = index;
    }

    private void changedQueue() {
        upNext.changed();
    }

    public boolean playNext(Track track) {
        return enqueue(track, true);
    }

    public boolean addToQueue(Track track) {
        return enqueue(track, false);
    }

    public boolean insertQueued(Track track, int destination) {
        if (track == null) return false;
        settlePromotion();
        if (upNext.full()) {
            warning.accept(Text.get("upnext.full", QUEUE_LIMIT));
            return false;
        }
        int index = Math.clamp(destination, 0, upNext.size());
        if (index == 0) invalidatePlan();
        upNext.insert(index, track);
        changedQueue();
        refreshNext();
        return true;
    }

    /**
     * Reject a stale drag if playback consumed or changed any occurrence meanwhile.
     */
    public boolean reorderQueued(List<Long> expectedIds, List<Integer> positions) {
        settlePromotion();
        var reordered = upNext.reordered(expectedIds, positions);
        if (reordered == null) return false;
        if (!reordered.getFirst().equals(upNext.first())) {
            invalidatePlan();
            if (!upNext.ids().equals(expectedIds)) {
                refreshNext();
                return false;
            }
        }
        upNext.replace(reordered);
        changedQueue();
        refreshNext();
        return true;
    }

    private boolean enqueue(Track track, boolean first) {
        if (track == null) return false;
        settlePromotion();
        if (upNext.full()) {
            warning.accept(Text.get("upnext.full", QUEUE_LIMIT));
            return false;
        }
        if (first || upNext.isEmpty()) invalidatePlan();
        upNext.add(track, first);
        changedQueue();
        refreshNext();
        return true;
    }

    public boolean removeQueued(long id) {
        settlePromotion();
        int index = upNext.indexOf(id);
        if (index < 0) return false;
        if (index == 0) invalidatePlan();
        if (!upNext.remove(id)) {
            refreshNext();
            return false;
        }
        changedQueue();
        refreshNext();
        return true;
    }

    public void clearQueued() {
        if (upNext.isEmpty()) return;
        invalidatePlan();
        upNext.clear();
        changedQueue();
        refreshNext();
    }

    /**
     * Used for locating a temporary song; never changes the preserved playback source.
     */
    public Source playingSource() {
        settlePromotion();
        if (current == null || !current.manual || sourceIndex.containsKey(current.track.id())) return source;
        var track = current.track;
        String folder = track.folder().equals("(root)") ? "" : "/" + track.folder();
        return new Source("All", "@" + track.rootId() + folder, "", track.rootId());
    }

    /**
     * The song that plays after this one: Up Next first, then the collection. Null when playback stops instead.
     */
    public Track upcoming() {
        if (stopAfter || current == null) return null;
        if (repeat == Repeat.ONE) return current.track;
        var snapshot = upNextSnapshot();
        if (!snapshot.manual().isEmpty()) return snapshot.manual().getFirst().track();
        return snapshot.automatic().isEmpty() ? null : snapshot.automatic().getFirst();
    }

    public UpNextSnapshot upNextSnapshot() {
        settlePromotion();
        if (upNext.snapshot() == null) {
            var automatic = new ArrayList<Track>();
            var list = order.list();
            int cursor = order.cursor();
            for (int i = cursor + 1; automatic.size() < PREVIEW_LIMIT && !list.isEmpty(); i++) {
                if (i >= list.size() && repeat != Repeat.ALL) break;
                if (i >= list.size() && shuffle && (planned == null || planned.manualId != 0 || planned.order == list))
                    break;
                // Reuse an already planned shuffled wrap; do not shuffle or copy a library for a preview.
                var previewOrder = i >= list.size() && planned != null && planned.manualId == 0 && !planned.hold ? planned.order : list;
                automatic.add(tracks.get(previewOrder.get(i % list.size())));
                if (i - cursor >= list.size()) break;
            }
            upNext.snapshot(new UpNextSnapshot(upNext.revision(), current == null ? null : current.track, upNext.entries(), automatic, shuffle, repeat, source, tracks.size()));
        }
        return upNext.snapshot();
    }

    public void reconcileQueued(Map<String, Track> index, Set<String> registeredRoots, Set<String> completeRoots) {
        settlePromotion();
        if (!upNext.stale(index, registeredRoots, completeRoots)) return;
        invalidatePlan();
        upNext.reconcile(index, registeredRoots, completeRoots);
        changedQueue();
        refreshNext();
    }

    private void start(int index) {
        start(index, 0, false);
    }

    private void start(int index, double position, boolean paused) {
        start(new Playing(tracks.get(index), false), position, paused, false);
    }

    private void start(Playing point, double position, boolean paused, boolean continuing) {
        invalidatePlan();
        current = point;
        changedQueue();
        long ticket = ++sequence;
        if (continuing)
            audio.resumeFrom(point.track, position, paused, () -> finished(point, ticket), message -> failed(point, ticket, message));
        else
            audio.playFrom(point.track, position, paused, () -> finished(point, ticket), message -> failed(point, ticket, message));
        refreshNext();
    }

    private void finished(Playing point, long ticket) {
        settlePromotion();
        if (ticket != sequence || current != point) return;
        failures = 0;
        if (stopAfter) stop();
        else if (repeat == Repeat.ONE) start(new Playing(point.track, point.manual), 0, false, false);
        else advance(false);
    }

    private void failed(Playing point, long ticket, String message) {
        settlePromotion();
        if (ticket != sequence || current != point) return;
        warning.accept(Text.get("audio.error.track", point.track.title(), message));
        if (!point.manual) failures++;
        if (upNext.isEmpty() && failures >= Math.max(1, tracks.size())) stop();
        else advance(false);
    }

    private void refreshNext() {
        settlePromotion();
        changedQueue();
        if (current == null) return;
        if (stopAfter) {
            // Nothing to preload: gapless playback or crossfade would otherwise start the next song early.
            invalidatePlan();
            audio.nextTrack(null, () -> {
            }, () -> {
            }, s -> {
            });
            return;
        }
        boolean hold = repeat == Repeat.ONE;
        var queued = !hold && !upNext.isEmpty() ? upNext.first() : null;
        long manualId = queued == null ? 0 : queued.id();
        if (planned != null && planned.from == current && planned.version == order.version() && planned.manualId == manualId && planned.hold == hold) {
            // Seeking may have dropped a consumed preload while retaining this controller plan.
            prepare(planned);
            return;
        }
        invalidatePlan();
        hold = repeat == Repeat.ONE;
        queued = !hold && !upNext.isEmpty() ? upNext.first() : null;
        manualId = queued == null ? 0 : queued.id();
        long ticket = sequence;
        int old = order.current();
        List<Integer> nextOrder = order.list();
        int nextCursor = order.cursor() + 1;
        if (hold || queued != null) nextCursor = order.cursor();
        else if (nextCursor >= order.size()) {
            if (repeat != Repeat.ALL) {
                audio.nextTrack(null, () -> {
                }, () -> {
                }, s -> {
                });
                return;
            }
            if (order.isEmpty()) {
                audio.nextTrack(null, () -> {
                }, () -> {
                }, s -> {
                });
                return;
            }
            nextCursor = 0;
            if (shuffle) {
                nextOrder = new ArrayList<>(order.list());
                Collections.shuffle(nextOrder);
                if (nextOrder.size() > 1 && nextOrder.getFirst() == old) Collections.swap(nextOrder, 0, 1);
            }
        }
        Playing target = hold ? new Playing(current.track, current.manual) : queued != null ? new Playing(queued.track(), true)
            : new Playing(tracks.get(nextOrder.get(nextCursor)), false);
        var plan = new Plan(current, target, ticket, order.version(), manualId, nextCursor, nextOrder, hold);
        planned = plan;
        prepare(plan);
    }

    private void prepare(Plan plan) {
        audio.nextTrack(plan.target.track, () -> {
            if (planned != plan || plan.ticket != sequence) return;
            settlePromotion();
            refreshNext();
        }, () -> finished(plan.target, plan.ticket), message -> failed(plan.target, plan.ticket, message), plan.gate);
    }

    private void remember() {
        if (current == null) return;
        order.remember(current.track, current.manual);
    }

    private void settlePromotion() {
        var plan = planned;
        if (plan != null) plan.gate.observe(audio.state().track(), plan.from.track, plan.target.track);
        if (plan == null || plan.applied || !plan.gate.claimed() || plan.ticket != sequence) return;
        plan.applied = true;
        if (!plan.hold) remember();
        if (plan.manualId != 0) upNext.remove(plan.manualId);
        else if (!plan.hold) order.adopt(plan.order, plan.destination);
        current = plan.target;
        failures = 0;
        changedQueue();
    }

    private void invalidatePlan() {
        settlePromotion();
        if (planned != null) {
            if (!planned.gate.cancel()) settlePromotion();
            planned = null;
        }
    }

    public void next() {
        failures = 0;
        advance(true);
    }

    public void outputChanged() {
        invalidatePlan();
        refreshNext();
    }

    private void advance(boolean manual) {
        invalidatePlan();
        if (!upNext.isEmpty()) {
            remember();
            var entry = upNext.removeFirst();
            start(new Playing(entry.track(), true), 0, false, false);
            return;
        }
        if (order.isEmpty()) {
            stop();
            return;
        }
        int old = order.cursor() >= 0 ? order.at(order.cursor()) : -1;
        if (order.cursor() + 1 >= order.size() && !manual && repeat != Repeat.ALL) {
            stop();
            return;
        }
        remember();
        if (order.cursor() + 1 >= order.size()) {
            if (!manual && repeat != Repeat.ALL) {
                stop();
                return;
            }
            order.wrap(old, tracks.size(), shuffle);
        } else order.cursor(order.cursor() + 1);
        start(order.at(order.cursor()));
    }

    public void previous() {
        invalidatePlan();
        failures = 0;
        var visited = order.back();
        if (visited != null) start(new Playing(visited.track(), visited.manual()), 0, false, false);
        else if (order.stepBack()) start(order.at(order.cursor()));
    }

    public void toggle() {
        switch (audio.state().status()) {
            case PLAYING, LOADING, SEEKING -> audio.pause();
            case PAUSED -> audio.resume();
            default -> {
                if (current != null) start(new Playing(current.track, current.manual), 0, false, false);
                else if (!upNext.isEmpty()) advance(true);
                else if (order.cursor() >= 0) start(order.at(order.cursor()));
            }
        }
    }

    public void pause() {
        audio.pause();
    }

    public void resume() {
        audio.resume();
    }

    public void stop() {
        stopAfter = false;
        settlePromotion();
        invalidatePlan();
        changedQueue();
        sequence++;
        audio.nextTrack(null, () -> {
        }, () -> {
        }, s -> {
        });
        audio.stop();
    }

    public void reset() {
        stop();
        tracks = List.of();
        upNext.clear();
        current = null;
        sourceIndex = Map.of();
        source = new Source("All", "", "", "");
        order.reset();
        failures = 0;
    }

    public void seek(double seconds) {
        audio.seek(seconds);
        refreshNext();
    }

    public void seekRelative(double seconds) {
        var state = audio.state();
        if (!Double.isFinite(seconds) || state.track() == null || !Double.isFinite(state.duration()) || state.duration() <= 0
            || state.status() == AudioBackend.Status.STOPPED || state.status() == AudioBackend.Status.ERROR) return;
        double position = Double.isFinite(state.position()) ? state.position() : 0;
        seek(Math.clamp(position + seconds, 0, state.duration()));
    }

    public record EditResume(long sequence, double position, AudioBackend.Status status) {
    }

    public EditResume suspendForEdit() {
        settlePromotion();
        var state = audio.state();
        // A device-buffered handoff may be committed before its audible-state announcement.
        // Never apply the outgoing song's position to the newly promoted decoder.
        double position = current != null && state.track() != null && !current.track.id().equals(state.track().id()) ? 0 : state.position();
        stop();
        return new EditResume(sequence, position, state.status());
    }

    public boolean awaitIdle(long seconds) throws InterruptedException {
        return audio.awaitIdle(seconds);
    }

    public void finishEdit(EditResume resume, Track old, Track replacement) {
        if (replacement == null) replacement = old;
        Track updated = replacement;
        tracks = tracks.stream().map(t -> t.id().equals(old.id()) ? updated : t).toList();
        indexSource();
        upNext.replaceTrack(old.id(), updated);
        order.replaceTrack(old.id(), updated);
        if (current != null && current.track.id().equals(old.id())) current = new Playing(updated, current.manual);
        changedQueue();
        if (sequence != resume.sequence || current == null) return;
        if (resume.status == AudioBackend.Status.PLAYING || resume.status == AudioBackend.Status.PAUSED
            || resume.status == AudioBackend.Status.SEEKING || resume.status == AudioBackend.Status.LOADING) {
            start(current, resume.position, resume.status == AudioBackend.Status.PAUSED, true);
        }
    }

    /**
     * Retain queue/shuffle order and resume only if the current file survived deletion.
     */
    public void finishDeletion(EditResume resume, Set<String> removed) {
        invalidatePlan();
        upNext.removeTracks(removed);
        Playing playing = current;
        var retained = new ArrayList<Track>();
        int[] mapping = new int[tracks.size()];
        Arrays.fill(mapping, -1);
        for (int i = 0; i < tracks.size(); i++)
            if (!removed.contains(tracks.get(i).id())) {
                mapping[i] = retained.size();
                retained.add(tracks.get(i));
            }
        order.remap(mapping, removed);
        tracks = List.copyOf(retained);
        indexSource();
        current = playing != null && !removed.contains(playing.track.id()) ? playing : null;
        order.bump();
        changedQueue();
        if (sequence == resume.sequence && current != null && (resume.status == AudioBackend.Status.PLAYING || resume.status == AudioBackend.Status.PAUSED
            || resume.status == AudioBackend.Status.SEEKING || resume.status == AudioBackend.Status.LOADING))
            start(current, resume.position, resume.status == AudioBackend.Status.PAUSED, true);
    }

    @Override
    public void close() {
        invalidatePlan();
        sequence++;
        audio.close();
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return audio.awaitStopped(seconds);
    }

    /**
     * Immutable origin of the actual repeat/shuffle queue, retained across track changes.
     */
    public record Source(String mode, String group, String query, String rootId) {
    }

    /**
     * For tests: the play order and where playback is in it.
     */
    PlayOrder playOrder() {
        return order;
    }
}
