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
    private final AudioProcessor processor = new AudioProcessor();
    private volatile com.darkmusic.client.config.SoundEnvironmentSettings environment = new com.darkmusic.client.config.SoundEnvironmentSettings();
    private volatile AudioComparison comparison = AudioComparison.OFF;
    private boolean protectionFlushed;
    private volatile LoudnessCache.Status normalizationStatus = LoudnessCache.Status.OFF;
    private volatile com.darkmusic.client.config.AudioSettings options = new com.darkmusic.client.config.AudioSettings();
    private LoudnessCache loudness;
    private final AtomicReference<NextAudio> upcoming = new AtomicReference<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.CompletableFuture<Void>> closingDecoders = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ThreadPoolExecutor preloader = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("darkmusic-preload").factory());
    private double overlap;
    private double normalizationGain = 1, incomingNormalization = 1;
    private NextAudio mixing;
    private Announcement announcement;
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
    private volatile AudioInputStream stream;
    private SourceDataLine line;
    private double offset;

    public JavaSoundBackend(Executor callbacks) {
        this(callbacks, PcmDecoder::openPrecise, format -> {
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
            int adjusted = Math.clamp(Math.round(sample * gain), -32768, 32767);
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
        playFrom(track, 0, false, ended, failed);
    }

    @Override
    public void playFrom(Track track, double position, boolean paused, Runnable ended, Consumer<String> failed) {
        this.paused = paused;
        submit(track, Double.isFinite(position) ? Math.max(0, position) : 0, ended, failed);
    }

    private void submit(Track track, double seconds, Runnable ended, Consumer<String> failed) {
        if (closed) return;
        cancelUpcoming();
        ExternalAudio.abort(stream);
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
    public void configure(com.darkmusic.client.config.AudioSettings value) {
        options = value.playbackCopy();
    }

    @Override
    public void configure(com.darkmusic.client.config.AudioSettings value, com.darkmusic.client.config.SoundEnvironmentSettings space) {
        environment = space.playbackCopy();
        configure(value);
    }

    @Override
    public AudioDiagnostics diagnostics() {
        return processor.diagnostics();
    }

    @Override
    public void comparison(AudioComparison value) {
        comparison = value;
    }

    @Override
    public LoudnessCache.Status normalization() {
        return normalizationStatus;
    }

    @Override
    public void nextTrack(Track track, Runnable started, Runnable ended, Consumer<String> failed) {
        var selected = options;
        if (closed || track == null || !selected.gapless && selected.crossfade <= 0) {
            cancelUpcoming();
            return;
        }
        var old = upcoming.get();
        long ticket = generation.get();
        if (old != null && old.generation == ticket && old.track.equals(track)) {
            old.started = started;
            old.ended = ended;
            old.failed = failed;
            return;
        }
        cancelUpcoming();
        var next = new NextAudio(ticket, track, started, ended, failed);
        upcoming.set(next);
        try {
            next.future = preloader.submit(() -> {
                try {
                    next.install(decoder.open(track.file()));
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger("darkmusic-audio").debug("Next track cannot be prepared yet", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            upcoming.compareAndSet(next, null);
            next.cancel();
        }
    }

    private void cancelUpcoming() {
        var old = upcoming.getAndSet(null);
        closingDecoders.removeIf(java.util.concurrent.CompletableFuture::isDone);
        if (old != null) closingDecoders.add(old.cancel());
        preloader.getQueue().clear();
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
        cancelUpcoming();
        ExternalAudio.abort(stream);
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
        float[] pcm = new float[2048];
        float[] nextPcm = new float[4096];
        try {
            while (!closed) {
                Request request = pending.getAndSet(null);
                if (request != null) {
                    release();
                    normalizationGain = incomingNormalization = 1;
                    mixing = null;
                    overlap = 0;
                    announcement = null;
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
                            Exception unavailable = null;
                            int preferredBits = format.getSampleSizeInBits() > 16 ? 24 : 16;
                            for (float fallback : new float[]{format.getSampleRate(), 48_000, 44_100}) {
                                for (int bits : preferredBits == 24 ? new int[]{24, 16} : new int[]{16}) {
                                    try {
                                        line = output.open(new AudioFormat(fallback, bits, format.getChannels(), true, false));
                                        break;
                                    } catch (Exception failure) {
                                        if (unavailable == null) unavailable = failure;
                                        else unavailable.addSuppressed(failure);
                                    }
                                }
                                if (line != null) break;
                            }
                            if (line == null) throw unavailable;
                            deviceRate = line.getFormat().getFrameRate();
                            processor.begin(deviceRate, line.getFormat().getChannels());
                            protectionFlushed = false;
                            processor.initialVolume(gain);
                            progressedAt = System.nanoTime();
                            lastPlayed = line.getLongFramePosition();
                            spectrum.begin(deviceRate, line.getFormat().getChannels());
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
                    } else if (line.available() >= line.getBufferSize()) {
                        // Decoder/disk starvation is not a stalled output device.
                        progressedAt = System.nanoTime();
                    } else if (System.nanoTime() - progressedAt > stallNanos)
                        throw new java.io.IOException("Audio output stopped making progress. Check or reconnect the audio device.");
                    int bytesPerSample = line.getFormat().getSampleSizeInBits() / 8;
                    int frame = line.getFormat().getChannels() * bytesPerSample;
                    int amount = Math.min(buffer.length, line.available()) / frame * frame;
                    if (amount == 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
                        continue;
                    }
                    double rate = speed;
                    if (timeline.getLast().speed != rate)
                        timeline.add(new Segment(submittedFrames, offset + converter.positionFrames() / stream.getFormat().getFrameRate(), rate));
                    int inputChannels = stream.getFormat().getChannels(), outputChannels = line.getFormat().getChannels();
                    processor.diagnostics().sourceChannels(inputChannels);
                    int frames = Math.min(amount / frame, pcm.length / inputChannels);
                    int samples = converter.read(pcm, frames * inputChannels, rate, deviceRate, !options.neutral && options.enhancements.preservePitch);
                    // Time spent decoding must not consume the output device's stall budget.
                    progressedAt = System.nanoTime();
                    if (samples > 0 && inputChannels != outputChannels) {
                        if (inputChannels == 1) {
                            for (int i = samples - 1; i >= 0; i--) {
                                pcm[2 * i] = pcm[i];
                                pcm[2 * i + 1] = pcm[i];
                            }
                            samples *= 2;
                        } else {
                            for (int i = 0; i < samples / 2; i++) pcm[i] = (pcm[2 * i] + pcm[2 * i + 1]) * .5f;
                            samples /= 2;
                        }
                    }
                    int count = samples < 0 ? -1 : samples * bytesPerSample;
                    boolean flushing = false;
                    var preparedNext = upcoming.get();
                    if (count < 0 && !protectionFlushed && processor.latencyFrames() > 0
                        && (preparedNext == null || preparedNext.peek() == null || !options.gapless && options.crossfade <= 0)) {
                        samples = processor.latencyFrames() * outputChannels;
                        java.util.Arrays.fill(pcm, 0, samples, 0);
                        count = samples * bytesPerSample;
                        protectionFlushed = flushing = true;
                    }
                    if (count < 0) {
                        if (handoff(request)) continue;
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
                        var selected = options;
                        processor.configure(selected, environment);
                        processor.comparison(comparison);
                        double normalized = 1;
                        boolean normalize = !selected.neutral && selected.normalization;
                        if (normalize && !flushing) {
                            if (loudness == null) loudness = new LoudnessCache();
                            normalized = loudness.gain(request.track, selected);
                        } else if (loudness != null) {
                            loudness.closeAsync();
                            loudness = null;
                        }
                        double gainSmoothing = 1 - Math.exp(-1 / (deviceRate * .05));
                        for (int f = 0; f < samples / outputChannels; f++) {
                            normalizationGain += (normalized - normalizationGain) * gainSmoothing;
                            for (int c = 0; c < outputChannels; c++)
                                pcm[f * outputChannels + c] *= (float) normalizationGain;
                        }
                        normalizationStatus = loudness == null ? LoudnessCache.Status.OFF : loudness.status(request.track, selected, normalizationGain);
                        var next = upcoming.get();
                        if (next != mixing) {
                            mixing = next;
                            overlap = 0;
                            incomingNormalization = 1;
                        }
                        var ready = next == null ? null : next.peek();
                        double remaining = request.track.duration() - (offset + converter.positionFrames() / stream.getFormat().getFrameRate());
                        if (selected.crossfade > 0 && remaining / rate <= selected.crossfade && request.track.duration() > 0 && ready != null && next.generation == request.generation) {
                            int outputFrames = samples / outputChannels;
                            int nextChannels = ready.stream().getFormat().getChannels();
                            int n;
                            try {
                                n = ready.converter().read(nextPcm, outputFrames * nextChannels, rate, deviceRate, !selected.neutral && selected.enhancements.preservePitch);
                            } catch (Exception failure) {
                                n = -1;
                                org.slf4j.LoggerFactory.getLogger("darkmusic-audio").debug("Prepared track became unavailable", failure);
                            }
                            if (n > 0) {
                                double incomingTarget = normalize ? loudness.gain(next.track, selected) : 1;
                                if (overlap == 0)
                                    overlap = Math.max(0, selected.crossfade - Math.max(0, remaining / rate));
                                for (int f = 0; f < outputFrames; f++) {
                                    incomingNormalization += (incomingTarget - incomingNormalization) * gainSmoothing;
                                    double t = Math.clamp((overlap + f / deviceRate) / selected.crossfade, 0, 1), mix = Math.pow(Math.sin(t * Math.PI / 2), 2);
                                    for (int c = 0; c < outputChannels; c++) {
                                        double incoming = 0;
                                        if (f * nextChannels < n)
                                            incoming = nextChannels == 1 ? nextPcm[f] : outputChannels == 1 ? (nextPcm[f * 2] + nextPcm[f * 2 + 1]) * .5 : nextPcm[f * 2 + c];
                                        pcm[f * outputChannels + c] = (float) (pcm[f * outputChannels + c] * (1 - mix) + incoming * incomingNormalization * mix);
                                    }
                                }
                                overlap += outputFrames / deviceRate;
                            }
                        }
                        processor.process(pcm, samples, gain, 1);
                        AudioProcessor.quantize(pcm, buffer, samples, bytesPerSample);
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
                            spectrum.accept(buffer, written, sent, submittedFrames, bytesPerSample);
                            processor.diagnostics().submitted(buffer, written, sent, submittedFrames, bytesPerSample, processor);
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
            if (loudness != null) loudness.closeAsync();
        }
    }

    private boolean handoff(Request previous) {
        var next = upcoming.get();
        var selected = options;
        if (next == null || next.generation != previous.generation || !selected.gapless && selected.crossfade <= 0)
            return false;
        var ready = next.peek();
        if (ready == null) return false;
        // Preserve one device and map mono/stereo only when its output layout is compatible.
        if (!upcoming.compareAndSet(next, null)) return false;
        ready = next.claim();
        if (ready == null) return false;
        double oldPosition = offset + converter.positionFrames() / stream.getFormat().getFrameRate();
        NextAudio.close(stream);
        stream = ready.stream();
        converter = ready.converter();
        offset = 0;
        timeline.clear();
        double start = converter.positionFrames() / stream.getFormat().getFrameRate();
        timeline.add(new Segment(submittedFrames, start, speed));
        active = new Request(previous.generation, next.track, 0, next.ended, next.failed);
        normalizationGain = incomingNormalization;
        incomingNormalization = 1;
        mixing = null;
        announcement = new Announcement(previous, next, submittedFrames, oldPosition);
        overlap = 0;
        processor.begin(deviceRate, line.getFormat().getChannels(), true);
        protectionFlushed = false;
        return true;
    }

    private void update(Request r, Status status) {
        if (current(r)) {
            long played = line.getLongFramePosition();
            spectrum.advance(played, status == Status.PLAYING);
            processor.diagnostics().advance(played);
            played = Math.max(0, played - processor.latencyFrames());
            if (announcement != null) {
                if (played < announcement.boundary) {
                    state = new State(announcement.previous.track, status, Math.max(0, announcement.oldPosition - (announcement.boundary - played) / deviceRate * speed), announcement.previous.track.duration(), "");
                    return;
                }
                var notice = announcement;
                announcement = null;
                callbacks.execute(() -> {
                    if (current(r)) notice.next.started.run();
                });
            }
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
        processor.diagnostics().clear();
        normalizationStatus = LoudnessCache.Status.OFF;
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
    public boolean awaitStopped(long seconds) throws InterruptedException {
        worker.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(seconds));
        return !worker.isAlive();
    }

    @Override
    public boolean awaitIdle(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            closingDecoders.removeIf(java.util.concurrent.CompletableFuture::isDone);
            var current = active;
            if (pending.get() == null && (current == null || current.track == null) && stream == null && preloader.getActiveCount() == 0 && closingDecoders.isEmpty())
                return true;
            Thread.sleep(10);
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        cancelUpcoming();
        ExternalAudio.abort(stream);
        preloader.shutdownNow();
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

    private record Announcement(Request previous, NextAudio next, long boundary, double oldPosition) {
    }
}
