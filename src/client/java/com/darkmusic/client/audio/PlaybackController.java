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
    private final List<Integer> order = new ArrayList<>();
    private final Deque<Visited> history = new ArrayDeque<>();
    private final List<Queued> manual = new ArrayList<>();
    private Map<String, Integer> sourceIndex = Map.of();
    private Playing current;
    private Plan planned;
    private long queueIds, queueRevision;
    private UpNextSnapshot queueSnapshot;
    public static final int QUEUE_LIMIT = 1000, PREVIEW_LIMIT = 25;
    private List<Track> tracks = List.of();
    private int cursor = -1, failures;
    private boolean shuffle;
    private double speed = 1;
    private Repeat repeat = Repeat.OFF;
    private AudioSettings configuredAudio;
    private SoundEnvironmentSettings configuredEnvironment = new SoundEnvironmentSettings();
    private long sequence;
    private long orderVersion;
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
        orderVersion++;
        refreshNext();
    }

    public void shuffle(boolean value) {
        invalidatePlan();
        shuffle = value;
        int current = cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1;
        rebuildOrder(current);
        refreshNext();
    }

    private void rebuildOrder(int current) {
        orderVersion++;
        order.clear();
        for (int i = 0; i < tracks.size(); i++) if (i != current) order.add(i);
        if (shuffle) Collections.shuffle(order);
        if (current >= 0) {
            if (shuffle) order.addFirst(current);
            else order.add(Math.min(current, order.size()), current);
        }
        cursor = current >= 0 ? order.indexOf(current) : -1;
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
        history.clear();
        failures = 0;
        int index = tracks.indexOf(selected);
        if (index < 0) return;
        rebuildOrder(index);
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

    private record Visited(Track track, boolean manual, int anchor) {
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
        queueRevision++;
        queueSnapshot = null;
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
        if (manual.size() >= QUEUE_LIMIT) {
            warning.accept(Text.get("upnext.full", QUEUE_LIMIT));
            return false;
        }
        int index = Math.clamp(destination, 0, manual.size());
        if (index == 0) invalidatePlan();
        manual.add(index, new Queued(++queueIds, track));
        changedQueue();
        refreshNext();
        return true;
    }

    /**
     * Reject a stale drag if playback consumed or changed any occurrence meanwhile.
     */
    public boolean reorderQueued(List<Long> expectedIds, List<Integer> order) {
        settlePromotion();
        if (!manual.stream().map(Queued::id).toList().equals(expectedIds) || order.size() != manual.size()
            || new HashSet<>(order).size() != manual.size()
            || order.stream().anyMatch(i -> i == null || i < 0 || i >= manual.size())) return false;
        var reordered = order.stream().map(manual::get).toList();
        if (reordered.equals(manual)) return false;
        if (!reordered.getFirst().equals(manual.getFirst())) {
            invalidatePlan();
            if (!manual.stream().map(Queued::id).toList().equals(expectedIds)) {
                refreshNext();
                return false;
            }
        }
        manual.clear();
        manual.addAll(reordered);
        changedQueue();
        refreshNext();
        return true;
    }

    private boolean enqueue(Track track, boolean first) {
        if (track == null) return false;
        settlePromotion();
        if (manual.size() >= QUEUE_LIMIT) {
            warning.accept(Text.get("upnext.full", QUEUE_LIMIT));
            return false;
        }
        if (first || manual.isEmpty()) invalidatePlan();
        var entry = new Queued(++queueIds, track);
        if (first) manual.addFirst(entry);
        else manual.add(entry);
        changedQueue();
        refreshNext();
        return true;
    }

    public boolean removeQueued(long id) {
        settlePromotion();
        int index = -1;
        for (int i = 0; i < manual.size(); i++)
            if (manual.get(i).id == id) {
                index = i;
                break;
            }
        if (index < 0) return false;
        if (index == 0) invalidatePlan();
        if (!manual.removeIf(q -> q.id == id)) {
            refreshNext();
            return false;
        }
        changedQueue();
        refreshNext();
        return true;
    }

    public void clearQueued() {
        if (manual.isEmpty()) return;
        invalidatePlan();
        manual.clear();
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
        if (queueSnapshot == null) {
            var automatic = new ArrayList<Track>();
            for (int i = cursor + 1; automatic.size() < PREVIEW_LIMIT && !order.isEmpty(); i++) {
                if (i >= order.size() && repeat != Repeat.ALL) break;
                if (i >= order.size() && shuffle && (planned == null || planned.manualId != 0 || planned.order == order))
                    break;
                // Reuse an already planned shuffled wrap; do not shuffle or copy a library for a preview.
                var previewOrder = i >= order.size() && planned != null && planned.manualId == 0 && !planned.hold ? planned.order : order;
                automatic.add(tracks.get(previewOrder.get(i % order.size())));
                if (i - cursor >= order.size()) break;
            }
            queueSnapshot = new UpNextSnapshot(queueRevision, current == null ? null : current.track, manual, automatic, shuffle, repeat, source, tracks.size());
        }
        return queueSnapshot;
    }

    public void reconcileQueued(Map<String, Track> index, Set<String> registeredRoots, Set<String> completeRoots) {
        settlePromotion();
        boolean changed = manual.stream().anyMatch(q -> !registeredRoots.contains(q.track.rootId())
            || !index.containsKey(q.track.id()) && completeRoots.contains(q.track.rootId())
            || index.containsKey(q.track.id()) && !index.get(q.track.id()).equals(q.track));
        if (!changed) return;
        invalidatePlan();
        manual.removeIf(q -> !registeredRoots.contains(q.track.rootId()) || !index.containsKey(q.track.id()) && completeRoots.contains(q.track.rootId()));
        manual.replaceAll(q -> new Queued(q.id, index.getOrDefault(q.track.id(), q.track)));
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
        if (manual.isEmpty() && failures >= Math.max(1, tracks.size())) stop();
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
        var queued = !hold && !manual.isEmpty() ? manual.getFirst() : null;
        long manualId = queued == null ? 0 : queued.id;
        if (planned != null && planned.from == current && planned.version == orderVersion && planned.manualId == manualId && planned.hold == hold) {
            // Seeking may have dropped a consumed preload while retaining this controller plan.
            prepare(planned);
            return;
        }
        invalidatePlan();
        hold = repeat == Repeat.ONE;
        queued = !hold && !manual.isEmpty() ? manual.getFirst() : null;
        manualId = queued == null ? 0 : queued.id;
        long ticket = sequence;
        int old = cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1;
        List<Integer> nextOrder = order;
        int nextCursor = cursor + 1;
        if (hold || queued != null) nextCursor = cursor;
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
                nextOrder = new ArrayList<>(order);
                Collections.shuffle(nextOrder);
                if (nextOrder.size() > 1 && nextOrder.getFirst() == old) Collections.swap(nextOrder, 0, 1);
            }
        }
        Playing target = hold ? new Playing(current.track, current.manual) : queued != null ? new Playing(queued.track, true)
            : new Playing(tracks.get(nextOrder.get(nextCursor)), false);
        var plan = new Plan(current, target, ticket, orderVersion, manualId, nextCursor, nextOrder, hold);
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
        history.addLast(new Visited(current.track, current.manual, cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1));
        if (history.size() > 1000) history.removeFirst();
    }

    private void settlePromotion() {
        var plan = planned;
        if (plan != null) plan.gate.observe(audio.state().track(), plan.from.track, plan.target.track);
        if (plan == null || plan.applied || !plan.gate.claimed() || plan.ticket != sequence) return;
        plan.applied = true;
        if (!plan.hold) remember();
        if (plan.manualId != 0) manual.removeIf(q -> q.id == plan.manualId);
        else if (!plan.hold) {
            if (plan.order != order) {
                order.clear();
                order.addAll(plan.order);
            }
            cursor = plan.destination;
        }
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
        if (!this.manual.isEmpty()) {
            remember();
            var entry = this.manual.removeFirst();
            start(new Playing(entry.track, true), 0, false, false);
            return;
        }
        if (order.isEmpty()) {
            stop();
            return;
        }
        int old = cursor >= 0 ? order.get(cursor) : -1;
        if (cursor + 1 >= order.size() && !manual && repeat != Repeat.ALL) {
            stop();
            return;
        }
        remember();
        if (cursor + 1 >= order.size()) {
            if (!manual && repeat != Repeat.ALL) {
                stop();
                return;
            }
            rebuildOrder(-1);
            cursor = 0;
            if (shuffle && order.size() > 1 && order.getFirst() == old) Collections.swap(order, 0, 1);
        } else cursor++;
        start(order.get(cursor));
    }

    public void previous() {
        invalidatePlan();
        failures = 0;
        if (!history.isEmpty()) {
            var visited = history.removeLast();
            cursor = order.indexOf(visited.anchor);
            start(new Playing(visited.track, visited.manual), 0, false, false);
        } else if (cursor >= 0 && !order.isEmpty()) {
            cursor = (cursor + order.size() - 1) % order.size();
            start(order.get(cursor));
        }
    }

    public void toggle() {
        switch (audio.state().status()) {
            case PLAYING, LOADING, SEEKING -> audio.pause();
            case PAUSED -> audio.resume();
            default -> {
                if (current != null) start(new Playing(current.track, current.manual), 0, false, false);
                else if (!manual.isEmpty()) advance(true);
                else if (cursor >= 0) start(order.get(cursor));
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
        manual.clear();
        current = null;
        sourceIndex = Map.of();
        source = new Source("All", "", "", "");
        order.clear();
        history.clear();
        cursor = -1;
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
        manual.replaceAll(q -> q.track.id().equals(old.id()) ? new Queued(q.id, updated) : q);
        var visits = history.stream().map(v -> v.track.id().equals(old.id()) ? new Visited(updated, v.manual, v.anchor) : v).toList();
        history.clear();
        history.addAll(visits);
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
        manual.removeIf(q -> removed.contains(q.track.id()));
        Playing playing = current;
        int oldCursor = cursor;
        var retained = new ArrayList<Track>();
        int[] mapping = new int[tracks.size()];
        Arrays.fill(mapping, -1);
        for (int i = 0; i < tracks.size(); i++)
            if (!removed.contains(tracks.get(i).id())) {
                mapping[i] = retained.size();
                retained.add(tracks.get(i));
            }
        var nextOrder = order.stream().filter(i -> mapping[i] >= 0).map(i -> mapping[i]).toList();
        var nextHistory = history.stream().filter(v -> !removed.contains(v.track.id()))
            .map(v -> new Visited(v.track, v.manual, v.anchor < 0 || mapping[v.anchor] < 0 ? -1 : mapping[v.anchor])).toList();
        int predecessor = (int) order.stream().limit(Math.max(0, oldCursor)).filter(i -> mapping[i] >= 0).count() - 1;
        int anchor = oldCursor >= 0 && oldCursor < order.size() ? mapping[order.get(oldCursor)] : -1;
        tracks = List.copyOf(retained);
        indexSource();
        order.clear();
        order.addAll(nextOrder);
        history.clear();
        history.addAll(nextHistory);
        cursor = anchor < 0 ? predecessor : order.indexOf(anchor);
        current = playing != null && !removed.contains(playing.track.id()) ? playing : null;
        orderVersion++;
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
     * For tests: the play order, as indexes into the tracks.
     */
    List<Integer> order() {
        return order;
    }

    /**
     * For tests: the current track's place in the play order.
     */
    int cursor() {
        return cursor;
    }
}
