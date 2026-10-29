package com.darkmusic.client.presence;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.library.Track;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DiscordPresenceTest {
    /**
     * Discord's side of the pipe: replies come from a script, writes are decoded for inspection.
     */
    static class FakeDiscord implements DiscordIpc.Transport {
        final ArrayDeque<Byte> incoming = new ArrayDeque<>();
        final List<DiscordIpc.Frame> written = new ArrayList<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        void reply(int op, String json) {
            for (byte b : DiscordIpc.encode(op, json)) incoming.add(b);
        }

        @Override
        public void write(byte[] bytes) {
            pending.writeBytes(bytes);
            byte[] all = pending.toByteArray();
            var buffer = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int op = buffer.getInt(), length = buffer.getInt();
            written.add(new DiscordIpc.Frame(op, JsonParser.parseString(new String(all, 8, length, StandardCharsets.UTF_8)).getAsJsonObject()));
            pending.reset();
        }

        @Override
        public void read(byte[] bytes, long deadlineNanos) throws IOException {
            for (int i = 0; i < bytes.length; i++) {
                if (incoming.isEmpty()) throw new IOException("Discord did not answer");
                bytes[i] = incoming.removeFirst();
            }
        }

        @Override
        public void close() {
        }
    }

    @Test
    void framesAreLittleEndianOpcodeLengthAndJson() {
        byte[] frame = DiscordIpc.encode(DiscordIpc.FRAME, "{\"a\":1}");
        var buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, buffer.getInt());
        assertEquals(7, buffer.getInt());
        assertEquals("{\"a\":1}", new String(frame, 8, 7, StandardCharsets.UTF_8));
    }

    @Test
    void handshakeNeedsReadyAndCommandsWaitForTheirNonceWhileAnsweringPings() throws Exception {
        var discord = new FakeDiscord();
        discord.reply(DiscordIpc.FRAME, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\"}");
        var ipc = new DiscordIpc(discord);
        ipc.handshake("123");
        assertEquals(DiscordIpc.HANDSHAKE, discord.written.getFirst().op());
        assertEquals("123", discord.written.getFirst().data().get("client_id").getAsString());

        var command = new JsonObject();
        command.addProperty("cmd", "SET_ACTIVITY");
        // The nonce is only known after sending, so script the replies from inside the transport.
        var scripted = new FakeDiscord() {
            @Override
            public void write(byte[] bytes) {
                super.write(bytes);
                var last = written.getLast();
                if (last.op() == DiscordIpc.FRAME) {
                    reply(DiscordIpc.PING, "{\"p\":1}");
                    reply(DiscordIpc.FRAME, "{\"nonce\":\"someone else\"}");
                    reply(DiscordIpc.FRAME, "{\"nonce\":\"" + last.data().get("nonce").getAsString() + "\",\"evt\":null}");
                }
            }
        };
        var answer = new DiscordIpc(scripted).command(command);
        assertEquals(command.get("nonce").getAsString(), answer.get("nonce").getAsString());
        assertEquals(List.of(DiscordIpc.FRAME, DiscordIpc.PONG), scripted.written.stream().map(DiscordIpc.Frame::op).toList());

        var refused = new FakeDiscord();
        refused.reply(DiscordIpc.CLOSE, "{\"code\":4000,\"message\":\"Invalid Client ID\"}");
        assertThrows(IOException.class, () -> new DiscordIpc(refused).handshake("123"));
        var silent = new FakeDiscord();
        assertThrows(IOException.class, () -> new DiscordIpc(silent).handshake("123"));
    }

    private static AudioBackend.State state(String title, AudioBackend.Status status, double position) {
        var track = new Track("root", title + ".mp3", Path.of(title + ".mp3"), title, "Artist", "Album", 200, 1, 0, "");
        return new AudioBackend.State(track, status, position, 200, "");
    }

    private static final class Recorder implements DiscordPresence.Connector {
        final List<JsonObject> sent = new ArrayList<>();
        int attempts, opens, closes;
        boolean available = true, broken;

        @Override
        public DiscordPresence.Session open() throws IOException {
            attempts++;
            if (!available) throw new IOException("Discord is not running");
            opens++;
            return new DiscordPresence.Session() {
                @Override
                public void activity(JsonObject activity) throws IOException {
                    if (broken) throw new IOException("pipe closed");
                    sent.add(activity);
                }

                @Override
                public void close() {
                    closes++;
                }
            };
        }
    }

    private static long seconds(double value) {
        return Math.round(value * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void aSongThatKeepsPlayingIsSentOnceAndSeeksPausesAndChangesAreSentRateLimited() {
        var discord = new Recorder();
        var presence = new DiscordPresence(discord, false);
        long epoch = 1_000_000;
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 10), 1, epoch);
        presence.step(seconds(100));
        assertEquals(1, discord.sent.size());
        // Ten seconds of normal playback is not a change.
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 20), 1, epoch + 10_000);
        presence.step(seconds(110));
        assertEquals(1, discord.sent.size());
        // A seek is.
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 90), 1, epoch + 11_000);
        presence.step(seconds(111));
        assertEquals(2, discord.sent.size());
        // Loading the next song keeps the old status instead of flickering.
        presence.update(true, true, state("Next", AudioBackend.Status.LOADING, 0), 1, epoch + 12_000);
        presence.step(seconds(112));
        assertEquals(2, discord.sent.size());
        // Pausing clears it, but no sooner than four seconds after the last update.
        presence.update(true, true, state("Departure", AudioBackend.Status.PAUSED, 91), 1, epoch + 12_500);
        presence.step(seconds(113));
        assertEquals(2, discord.sent.size());
        presence.step(seconds(115.1));
        assertEquals(3, discord.sent.size());
        assertNull(discord.sent.getLast());
        // Turning the option off disconnects, which also clears the status in Discord.
        presence.update(false, true, state("Departure", AudioBackend.Status.PLAYING, 91), 1, epoch + 20_000);
        presence.step(seconds(120));
        assertEquals(1, discord.closes);
    }

    @Test
    void missingOrRestartedDiscordIsRetriedEveryFiveSecondsWithoutSpinning() {
        var discord = new Recorder();
        discord.available = false;
        var presence = new DiscordPresence(discord, false);
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 10), 1, 1_000_000);
        presence.step(seconds(100));
        presence.step(seconds(101));
        assertEquals(1, discord.attempts);
        assertEquals(0, discord.opens);
        discord.available = true;
        presence.step(seconds(104));
        assertEquals(0, discord.opens);
        presence.step(seconds(105.1));
        assertEquals(1, discord.opens);
        assertEquals(1, discord.sent.size());
        // Discord restarts: the periodic refresh finds the broken pipe and reconnects after the retry delay.
        discord.broken = true;
        presence.step(seconds(191));
        assertEquals(1, discord.closes);
        discord.broken = false;
        presence.step(seconds(196.1));
        assertEquals(2, discord.opens);
        assertEquals(2, discord.sent.size());
    }

    @Test
    void discordOpenedAfterPlaybackStartsReceivesTheCurrentSongWithoutToggling() {
        var discord = new Recorder();
        discord.available = false;
        var presence = new DiscordPresence(discord, false);
        presence.update(true, true, null, 1, 1_000_000);
        presence.step(seconds(100));
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 10), 1, 1_001_000);
        presence.step(seconds(101));
        discord.available = true;
        for (int i = 102; i < 105; i++) presence.step(seconds(i));
        assertEquals(1, discord.attempts, "Do not spin while Discord is unavailable");
        assertTrue(discord.sent.isEmpty());
        presence.step(seconds(105));
        assertEquals(2, discord.attempts);
        assertEquals(1, discord.sent.size());
        assertEquals("Departure", discord.sent.getFirst().get("details").getAsString());
        assertEquals("discord.darkmusic.state.connected", presence.state());
    }
    @Test
    void activityShowsTheSongOrOnlyThatMusicIsPlayingWithAProgressBarAtTheCurrentSpeed() {
        var song = new DiscordPresence.Song("Departure", "Kavinsky", 30, 200, 2, 1_000_000);
        var shown = DiscordPresence.activity(new DiscordPresence.Wanted(true, true, song));
        assertEquals(2, shown.get("type").getAsInt());
        assertEquals("Departure", shown.get("details").getAsString());
        assertEquals("Kavinsky", shown.get("state").getAsString());
        var timestamps = shown.getAsJsonObject("timestamps");
        assertEquals(1_000_000 - 15_000, timestamps.get("start").getAsLong());
        assertEquals(1_000_000 - 15_000 + 100_000, timestamps.get("end").getAsLong());
        assertEquals(DiscordPresence.ICON, shown.getAsJsonObject("assets").get("large_image").getAsString());
        assertEquals(DiscordPresence.RELEASES, shown.getAsJsonArray("buttons").get(0).getAsJsonObject().get("url").getAsString());

        var hidden = DiscordPresence.activity(new DiscordPresence.Wanted(true, false, song));
        assertEquals("Listening to music", hidden.get("details").getAsString());
        assertFalse(hidden.has("state"));
        assertFalse(hidden.toString().contains("Departure") || hidden.toString().contains("Kavinsky"));

        var unknownLength = DiscordPresence.activity(new DiscordPresence.Wanted(true, true,
            new DiscordPresence.Song("A", "", 0, Double.NaN, 1, 0)));
        assertFalse(unknownLength.has("timestamps"));
        assertFalse(unknownLength.has("state"));
        assertEquals(2, unknownLength.get("details").getAsString().length(), "Discord needs at least two characters");
        assertNull(DiscordPresence.activity(new DiscordPresence.Wanted(true, true, null)));
        assertNull(DiscordPresence.activity(DiscordPresence.Wanted.OFF));
    }

    @Test
    void youtubeSourcesGiveTheirPublicThumbnailAndEverythingElseKeepsTheIcon() {
        String thumb = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg";
        for (String link : List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?list=PL1&v=dQw4w9WgXcQ&t=5",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/live/dQw4w9WgXcQ"))
            assertEquals(thumb, DiscordPresence.artwork(link), link);
        for (String link : List.of("", "https://soundcloud.com/a/b", "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=short", "https://www.youtube.com/watch?v=dQw4w9WgXcQ%22", "not a link"))
            assertEquals("", DiscordPresence.artwork(link), link);
        assertEquals("", DiscordPresence.artwork(null));

        var song = new DiscordPresence.Song("Departure", "Kavinsky", 30, 200, 1, 1_000_000, thumb);
        var assets = DiscordPresence.activity(new DiscordPresence.Wanted(true, true, song)).getAsJsonObject("assets");
        assertEquals(thumb, assets.get("large_image").getAsString());
        assertEquals(DiscordPresence.ICON, assets.get("small_image").getAsString(), "DarkMusic stays visible as the badge");
        var hidden = DiscordPresence.activity(new DiscordPresence.Wanted(true, false, song)).getAsJsonObject("assets");
        assertEquals(DiscordPresence.ICON, hidden.get("large_image").getAsString(), "Artwork would reveal a hidden song");
        assertFalse(hidden.has("small_image"));
    }

    @Test
    void artworkArrivingLaterUpdatesTheStatusOnce() {
        var discord = new Recorder();
        var presence = new DiscordPresence(discord, false);
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 10), 1, 1_000_000, "");
        presence.step(seconds(100));
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 11), 1, 1_001_000, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        presence.step(seconds(105));
        assertEquals(2, discord.sent.size());
        assertTrue(discord.sent.getLast().toString().contains("i.ytimg.com"));
        presence.update(true, true, state("Departure", AudioBackend.Status.PLAYING, 12), 1, 1_002_000, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        presence.step(seconds(110));
        assertEquals(2, discord.sent.size());
    }

    @Test
    void longTextIsCutToDiscordsLimitsWithoutSplittingCharacters() {
        String emoji = "🎵";
        String fitted = DiscordPresence.fit(emoji.repeat(200), 128);
        assertEquals(128, fitted.codePointCount(0, fitted.length()));
        assertTrue(fitted.endsWith("…"));
        assertFalse(Character.isHighSurrogate(fitted.charAt(fitted.length() - 2)));
        assertEquals("ab", DiscordPresence.fit("  ab  ", 128));
        assertTrue(DiscordPresence.fit(DiscordPresence.RELEASES.repeat(3), 32).length() <= 32);
    }
}
