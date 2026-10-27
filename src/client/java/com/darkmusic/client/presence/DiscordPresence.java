package com.darkmusic.client.presence;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Opt-in "Listening to" status on the player's Discord profile, through the Discord desktop app on this computer.
 * The client thread only records what should be shown; a worker thread connects, rate-limits and sends.
 */
public final class DiscordPresence implements AutoCloseable {
    /**
     * Public ID of the DarkMusic application in Discord's developer portal. Not a secret.
     */
    public static final String APPLICATION_ID = "1564695332141404342";
    static final String ICON = "https://raw.githubusercontent.com/DarkLian/DarkMusic/main/src/main/resources/assets/darkmusic/icon.png";
    static final String RELEASES = "https://github.com/DarkLian/DarkMusic/releases/latest";
    // Discord accepts five activity updates per 20 seconds.
    static final long MIN_INTERVAL = TimeUnit.SECONDS.toNanos(4);
    static final long RETRY = TimeUnit.SECONDS.toNanos(30);
    // Re-sending the same activity notices a restarted Discord, which drops the old pipe.
    static final long REFRESH = TimeUnit.SECONDS.toNanos(60);
    private static final double SEEK_TOLERANCE = 2;

    /**
     * A playing song. Position is in seconds at {@code at} (epoch milliseconds); speed scales how fast it advances.
     */
    public record Song(String title, String artist, double position, double duration, double speed, long at,
                       String artwork) {
        public Song(String title, String artist, double position, double duration, double speed, long at) {
            this(title, artist, position, duration, speed, at, "");
        }

        double positionAt(long time) {
            return position + (time - at) / 1000.0 * speed;
        }
    }

    /**
     * What should show: nothing when disabled or when no song is playing.
     */
    record Wanted(boolean enabled, boolean showTitle, Song song) {
        static final Wanted OFF = new Wanted(false, true, null);
    }

    interface Session extends AutoCloseable {
        /**
         * Sets the activity, or clears it when null.
         */
        void activity(JsonObject activity) throws IOException;

        @Override
        void close();
    }

    interface Connector {
        Session open() throws IOException;
    }

    private final Connector connector;
    private final boolean threaded;
    private volatile Wanted wanted = Wanted.OFF;
    private volatile boolean closed;
    private volatile Thread worker;

    // Worker-thread state.
    private Session session;
    private Wanted sent;
    private long nextSend, nextConnect, sentAt;
    private boolean refused, unreachableReported;
    private volatile String state = "discord.darkmusic.state.waiting";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("darkmusic-discord");

    public DiscordPresence() {
        this(DiscordPresence::connect, true);
    }

    /**
     * Tests pass {@code threaded = false} and drive {@link #step} themselves.
     */
    DiscordPresence(Connector connector, boolean threaded) {
        this.connector = connector;
        this.threaded = threaded;
    }

    private static Session connect() throws IOException {
        var ipc = DiscordIpc.open(APPLICATION_ID);
        long pid = ProcessHandle.current().pid();
        return new Session() {
            @Override
            public void activity(JsonObject activity) throws IOException {
                var args = new JsonObject();
                args.addProperty("pid", pid);
                args.add("activity", activity == null ? JsonNull.INSTANCE : activity);
                var command = new JsonObject();
                command.addProperty("cmd", "SET_ACTIVITY");
                command.add("args", args);
                var reply = ipc.command(command);
                if ("ERROR".equals(DiscordIpc.string(reply, "evt")))
                    throw new IllegalArgumentException("Discord rejected the activity: " + reply.get("data"));
            }

            @Override
            public void close() {
                ipc.close();
            }
        };
    }

    /**
     * Called every client tick. Cheap: a song that keeps playing as expected leaves the request unchanged.
     */
    public void update(boolean enabled, boolean showTitle, AudioBackend.State state, double speed, String artwork) {
        update(enabled, showTitle, state, speed, System.currentTimeMillis(), artwork);
    }

    void update(boolean enabled, boolean showTitle, AudioBackend.State state, double speed, long now) {
        update(enabled, showTitle, state, speed, now, "");
    }

    void update(boolean enabled, boolean showTitle, AudioBackend.State state, double speed, long now, String artwork) {
        if (closed) return;
        var previous = wanted;
        Song song = null;
        if (enabled && state != null && state.track() != null) {
            switch (state.status()) {
                // Between songs and while seeking, keep what is showing instead of clearing it for a moment.
                case LOADING, SEEKING -> song = previous.song();
                case PLAYING -> {
                    var track = state.track();
                    double position = Double.isFinite(state.position()) ? state.position() : 0;
                    var candidate = new Song(Objects.requireNonNullElse(track.title(), ""), Objects.requireNonNullElse(track.artist(), ""),
                        position, state.duration(), speed, now, Objects.requireNonNullElse(artwork, ""));
                    var old = previous.song();
                    boolean same = old != null && old.title().equals(candidate.title()) && old.artist().equals(candidate.artist())
                        && old.artwork().equals(candidate.artwork())
                        && old.speed() == speed && old.duration() == candidate.duration()
                        && Math.abs(old.positionAt(now) - position) < SEEK_TOLERANCE;
                    song = same ? old : candidate;
                }
                default -> song = null;
            }
        }
        var next = enabled ? new Wanted(true, showTitle, song) : Wanted.OFF;
        if (next.equals(previous)) return;
        wanted = next;
        if (enabled) start();
        var thread = worker;
        if (thread != null) LockSupport.unpark(thread);
    }

    private synchronized void start() {
        if (worker != null || closed || !threaded) return;
        worker = Thread.ofPlatform().daemon().name("darkmusic-discord").start(() -> {
            while (!closed) {
                try {
                    step(System.nanoTime());
                } catch (Throwable unexpected) {
                    // Keep the status working after an unexpected error instead of silently ending the worker.
                    LOG.warn("Discord status update failed", unexpected);
                }
                LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(250));
            }
            disconnect();
        });
    }

    /**
     * One pass of the worker: connect when needed, then send the latest request at most every four seconds.
     */
    void step(long now) {
        var want = wanted;
        try {
            if (!want.enabled()) {
                disconnect();
                state = "discord.darkmusic.state.waiting";
                return;
            }
            boolean changed = !want.equals(sent);
            if (!changed && (session == null || now - sentAt < REFRESH)) return;
            if (session == null) {
                if (now < nextConnect) return;
                try {
                    session = connector.open();
                    refused = false;
                    unreachableReported = false;
                    state = "discord.darkmusic.state.connected";
                    LOG.info("Connected to the Discord desktop app for the listening status");
                } catch (IOException unavailable) {
                    // Usually Discord is simply not running; say so once, then retry quietly.
                    if (!unreachableReported)
                        LOG.info("Discord status: cannot reach the Discord desktop app ({}); retrying every 30 seconds", unavailable.getMessage());
                    unreachableReported = true;
                    state = "discord.darkmusic.state.unreachable";
                    nextConnect = now + RETRY;
                    return;
                }
            }
            if (now < nextSend) return;
            nextSend = now + MIN_INTERVAL;
            try {
                session.activity(activity(want));
            } catch (IllegalArgumentException rejected) {
                // Reconnecting would not help: remember it as sent so the same request is not retried in a loop.
                if (!refused) LOG.warn("Discord did not accept the status", rejected);
                refused = true;
                state = "discord.darkmusic.state.rejected";
            }
            sent = want;
            sentAt = now;
        } catch (IOException | RuntimeException lost) {
            LOG.info("Discord status connection lost ({}); reconnecting in 30 seconds", lost.toString());
            disconnect();
            state = "discord.darkmusic.state.unreachable";
            nextConnect = now + RETRY;
        }
    }

    private void disconnect() {
        if (session != null) session.close();
        session = null;
        sent = null;
    }

    /**
     * A message key for Settings: waiting to connect, connected, Discord unreachable, or status rejected.
     */
    public String state() {
        return state;
    }

    /**
     * The activity Discord shows, or null to clear it. Text is in the player's language.
     */
    static JsonObject activity(Wanted want) {
        var song = want.song();
        if (!want.enabled() || song == null) return null;
        var activity = new JsonObject();
        activity.addProperty("type", 2); // Listening
        if (want.showTitle()) {
            activity.addProperty("details", fit(song.title().isBlank() ? Text.get("discord.darkmusic.hidden") : song.title(), 128));
            if (!song.artist().isBlank()) activity.addProperty("state", fit(song.artist(), 128));
        } else activity.addProperty("details", fit(Text.get("discord.darkmusic.hidden"), 128));
        if (Double.isFinite(song.duration()) && song.duration() > 0 && song.speed() > 0) {
            var timestamps = new JsonObject();
            long start = song.at() - Math.round(song.position() / song.speed() * 1000);
            timestamps.addProperty("start", start);
            timestamps.addProperty("end", start + Math.round(song.duration() / song.speed() * 1000));
            activity.add("timestamps", timestamps);
        }
        var assets = new JsonObject();
        if (want.showTitle() && !song.artwork().isEmpty()) {
            // The song's artwork, with the DarkMusic icon as the small badge.
            assets.addProperty("large_image", song.artwork());
            assets.addProperty("large_text", fit(song.title().isBlank() ? "DarkMusic" : song.title(), 128));
            assets.addProperty("small_image", ICON);
            assets.addProperty("small_text", "DarkMusic");
        } else {
            assets.addProperty("large_image", ICON);
            assets.addProperty("large_text", "DarkMusic");
        }
        activity.add("assets", assets);
        var button = new JsonObject();
        button.addProperty("label", fit(Text.get("discord.darkmusic.get"), 32));
        button.addProperty("url", RELEASES);
        var buttons = new JsonArray();
        buttons.add(button);
        activity.add("buttons", buttons);
        return activity;
    }

    /**
     * Public artwork for a song's source page, or "". Only YouTube is supported: its thumbnail address follows from the
     * video ID, so nothing is uploaded or looked up. Other sites and local files keep the DarkMusic icon.
     */
    public static String artwork(String source) {
        if (source == null || source.isBlank()) return "";
        try {
            var uri = java.net.URI.create(source.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String id = "";
            if (host.equals("youtu.be")) id = path.replaceFirst("^/", "");
            else if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
                if (path.equals("/watch") && uri.getRawQuery() != null)
                    for (String pair : uri.getRawQuery().split("&"))
                        if (pair.startsWith("v=")) id = pair.substring(2);
                var parts = path.split("/");
                if (parts.length >= 3 && java.util.Set.of("shorts", "live", "embed").contains(parts[1])) id = parts[2];
            }
            return id.matches("[A-Za-z0-9_-]{11}") ? "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg" : "";
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    /**
     * Discord requires 2 to {@code max} characters.
     */
    static String fit(String text, int max) {
        String value = text.strip();
        if (value.codePointCount(0, value.length()) > max)
            value = value.substring(0, value.offsetByCodePoints(0, max - 1)) + "…";
        // U+2800 is blank but not whitespace, so Discord does not trim it away.
        while (value.length() < 2) value += "⠀";
        return value;
    }

    @Override
    public void close() {
        closed = true;
        wanted = Wanted.OFF;
        var thread = worker;
        if (thread != null) LockSupport.unpark(thread);
    }
}
