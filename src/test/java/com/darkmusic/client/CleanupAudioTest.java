package com.darkmusic.client;

import com.darkmusic.client.audio.ExternalAudio;
import com.darkmusic.client.cleanup.*;
import com.darkmusic.client.download.ProcessRunner;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CleanupAudioTest {
    @Test
    void realRecordingMatchesAcrossCodecsTagsAndTrimmedOffset() throws Exception {
        Path root = Path.of(System.getProperty("darkmusic.test.music", "run/config/darkmusic/music"));
        List<Path> music;
        if (!Files.isDirectory(root)) {
            assertFalse(Boolean.getBoolean("darkmusic.test.requireMusic"), "Required private collection unavailable");
            Assumptions.assumeTrue(false, "Private collection unavailable");
            return;
        }
        try (var paths = Files.walk(root)) {
            music = paths.filter(Files::isRegularFile).filter(p -> p.toString().matches("(?i).*\\.(mp3|flac|opus|wav|ogg)$")).sorted().toList();
        }
        if (Boolean.getBoolean("darkmusic.test.requireMusic"))
            assertFalse(music.isEmpty(), "Required private collection unavailable");
        Assumptions.assumeFalse(music.isEmpty(), "Private collection unavailable");
        Path ff = Path.of(System.getProperty("darkmusic.test.ffmpeg", "missing-ffmpeg"));
        Assumptions.assumeTrue(Files.isRegularFile(ff), "Existing codec tools unavailable");
        Path dir = Files.createTempDirectory(Path.of(".local-tools/verification"), "cleanup-audio-").toAbsolutePath();
        var runner = new ProcessRunner();
        var token = new ProcessRunner.Token();
        try {
            var prints = new ArrayList<int[]>();
            for (String codec : List.of("flac", "libmp3lame", "libopus", "trim")) {
                Path output = dir.resolve(codec + (codec.equals("flac") || codec.equals("trim") ? ".flac" : codec.equals("libopus") ? ".opus" : ".mp3"));
                var args = new ArrayList<>(List.of(ff.toAbsolutePath().toString(), "-nostdin", "-v", "error", "-ss", codec.equals("trim") ? "12.3" : "5", "-i", music.getFirst().toAbsolutePath().toString(), "-t", codec.equals("trim") ? "29" : "40", "-vn", "-map_metadata", "-1", "-metadata", "title=Different title " + codec, "-c:a", codec.equals("trim") ? "flac" : codec));
                if (codec.equals("libmp3lame")) args.addAll(List.of("-b:a", "96k"));
                args.addAll(List.of("-y", output.toString()));
                assertEquals(0, runner.run(args, dir, token, Duration.ofSeconds(40), 8192, s -> {
                }).exit());
                long start = System.nanoTime();
                prints.add(new FingerprintService(new ProcessRunner(), ff.toAbsolutePath()).extract(output, dir, token));
                System.out.println("CLEANUP_FINGERPRINT " + codec + ": ms=" + (System.nanoTime() - start) / 1_000_000);
            }
            for (int i = 1; i < prints.size(); i++) {
                var match = FingerprintService.compare(prints.getFirst(), prints.get(i), DuplicatePolicy.BALANCED);
                System.out.println("CLEANUP_AUDIO " + i + ": " + match);
                assertTrue(match.matched(), "Cross-format/trim audio match " + i);
            }
            // Repeat cross-format discovery on additional real recordings, with unrelated metadata.
            for (Path source : music.stream().skip(1).limit(2).toList()) {
                var fingerprints = new ArrayList<int[]>();
                for (String codec : List.of("flac", "libmp3lame")) {
                    Path output = dir.resolve("extra-" + codec + (codec.equals("flac") ? ".flac" : ".mp3"));
                    var args = List.of(ff.toAbsolutePath().toString(), "-nostdin", "-v", "error", "-i", source.toAbsolutePath().toString(), "-t", "40", "-vn", "-map_metadata", "-1", "-c:a", codec, "-y", output.toString());
                    assertEquals(0, runner.run(args, dir, token, Duration.ofSeconds(40), 8192, s -> {
                    }).exit());
                    fingerprints.add(new FingerprintService(new ProcessRunner(), ff.toAbsolutePath()).extract(output, dir, token));
                }
                assertTrue(FingerprintService.compare(fingerprints.get(0), fingerprints.get(1), DuplicatePolicy.BALANCED).matched(), "Additional real recording matches across codecs");
            }
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
