package com.darkmusic.client;

import com.darkmusic.client.audio.PcmDecoder;
import com.darkmusic.client.audio.PcmRateConverter;
import com.darkmusic.client.config.DownloadFormat;
import com.darkmusic.client.download.*;
import com.darkmusic.client.library.LyricsFiles;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.LyricsService;
import com.darkmusic.client.media.AudioFormats;
import com.darkmusic.client.media.ExternalAudio;
import com.darkmusic.client.tools.ProcessRunner;
import com.darkmusic.client.tools.Toolchain;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class NativeAudioTest {
    @TempDir
    Path temp;

    @AfterEach
    void restore() {
        ExternalAudio.configure(null);
    }

    private Path tools() {
        String ff = System.getProperty("darkmusic.test.ffmpeg", "");
        Assumptions.assumeFalse(ff.isBlank(), "Real FFmpeg integration is opt-in");
        Path ffmpeg = Path.of(ff);
        ExternalAudio.configure(() -> new ExternalAudio.Paths(temp, ff, ffmpeg.resolveSibling(ffmpeg.getFileName().toString().replace("ffmpeg", "ffprobe")).toString()));
        return ffmpeg;
    }

    private Path source() throws Exception {
        Path wav = temp.resolve("real-excerpt.wav");
        try (var stream = TestMusic.clip().stream()) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        return wav;
    }

    @Test
    void nativeAacAlacOpusFlacAndWrongExtensionDecodeWithoutTouchingSource() throws Exception {
        Path ff = tools(), source = source();
        var runner = new ProcessRunner();
        for (String[] format : List.of(new String[]{"alac", "m4a"}, new String[]{"aac", "m4a"}, new String[]{"aac", "aac"}, new String[]{"libopus", "opus"}, new String[]{"libvorbis", "ogg"}, new String[]{"flac", "flac"}, new String[]{"pcm_s24le", "wav"})) {
            Path target = temp.resolve(format[0] + "-音樂." + format[1]);
            var r = runner.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-map", "0:a:0", "-c:a", format[0], "-metadata", "title=測試音樂", "-metadata", "artist=演奏者", target.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 16000, s -> {
            });
            assertEquals(0, r.exit(), r.output());
            byte[] before = Files.readAllBytes(target);
            try (var decoded = PcmDecoder.openPrecise(target)) {
                assertEquals(32, decoded.getFormat().getSampleSizeInBits());
                float[] pcm = new float[16384];
                int n = new PcmRateConverter(decoded).read(pcm, pcm.length, 1);
                assertTrue(n > 0);
                for (int i = 0; i < n; i++) assertTrue(Float.isFinite(pcm[i]));
            }
            assertArrayEquals(before, Files.readAllBytes(target));
            var metadata = new MetadataReader().read("test", target.getFileName().toString(), target, Files.size(target), 0);
            if (!format[1].equals("aac")) assertEquals("測試音樂", metadata.title(), target.toString());
            assertTrue(metadata.duration() > 0, target.toString());
        }
        Path disguised = Files.copy(source, temp.resolve("real-audio.bin"));
        assertEquals(AudioFormats.Container.WAVE, AudioFormats.detect(disguised));
        assertTrue(MusicLibrary.supported(disguised));
        try (var pcm = PcmDecoder.openPrecise(disguised)) {
            assertTrue(pcm.readNBytes(4096).length > 0);
        }
        assertThrows(Exception.class, () -> PcmDecoder.openPrecise(Files.writeString(temp.resolve("broken.m4a"), "invalid")));
    }

    @Test
    void sourcePlansNeverSilentlyLossyEncode() {
        for (String codec : List.of("aac", "alac", "opus", "vorbis", "mp3", "flac", "pcm_s24le"))
            assertEquals("copy", DownloadEncoding.plan(codec).codec());
        assertEquals("flac", DownloadEncoding.plan("unsupported").codec());
        assertFalse(DownloadEncoding.plan("unsupported").warning().isBlank());
    }

    @Test
    void originalOggDownloadsEmbedReadableArtworkWithoutChangingAudioPackets() throws Exception {
        Path ff = tools(), source = source();
        byte[] artwork = new MetadataReader().artwork(TestMusic.featured());
        assertNotNull(artwork, "Use a private collection excerpt with its actual cover");
        var token = new ProcessRunner.Token();
        var real = new ProcessRunner();
        var tools = new Toolchain(Path.of("unused-yt-dlp"), ff, ExternalAudio.tool(true), "", "");
        for (String[] codec : List.of(new String[]{"libopus", "opus"}, new String[]{"libvorbis", "ogg"})) {
            Path input = temp.resolve("source." + codec[1]);
            assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", codec[0], "-metadata", "title=Stale title", "-metadata", "artist=Stale artist", "-metadata", "album=Stale album", "-metadata", "track=1", "-metadata", "comment=Keep source comment", input.toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
            }).exit());
            byte[] original = Files.readAllBytes(input);
            var captionCalls = new AtomicInteger();
            var runner = new ProcessRunner() {
                @Override
                public Result run(List<String> args, Path dir, Token cancellation, Duration timeout, int max, Consumer<String> lines) throws IOException, InterruptedException {
                    if (args.contains("--write-info-json")) {
                        Files.copy(input, dir.resolve("source." + codec[1]));
                        Files.write(dir.resolve("source.jpg"), artwork);
                        Files.writeString(dir.resolve("source.info.json"), "{\"title\":\"Cover test\",\"artist\":\"Original artist\",\"album\":\"Current album\",\"track_number\":7,\"subtitles\":{\"en\":[{}]}}");
                        return new Result(0, "");
                    }
                    if (args.contains("--write-subs")) {
                        captionCalls.incrementAndGet();
                        Files.writeString(dir.resolve("lyrics.en.lrc"), "[00:01.00]Caption timing check");
                        return new Result(0, "");
                    }
                    return super.run(args, dir, cancellation, timeout, max, lines);
                }
            };
            var entry = new DownloadModels.Entry(1, "Cover test", "https://example.com/audio", "test", codec[1], true);
            var saved = new DownloadPipeline(runner, temp.resolve("data")).execute(entry, temp.resolve("music"), tools, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL, () -> true);
            assertFalse(saved.warning().contains("without artwork"), saved.warning());
            assertEquals("[00:01.00]Caption timing check", Files.readString(LyricsFiles.sidecar(saved.file())));
            assertEquals(1, captionCalls.get());
            var disabledEntry = new DownloadModels.Entry(1, "Cover test", "https://example.com/audio", "test", codec[1] + "-disabled", true);
            var disabled = new DownloadPipeline(runner, temp.resolve("data")).execute(disabledEntry, temp.resolve("music"), tools, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL, () -> false);
            assertEquals(1, captionCalls.get(), "Disabled lyrics must not request captions");
            assertFalse(Files.exists(LyricsFiles.sidecar(disabled.file())));
            byte[] embedded = new MetadataReader().artwork(saved.file());
            assertNotNull(embedded, codec[1]);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(embedded)));
            var metadata = new MetadataReader().read("test", saved.file().getFileName().toString(), saved.file(), Files.size(saved.file()), 0);
            assertEquals("Cover test", metadata.title());
            assertEquals("Original artist", metadata.artist());
            assertEquals("Current album", metadata.album());
            var inspected = ExternalAudio.probe(saved.file()).getAsJsonArray("streams").get(0).getAsJsonObject().getAsJsonObject("tags");
            assertTrue(inspected.entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("track") && e.getValue().getAsString().equals("7")), inspected.toString());
            var allTags = real.run(List.of(tools.ffprobe().toString(), "-v", "error", "-show_entries", "stream_tags=comment", "-of", "json", saved.file().toString()), temp, token, Duration.ofSeconds(10), 16000, s -> {
            });
            assertTrue(allTags.output().contains("Keep source comment"), allTags.output());
            Path before = temp.resolve("before-" + codec[1] + ".bin"), after = temp.resolve("after-" + codec[1] + ".bin");
            for (var pair : List.of(new Path[]{input, before}, new Path[]{saved.file(), after}))
                assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", pair[0].toString(), "-map", "0:a:0", "-c:a", "copy", "-f", "data", pair[1].toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
                }).exit());
            assertEquals(-1, Files.mismatch(before, after), "Embedding cover art must preserve compressed audio");
            assertArrayEquals(original, Files.readAllBytes(input));
        }
    }

    @Test
    void bigEndianPcmDownloadsPreservePrecisionAndAreImportedAndDecoded() throws Exception {
        Path ff = tools(), source = source();
        var real = new ProcessRunner();
        var token = new ProcessRunner.Token();
        var chain = new Toolchain(temp.resolve("unused-yt-dlp"), ff, ExternalAudio.tool(true), "", "");
        for (String codec : List.of("pcm_s24be", "pcm_f32be", "pcm_f64be")) {
            Path input = temp.resolve(codec + ".aiff");
            assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", codec, input.toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
            }).exit());
            var supplied = new ProcessRunner() {
                @Override
                public Result run(List<String> args, Path dir, Token cancellation, Duration timeout, int max, Consumer<String> lines) throws IOException, InterruptedException {
                    if (args.contains("--write-info-json")) {
                        Files.copy(input, dir.resolve("source.aiff"));
                        return new Result(0, "");
                    }
                    return super.run(args, dir, cancellation, timeout, max, lines);
                }
            };
            var entry = new DownloadModels.Entry(1, "Private PCM excerpt", "https://example.com/audio", "test", codec, true);
            var saved = new DownloadPipeline(supplied, temp.resolve("data")).execute(entry, temp.resolve("music"), chain, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL);
            String littleEndian = codec.replace("be", "le");
            assertEquals(littleEndian, DownloadEncoding.codec(real, chain, saved.file(), temp, token));
            Path before = temp.resolve(codec + "-before.bin"), after = temp.resolve(codec + "-after.bin");
            for (var pair : List.of(new Path[]{input, before}, new Path[]{saved.file(), after}))
                assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", pair[0].toString(), "-map", "0:a:0", "-c:a", littleEndian, "-f", "data", pair[1].toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
                }).exit());
            assertEquals(-1, Files.mismatch(before, after), "Endian conversion must preserve every PCM value");
            try (var pcm = PcmDecoder.openPrecise(saved.file())) {
                assertTrue(pcm.readNBytes(4096).length > 0);
            }
        }
        try (var library = new MusicLibrary(temp.resolve("library-config"))) {
            library.setRoot(temp.resolve("music"), "pcm-downloads");
            StorageTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 3);
        }
    }

    @Test
    void originalDownloadRetainsAacPacketsInsteadOfReencoding() throws Exception {
        Path ff = tools(), source = source(), aac = temp.resolve("source.m4a");
        String yt = System.getProperty("darkmusic.test.ytdlp", "");
        Assumptions.assumeFalse(yt.isBlank());
        var runner = new ProcessRunner();
        var token = new ProcessRunner.Token();
        assertEquals(0, runner.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", "aac", aac.toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
        }).exit());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = Files.readAllBytes(aac);
        server.createContext("/authorized.m4a", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            var tools = new Toolchain(Path.of(yt), ff, ExternalAudio.tool(true), "", "");
            var entry = new DownloadModels.Entry(1, "Authorized local excerpt", "http://127.0.0.1:" + server.getAddress().getPort() + "/authorized.m4a", "Generic", "real", true);
            var saved = new DownloadPipeline(runner, temp.resolve("data")).execute(entry, temp.resolve("music"), tools, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL);
            assertTrue(saved.file().toString().endsWith(".m4a"));
            assertEquals("aac", DownloadEncoding.codec(runner, tools, saved.file(), temp, token));
            Path before = temp.resolve("before.aac"), after = temp.resolve("after.aac");
            for (var pair : List.of(new Path[]{aac, before}, new Path[]{saved.file(), after}))
                assertEquals(0, runner.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", pair[0].toString(), "-map", "0:a:0", "-c:a", "copy", pair[1].toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
                }).exit());
            assertEquals(-1, Files.mismatch(before, after), "Native download must preserve compressed audio packets");
            assertArrayEquals(bytes, Files.readAllBytes(aac));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void actualYtDlpConvertsManualCaptionsToLrcReadableByLyricsService() throws Exception {
        Path ff = tools(), audio = source();
        String yt = System.getProperty("darkmusic.test.ytdlp", "");
        Assumptions.assumeFalse(yt.isBlank());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pages = Map.of(
            "/song.html", "<html><head><title>Private audio caption check</title></head><body><video src=\"/audio.wav\"><track kind=\"subtitles\" src=\"/captions.vtt\" srclang=\"en\" label=\"English\"></video></body></html>".getBytes(StandardCharsets.UTF_8),
            "/captions.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCaption timing check\n\n00:00:02.000 --> 00:00:03.000\nNext caption\n".getBytes(StandardCharsets.UTF_8),
            "/audio.wav", Files.readAllBytes(audio));
        pages.forEach((path, bytes) -> server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", path.endsWith(".html") ? "text/html" : path.endsWith(".vtt") ? "text/vtt" : "audio/wav");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }));
        server.start();
        try {
            var chain = new Toolchain(Path.of(yt), ff, ExternalAudio.tool(true), "", "");
            var entry = new DownloadModels.Entry(1, "Private caption check", "http://127.0.0.1:" + server.getAddress().getPort() + "/song.html", "Generic", "captions", true);
            var metadata = JsonParser.parseString("{\"subtitles\":{\"en\":[{}]}}").getAsJsonObject();
            var result = DownloadLyrics.fetch(new ProcessRunner(), chain, entry, metadata, temp, new ProcessRunner.Token());
            assertTrue(result.warning().isBlank(), result.warning());
            assertTrue(result.text().contains("Caption timing check"), result.text());
            Files.writeString(LyricsFiles.sidecar(audio), result.text());
            var track = new Track("test", audio.getFileName().toString(), audio, "Private excerpt", "", "", 4, Files.size(audio), 0, "");
            try (var service = new LyricsService()) {
                service.request(track);
                StorageTest.await(() -> !service.get(track).lines().isEmpty());
                var lyrics = service.get(track);
                assertEquals("Caption timing check", lyrics.lines().get(lyrics.current(1.2)).text());
                assertEquals("Next caption", lyrics.lines().get(lyrics.current(2.2)).text());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void originalDownloadFormatMatrixReusesLibraryMetadataArtworkAndDecoder() throws Exception {
        Path ff = tools(), source = source(), destination = temp.resolve("matrix-music");
        byte[] cover = new MetadataReader().artwork(TestMusic.featured());
        assertNotNull(cover);
        var real = new ProcessRunner();
        var token = new ProcessRunner.Token();
        var chain = new Toolchain(temp.resolve("unused-yt-dlp"), ff, ExternalAudio.tool(true), "", "");
        for (String[] format : List.of(new String[]{"libmp3lame", "mp3"}, new String[]{"aac", "m4a"}, new String[]{"alac", "m4a"}, new String[]{"libopus", "opus"}, new String[]{"libvorbis", "ogg"}, new String[]{"flac", "flac"}, new String[]{"pcm_s24le", "wav"})) {
            Path input = temp.resolve("matrix-" + format[0] + "." + format[1]);
            assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", format[0], input.toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
            }).exit());
            byte[] original = Files.readAllBytes(input);
            var supplied = new ProcessRunner() {
                @Override
                public Result run(List<String> args, Path dir, Token cancellation, Duration timeout, int max, Consumer<String> lines) throws IOException, InterruptedException {
                    if (args.contains("--write-info-json")) {
                        Files.copy(input, dir.resolve("source." + format[1]));
                        Files.write(dir.resolve("source.jpg"), cover);
                        Files.writeString(dir.resolve("source.info.json"), "{\"title\":\"音樂 test\",\"artist\":\"Artist\",\"album\":\"Album\",\"track_number\":4}");
                        return new Result(0, "");
                    }
                    return super.run(args, dir, cancellation, timeout, max, lines);
                }
            };
            var entry = new DownloadModels.Entry(1, "Private excerpt", "https://example.com/audio", "test", format[0], true);
            var saved = new DownloadPipeline(supplied, temp.resolve("data")).execute(entry, destination, chain, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL);
            var metadata = new MetadataReader().read("test", saved.file().getFileName().toString(), saved.file(), Files.size(saved.file()), 0);
            assertEquals("音樂 test", metadata.title(), format[0]);
            assertEquals("Artist", metadata.artist(), format[0]);
            assertEquals("Album", metadata.album(), format[0]);
            if (!format[1].equals("wav")) assertNotNull(new MetadataReader().artwork(saved.file()), format[0]);
            try (var pcm = PcmDecoder.openPrecise(saved.file())) {
                long frames = (long) (pcm.getFormat().getFrameRate() * .5);
                assertEquals(frames * pcm.getFormat().getFrameSize(), pcm.readNBytes((int) frames * pcm.getFormat().getFrameSize()).length);
                assertTrue(pcm.readNBytes(4096).length > 0, "Mid-track decode failed: " + format[0]);
            }
            assertArrayEquals(original, Files.readAllBytes(input));
        }
        try (var library = new MusicLibrary(temp.resolve("matrix-config"))) {
            library.setRoot(destination, "format-matrix");
            StorageTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 7);
            assertTrue(library.snapshot().tracks().stream().allMatch(t -> t.title().equals("音樂 test")));
        }
    }

    @Test
    void originalDownloadsImportMetadataCoversAndDecodePastTheMidpointAcrossContainers() throws Exception {
        Path ff = tools(), source = source();
        var real = new ProcessRunner();
        var token = new ProcessRunner.Token();
        var chain = new Toolchain(temp.resolve("unused-yt-dlp"), ff, ExternalAudio.tool(true), "", "");
        byte[] artwork = new MetadataReader().artwork(TestMusic.featured());
        assertNotNull(artwork);
        var formats = List.of(new String[]{"libmp3lame", "mp3"}, new String[]{"aac", "m4a"}, new String[]{"alac", "m4a"}, new String[]{"flac", "flac"}, new String[]{"pcm_s24le", "wav"});
        for (String[] format : formats) {
            Path input = temp.resolve(format[0] + "." + format[1]);
            assertEquals(0, real.run(List.of(ff.toString(), "-nostdin", "-v", "error", "-i", source.toString(), "-c:a", format[0], input.toString()), temp, token, Duration.ofSeconds(30), 16000, s -> {
            }).exit());
            var supplied = new ProcessRunner() {
                @Override
                public Result run(List<String> args, Path dir, Token cancellation, Duration timeout, int max, Consumer<String> lines) throws IOException, InterruptedException {
                    if (args.contains("--write-info-json")) {
                        Files.copy(input, dir.resolve("source." + format[1]));
                        if (!format[1].equals("wav")) Files.write(dir.resolve("source.jpg"), artwork);
                        Files.writeString(dir.resolve("source.info.json"), "{\"title\":\"Tagged track\",\"artist\":\"Tagged artist\",\"album\":\"Tagged album\"}");
                        return new Result(0, "");
                    }
                    return super.run(args, dir, cancellation, timeout, max, lines);
                }
            };
            var entry = new DownloadModels.Entry(1, "Tagged track", "https://example.com/audio", "test", format[0], true);
            var saved = new DownloadPipeline(supplied, temp.resolve("data")).execute(entry, temp.resolve("music"), chain, token, (s, d) -> {
            }, DownloadFormat.ORIGINAL);
            var metadata = new MetadataReader().read("test", saved.file().getFileName().toString(), saved.file(), Files.size(saved.file()), 0);
            assertEquals("Tagged track", metadata.title(), format[0]);
            assertEquals("Tagged artist", metadata.artist(), format[0]);
            assertEquals("Tagged album", metadata.album(), format[0]);
            if (!format[1].equals("wav")) assertNotNull(new MetadataReader().artwork(saved.file()), format[0]);
            try (var decoded = PcmDecoder.openPrecise(saved.file())) {
                var pcm = decoded.getFormat();
                assertTrue(decoded.readNBytes((int) (pcm.getFrameRate() * 2) * pcm.getFrameSize()).length > 0);
                assertTrue(decoded.readNBytes(4096).length > 0, "Mid-track decoding failed: " + format[0]);
            }
        }
        try (var library = new MusicLibrary(temp.resolve("library-config"))) {
            library.setRoot(temp.resolve("music"), "format-matrix");
            StorageTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == formats.size());
            assertTrue(library.snapshot().tracks().stream().allMatch(t -> t.title().equals("Tagged track")));
        }
    }
}
