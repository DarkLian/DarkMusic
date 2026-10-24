package com.darkmusic.client;

import com.darkmusic.client.audio.*;
import com.darkmusic.client.config.*;
import com.darkmusic.client.hud.*;
import com.darkmusic.client.library.*;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WaveformProgressTest {
    @TempDir
    Path temp;

    @Test
    void oldSettingsKeepNormalBarsAndWaveformGeometryRespectsProgressVisibility() {
        var settings = ConfigStore.JSON.fromJson("{}", Settings.class);
        settings.validate();
        assertFalse(settings.waveformProgress);
        for (var style : Settings.HudStyle.values()) {
            settings.hudStyle = style;
            settings.hudProgress = true;
            settings.waveformProgress = false;
            var normal = HudLayout.content(settings, 1000);
            settings.waveformProgress = true;
            var waveform = HudLayout.content(settings, 1000);
            assertTrue(waveform.height() >= normal.height());
            if (style == Settings.HudStyle.CIRCULAR) {
                for (boolean spectrum : new boolean[]{false, true}) {
                    settings.hudVisualizer = spectrum;
                    var expanded = HudLayout.content(settings, 1000);
                    settings.waveformProgress = false;
                    assertEquals(HudLayout.content(settings, 1000).height() + (settings.waveform.height - 3) * 2, expanded.height());
                    settings.waveformProgress = true;
                }
            }
            settings.hudProgress = false;
            var hiddenWaveform = HudLayout.content(settings, 1000);
            settings.waveformProgress = false;
            assertEquals(hiddenWaveform, HudLayout.content(settings, 1000), "Disabled progress has no waveform rail or extra space");
        }
        settings.waveformProgress = true;
        assertTrue(ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class).waveformProgress);
    }

    @Test
    void appearanceDefaultsRoundTripAndInvalidValuesAreClamped() {
        var settings = ConfigStore.JSON.fromJson("{\"waveform\":null}", Settings.class);
        settings.validate();
        assertEquals(WaveformSettings.Style.FILLED, settings.waveform.style);
        settings.waveform.height = 1000;
        settings.waveform.detail = -1;
        settings.waveform.spacing = 99;
        settings.waveform.smoothing = Double.NaN;
        settings.waveform.contrast = Double.POSITIVE_INFINITY;
        settings.waveform.opacity = -1;
        settings.validate();
        assertEquals(18, settings.waveform.height);
        assertEquals(48, settings.waveform.detail);
        assertEquals(4, settings.waveform.spacing);
        assertEquals(.35, settings.waveform.smoothing);
        assertEquals(1.4, settings.waveform.contrast);
        assertEquals(.15, settings.waveform.opacity);
        assertEquals(settings.waveform.look(), ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class).waveform.look());
    }

    @Test
    void realPcmEnvelopeIsBoundedStereoSafeCancellableAndIndependentOfReadChunks() throws Exception {
        var clip = TestMusic.clip();
        var data = TrackWaveform.analyze(clip.stream(), () -> false);
        assertEquals(clip.bytes().length / (double) clip.format().getFrameSize() / clip.format().getSampleRate(), data.duration(), .00001);
        boolean variation = false;
        for (int i = 0; i < TrackWaveform.BINS; i++) {
            assertTrue(Float.isFinite(data.level(i)) && data.level(i) >= 0 && data.level(i) <= 1);
            variation |= data.level(i) != data.level(0);
        }
        assertTrue(variation);
        assertEquals(1, data.peak(0, 1), .00001);
        try (var smallReads = new javax.sound.sampled.AudioInputStream(clip.stream(), clip.format(), -1) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                return super.read(bytes, offset, Math.min(length, 64 * getFormat().getFrameSize()));
            }
        }) {
            var small = TrackWaveform.analyze(smallReads, () -> false);
            for (int i = 0; i < TrackWaveform.BINS; i++) assertEquals(data.level(i), small.level(i));
        }
        assertThrows(InterruptedException.class, () -> TrackWaveform.analyze(clip.stream(), () -> true));
        if (clip.format().getChannels() == 2) {
            byte[] swapped = clip.bytes().clone();
            for (int i = 0; i < swapped.length; i += 4) {
                byte a = swapped[i], b = swapped[i + 1];
                swapped[i] = swapped[i + 2];
                swapped[i + 1] = swapped[i + 3];
                swapped[i + 2] = a;
                swapped[i + 3] = b;
            }
            try (var pcm = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(swapped), clip.format(), swapped.length / 4)) {
                var reversed = TrackWaveform.analyze(pcm, () -> false);
                for (int i = 0; i < TrackWaveform.BINS; i++)
                    assertEquals(data.level(i), reversed.level(i), "Opposite stereo channels must not cancel");
            }
        }
    }

    @Test
    void persistentCacheReusesRealAudioAndInvalidatesChangedOrCorruptEntries() throws Exception {
        Path original = TestMusic.featured();
        byte[] before = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original));
        Path audio = temp.resolve(original.getFileName());
        Files.copy(original, audio);
        Track track = track(audio);
        Path cacheDirectory = temp.resolve("cache");
        TrackWaveform first;
        try (var cache = new WaveformCache(cacheDirectory)) {
            cache.update(track, false);
            assertNull(cache.get(track));
            assertFalse(Files.exists(cacheDirectory));
            cache.update(track, true);
            Await.until(Duration.ofSeconds(20), () -> cache.get(track) != null, "Real waveform analysis completes");
            first = cache.get(track);
            cache.update(track, false);
            assertNull(cache.get(track));
        }
        Path saved;
        try (var files = Files.list(cacheDirectory.resolve("waveforms"))) {
            saved = files.findFirst().orElseThrow();
        }
        String savedContents = Files.readString(saved);
        try (var cache = new WaveformCache(cacheDirectory)) {
            cache.update(track, true);
            Await.until(Duration.ofSeconds(20), () -> cache.get(track) != null, "Saved waveform reloads");
            assertEquals(savedContents, Files.readString(saved), "Cache hits do not rewrite files");
            for (int i = 0; i < TrackWaveform.BINS; i++) assertEquals(first.level(i), cache.get(track).level(i));
            Files.setLastModifiedTime(audio, java.nio.file.attribute.FileTime.fromMillis(track.modified() + 2000));
            Track changed = track(audio);
            cache.update(changed, true);
            assertNull(cache.get(track), "Old metadata cannot retrieve a new waveform");
            Await.until(Duration.ofSeconds(20), () -> cache.get(changed) != null, "Changed identity is analyzed again");
        }
        Files.writeString(saved, "invalid cache");
        Files.setLastModifiedTime(audio, java.nio.file.attribute.FileTime.fromMillis(track.modified()));
        try (var cache = new WaveformCache(cacheDirectory)) {
            cache.update(track, true);
            Await.until(Duration.ofSeconds(20), () -> cache.get(track) != null, "Corrupt entry is rebuilt from real audio");
            assertNotEquals("invalid cache", Files.readString(saved));
        }
        assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
    }

    private static Track track(Path path) throws Exception {
        return new Track("waveform", path.getFileName().toString(), path, "Real music", "", "", 0,
            Files.size(path), Files.getLastModifiedTime(path).toMillis(), "");
    }
}
