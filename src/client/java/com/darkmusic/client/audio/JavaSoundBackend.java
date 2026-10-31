package com.darkmusic.client.audio;

import com.darkmusic.client.config.AudioSettings;
import com.darkmusic.client.config.SoundEnvironmentSettings;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.media.OpenedAudio;
import com.darkmusic.client.text.Text;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One worker owns every decoder and output resource. PCM is bounded to small buffers.
 */
public final class JavaSoundBackend implements AudioBackend {
    private static final class OutputFailure extends IOException {
        OutputFailure(String key) {
            super(Text.get(key));
        }
    }

    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer();
    private final AudioProcessor processor = new AudioProcessor();
    private final AudioProcessor.Dither dither = new AudioProcessor.Dither(System.nanoTime());
    private volatile SoundEnvironmentSettings environment = new SoundEnvironmentSettings();
    private volatile AudioComparison comparison = AudioComparison.OFF;
    private boolean protectionFlushed;
    private volatile LoudnessCache.Status normalizationStatus = LoudnessCache.Status.OFF;
    private volatile AudioSettings options = new AudioSettings();
    private volatile LoudnessCache loudness;
    private final AtomicReference<NextAudio> upcoming = new AtomicReference<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> closingDecoders = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor preloader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("darkmusic-preload").factory());
    private volatile double overlap;
    private double normalizationGain = 1, incomingNormalization = 1;
    private LoudnessCache.Session normalizationSession, incomingSession;
    private long normalizationSessionId;
    private volatile NextAudio mixing;
    private volatile Announcement announcement;
    private final Executor callbacks;
    private final Decoder decoder;
    private volatile Output output;
    private final AtomicReference<OutputChange> outputChange = new AtomicReference<>();
    private static final AtomicLong SESSIONS = new AtomicLong();
    private long hearingFrame;
    private volatile long playbackSession;
    private volatile HearingListener hearing = (track, session, seconds) -> {
    };

    @FunctionalInterface
    public interface HearingListener {
        void heard(Track track, long session, double seconds);
    }

    public void listening(HearingListener listener) {
        hearing = listener;
    }

    public String outputDeviceId() {
        return output instanceof OutputDevices devices ? devices.actual() : "";
    }

    public CompletableFuture<Boolean> output(Output selected) {
        var result = new CompletableFuture<Boolean>();
        if (closed) {
            result.complete(false);
            return result;
        }
        var change = new OutputChange(selected, result);
        var old = outputChange.getAndSet(change);
        if (old != null) old.result.complete(false);
        if (closed && outputChange.compareAndSet(change, null)) result.complete(false);
        LockSupport.unpark(worker);
        return result;
    }

    private final Thread worker;
    private final ArrayDeque<Segment> timeline = new ArrayDeque<>();
    private final long stallNanos;
    private final long decoderStallNanos;
    private PcmRateConverter emptyConverter;
    private long decoderEmptySince;
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
        this(callbacks, new Decoder() {
            public AudioInputStream open(Path path) throws Exception {
                return PcmDecoder.openPrecise(path);
            }

            public OpenedAudio openAt(Path path, double seconds) throws Exception {
                return PcmDecoder.openAt(path, seconds);
            }
        }, format -> {
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
        this(callbacks, decoder, output, Duration.ofSeconds(5));
    }

    public JavaSoundBackend(Executor callbacks, Decoder decoder, Output output, Duration stallTimeout) {
        this(callbacks, decoder, output, stallTimeout, Duration.ofSeconds(5));
    }

    public JavaSoundBackend(Executor callbacks, Decoder decoder, Output output, Duration stallTimeout, Duration decoderTimeout) {
        this.callbacks = callbacks;
        this.stallNanos = stallTimeout.toNanos();
        this.decoderStallNanos = Math.max(1, decoderTimeout.toNanos());
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
        long session = SESSIONS.incrementAndGet();
        playbackSession = session;
        submit(track, Double.isFinite(position) ? Math.max(0, position) : 0, ended, failed, session);
    }

    @Override
    public void resumeFrom(Track track, double position, boolean paused, Runnable ended, Consumer<String> failed) {
        this.paused = paused;
        if (playbackSession == 0) playbackSession = SESSIONS.incrementAndGet();
        submit(track, Double.isFinite(position) ? Math.max(0, position) : 0, ended, failed, playbackSession);
    }

    private void submit(Track track, double seconds, Runnable ended, Consumer<String> failed, long session) {
        if (closed) return;
        var request = active;
        boolean preserveNext = request != null && request.session == session && request.track.equals(track)
            && mixing == null && overlap <= 0 && announcement == null;
        if (!preserveNext) cancelUpcoming();
        ExternalAudio.abort(stream);
        long ticket = generation.incrementAndGet();
        var prepared = upcoming.get();
        if (preserveNext && prepared != null) prepared.generation = ticket;
        pending.set(new Request(ticket, track, seconds, ended, failed, session));
        state = new State(track, seconds > 0 ? Status.SEEKING : Status.LOADING, seconds, track.duration(), "");
        LockSupport.unpark(worker);
    }

    @Override
    public void pause() {
        paused = true;
        LockSupport.unpark(worker);
    }

    @Override
    public void resume() {
        paused = false;
        LockSupport.unpark(worker);
    }

    @Override
    public void volume(double value) {
        gain = Double.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
    }

    @Override
    public void speed(double value) {
        speed = PcmRateConverter.clamp(value);
        LockSupport.unpark(worker);
    }

    @Override
    public void configure(AudioSettings value) {
        var previous = options;
        options = value.playbackCopy();
        if (!options.neutral && options.normalization) loudnessCache();
        var cache = loudness;
        if (cache != null) {
            cache.parallel(options.parallelLoudnessAnalysis);
            if (options.neutral || !options.normalization || !options.analyzeLoudness || options.gainMode != previous.gainMode)
                cache.cancelAnalysis();
        }
    }

    public void analyzeLibrary(List<Track> tracks, AudioSettings settings) {
        var cache = loudnessCache();
        if (cache != null) cache.analyzeLibrary(tracks, settings);
    }

    public LoudnessCache.AnalysisProgress analysisProgress() {
        var cache = loudness;
        return cache == null ? LoudnessCache.AnalysisProgress.IDLE : cache.analysisProgress();
    }

    public void cancelAnalysis() {
        var cache = loudness;
        if (cache != null) cache.cancelAnalysis();
    }

    private synchronized LoudnessCache loudnessCache() {
        if (loudness == null && !closed) loudness = new LoudnessCache();
        return loudness;
    }

    @Override
    public void configure(AudioSettings value, SoundEnvironmentSettings space) {
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
        var old = upcoming.get();
        nextTrack(track, started, ended, failed, old != null && old.generation == generation.get()
            && old.track.equals(track) ? old.transition : new AudioBackend.Transition());
    }

    @Override
    public void nextTrack(Track track, Runnable started, Runnable ended, Consumer<String> failed, AudioBackend.Transition transition) {
        var selected = options;
        if (!closed && track != null && !selected.neutral && selected.normalization) {
            var cache = loudnessCache();
            if (cache != null) cache.prefetch(track, selected);
        }
        if (closed || track == null || !selected.gapless && selected.crossfade <= 0) {
            cancelUpcoming();
            return;
        }
        var old = upcoming.get();
        long ticket = generation.get();
        if (old != null && old.generation == ticket && old.track.equals(track) && old.transition == transition) {
            old.started = started;
            old.ended = ended;
            old.failed = failed;
            return;
        }
        cancelUpcoming();
        var next = new NextAudio(ticket, track, started, ended, failed, transition);
        upcoming.set(next);
        try {
            next.future = preloader.submit(() -> {
                try {
                    next.install(decoder.open(track.file()));
                } catch (Exception e) {
                    LoggerFactory.getLogger("darkmusic-audio").debug("Next track cannot be prepared yet", e);
                }
            });
        } catch (RejectedExecutionException e) {
            upcoming.compareAndSet(next, null);
            next.cancel();
        }
    }

    private void cancelUpcoming() {
        var old = upcoming.getAndSet(null);
        closingDecoders.removeIf(CompletableFuture::isDone);
        if (old != null) closingDecoders.add(old.cancel());
        preloader.getQueue().clear();
    }

    @Override
    public void seek(double seconds) {
        Request request = pending.get();
        if (request == null) request = active;
        var notice = announcement;
        if (notice != null && request == active) request = notice.previous;
        if (request == null || request.track == null || !Double.isFinite(seconds)) return;
        submit(request.track, Math.max(0, seconds), request.ended, request.failed, request.session);
    }

    @Override
    public void stop() {
        cancelUpcoming();
        ExternalAudio.abort(stream);
        long ticket = generation.incrementAndGet();
        pending.set(new Request(ticket, null, 0, () -> {
        }, s -> {
        }, 0));
        state = new State(state.track(), Status.STOPPED, 0, state.duration(), "");
        LockSupport.unpark(worker);
    }

    private boolean current(Request request) {
        return !closed && request.generation == generation.get();
    }

    static long discardFrames(AudioInputStream input, long frames, byte[] buffer, BooleanSupplier current) throws IOException, InterruptedException {
        int frame = input.getFormat().getFrameSize();
        if (frame <= 0 || frame > buffer.length) throw new IOException("Invalid PCM frame size");
        long skipped = 0;
        int emptyReads = 0;
        while (skipped < frames && current.getAsBoolean()) {
            int wanted = (int) Math.min(buffer.length / frame, frames - skipped) * frame;
            int read = input.read(buffer, 0, wanted);
            if (read < 0) break;
            if (read == 0) {
                if (++emptyReads >= 8)
                    throw new IOException(Text.get("audio.error.decoder_stalled"));
                Thread.sleep(2);
                continue;
            }
            emptyReads = 0;
            skipped += read / frame;
        }
        return skipped;
    }

    private void run() {
        byte[] buffer = new byte[4096];
        float[] pcm = new float[2048];
        float[] nextPcm = new float[4096];
        try {
            while (!closed) {
                Request request = pending.getAndSet(null);
                if (request != null && !begin(request)) continue;
                var route = outputChange.get();
                // A prepared next track is not an active crossfade.
                if (route != null && (paused || overlap <= 0 && announcement == null) && outputChange.compareAndSet(route, null))
                    changeOutput(route);
                request = active;
                if (stream == null || line == null || request == null || !current(request)) {
                    LockSupport.park();
                    continue;
                }
                try {
                    play(request, buffer, pcm, nextPcm);
                } catch (Throwable e) {
                    if (!recover(e)) fail(request, e);
                }

            }
        } finally {
            release();
            synchronized (this) {
                if (loudness != null) loudness.closeAsync();
            }
        }
    }

    /**
     * Takes a new request: carries over the prepared next track and its normalization session, releases the old line
     * and opens the request's song. Returns false when the request was replaced while it was opening, so the loop starts
     * over without playing.
     */
    private boolean begin(Request request) {
        var prepared = upcoming.get();
        if (prepared != null && !prepared.untouched()) {
            cancelUpcoming();
            if (current(request))
                nextTrack(prepared.track, prepared.started, prepared.ended, prepared.failed, prepared.transition);
        }
        if (announcement != null && request.session == announcement.previous.session) {
            normalizationSession = announcement.normalization;
            normalizationSessionId = request.session;
            normalizationGain = announcement.gain;
        }
        release();
        if (normalizationSessionId != request.session) {
            normalizationSession = null;
            normalizationGain = 1;
            normalizationSessionId = request.session;
        }
        incomingSession = null;
        incomingNormalization = 1;
        mixing = null;
        overlap = 0;
        announcement = null;
        active = request;
        return request.track == null || open(request);
    }

    /**
     * Opens the request's song at its position, then the output line. A failure fails the request. Returns false when
     * the request was replaced while seeking.
     */
    private boolean open(Request request) {
        try {
            var opened = decoder.openAt(request.track.file(), request.position);
            stream = opened.stream();
            AudioFormat format = stream.getFormat();
            long framesToSkip = Math.max(0, Math.round((request.position - opened.offset()) * format.getFrameRate()));
            Request seeking = request;
            long skipped = discardFrames(stream, framesToSkip, new byte[65536], () -> current(seeking));
            if (!current(request)) {
                release();
                return false;
            }
            offset = opened.offset() + skipped / (double) format.getFrameRate();
            converter = new PcmRateConverter(stream);
            submittedFrames = 0;
            hearingFrame = 0;
            timeline.clear();
            timeline.add(new Segment(0, offset, speed));
            openLine(format);
            deviceRate = line.getFormat().getFrameRate();
            processor.begin(deviceRate, line.getFormat().getChannels());
            protectionFlushed = false;
            processor.initialVolume(gain);
            var selected = options;
            if (!selected.neutral && selected.normalization)
                normalizationGain = normalization(request.track, selected, false).gain(selected);
            normalizationStatus = normalizationSession == null ? LoudnessCache.Status.OFF : normalizationSession.status(selected, normalizationGain);
            progressedAt = System.nanoTime();
            lastPlayed = line.getLongFramePosition();
            spectrum.begin(deviceRate, line.getFormat().getChannels());
            if (!paused) line.start();
        } catch (Throwable e) {
            fail(request, e);
        }
        return true;
    }

    /**
     * The song's own rate and depth first, then 48 and 44.1 kHz; 24-bit sources try 24 bits before 16. Throws the first
     * failure, with the later ones suppressed, when no format opens.
     */
    private void openLine(AudioFormat format) throws Exception {
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
    }

    /**
     * One pass of the playing request: wait for the device, decode a block, then finish, hand off or mix and write it.
     */
    private void play(Request request, byte[] buffer, float[] pcm, float[] nextPcm) throws Exception {
        if (!waitForOutput(request)) return;
        int bytesPerSample = line.getFormat().getSampleSizeInBits() / 8;
        int frame = line.getFormat().getChannels() * bytesPerSample;
        int amount = Math.min(buffer.length, line.available()) / frame * frame;
        if (amount == 0) {
            LockSupport.parkNanos(5_000_000);
            return;
        }
        double rate = speed;
        int samples = decode(pcm, amount, frame, rate);
        int outputChannels = line.getFormat().getChannels();
        int count = samples < 0 ? -1 : samples * bytesPerSample;
        boolean flushing = false;
        var preparedNext = upcoming.get();
        if (count < 0 && !protectionFlushed && processor.latencyFrames() > 0
            && (preparedNext == null || preparedNext.peek() == null || !options.gapless && options.crossfade <= 0)) {
            samples = processor.latencyFrames() * outputChannels;
            Arrays.fill(pcm, 0, samples, 0);
            count = samples * bytesPerSample;
            protectionFlushed = flushing = true;
        }
        if (count < 0) {
            finishOrHandoff(request);
        } else if (count == 0) {
            // Vorbis providers can return zero while priming their PCM buffer.
            LockSupport.parkNanos(5_000_000);
        } else if (current(request)) {
            mix(request, pcm, nextPcm, samples, rate, flushing, outputChannels);
            write(request, pcm, buffer, samples, count, bytesPerSample, frame);
        }
    }

    /**
     * Checks the device before a block: a disconnected explicit device fails, a paused request parks (returning false),
     * and a device whose position stopped moving for the stall time fails.
     */
    private boolean waitForOutput(Request request) throws Exception {
        if (output instanceof OutputDevices && !line.isOpen())
            throw new LineUnavailableException("Audio output disconnected");
        if (paused) {
            decoderEmptySince = 0;
            line.stop();
            update(request, Status.PAUSED);
            progressedAt = System.nanoTime();
            if (output instanceof OutputDevices devices && devices.isExplicit())
                LockSupport.parkNanos(100_000_000);
            else LockSupport.park();
            progressedAt = System.nanoTime();
            return false;
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
            throw new OutputFailure("audio.error.stalled");
        return true;
    }

    /**
     * Decodes up to amount bytes' worth of frames at rate into pcm, in the line's channel count. Returns the samples
     * decoded, or a negative number at the end of the stream. A decoder that yields nothing for too long fails.
     */
    private int decode(float[] pcm, int amount, int frame, double rate) throws Exception {
        if (timeline.getLast().speed != rate)
            timeline.add(new Segment(submittedFrames, offset + converter.positionFrames() / stream.getFormat().getFrameRate(), rate));
        int inputChannels = stream.getFormat().getChannels(), outputChannels = line.getFormat().getChannels();
        processor.diagnostics().sourceChannels(inputChannels);
        int frames = Math.min(amount / frame, pcm.length / inputChannels);
        long decodedBefore = converter.decodedFrames();
        int samples = converter.read(pcm, frames * inputChannels, rate, deviceRate, options.enhancements.preservePitch);
        if (samples == 0 && converter.decodedFrames() == decodedBefore) {
            long now = System.nanoTime();
            if (emptyConverter != converter || decoderEmptySince == 0) {
                emptyConverter = converter;
                decoderEmptySince = now;
            } else if (now - decoderEmptySince > decoderStallNanos)
                throw new IOException(Text.get("audio.error.decoder_stalled"));
        } else decoderEmptySince = 0;
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
        return samples;
    }

    /**
     * The end of the stream: hand off to the prepared track, or let the line drain with short polls, so stop and seek
     * never wait on a blocking drain(), and then report the song ended.
     */
    private void finishOrHandoff(Request request) {
        if (handoff(request)) return;
        // Drain with short polls so stop/seek never waits on a blocking drain().
        if (line.available() < line.getBufferSize()) {
            LockSupport.parkNanos(5_000_000);
            return;
        }
        update(request, Status.STOPPED);
        release();
        active = null;
        Request finished = request;
        callbacks.execute(() -> {
            if (current(finished)) finished.ended.run();
        });
    }

    /**
     * Applies loudness normalization, smoothed over 50 ms, and in the last crossfade seconds mixes in the prepared
     * track at its own normalization.
     */
    private void mix(Request request, float[] pcm, float[] nextPcm, int samples, double rate, boolean flushing, int outputChannels) {
        var selected = options;
        processor.configure(selected, environment);
        processor.comparison(comparison);
        double normalized = 1;
        boolean normalize = !selected.neutral && selected.normalization;
        if (normalize && !flushing) {
            normalized = normalization(request.track, selected, false).gain(selected);
        } else if (normalize) {
            normalized = normalizationGain;
        }
        double gainSmoothing = 1 - Math.exp(-1 / (deviceRate * .05));
        for (int f = 0; f < samples / outputChannels; f++) {
            normalizationGain += (normalized - normalizationGain) * gainSmoothing;
            for (int c = 0; c < outputChannels; c++)
                pcm[f * outputChannels + c] *= (float) normalizationGain;
        }
        normalizationStatus = normalizationSession == null ? LoudnessCache.Status.OFF : normalizationSession.status(selected, normalizationGain);
        var next = upcoming.get();
        if (next != mixing) {
            mixing = next;
            overlap = 0;
            incomingNormalization = 1;
            incomingSession = null;
        }
        var ready = next == null ? null : next.peek();
        double remaining = request.track.duration() - (offset + converter.positionFrames() / stream.getFormat().getFrameRate());
        if (selected.crossfade > 0 && remaining / rate <= selected.crossfade && request.track.duration() > 0 && ready != null && next.generation == request.generation)
            crossfade(selected, next, ready, pcm, nextPcm, samples, rate, remaining, normalize, gainSmoothing, outputChannels);
    }

    /**
     * Mixes the prepared track into the block, rising as this one falls; selected is the settings the block began with.
     */
    private void crossfade(AudioSettings selected, NextAudio next, NextAudio.Ready ready, float[] pcm, float[] nextPcm, int samples,
                           double rate, double remaining, boolean normalize, double gainSmoothing, int outputChannels) {
        int outputFrames = samples / outputChannels;
        int nextChannels = ready.stream().getFormat().getChannels();
        int n;
        try {
            n = ready.converter().read(nextPcm, outputFrames * nextChannels, rate, deviceRate, selected.enhancements.preservePitch);
        } catch (Exception failure) {
            n = -1;
            LoggerFactory.getLogger("darkmusic-audio").debug("Prepared track became unavailable", failure);
        }
        if (n > 0) {
            double incomingTarget = normalize ? normalization(next.track, selected, true).gain(selected) : 1;
            if (overlap == 0)
                overlap = Math.max(0, selected.crossfade - Math.max(0, remaining / rate));
            for (int f = 0; f < outputFrames; f++) {
                incomingNormalization += (incomingTarget - incomingNormalization) * gainSmoothing;
                double t = Math.clamp((overlap + f / deviceRate) / selected.crossfade, 0, 1);
                double incomingGain = crossfadeGain(t, true), outgoingGain = crossfadeGain(t, false);
                for (int c = 0; c < outputChannels; c++) {
                    double incoming = 0;
                    if (f * nextChannels < n)
                        incoming = nextChannels == 1 ? nextPcm[f] : outputChannels == 1 ? (nextPcm[f * 2] + nextPcm[f * 2 + 1]) * .5 : nextPcm[f * 2 + c];
                    pcm[f * outputChannels + c] = (float) (pcm[f * outputChannels + c] * outgoingGain + incoming * incomingNormalization * incomingGain);
                }
            }
            overlap += outputFrames / deviceRate;
        }
    }

    /**
     * Processes the block and writes it, waiting while paused and failing a device that accepts nothing for the stall
     * time. A pending output change ends the wait so the loop can switch devices.
     */
    private void write(Request request, float[] pcm, byte[] buffer, int samples, int count, int bytesPerSample, int frame) throws Exception {
        processor.process(pcm, samples, gain, 1);
        AudioProcessor.quantize(pcm, buffer, samples, bytesPerSample, dither);
        int written = 0;
        while (written < count && current(request)) {
            if (paused) {
                line.stop();
                update(request, Status.PAUSED);
                LockSupport.park();
                progressedAt = System.nanoTime();
                if (outputChange.get() != null) break;
                if (!paused && current(request)) line.start();
                continue;
            }
            int capacity = Math.min(count - written, line.available()) / frame * frame;
            int sent = capacity > 0 ? line.write(buffer, written, capacity) : 0;
            if (sent <= 0) {
                if (System.nanoTime() - progressedAt > stallNanos)
                    throw new OutputFailure("audio.error.stalled");
                LockSupport.parkNanos(5_000_000);
                continue;
            }
            if (sent % frame != 0)
                throw new OutputFailure("audio.error.device");
            spectrum.accept(buffer, written, sent, submittedFrames, bytesPerSample);
            processor.diagnostics().submitted(buffer, written, sent, submittedFrames, bytesPerSample, processor);
            written += sent;
            submittedFrames += sent / frame;
        }
    }

    /**
     * An explicit output device that disconnected, stalled or failed falls back to the default device. Returns true
     * when it did, so the request keeps playing there.
     */
    private boolean recover(Throwable e) {
        if (e instanceof Exception && output instanceof OutputDevices selected && selected.isExplicit() &&
            (!line.isOpen() || e instanceof LineUnavailableException ||
                e instanceof OutputFailure)) {
            var recovery = new CompletableFuture<Boolean>();
            changeOutput(new OutputChange(new OutputDevices("", false, s -> {
            }), recovery));
            if (recovery.getNow(false)) {
                callbacks.execute(selected::reportFallback);
                return true;
            }
        }
        return false;
    }

    private LoudnessCache.Session normalization(Track track, AudioSettings selected, boolean incoming) {
        var session = incoming ? incomingSession : normalizationSession;
        if (session == null || !session.matches(selected)) {
            var cache = loudnessCache();
            if (cache == null) throw new CancellationException("Playback closed");
            session = cache.session(track, selected);
            if (incoming) {
                incomingSession = session;
                incomingNormalization = session.gain(selected);
            } else {
                normalizationSession = session;
                normalizationGain = session.gain(selected);
            }
        }
        return session;
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
        if (ready == null) {
            next.cancel();
            return false;
        }
        double oldPosition = offset + converter.positionFrames() / stream.getFormat().getFrameRate();
        NextAudio.close(stream);
        stream = ready.stream();
        converter = ready.converter();
        offset = 0;
        timeline.clear();
        double start = converter.positionFrames() / stream.getFormat().getFrameRate();
        timeline.add(new Segment(submittedFrames, start, speed));
        var previousNormalization = normalizationSession;
        double previousGain = normalizationGain;
        active = new Request(previous.generation, next.track, 0, next.ended, next.failed, SESSIONS.incrementAndGet());
        if (!selected.neutral && selected.normalization) normalization(next.track, selected, true);
        normalizationSession = incomingSession;
        normalizationSessionId = active.session;
        normalizationGain = incomingNormalization;
        incomingNormalization = 1;
        incomingSession = null;
        mixing = null;
        announcement = new Announcement(previous, next, submittedFrames, oldPosition, previousNormalization, previousGain);
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
                    heard(announcement.previous, played, status);
                    state = new State(announcement.previous.track, status, Math.max(0, announcement.oldPosition - (announcement.boundary - played) / deviceRate * speed), announcement.previous.track.duration(), "");
                    return;
                }
                var notice = announcement;
                heard(notice.previous, notice.boundary, status);
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
            heard(r, played, status);
            double position = segment.sourceSeconds + Math.max(0, played - segment.outputFrame) / (double) line.getFormat().getFrameRate() * segment.speed;
            double duration = status == Status.STOPPED ? position : Math.max(r.track.duration(), position);
            state = new State(r.track, status, position, duration, "");
        }
    }

    private void fail(Request request, Throwable exception) {
        release();
        active = null;
        if (!current(request)) return;
        LoggerFactory.getLogger("darkmusic-audio").warn("Playback failed for {}", request.track.file(), exception);
        String message = Text.isMessage(exception.getMessage()) ? exception.getMessage()
            : Text.get(exception instanceof LineUnavailableException ? "audio.error.device" : "audio.error.playback");
        state = new State(request.track, Status.ERROR, 0, request.track.duration(), message);
        callbacks.execute(() -> {
            if (current(request)) request.failed.accept(message);
        });
    }

    private void heard(Request request, long played, Status status) {
        playbackSession = request.session;
        long advanced = Math.max(0, played - hearingFrame);
        hearingFrame = played;
        if (advanced > 0 && gain > 0 && (status == Status.PLAYING || status == Status.PAUSED || status == Status.STOPPED))
            hearing.heard(request.track, request.session, advanced / deviceRate);
    }

    /**
     * Prepare a compatible line and decoder before releasing the working route.
     */
    private void changeOutput(OutputChange change) {
        SourceDataLine candidate = null;
        AudioInputStream replacement = null;
        try {
            if (line == null || stream == null || active == null || active.track == null) {
                candidate = openRoute(change.output, new AudioFormat(48_000, 16, 2, true, false));
                candidate.close();
                candidate = null;
                output = change.output;
                change.result.complete(true);
                return;
            }
            try {
                update(active, paused ? Status.PAUSED : Status.PLAYING);
            } catch (RuntimeException lostDevice) { /* Retain the last device-clock position for recovery. */ }
            Request request = announcement != null ? announcement.previous : active;
            double position = state.position();
            candidate = openRoute(change.output, line.getFormat());
            var opened = decoder.openAt(request.track.file(), position);
            replacement = opened.stream();
            long frames = Math.max(0, Math.round((position - opened.offset()) * replacement.getFormat().getFrameRate()));
            long skipped = discardFrames(replacement, frames, new byte[65536], () -> current(request));
            if (!current(request) || closed) throw new IOException("Playback changed during output switch");
            if (announcement != null && request == announcement.previous) {
                normalizationSession = announcement.normalization;
                normalizationSessionId = request.session;
                normalizationGain = announcement.gain;
            }
            release();
            cancelUpcoming();
            mixing = null;
            announcement = null;
            overlap = 0;
            active = request;
            line = candidate;
            candidate = null;
            stream = replacement;
            replacement = null;
            output = change.output;
            converter = new PcmRateConverter(stream);
            offset = opened.offset() + skipped / (double) stream.getFormat().getFrameRate();
            submittedFrames = hearingFrame = 0;
            timeline.add(new Segment(0, offset, speed));
            deviceRate = line.getFormat().getFrameRate();
            processor.begin(deviceRate, line.getFormat().getChannels());
            processor.initialVolume(gain);
            normalizationStatus = normalizationSession == null ? LoudnessCache.Status.OFF : normalizationSession.status(options, normalizationGain);
            protectionFlushed = false;
            spectrum.begin(deviceRate, line.getFormat().getChannels());
            progressedAt = System.nanoTime();
            lastPlayed = line.getLongFramePosition();
            if (!paused) line.start();
            change.result.complete(true);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("darkmusic-audio").warn("Cannot switch audio output", failure);
            change.result.complete(false);
        } finally {
            if (candidate != null) candidate.close();
            if (replacement != null) try {
                replacement.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static SourceDataLine openRoute(Output output, AudioFormat preferred) throws Exception {
        Exception unavailable = null;
        for (float rate : new float[]{preferred.getSampleRate(), 48_000, 44_100})
            for (int channels : new int[]{preferred.getChannels(), preferred.getChannels() == 1 ? 2 : 1})
                for (int bits : preferred.getSampleSizeInBits() > 16 ? new int[]{24, 16} : new int[]{16}) {
                    try {
                        return output.open(new AudioFormat(rate, bits, channels, true, false));
                    } catch (Exception failure) {
                        if (unavailable == null) unavailable = failure;
                        else unavailable.addSuppressed(failure);
                    }
                }
        throw unavailable;
    }

    static double crossfadeGain(double fraction, boolean incoming) {
        double angle = Math.clamp(fraction, 0, 1) * Math.PI / 2;
        return incoming ? Math.sin(angle) : Math.cos(angle);
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
            } catch (Throwable ignored) {
            }
            try {
                line.flush();
            } catch (Throwable ignored) {
            }
            try {
                line.close();
            } catch (Throwable ignored) {
            } finally {
                line = null;
            }
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            } finally {
                stream = null;
            }
        }
    }

    /**
     * The worker has ended and the loudness cache's final write, which the worker queues as it ends, is on disk:
     * reset deletes that file next, and a late write would bring it back.
     */
    @Override
    public boolean awaitStopped(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        worker.join(TimeUnit.SECONDS.toMillis(seconds));
        if (worker.isAlive()) return false;
        var cache = loudness;
        if (cache == null) return true;
        try {
            cache.closeAsync().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException slow) {
            return false;
        } catch (ExecutionException failed) {
            // The cache logs a failed final write; nothing is left running.
            return true;
        }
    }

    @Override
    public boolean awaitIdle(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            closingDecoders.removeIf(CompletableFuture::isDone);
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
        var route = outputChange.getAndSet(null);
        if (route != null) route.result.complete(false);
        cancelUpcoming();
        ExternalAudio.abort(stream);
        preloader.shutdownNow();
        generation.incrementAndGet();
        pending.set(null);
        LockSupport.unpark(worker);
        try {
            worker.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface Decoder {
        AudioInputStream open(Path path) throws Exception;

        default OpenedAudio openAt(Path path, double seconds) throws Exception {
            return new OpenedAudio(open(path), 0);
        }
    }

    @FunctionalInterface
    public interface Output {
        SourceDataLine open(AudioFormat format) throws Exception;
    }

    private record Request(long generation, Track track, double position, Runnable ended, Consumer<String> failed,
                           long session) {
    }

    private record OutputChange(Output output, CompletableFuture<Boolean> result) {
    }

    private record Segment(long outputFrame, double sourceSeconds, double speed) {
    }

    private record Announcement(Request previous, NextAudio next, long boundary, double oldPosition,
                                LoudnessCache.Session normalization, double gain) {
    }

    /**
     * For tests: the audio worker thread.
     */
    Thread worker() {
        return worker;
    }

    /**
     * For tests: the loudness cache, or null before the first track opens.
     */
    LoudnessCache loudness() {
        return loudness;
    }

    /**
     * For tests: uses this cache instead of creating one.
     */
    synchronized void loudness(LoudnessCache cache) {
        loudness = cache;
    }

    /**
     * For tests: the crossfade length of the transition in progress, or 0.
     */
    double overlap() {
        return overlap;
    }

    /**
     * For tests: the settings snapshot playback uses.
     */
    AudioSettings options() {
        return options;
    }

    /**
     * For tests: the prepared next track, or null.
     */
    NextAudio upcoming() {
        return upcoming.get();
    }
}
