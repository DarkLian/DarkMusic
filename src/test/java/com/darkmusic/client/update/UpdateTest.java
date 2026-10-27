package com.darkmusic.client.update;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.hud.HudLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class UpdateTest {
    @TempDir
    Path root;

    String release(String version, String digest, String url) {
        return "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v" + version + "\",\"assets\":[{\"name\":\"darkmusic-" + version + ".jar\",\"size\":100,\"digest\":\"" + digest + "\",\"browser_download_url\":\"" + url + "\"}]}";
    }

    @Test
    void onlyNewStableOfficialChecksumBearingReleasesAreOffered() throws Exception {
        String digest = "sha256:" + "a".repeat(64), url = "https://github.com/DarkLian/DarkMusic/releases/download/v2.4.0/darkmusic-2.4.0.jar";
        String json = release("2.4.0", digest, url);
        assertEquals("2.4.0", GitHubRelease.parse(json, "2.3.1").orElseThrow().version());
        assertTrue(GitHubRelease.parse(json, "2.4.0").isEmpty());
        assertTrue(GitHubRelease.parse(json, "2.10.0").isEmpty());
        assertTrue(GitHubRelease.parse(json.replace("\"prerelease\":false", "\"prerelease\":true"), "2.3.1").isEmpty());
        assertThrows(Exception.class, () -> GitHubRelease.parse(release("2.4.0", "", url), "2.3.1"));
        assertThrows(Exception.class, () -> GitHubRelease.parse(release("2.4.0", digest, "https://evil.invalid/mod.jar"), "2.3.1"));
        for (String bad : List.of("http://github.com/", "https://github.com.evil.invalid/", "https://user@github.com/", "https://github.com:123/"))
            assertFalse(UpdateService.trusted(URI.create(bad)));
        assertTrue(UpdateService.trusted(URI.create("https://release-assets.githubusercontent.com/download")));
    }

    @Test
    void oversizedResponsesCancelBeforeAccumulatingTheBody() {
        var cancelled = new AtomicBoolean();
        var body = UpdateService.limited(3);
        body.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
            }

            public void cancel() {
                cancelled.set(true);
            }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[4])));
        assertTrue(cancelled.get());
        assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    }

    Properties fixture() throws Exception {
        Path mods = Files.createDirectories(root.resolve("mods")), job = Files.createDirectories(root.resolve("job"));
        Path old = mods.resolve("darkmusic-2.3.1.jar"), next = job.resolve("download.jar");
        Files.writeString(old, "old mod fixture");
        Files.writeString(next, "verified new mod fixture");
        var p = new Properties();
        p.setProperty("old", old.toString());
        p.setProperty("name", "darkmusic-2.4.0.jar");
        p.setProperty("oldHash", GitHubRelease.hash(old));
        p.setProperty("sha256", GitHubRelease.hash(next));
        return p;
    }

    @Test
    void installerReplacesOnlyTheExpectedJarAndPreservesBackupAndMusic() throws Exception {
        var p = fixture();
        Path music = Files.writeString(root.resolve("music-untouched.txt"), "sentinel");
        var preserved = new java.util.LinkedHashMap<Path, String>();
        for (String name : List.of("config/darkmusic/config.json", "config/darkmusic/playlists.json", "resourcepacks/custom/pack.mcmeta", "mods/other-mod.jar")) {
            Path path = root.resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "existing user file: " + name);
            preserved.put(path, GitHubRelease.hash(path));
        }
        UpdateInstaller.install(root.resolve("job"), p);
        assertEquals("old mod fixture", Files.readString(root.resolve("job/previous.jar")));
        assertEquals("verified new mod fixture", Files.readString(root.resolve("mods/darkmusic-2.4.0.jar")));
        assertFalse(Files.exists(Path.of(p.getProperty("old"))));
        assertEquals("sentinel", Files.readString(music));
        for (var entry : preserved.entrySet()) assertEquals(entry.getValue(), GitHubRelease.hash(entry.getKey()));
    }

    @Test
    void oldSettingsRetainPreferencesWhileNewOptionsReceiveDefaults() {
        var old = ConfigStore.JSON.fromJson("{\"volume\":0.35,\"hudScale\":1.6,\"hudStyle\":\"ARTWORK\",\"musicDirectory\":\"custom-library\",\"lyricsEnabled\":true}", Settings.class);
        old.validate();
        assertTrue(old.updateNotifications);
        assertEquals(.35, old.volume);
        assertEquals(1.6, old.hudScale);
        assertEquals(Settings.HudStyle.ARTWORK, old.hudStyle);
        assertEquals("custom-library", old.musicDirectory);
        assertTrue(old.lyricsEnabled);
        old.updateNotifications = false;
        var roundTrip = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(old), Settings.class);
        roundTrip.validate();
        assertFalse(roundTrip.updateNotifications);
        assertEquals(.35, roundTrip.volume);
    }

    @Test
    void modifiedDownloadOrExistingDestinationNeverRemovesCurrentMod() throws Exception {
        var p = fixture();
        Files.writeString(root.resolve("job/download.jar"), "corrupt");
        assertThrows(Exception.class, () -> UpdateInstaller.install(root.resolve("job"), p));
        assertEquals("old mod fixture", Files.readString(Path.of(p.getProperty("old"))));
        p.setProperty("sha256", GitHubRelease.hash(root.resolve("job/download.jar")));
        Files.writeString(root.resolve("mods/darkmusic-2.4.0.jar"), "do not overwrite");
        assertThrows(Exception.class, () -> UpdateInstaller.install(root.resolve("job"), p));
        assertEquals("old mod fixture", Files.readString(Path.of(p.getProperty("old"))));
    }

    @Test
    void failedSwapAlwaysLeavesExactlyOneWorkingModJar() throws Exception {
        var p = fixture();
        Path old = Path.of(p.getProperty("old")), mods = old.getParent();
        // New name: the old JAR cannot be moved aside, so the newly placed JAR is withdrawn again.
        assertThrows(Exception.class, () -> UpdateInstaller.install(root.resolve("job"), p, (source, target) -> source.equals(old)));
        assertEquals("old mod fixture", Files.readString(old));
        assertFalse(Files.exists(mods.resolve("darkmusic-2.4.0.jar")));
        assertFalse(Files.exists(root.resolve("job/previous.jar")));
        try (var files = Files.list(mods)) {
            assertEquals(List.of(old), files.toList(), "No temporary or second JAR is left behind");
        }
    }

    @Test
    void sameNameSwapRestoresTheOldJarEvenWhenTheBackupCannotBeMoved() throws Exception {
        var p = fixture();
        Path mods = root.resolve("mods"), old = mods.resolve("darkmusic-2.4.0.jar");
        Files.move(Path.of(p.getProperty("old")), old);
        p.setProperty("old", old.toString());
        Path backup = root.resolve("job/previous.jar");
        // Both the new JAR's move and the backup's move back fail; the copy fallback must restore the mod.
        var failure = assertThrows(Exception.class, () -> UpdateInstaller.install(root.resolve("job"), p,
            (source, target) -> target.equals(old)));
        assertEquals("old mod fixture", Files.readString(old));
        assertTrue(failure.getMessage().contains("Move refused"), "The original failure is kept, not replaced by the rollback");
        assertTrue(failure.getSuppressed().length >= 1);
        assertTrue(Files.exists(backup), "The backup stays available after a copy-based restore");
    }

    @Test
    void extractedInstallerRunsWithOnlyTheJdk() throws Exception {
        var p = fixture();
        Path job = root.resolve("job"), manifest = job.resolve("install.properties");
        try (var out = Files.newOutputStream(manifest)) {
            p.store(out, "");
        }
        String resource = UpdateInstaller.class.getName().replace('.', '/') + ".class";
        Path helper = job.resolve(resource);
        Files.createDirectories(helper.getParent());
        try (var in = UpdateInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(in, helper);
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var process = new ProcessBuilder(java.toString(), "-cp", job.toString(), UpdateInstaller.class.getName(), Long.toString(Long.MAX_VALUE), manifest.toString()).redirectErrorStream(true).redirectOutput(job.resolve("test.log").toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals("verified new mod fixture", Files.readString(root.resolve("mods/darkmusic-2.4.0.jar")));
        var result = new Properties();
        try (var in = Files.newInputStream(manifest)) {
            result.load(in);
        }
        assertEquals("installed", result.getProperty("result"));
    }

    @Test
    void spectrumOnlyHasNoHiddenTextArtworkOrProgressSpace() {
        var s = new Settings();
        s.validate();
        assertTrue(s.updateNotifications);
        s.hudStyle = Settings.HudStyle.SPECTRUM;
        for (boolean artwork : new boolean[]{false, true})
            for (boolean progress : new boolean[]{false, true})
                for (boolean lyrics : new boolean[]{false, true}) {
                    s.hudArtwork = artwork;
                    s.hudProgress = progress;
                    s.lyricsEnabled = s.lyricsHud = s.lyricsNextLine = lyrics;
                    var c = HudLayout.content(s, 500);
                    assertEquals(220, c.width());
                    assertEquals(s.classicSpectrumHeight, c.height());
                    assertEquals(0, c.mainHeight());
                    assertEquals(0, c.spectrumY());
                    assertEquals(0, HudLayout.lyricsHeight(s));
                    for (int w : new int[]{320, 854}) {
                        var b = HudLayout.bounds(s, w, 240);
                        assertTrue(b.x() + b.width() <= w);
                        assertTrue(b.y() + b.height() <= 240);
                    }
                }
    }
}
