package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import java.util.*;
import java.util.function.Consumer;

/**
 * Queue mutations and completion callbacks are confined to the client thread.
 */
public final class PlaybackController implements AutoCloseable {
    private final AudioBackend audio;
    private final Consumer<String> warning;
    private final List<Integer> order = new ArrayList<>();
    private final Deque<Integer> history = new ArrayDeque<>();
    private List<Track> tracks = List.of();
    private int cursor = -1, failures;
    private boolean shuffle;
    private double speed = 1;
    private Repeat repeat = Repeat.OFF;
    private long sequence;
    private Source source = new Source("All", "", "", "");

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

    public void configure(com.darkmusic.client.config.AudioSettings settings) {
        audio.configure(settings);
        refreshNext();
    }

    public void repeat(Repeat value) {
        repeat = Objects.requireNonNullElse(value, Repeat.OFF);
        refreshNext();
    }

    public void shuffle(boolean value) {
        shuffle = value;
        int current = cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1;
        rebuildOrder(current);
        refreshNext();
    }

    private void rebuildOrder(int current) {
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
        if (!queue.contains(selected)) return;
        source = Objects.requireNonNull(origin);
        tracks = List.copyOf(queue);
        history.clear();
        failures = 0;
        int index = tracks.indexOf(selected);
        if (index < 0) return;
        rebuildOrder(index);
        start(index);
    }

    private void start(int index) {
        long ticket = ++sequence;
        audio.play(tracks.get(index), () -> finished(index, ticket), message -> failed(index, ticket, message));
        refreshNext();
    }

    private void finished(int index, long ticket) {
        if (ticket != sequence) return;
        failures = 0;
        if (repeat == Repeat.ONE) start(index);
        else advance(false);
    }

    private void failed(int index, long ticket, String message) {
        if (ticket != sequence) return;
        warning.accept("Cannot play " + tracks.get(index).title() + ": " + message);
        if (++failures >= tracks.size()) stop();
        else advance(false);
    }

    private void refreshNext() {
        if (cursor < 0 || cursor >= order.size()) return;
        long ticket = sequence;
        int old = order.get(cursor);
        List<Integer> nextOrder = new ArrayList<>(order);
        int nextCursor = cursor + 1;
        if (repeat == Repeat.ONE) nextCursor = cursor;
        else if (nextCursor >= order.size()) {
            if (repeat != Repeat.ALL) {
                audio.nextTrack(null, () -> {
                }, () -> {
                }, s -> {
                });
                return;
            }
            nextCursor = 0;
            if (shuffle) {
                Collections.shuffle(nextOrder);
                if (nextOrder.size() > 1 && nextOrder.getFirst() == old) Collections.swap(nextOrder, 0, 1);
            }
        }
        int destination = nextCursor, index = nextOrder.get(destination);
        audio.nextTrack(tracks.get(index), () -> {
            if (ticket != sequence) return;
            if (repeat != Repeat.ONE) {
                history.addLast(old);
                if (history.size() > 1000) history.removeFirst();
            }
            order.clear();
            order.addAll(nextOrder);
            cursor = destination;
            failures = 0;
            refreshNext();
        }, () -> finished(index, ticket), message -> failed(index, ticket, message));
    }

    public void next() {
        failures = 0;
        advance(true);
    }

    private void advance(boolean manual) {
        if (order.isEmpty() || cursor < 0) return;
        int old = order.get(cursor);
        if (cursor + 1 >= order.size()) {
            if (repeat != Repeat.ALL) {
                stop();
                return;
            }
            rebuildOrder(-1);
            cursor = 0;
            if (shuffle && order.size() > 1 && order.getFirst() == old) Collections.swap(order, 0, 1);
        } else cursor++;
        history.addLast(old);
        if (history.size() > 1000) history.removeFirst();
        start(order.get(cursor));
    }

    public void previous() {
        failures = 0;
        if (!history.isEmpty()) {
            int index = history.removeLast();
            cursor = order.indexOf(index);
            start(index);
        } else if (cursor >= 0) start(order.get(cursor));
    }

    public void toggle() {
        switch (audio.state().status()) {
            case PLAYING, LOADING, SEEKING -> audio.pause();
            case PAUSED -> audio.resume();
            default -> {
                if (cursor >= 0) start(order.get(cursor));
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

    @Override
    public void close() {
        sequence++;
        audio.close();
    }

    public enum Repeat {OFF, ONE, ALL}

    /**
     * Immutable origin of the actual repeat/shuffle queue, retained across track changes.
     */
    public record Source(String mode, String group, String query, String rootId) {
    }
}
