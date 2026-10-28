package com.darkmusic.client.systemmedia;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.Track;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the Media Controls helper while the option is on: sends what is playing, and passes the buttons Windows
 * reports (media keys, headset buttons, the media flyout) to {@code actions} on a background thread.
 */
public final class MediaControls implements AutoCloseable {
    // Windows shows the timeline from the last update, so playing positions are refreshed this often.
    static final long POSITION_REFRESH = TimeUnit.SECONDS.toNanos(5);
    private static final Logger LOG = LoggerFactory.getLogger("darkmusic-media");

    /**
     * What Windows shows. Artwork is a file path, or "".
     */
    public record State(String status, String title, String artist, String album, double position, double duration,
                        String artwork) {
        static State of(AudioBackend.State playback, String artwork) {
            var track = playback.track();
            String status = switch (playback.status()) {
                case PLAYING, LOADING, SEEKING -> "playing";
                case PAUSED -> "paused";
                default -> "stopped";
            };
            if (track == null) return new State("stopped", "", "", "", 0, 0, "");
            return new State(status, Objects.requireNonNullElse(track.title(), ""), Objects.requireNonNullElse(track.artist(), ""),
                Objects.requireNonNullElse(track.album(), ""), Double.isFinite(playback.position()) ? playback.position() : 0,
                Double.isFinite(playback.duration()) ? playback.duration() : 0, artwork);
        }

        boolean sameExceptPosition(State other) {
            return other != null && status.equals(other.status) && title.equals(other.title) && artist.equals(other.artist)
                && album.equals(other.album) && duration == other.duration && artwork.equals(other.artwork);
        }

        String json() {
            var o = new JsonObject();
            o.addProperty("status", status);
            o.addProperty("title", title);
            o.addProperty("artist", artist);
            o.addProperty("album", album);
            o.addProperty("position", position);
            o.addProperty("duration", duration);
            o.addProperty("artwork", artwork);
            return o.toString();
        }
    }

    /**
     * A button press: one word, and for seek a position in seconds.
     */
    public record Action(String button, double position) {
        static Action parse(String line) {
            if (line == null) return null;
            String[] parts = line.strip().split(" ");
            return switch (parts[0]) {
                case "play", "pause", "next", "previous", "stop" -> parts.length == 1 ? new Action(parts[0], 0) : null;
                case "seek" -> {
                    try {
                        double seconds = parts.length == 2 ? Double.parseDouble(parts[1]) : Double.NaN;
                        yield Double.isFinite(seconds) && seconds >= 0 ? new Action("seek", seconds) : null;
                    } catch (NumberFormatException invalid) {
                        yield null;
                    }
                }
                default -> null;
            };
        }
    }

    /**
     * Whether to send now: anything but the position changed, a seek of two seconds or more, or the regular refresh.
     */
    static boolean due(State sent, long sentAt, State state, long now) {
        if (!state.sameExceptPosition(sent)) return true;
        double expected = sent.position() + (state.status().equals("playing") ? (now - sentAt) / 1e9 : 0);
        if (Math.abs(state.position() - expected) >= 2) return true;
        return state.status().equals("playing") && now - sentAt >= POSITION_REFRESH;
    }

    private final Path folder;
    private final Consumer<Action> actions;
    private final ExecutorService artworkWorker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("darkmusic-media-artwork").factory());
    private Process process;
    private BufferedWriter input;
    private State sent;
    private long sentAt, retryAt, startedAt;
    private int failures;
    private volatile boolean connected, closed;
    private volatile String artworkTrack = "";
    private volatile String artworkPath = "";

    public MediaControls(Path folder, Consumer<Action> actions) {
        this.folder = folder;
        this.actions = actions;
    }

    public boolean connected() {
        return connected && process != null && process.isAlive();
    }

    /**
     * Called every client tick. A null executable stops the helper.
     */
    public void update(Path executable, AudioBackend.State playback, long now) {
        if (closed) return;
        if (executable == null || playback == null) {
            stop();
            return;
        }
        if (process == null || !process.isAlive()) {
            if (process != null) ended(now);
            if (now < retryAt || !start(executable, now)) return;
        }
        var state = State.of(playback, artwork(playback.track()));
        if (!due(sent, sentAt, state, now)) return;
        try {
            input.write(state.json());
            input.newLine();
            input.flush();
            sent = state;
            sentAt = now;
        } catch (IOException gone) {
            LOG.info("Media Controls helper stopped responding ({})", gone.toString());
            stop();
            retryAt = now + TimeUnit.SECONDS.toNanos(5);
        }
    }

    private boolean start(Path executable, long now) {
        try {
            Files.createDirectories(folder);
            process = new ProcessBuilder(executable.toString()).directory(executable.getParent().toFile())
                .redirectError(ProcessBuilder.Redirect.to(folder.resolve("helper-errors.log").toFile())).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            sent = null;
            startedAt = now;
            var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread.ofPlatform().daemon().name("darkmusic-media-controls").start(() -> read(output));
            return true;
        } catch (IOException unavailable) {
            LOG.warn("Media Controls helper could not start", unavailable);
            process = null;
            retryAt = now + TimeUnit.SECONDS.toNanos(60);
            return false;
        }
    }

    private void read(BufferedReader output) {
        try (output) {
            for (String line; (line = output.readLine()) != null; ) {
                if (line.startsWith("ready ")) {
                    connected = true;
                    LOG.info("Media Controls connected to Windows media controls");
                    continue;
                }
                var action = Action.parse(line);
                if (action != null) actions.accept(action);
            }
        } catch (IOException ended) {
            // The helper exited; update() restarts it.
        } finally {
            connected = false;
        }
    }

    /**
     * A helper that keeps exiting soon after starting waits longer each time, up to a minute.
     */
    private void ended(long now) {
        int code = process.exitValue();
        process = null;
        connected = false;
        failures = now - startedAt < TimeUnit.SECONDS.toNanos(10) ? failures + 1 : 0;
        retryAt = now + Math.min(TimeUnit.SECONDS.toNanos(60), TimeUnit.SECONDS.toNanos(2) << Math.min(failures, 5));
        LOG.info("Media Controls helper exited with code {}; restarting", code);
    }

    /**
     * Embedded artwork is written to a file for Windows once per song, off the client thread.
     */
    private String artwork(Track track) {
        String id = track == null ? "" : track.id();
        if (!id.equals(artworkTrack)) {
            artworkTrack = id;
            artworkPath = "";
            if (track != null) {
                try {
                    artworkWorker.execute(() -> {
                        try {
                            byte[] bytes = new MetadataReader().artwork(track.file());
                            if (bytes == null || bytes.length > 16 * 1024 * 1024 || !id.equals(artworkTrack)) return;
                            boolean png = bytes.length > 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P';
                            Path dir = Files.createDirectories(folder.resolve("artwork"));
                            try (var old = Files.list(dir)) {
                                for (Path file : old.toList()) Files.deleteIfExists(file);
                            }
                            Path file = dir.resolve(Integer.toHexString(id.hashCode()) + (png ? ".png" : ".jpg"));
                            Files.write(file, bytes);
                            if (id.equals(artworkTrack)) artworkPath = file.toAbsolutePath().toString();
                        } catch (Exception unreadable) {
                            LOG.debug("No artwork for Media Controls", unreadable);
                        }
                    });
                } catch (RejectedExecutionException closing) {
                    // Shutting down.
                }
            }
        }
        return artworkPath;
    }

    private void stop() {
        connected = false;
        sent = null;
        if (process == null) return;
        var ending = process;
        process = null;
        try {
            // Closing its input ends the helper on its own.
            input.close();
        } catch (IOException ignored) {
        }
        ending.onExit().orTimeout(2, TimeUnit.SECONDS).exceptionally(slow -> {
            ending.destroy();
            return null;
        });
    }

    @Override
    public void close() {
        closed = true;
        stop();
        artworkWorker.shutdownNow();
    }
}
