package com.darkmusic.client.presence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Discord's local RPC: length-prefixed JSON frames over the desktop app's named pipe (Windows) or Unix socket.
 * Nothing signs in; the desktop app shows the activity to the player's friends.
 */
final class DiscordIpc implements AutoCloseable {
    static final int HANDSHAKE = 0, FRAME = 1, CLOSE = 2, PING = 3, PONG = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    interface Transport extends AutoCloseable {
        void write(byte[] bytes) throws IOException;

        /**
         * Fills the array, or throws once the deadline passes.
         */
        void read(byte[] bytes, long deadlineNanos) throws IOException;

        @Override
        void close() throws IOException;
    }

    record Frame(int op, JsonObject data) {
    }

    private final Transport transport;

    DiscordIpc(Transport transport) {
        this.transport = transport;
    }

    static byte[] encode(int op, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(payload.length).put(payload).array();
    }

    void send(int op, JsonObject data) throws IOException {
        transport.write(encode(op, data.toString()));
    }

    Frame receive(long deadlineNanos) throws IOException {
        byte[] header = new byte[8];
        transport.read(header, deadlineNanos);
        var buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int op = buffer.getInt(), length = buffer.getInt();
        if (length < 0 || length > 65_536) throw new IOException("Discord frame too large");
        byte[] payload = new byte[length];
        transport.read(payload, deadlineNanos);
        try {
            JsonElement parsed = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            return new Frame(op, parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject());
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed Discord frame", malformed);
        }
    }

    void handshake(String applicationId) throws IOException {
        var hello = new JsonObject();
        hello.addProperty("v", 1);
        hello.addProperty("client_id", applicationId);
        send(HANDSHAKE, hello);
        var reply = receive(System.nanoTime() + TIMEOUT.toNanos());
        if (reply.op() != FRAME || !"READY".equals(string(reply.data(), "evt")))
            throw new IOException("Discord refused the connection: " + reply.data());
    }

    /**
     * Sends a command and waits for the reply with its nonce, answering pings meanwhile.
     */
    JsonObject command(JsonObject command) throws IOException {
        String nonce = UUID.randomUUID().toString();
        command.addProperty("nonce", nonce);
        send(FRAME, command);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            var frame = receive(deadline);
            if (frame.op() == PING) send(PONG, frame.data());
            else if (frame.op() == CLOSE) throw new IOException("Discord closed the connection: " + frame.data());
            else if (frame.op() == FRAME && nonce.equals(string(frame.data(), "nonce"))) return frame.data();
        }
    }

    static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Connects to the first Discord instance that accepts this application, trying each of Discord's ten slots.
     */
    static DiscordIpc open(String applicationId) throws IOException {
        IOException last = new IOException("Discord is not running");
        for (Object candidate : candidates()) {
            Transport transport = null;
            try {
                transport = candidate instanceof Path path ? new UnixSocket(path) : new WindowsPipe((String) candidate);
                var ipc = new DiscordIpc(transport);
                ipc.handshake(applicationId);
                return ipc;
            } catch (IOException failed) {
                last = failed;
                if (transport != null) try {
                    transport.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw last;
    }

    private static List<Object> candidates() {
        var result = new ArrayList<Object>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            for (int i = 0; i < 10; i++) result.add("\\\\.\\pipe\\discord-ipc-" + i);
            return result;
        }
        var directories = new ArrayList<Path>();
        for (String name : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) directories.add(Path.of(value));
        }
        directories.add(Path.of("/tmp"));
        for (int i = 0; i < 10; i++)
            for (Path directory : directories)
                // Plain, Flatpak and Snap installations of the desktop app.
                for (String prefix : new String[]{"", "app/com.discordapp.Discord/", "snap.discord/"}) {
                    Path socket = directory.resolve(prefix + "discord-ipc-" + i);
                    if (Files.exists(socket) && !result.contains(socket)) result.add(socket);
                }
        return result;
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }

    static final class WindowsPipe implements Transport {
        private final RandomAccessFile file;

        WindowsPipe(String path) throws IOException {
            file = new RandomAccessFile(path, "rw");
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            file.write(bytes);
        }

        @Override
        public void read(byte[] bytes, long deadlineNanos) throws IOException {
            int done = 0;
            while (done < bytes.length) {
                // A synchronous pipe read blocks until data arrives; length() reports the bytes already waiting.
                if (file.length() <= 0) {
                    if (System.nanoTime() > deadlineNanos) throw new IOException("Discord did not answer");
                    pause();
                    continue;
                }
                int read = file.read(bytes, done, bytes.length - done);
                if (read < 0) throw new EOFException("Discord closed the pipe");
                done += read;
            }
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    static final class UnixSocket implements Transport {
        private final SocketChannel channel;

        UnixSocket(Path path) throws IOException {
            channel = SocketChannel.open(UnixDomainSocketAddress.of(path));
            channel.configureBlocking(false);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            var buffer = ByteBuffer.wrap(bytes);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (buffer.hasRemaining())
                if (channel.write(buffer) == 0) {
                    if (System.nanoTime() > deadline) throw new IOException("Discord is not reading");
                    pause();
                }
        }

        @Override
        public void read(byte[] bytes, long deadlineNanos) throws IOException {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) throw new EOFException("Discord closed the socket");
                if (read == 0) {
                    if (System.nanoTime() > deadlineNanos) throw new IOException("Discord did not answer");
                    pause();
                }
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
