package com.darkmusic.client.media;

import com.darkmusic.client.testing.JavaTools;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Process ownership and failure signaling, with no generated audio assets or external executable.
 */
@Timeout(10)
class ExternalAudioTest {
    @TempDir
    Path temp;

    @BeforeEach
    void configure() {
        var tools = JavaTools.paths();
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, tools.ffmpeg(), tools.ffprobe()));
    }

    @AfterEach
    void clear() {
        ExternalAudio.configure(null);
        Thread.interrupted();
    }

    private static JsonObject layout(int rate, int channels) {
        return JsonParser.parseString("{\"streams\":[{\"sample_rate\":" + rate + ",\"channels\":" + channels + "}]}").getAsJsonObject();
    }

    private static final class Opener implements ExternalAudio.Opener {
        JsonObject info = layout(48000, 2);
        final FakeProcess process = new FakeProcess();
        final List<List<String>> commands = new ArrayList<>();

        public JsonObject probe(Path path) {
            return info;
        }

        public Process start(List<String> command) {
            commands.add(List.copyOf(command));
            return process;
        }
    }

    private static final class FakeProcess extends Process {
        boolean inputClosed, errorsClosed, failStdin, interrupted, exited = true, destroyed;
        int exit, forced;
        final InputStream input = new ByteArrayInputStream(new byte[0]) {
            public void close() {
                inputClosed = true;
            }
        };
        final InputStream errors = new ByteArrayInputStream("Decoder error detail".getBytes()) {
            public void close() {
                errorsClosed = true;
            }
        };

        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                public void close() throws IOException {
                    if (failStdin) throw new IOException("Cannot close decoder stdin");
                }
            };
        }

        public InputStream getInputStream() {
            return input;
        }

        public InputStream getErrorStream() {
            return errors;
        }

        public int waitFor() {
            return exit;
        }

        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interrupted) throw new InterruptedException("fixture interruption");
            return exited;
        }

        public int exitValue() {
            return exit;
        }

        public void destroy() {
            destroyed = true;
        }

        public Process destroyForcibly() {
            forced++;
            destroyed = true;
            return this;
        }

        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    @Test
    void invalidPositionsAndLayoutsNeverStartAProcess() {
        var opener = new Opener();
        for (double position : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> ExternalAudio.openAt(temp.resolve("unused"), position, opener));
        for (JsonObject info : List.of(new JsonObject(), JsonParser.parseString("{\"streams\":[]}").getAsJsonObject(),
            layout(7999, 2), layout(384001, 2), layout(48000, 0), layout(48000, 33))) {
            opener.info = info;
            assertThrows(IOException.class, () -> ExternalAudio.openAt(temp.resolve("unused"), 0, opener));
        }
        assertTrue(opener.commands.isEmpty());
    }

    @Test
    void cleanEofCloseAndAbortKeepTheirProcessOwnership() throws Exception {
        var opener = new Opener();
        var opened = ExternalAudio.openAt(temp.resolve("unused"), 1.00001, opener);
        assertEquals(Math.floor(1.00001 * 48000) / 48000, opened.offset());
        assertTrue(opener.commands.getFirst().contains("-ss"));
        assertEquals(-1, opened.stream().read(new byte[8]));
        ExternalAudio.abort(opened.stream());
        assertEquals(1, opener.process.forced);
        opened.stream().close();
        assertTrue(opener.process.destroyed);
        assertTrue(opener.process.inputClosed);
        int forced = opener.process.forced;
        ExternalAudio.abort(opened.stream());
        assertEquals(forced, opener.process.forced, "close unregistered the stream");
    }

    @Test
    void decoderFailureAndFailureToExitAreNotCleanEof() throws Exception {
        for (boolean exited : List.of(true, false)) {
            var opener = new Opener();
            opener.process.exit = 7;
            opener.process.exited = exited;
            try (var stream = ExternalAudio.openAt(temp.resolve("unused"), 0, opener).stream()) {
                var failure = assertThrows(IOException.class, () -> stream.read(new byte[8]));
                assertNotNull(failure.getCause());
                if (exited) assertTrue(failure.getCause().getMessage().contains("Decoder error detail"));
            }
            assertTrue(opener.process.inputClosed);
            if (!exited) assertTrue(opener.process.forced > 0);
        }
    }

    @Test
    void interruptedEofPreservesTheInterruptAndClosesThePipe() throws Exception {
        var opener = new Opener();
        var stream = ExternalAudio.openAt(temp.resolve("unused"), 0, opener).stream();
        opener.process.interrupted = true;
        try {
            var failure = assertThrows(IOException.class, () -> stream.read(new byte[8]));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            opener.process.interrupted = false;
            stream.close();
        }
        assertTrue(opener.process.inputClosed);
    }

    @Test
    void failedOpenDestroysTheUnregisteredProcessAndClosesBothStreams() {
        var opener = new Opener();
        opener.process.failStdin = true;
        var failure = assertThrows(IOException.class, () -> ExternalAudio.openAt(temp.resolve("unused"), 0, opener));
        assertEquals("Cannot close decoder stdin", failure.getMessage());
        assertTrue(opener.process.forced > 0);
        assertTrue(opener.process.inputClosed);
        assertTrue(opener.process.errorsClosed);
    }
}
