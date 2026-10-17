package com.darkmusic.client;

import com.darkmusic.client.download.DownloadJson;
import com.darkmusic.client.download.DownloadPipeline;
import com.darkmusic.client.download.ProcessRunner;
import com.darkmusic.client.download.Toolchain;
import com.darkmusic.client.library.MetadataReader;
import com.darkmusic.client.library.MusicLibrary;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.darkmusic.client.download.DownloadModels.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual executables with real music excerpts served only on loopback; no public upload.
 */
class DownloadIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void realMusicDecodesAcrossSupportedConvertedFormats() throws Exception {
        Tools selected = settings();
        var runner = new ProcessRunner();
        Path source = TestMusic.featured();
        for (String format : List.of("wav", "flac", "ogg")) {
            Path file = temp.resolve("excerpt." + format);
            String codec = switch (format) {
                case "wav" -> "pcm_s16le";
                case "flac" -> "flac";
                default -> "libvorbis";
            };
            var result = runner.run(List.of(selected.ffmpeg(), "-nostdin", "-v", "error", "-ss", "30", "-i", source.toString(), "-t", "2", "-vn", "-c:a", codec, "-sample_fmt", format.equals("ogg") ? "fltp" : "s16", file.toString()), temp, new ProcessRunner.Token(), Duration.ofSeconds(30), 32000, line -> {
            });
            assertEquals(0, result.exit(), result.output());
            try (var pcm = com.darkmusic.client.audio.PcmDecoder.open(file)) {
                assertTrue(pcm.readNBytes(32768).length > 0);
            }
            assertTrue(new MetadataReader().read("real", file.getFileName().toString(), file, Files.size(file), 0).duration() > 1);
        }
    }

    Tools settings() {
        Assumptions.assumeTrue(System.getProperty("darkmusic.test.ytdlp") != null, "Enable -PdownloadIntegration with development tools installed");
        return new Tools(System.getProperty("darkmusic.test.ytdlp"), System.getProperty("darkmusic.test.ffmpeg"), System.getProperty("darkmusic.test.ffprobe"), "");
    }

    @Test
    void realYtDlpDownloadsAndConvertsAuthorizedAudioAndLibraryFindsItImmediately() throws Exception {
        Tools settings = settings();
        var runner = new ProcessRunner();
        var token = new ProcessRunner.Token();
        Path data = temp.resolve("instance");
        Files.createDirectories(data);
        var chain = Toolchain.resolve(settings, data, runner, token);
        Path excerpt = temp.resolve("excerpt.wav");
        StorageTest.writeWav(excerpt);
        byte[] fixture = Files.readAllBytes(excerpt);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var http = Executors.newCachedThreadPool();
        server.setExecutor(http);
        server.createContext("/音乐-日本語.wav", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(fixture.length));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, fixture.length);
                exchange.getResponseBody().write(fixture);
            }
            exchange.close();
        });
        server.start();
        try (var library = new MusicLibrary(data)) {
            Path destination = Files.createDirectories(temp.resolve("chosen 音楽"));
            library.setRoot(destination, "integration");
            String url = url("http://127.0.0.1:" + server.getAddress().getPort() + "/音乐-日本語.wav");
            var args = chain.base();
            args.addAll(List.of("--dump-single-json", "--skip-download", "--no-playlist", "--", url));
            var info = runner.run(args, data, token, Duration.ofSeconds(40), 2_000_000, s -> {
            });
            assertEquals(0, info.exit(), info.output());
            var preview = DownloadJson.preview(url, 1, DownloadJson.object(info.output()));
            assertFalse(preview.collection());
            var saved = new DownloadPipeline(runner, data).execute(preview.entries().getFirst(), destination, chain, token, (status, detail) -> {
            });
            assertTrue(Files.size(saved.file()) > 1000);
            assertFalse(saved.skipped());
            library.rescan();
            DownloadTest.await(() -> !library.snapshot().scanning() && library.snapshot().tracks().size() == 1);
            assertTrue(library.snapshot().tracks().getFirst().duration() > 1);
            try (var pcm = com.darkmusic.client.audio.PcmDecoder.open(saved.file())) {
                assertTrue(pcm.readNBytes(10000).length > 0);
            }
            assertTrue(new DownloadPipeline(runner, data).execute(preview.entries().getFirst(), destination, chain, token, (a, b) -> {
            }).skipped());
        } finally {
            server.stop(0);
            http.shutdownNow();
        }
    }

    @Test
    void realFfmpegPreservesUnicodeMetadataAndArtworkWithoutInventingMissingAlbum() throws Exception {
        Tools settings = settings();
        var runner = new ProcessRunner();
        var token = new ProcessRunner.Token();
        Path data = Files.createDirectories(temp.resolve("instance"));
        var chain = Toolchain.resolve(settings, data, runner, token);
        Path fixture = temp.resolve("excerpt.mp3");
        var excerpt = runner.run(List.of(chain.ffmpeg().toString(), "-nostdin", "-v", "error", "-ss", "30", "-i", TestMusic.featured().toString(), "-t", "4", "-c:a", "libmp3lame", fixture.toString()), data, token, Duration.ofSeconds(30), 32000, line -> {
        });
        assertEquals(0, excerpt.exit(), excerpt.output());
        ProcessRunner supply = new ProcessRunner() {
            @Override
            public Result run(List<String> args, Path dir, Token t, Duration timeout, int max, Consumer<String> lines) throws java.io.IOException, InterruptedException {
                if (!args.contains("--write-info-json")) return super.run(args, dir, t, timeout, max, lines);
                Files.copy(fixture, dir.resolve("source.mp3"));
                if (args.getLast().endsWith("/untagged")) {
                    Files.writeString(dir.resolve("source.info.json"), "{}");
                    return new Result(0, "");
                }
                Files.writeString(dir.resolve("source.info.json"), "{\"title\":\"歌曲と音楽\",\"artist\":\"作曲家\",\"album\":\"专辑\",\"track_number\":7}");
                var image = new java.awt.image.BufferedImage(80, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
                javax.imageio.ImageIO.write(image, "png", dir.resolve("source.png").toFile());
                return new Result(0, "");
            }
        };
        Path destination = temp.resolve("music");
        var saved = new DownloadPipeline(supply, data).execute(new Entry(0, "Track", "https://example.com/owned", "Test", "owned", true), destination, chain, token, (a, b) -> {
        });
        var metadata = new MetadataReader().read("r", saved.file().getFileName().toString(), saved.file(), Files.size(saved.file()), 0);
        assertEquals("歌曲と音楽", metadata.title());
        assertEquals("作曲家", metadata.artist());
        assertEquals("专辑", metadata.album());
        assertNotNull(new MetadataReader().artwork(saved.file()));
        var probe = runner.run(List.of(chain.ffprobe().toString(), "-v", "error", "-show_entries", "format_tags=track", "-of", "json", saved.file().toString()), data, token, Duration.ofSeconds(10), 32000, s -> {
        });
        assertTrue(probe.output().contains("7"), probe.output());
        var untagged = new DownloadPipeline(supply, data).execute(new Entry(0, "Untagged", "https://example.com/untagged", "Test", "untagged", true), destination, chain, token, (a, b) -> {
        });
        var tags = runner.run(List.of(chain.ffprobe().toString(), "-v", "error", "-show_entries", "format_tags", "-of", "json", untagged.file().toString()), data, token, Duration.ofSeconds(10), 32000, s -> {
        });
        var json = JsonParser.parseString(tags.output()).getAsJsonObject().getAsJsonObject("format").getAsJsonObject("tags");
        for (String key : List.of("artist", "album", "track")) assertFalse(json.has(key), tags.output());
        assertTrue(untagged.warning().contains("No thumbnail"));
    }
}
