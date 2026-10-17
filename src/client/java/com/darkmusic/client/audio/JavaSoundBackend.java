package com.darkmusic.client.audio;

import com.darkmusic.client.library.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One worker owns every decoder and output resource. PCM is bounded to small buffers.
 */
public final class JavaSoundBackend implements AudioBackend {
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer();
    private final Executor callbacks;
    private final Decoder decoder;
    private final Output output;
    private final Thread worker;
    private final java.util.ArrayDeque<Segment> timeline = new java.util.ArrayDeque<>();
    private final long stallNanos;
    private volatile State state = new State(null, Status.STOPPED, 0, 0, "");
    private volatile boolean closed, paused;
    private volatile double gain = 0.7;
    private volatile double speed = 1;
    private PcmRateConverter converter;
    private long submittedFrames;
    private double deviceRate;
    private long progressedAt, lastPlayed;
    private volatile Request active;
    private AudioInputStream stream;
    private SourceDataLine line;
    private double offset;

    public JavaSoundBackend(Executor callbacks) {
        this(callbacks, PcmDecoder::open, format -> {
            SourceDataLine result = AudioSystem.getSourceDataLine(format);
            try {
                result.open(format, Math.max(4096, ((int) (format.getFrameRate() / 5)) * format.getFrameSize()));
            } catch (Exception e) {
                result.close();
                throw e;
            }
            return result;
        });
    }

    public JavaSoundBackend(Executor callbacks, Decoder decoder, Output output) {
        this(callbacks, decoder, output, java.time.Duration.ofSeconds(5));
    }

    public JavaSoundBackend(Executor callbacks, Decoder decoder, Output output, java.time.Duration stallTimeout) {
        this.callbacks = callbacks;
        this.stallNanos = stallTimeout.toNanos();
        this.decoder = decoder;
        this.output = output;
        worker = Thread.ofPlatform().daemon().name("darkmusic-audio").start(this::run);
    }

    public static void scale(byte[] bytes, int count, double gain) {
        for (int i = 0; i + 1 < count; i += 2) {
            short sample = (short) ((bytes[i] & 255) | (bytes[i + 1] << 8));
            int adjusted = (int) Math.round(sample * gain);
            bytes[i] = (byte) adjusted;
            bytes[i + 1] = (byte) (adjusted >> 8);
        }
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public SpectrumAnalyzer spectrum() {
        return spectrum;
    }

    @Override
    public void play(Track track, Runnable ended, Consumer<String> failed) {
        paused = false;
        submit(track, 0, ended, failed);
    }

    private void submit(Track track, double seconds, Runnable ended, Consumer<String> failed) {
        if (closed) return;
        long ticket = generation.incrementAndGet();
        pending.set(new Request(ticket, track, seconds, ended, failed));
        state = new State(track, seconds > 0 ? Status.SEEKING : Status.LOADING, seconds, track.duration(), "");
        java.util.concurrent.locks.LockSupport.unpark(worker);
    }

    @Override
    public void pause() {
        paused = true;
        java.util.concurrent.locks.LockSupport.unpark(worker);
    }

    @Override
    public void resume() {
        paused = false;
        java.util.concurrent.locks.LockSupport.unpark(worker);
    }

    @Override
    public void volume(double value) {
        gain = Double.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
    }

    @Override
    public void speed(double value) {
        speed = PcmRateConverter.clamp(value);
        java.util.concurrent.locks.LockSupport.unpark(worker);
    }

    @Override
    public void seek(double seconds) {
        Request request = pending.get();
        if (request == null) request = active;
        if (request == null || request.track == null || !Double.isFinite(seconds)) return;
        submit(request.track, Math.max(0, seconds), request.ended, request.failed);
    }

    @Override
    public void stop() {
        long ticket = generation.incrementAndGet();
        pending.set(new Request(ticket, null, 0, () -> {
        }, s -> {
        }));
        state = new State(state.track(), Status.STOPPED, 0, state.duration(), "");
        java.util.concurrent.locks.LockSupport.unpark(worker);
    }

    private boolean current(Request request) {
        return !closed && request.generation == generation.get();
    }

    private void run() {
        byte[] buffer = new byte[4096];
        try {
            while (!closed) {
                Request request = pending.getAndSet(null);
                if (request != null) {
                    release();
                    active = request;
                    if (request.track != null) {
                        try {
                            stream = decoder.open(request.track.file());
                            AudioFormat format = stream.getFormat();
                            int frame = format.getFrameSize();
                            long framesToSkip = (long) (request.position * format.getFrameRate());
                            long skipped = 0;
                            while (skipped < framesToSkip && current(request)) {
                                int wanted = (int) Math.min(buffer.length / frame, framesToSkip - skipped) * frame;
                                int read = stream.read(buffer, 0, wanted);
                                if (read < 0) break;
                                skipped += read / frame;
                            }
                            if (!current(request)) {
                                release();
                                continue;
                            }
                            offset = skipped / (double) format.getFrameRate();
                            converter = new PcmRateConverter(stream);
                            submittedFrames = 0;
                            timeline.clear();
                            timeline.add(new Segment(0, offset, speed));
                            try {
                                line = output.open(format);
                            } catch (Exception original) {
                                for (float fallback : new float[]{48_000, 44_100}) {
                                    if (fallback == format.getFrameRate()) continue;
                                    try {
                                        line = output.open(new AudioFormat(fallback, 16, format.getChannels(), true, false));
                                        break;
                                    } catch (Exception failure) {
                                        original.addSuppressed(failure);
                                    }
                                }
                                if (line == null) throw original;
                            }
                            deviceRate = line.getFormat().getFrameRate();
                            progressedAt = System.nanoTime();
                            lastPlayed = line.getLongFramePosition();
                            spectrum.begin(deviceRate, format.getChannels());
                            if (!paused) line.start();
                        } catch (Exception e) {
                            fail(request, e);
                        }
                    }
                }
                request = active;
                if (stream == null || line == null || request == null || !current(request)) {
                    java.util.concurrent.locks.LockSupport.park();
                    continue;
                }
                try {
                    if (paused) {
                        line.stop();
                        update(request, Status.PAUSED);
                        progressedAt = System.nanoTime();
                        java.util.concurrent.locks.LockSupport.park();
                        progressedAt = System.nanoTime();
                        continue;
                    }
                    if (!line.isRunning()) line.start();
                    update(request, Status.PLAYING);
                    long played = line.getLongFramePosition();
                    if (played != lastPlayed) {
                        lastPlayed = played;
                        progressedAt = System.nanoTime();
                    } else if (System.nanoTime() - progressedAt > stallNanos)
                        throw new java.io.IOException("Audio output stopped making progress. Check or reconnect the audio device.");
                    int frame = stream.getFormat().getFrameSize();
                    int amount = Math.min(buffer.length, line.available()) / frame * frame;
                    if (amount == 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                        continue;
                    }
                    double rate = speed;
                    if (timeline.getLast().speed != rate)
                        timeline.add(new Segment(submittedFrames, offset + converter.positionFrames() / stream.getFormat().getFrameRate(), rate));
                    int count = converter.read(buffer, amount, rate * stream.getFormat().getFrameRate() / deviceRate);
                    if (count < 0) {
                        // Drain with short polls so stop/seek never waits on a blocking drain().
                        if (line.available() < line.getBufferSize()) {
                            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                            continue;
                        }
                        update(request, Status.STOPPED);
                        release();
                        active = null;
                        Request finished = request;
                        callbacks.execute(() -> {
                            if (current(finished)) finished.ended.run();
                        });
                    } else if (count == 0) {
                        // Vorbis providers can return zero while priming their PCM buffer.
                        java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                    } else if (current(request)) {
                        scale(buffer, count, gain);
                        int written = 0;
                        while (written < count && current(request)) {
                            if (paused) {
                                line.stop();
                                update(request, Status.PAUSED);
                                java.util.concurrent.locks.LockSupport.park();
                                progressedAt = System.nanoTime();
                                if (!paused && current(request)) line.start();
                                continue;
                            }
                            int capacity = Math.min(count - written, line.available()) / frame * frame;
                            int sent = capacity > 0 ? line.write(buffer, written, capacity) : 0;
                            if (sent <= 0) {
                                if (System.nanoTime() - progressedAt > stallNanos)
                                    throw new java.io.IOException("Audio device accepted no samples. Check or reconnect the audio device.");
                                java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                                continue;
                            }
                            if (sent % frame != 0)
                                throw new java.io.IOException("Audio device returned an incomplete PCM frame");
                            spectrum.accept(buffer, written, sent, submittedFrames);
                            written += sent;
                            submittedFrames += sent / frame;
                        }
                    }
                } catch (Exception e) {
                    fail(request, e);
                }
            }
        } finally {
            release();
        }
    }

    private void update(Request r, Status status) {
        if (current(r)) {
            long played = line.getLongFramePosition();
            spectrum.advance(played, status == Status.PLAYING);
            while (timeline.size() > 1) {
                var first = timeline.removeFirst();
                if (timeline.getFirst().outputFrame > played) {
                    timeline.addFirst(first);
                    break;
                }
            }
            var segment = timeline.getFirst();
            double position = segment.sourceSeconds + Math.max(0, played - segment.outputFrame) / (double) line.getFormat().getFrameRate() * segment.speed;
            double duration = status == Status.STOPPED ? position : Math.max(r.track.duration(), position);
            state = new State(r.track, status, position, duration, "");
        }
    }

    private void fail(Request request, Exception exception) {
        release();
        active = null;
        if (!current(request)) return;
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        state = new State(request.track, Status.ERROR, 0, request.track.duration(), message);
        callbacks.execute(() -> {
            if (current(request)) request.failed.accept(message);
        });
    }

    private void release() {
        spectrum.clear();
        converter = null;
        timeline.clear();
        if (line != null) {
            try {
                line.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                line.flush();
            } catch (RuntimeException ignored) {
            }
            try {
                line.close();
            } catch (RuntimeException ignored) {
            } finally {
                line = null;
            }
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
            } finally {
                stream = null;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        pending.set(null);
        java.util.concurrent.locks.LockSupport.unpark(worker);
        try {
            worker.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface Decoder {
        AudioInputStream open(java.nio.file.Path path) throws Exception;
    }

    @FunctionalInterface
    public interface Output {
        SourceDataLine open(AudioFormat format) throws Exception;
    }

    private record Request(long generation, Track track, double position, Runnable ended, Consumer<String> failed) {
    }

    private record Segment(long outputFrame, double sourceSeconds, double speed) {
    }
}
