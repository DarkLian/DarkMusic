package com.darkmusic.client.systemmedia;

import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.tools.Hashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MediaControlsTest {
    @TempDir
    Path temp;

    /**
     * The installer refuses symbolic links in its path; macOS temporary folders live under the /var link.
     */
    @org.junit.jupiter.api.BeforeEach
    void realPath() throws IOException {
        temp = temp.toRealPath();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static MediaControlsPackage.Manifest manifest(byte[] zip, byte[] helper) {
        return new MediaControlsPackage.Manifest("3.5.0", "darkmusic-media-controls-3.5.0-windows-x64.zip", zip.length,
            Hashes.sha256(zip), Hashes.sha256(helper));
    }

    @Test
    void manifestsMustNameThisVersionsPackageWithItsHashes() throws Exception {
        String good = "{\"schema\":1,\"version\":\"3.5.0\",\"name\":\"darkmusic-media-controls-3.5.0-windows-x64.zip\",\"size\":10,"
            + "\"sha256\":\"" + "a".repeat(64) + "\",\"executableSha256\":\"" + "b".repeat(64) + "\"}";
        var parsed = MediaControlsPackage.Manifest.parse(good);
        assertEquals("https://github.com/DarkLian/DarkMusic/releases/download/v3.5.0/darkmusic-media-controls-3.5.0-windows-x64.zip", parsed.url().toString());
        for (String bad : List.of(good.replace("\"schema\":1", "\"schema\":2"), good.replace("3.5.0-windows", "3.4.0-windows"),
            good.replace("\"size\":10", "\"size\":0"), good.replace("a".repeat(64), "A".repeat(64)), "{}", "not json"))
            assertThrows(IOException.class, () -> MediaControlsPackage.Manifest.parse(bad), bad);
    }

    @Test
    void installVerifiesTheDownloadAndHelperAndKeepsExactlyOneVersion() throws Exception {
        byte[] helper = "fake helper executable for tests".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zip(Map.of("DarkMusic.Media.exe", helper, "DOTNET-LICENSE.TXT", "license".getBytes(StandardCharsets.UTF_8),
            "licenses/CSWINRT-LICENSE.txt", "license".getBytes(StandardCharsets.UTF_8)));
        var downloads = new AtomicInteger();
        var media = new MediaControlsPackage(temp, manifest(zip, helper), (uri, out, max, token, progress) -> {
            downloads.incrementAndGet();
            Files.write(out, zip);
        }, true);
        Path root = temp.resolve("media-native");
        Files.createDirectories(root.resolve("c".repeat(64)));
        Files.writeString(root.resolve("c".repeat(64)).resolve("DarkMusic.Media.exe"), "older version");
        Files.writeString(root.resolve("download-123.zip"), "interrupted");
        Files.createDirectories(root.resolve("staging-old"));
        Path exe = media.install(manifest(zip, helper));
        assertEquals(root.resolve(Hashes.sha256(helper)).resolve("DarkMusic.Media.exe"), exe);
        assertArrayEquals(helper, Files.readAllBytes(exe));
        assertTrue(Files.isRegularFile(exe.getParent().resolve("licenses/CSWINRT-LICENSE.txt")));
        try (var left = Files.list(root)) {
            assertEquals(List.of(".setup-lock", Hashes.sha256(helper)), left.map(p -> p.getFileName().toString()).sorted().toList(),
                "Only this version remains: no older version, interrupted download or staging folder");
        }
        assertEquals(exe, media.install(manifest(zip, helper)));
        assertEquals(1, downloads.get(), "An installed, verified helper is not downloaded again");
        media.close();
    }

    @Test
    void aPackageThatDoesNotMatchItsFingerprintInstallsNothing() throws Exception {
        byte[] helper = "fake helper".getBytes(StandardCharsets.UTF_8);
        byte[] zip = zip(Map.of("DarkMusic.Media.exe", helper));
        var tampered = zip(Map.of("DarkMusic.Media.exe", "something else".getBytes(StandardCharsets.UTF_8)));
        var media = new MediaControlsPackage(temp, manifest(zip, helper), (uri, out, max, token, progress) -> Files.write(out, tampered), true);
        var error = assertThrows(IOException.class, () -> media.install(manifest(zip, helper)));
        assertEquals("media.package_damaged", error.getMessage());
        // The archive itself matches but the helper inside does not.
        var wrongHelper = new MediaControlsPackage(temp, null, (uri, out, max, token, progress) -> Files.write(out, tampered), true);
        var swapped = new MediaControlsPackage.Manifest("3.5.0", "darkmusic-media-controls-3.5.0-windows-x64.zip", tampered.length,
            Hashes.sha256(tampered), Hashes.sha256(helper));
        assertThrows(IOException.class, () -> wrongHelper.install(swapped));
        try (var left = Files.list(temp.resolve("media-native"))) {
            assertEquals(List.of(".setup-lock"), left.map(p -> p.getFileName().toString()).toList());
        }
        media.close();
        wrongHelper.close();
    }

    @Test
    void archivesCannotPlaceFilesOutsideTheirFolder() throws Exception {
        for (String entry : List.of("../escape.exe", "a/b/c.txt", "/absolute.exe", "C:/drive.exe", "name with space.exe")) {
            Path archive = Files.write(temp.resolve("bad.zip"), zip(Map.of(entry, new byte[]{1})));
            Path target = temp.resolve("out-" + Math.abs(entry.hashCode()));
            assertThrows(IOException.class, () -> MediaControlsPackage.extract(archive, target), entry);
        }
        assertFalse(Files.exists(temp.resolve("escape.exe")));
    }

    @Test
    void unsupportedSystemsAndJavaOnlyBuildsOfferNothing() {
        var noManifest = new MediaControlsPackage(temp, null, (uri, out, max, token, progress) -> fail("No download"), true);
        assertFalse(noManifest.available());
        assertNull(noManifest.executable());
        var notWindows = new MediaControlsPackage(temp, new MediaControlsPackage.Manifest("3.5.0", "x", 1, "a".repeat(64), "b".repeat(64)),
            (uri, out, max, token, progress) -> fail("No download"), false);
        assertEquals(MediaControlsPackage.State.UNSUPPORTED, notWindows.state());
        noManifest.close();
        notWindows.close();
    }

    @Test
    void theBuiltPackageInstallsAndItsHelperConnectsToWindowsAndStopsCleanly() throws Exception {
        String built = System.getProperty("darkmusic.test.mediaControls", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("darkmusic.test.desktop") && MediaControlsPackage.supported()
            && !built.isBlank() && Files.isRegularFile(Path.of(built)), "Optional Windows desktop Media Controls check");
        byte[] archive = Files.readAllBytes(Path.of(built));
        Path unpacked = temp.resolve("read");
        MediaControlsPackage.extract(Path.of(built), unpacked);
        String version = Path.of(built).getFileName().toString().replaceAll("darkmusic-media-controls-(.*)-windows-x64\\.zip", "$1");
        var manifest = new MediaControlsPackage.Manifest(version, Path.of(built).getFileName().toString(), archive.length,
            Hashes.sha256(archive), Hashes.sha256(unpacked.resolve("DarkMusic.Media.exe")));
        var media = new MediaControlsPackage(temp, manifest, MediaControlsPackage.developmentDownload(true, built, (u, o, m, t, p) -> fail("No network")), true);
        Path exe = media.install(manifest);
        var controls = new MediaControls(temp.resolve("media-native"), action -> {
        });
        var track = new Track("root", "probe.mp3", temp.resolve("probe.mp3"), "DarkMusic desktop test", "DarkMusic", "Test", 200, 1, 0, "");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!controls.connected() && System.nanoTime() < deadline) {
            controls.update(exe, new AudioBackend.State(track, AudioBackend.Status.PAUSED, 5, 200, ""), System.nanoTime());
            Thread.sleep(100);
        }
        assertTrue(controls.connected(), "The helper registered with Windows media controls");
        controls.update(null, null, System.nanoTime());
        Thread.sleep(2500);
        try (var processes = ProcessHandle.allProcesses()) {
            assertTrue(processes.noneMatch(p -> p.info().command().map(c -> c.equals(exe.toString())).orElse(false)),
                "Turning the option off ends the helper");
        }
        media.remove().get(30, TimeUnit.SECONDS);
        assertFalse(Files.exists(exe), "Removing Media Controls deletes its files");
        assertEquals(MediaControlsPackage.State.MISSING, media.state());
        controls.close();
        media.close();
    }

    @Test
    void removingDeletesEveryInstalledVersionButKeepsTheSetupLock() throws Exception {
        var media = new MediaControlsPackage(temp, null, (uri, out, max, token, progress) -> fail("No download"), true);
        Path root = Files.createDirectories(temp.resolve("media-native"));
        Files.writeString(root.resolve(".setup-lock"), "");
        Files.createDirectories(root.resolve("a".repeat(64)).resolve("licenses"));
        Files.writeString(root.resolve("a".repeat(64)).resolve("DarkMusic.Media.exe"), "helper");
        Files.createDirectories(root.resolve("artwork"));
        Files.writeString(root.resolve("helper-errors.log"), "");
        media.removeAll();
        try (var left = Files.list(root)) {
            assertEquals(List.of(".setup-lock"), left.map(p -> p.getFileName().toString()).toList());
        }
        media.close();
    }

    @Test
    void helperMessagesBecomeButtonsAndAnythingElseIsIgnored() {
        assertEquals(new MediaControls.Action("play", 0), MediaControls.Action.parse("play"));
        assertEquals(new MediaControls.Action("next", 0), MediaControls.Action.parse("next "));
        assertEquals(new MediaControls.Action("seek", 90.5), MediaControls.Action.parse("seek 90.5"));
        for (String ignored : List.of("ready 1", "seek -3", "seek NaN", "seek", "play now", "delete everything", ""))
            assertNull(MediaControls.Action.parse(ignored), ignored);
        assertNull(MediaControls.Action.parse(null));
    }

    @Test
    void windowsIsUpdatedOnChangesSeeksAndEveryFiveSecondsWhilePlaying() {
        var track = new Track("root", "a.mp3", Path.of("a.mp3"), "Departure", "Kavinsky", "Album", 200, 1, 0, "");
        var playing = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PLAYING, 10, 200, ""), "");
        assertEquals("playing", playing.status());
        assertTrue(playing.json().contains("\"title\":\"Departure\""));
        long second = TimeUnit.SECONDS.toNanos(1);
        assertTrue(MediaControls.due(null, 0, playing, 0), "Nothing sent yet");
        var later = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PLAYING, 13, 200, ""), "");
        assertFalse(MediaControls.due(playing, 0, later, 3 * second), "Normal playback needs no update");
        var afterRefresh = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PLAYING, 15, 200, ""), "");
        assertTrue(MediaControls.due(playing, 0, afterRefresh, 5 * second), "Regular refresh keeps the flyout timeline right");
        var seeked = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PLAYING, 120, 200, ""), "");
        assertTrue(MediaControls.due(playing, 0, seeked, second));
        var paused = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PAUSED, 11, 200, ""), "");
        assertTrue(MediaControls.due(playing, 0, paused, second));
        assertFalse(MediaControls.due(paused, 0, paused, 60 * second), "A paused song needs no refresh");
        var withArtwork = MediaControls.State.of(new AudioBackend.State(track, AudioBackend.Status.PAUSED, 11, 200, ""), "C:/art.jpg");
        assertTrue(MediaControls.due(paused, 0, withArtwork, second), "Artwork arriving later is sent");
        assertEquals("stopped", MediaControls.State.of(new AudioBackend.State(null, AudioBackend.Status.STOPPED, 0, 0, ""), "").status());
    }
}
