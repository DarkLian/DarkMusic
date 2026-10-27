package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.library.*;
import com.darkmusic.client.download.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Isolated real-device checks. All converted audio derives from the private collection and is removed.
 */
final class AdvancedSmokeClient {
    private int tick, stage = -1, captures;
    private CompletableFuture<List<Track>> preparation;
    private final Set<String> heard = new HashSet<>();
    private Path work;
    private long at;

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static void require(boolean test, String message) {
        if (!test) throw new IllegalStateException(message);
    }

    private boolean capture(Minecraft mc, String name) throws Exception {
        if (!mc.getWindow().isFullscreen() || mc.options.guiScale().get() != 2 || mc.getWindow().getGuiScale() != 2) {
            if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return false;
        }
        require(mc.getWindow().isFullscreen() && mc.options.guiScale().get() == 2 && mc.getWindow().getGuiScale() == 2, "Fullscreen GUI scale 2 required");
        SmokeUi.captureReady(mc);
        Path root = Path.of(System.getProperty("darkmusic.privateImages"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), "v2-review-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, s -> captures++);
        return true;
    }

    private void tick(Minecraft mc) {
        if (stage == 999) return;
        try {
            if (++tick > 6000) throw new IllegalStateException("Advanced audio runtime timeout");
            if (tick % 10 != 0) return;
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.settings == null || app.library == null) return;
            switch (stage) {
                case -1 -> {
                    Path source = Path.of(System.getProperty("darkmusic.music"));
                    if (!source.equals(app.library.root())) {
                        app.folders.choose(source, () -> {
                        });
                        return;
                    }
                    if (app.library.snapshot().scanning() || app.library.snapshot().tracks().isEmpty()) return;
                    Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                    app.settings.ffmpegPath = tools.resolve("ffmpeg.exe").toString();
                    app.settings.ffprobePath = tools.resolve("ffprobe.exe").toString();
                    app.settings.audio = new AudioSettings();
                    app.settings.visualQuality = new VisualQuality();
                    app.save();
                    if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                    work = Path.of("native-audio").toAbsolutePath();
                    Files.createDirectories(work);
                    Track song = app.library.snapshot().tracks().getFirst();
                    preparation = CompletableFuture.supplyAsync(() -> {
                        try {
                            List<Track> tracks = new ArrayList<>();
                            int i = 0;
                            for (String[] codec : List.of(new String[]{"alac", "m4a"}, new String[]{"aac", "m4a"}, new String[]{"libopus", "opus"}, new String[]{"flac", "flac"})) {
                                Path file = work.resolve("excerpt-" + (i++) + "." + codec[1]);
                                var result = new ProcessRunner().run(List.of(app.settings.ffmpegPath, "-nostdin", "-v", "error", "-y", "-i", song.file().toString(), "-map", "0:a:0", "-vn", "-t", "4", "-ar", i % 2 == 0 || codec[0].equals("libopus") ? "48000" : "44100", "-ac", i % 2 == 0 ? "1" : "2", "-c:a", codec[0], "-metadata", "title=" + song.title(), file.toString()), work, new ProcessRunner.Token(), Duration.ofSeconds(30), 16000, s -> {
                                });
                                require(result.exit() == 0, result.output());
                                tracks.add(new MetadataReader().read("runtime", file.getFileName().toString(), file, Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                            }
                            return List.copyOf(tracks);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, app.browsing);
                    stage = 0;
                }
                case 0 -> {
                    if (!preparation.isDone()) return;
                    var tracks = preparation.join();
                    app.playback.play(tracks, tracks.getFirst());
                    app.settings.audio.crossfade = .5;
                    app.save();
                    mc.gui.setScreen(new MusicScreen(app, null));
                    stage = 1;
                    at = System.nanoTime();
                }
                case 1 -> {
                    var audio = app.playback.state();
                    require(audio.status() != AudioBackend.Status.ERROR, audio.error());
                    require(!app.warning.contains("Cannot play"), app.warning);
                    if (audio.status() == AudioBackend.Status.PLAYING && audio.position() > .5) {
                        heard.add(audio.track().relativePath());
                    }
                    if (heard.size() < 4 || audio.position() < 1) return;
                    app.playback.pause();
                    stage = 2;
                    at = System.nanoTime();
                }
                case 2 -> {
                    require(app.playback.state().status() == AudioBackend.Status.PAUSED, "Pause did not reach audio device");
                    app.playback.seek(.5);
                    app.playback.resume();
                    app.settings.audio.neutral = false;
                    app.settings.audio.equalizer = true;
                    app.settings.audio.equalizer(AudioSettings.Equalizer.ROCK);
                    app.settings.audio.limiter = true;
                    app.settings.audio.reverb = AudioSettings.Reverb.SMALL_ROOM;
                    app.save();
                    var songs = app.library.snapshot().tracks();
                    app.playback.play(songs, songs.getFirst());
                    app.settings.audio.equalizer = false;
                    mc.gui.setScreen(new AudioSettingsScreen(app, null));
                    require(mc.gui.screen().children().stream().noneMatch(w -> w instanceof com.darkmusic.client.gui.EqualizerGraph), "Inactive EQ bands were not collapsed");
                    app.settings.audio.equalizer = true;
                    app.settings.audio.neutral = true;
                    mc.gui.setScreen(new AudioSettingsScreen(app, null));
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof net.minecraft.client.gui.components.AbstractWidget).map(w -> (net.minecraft.client.gui.components.AbstractWidget) w).filter(w -> w.getMessage().getString().equals("10-Band Equalizer")).noneMatch(w -> w.active), "Neutral did not disable bypassed EQ controls");
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof com.darkmusic.client.gui.EqualizerGraph).map(w -> (com.darkmusic.client.gui.EqualizerGraph) w).noneMatch(w -> w.active), "Neutral did not disable the EQ graph");
                    app.settings.audio.neutral = false;
                    mc.gui.setScreen(new AudioSettingsScreen(app, null));
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof com.darkmusic.client.gui.EqualizerGraph).count() == 1, "Active EQ bands did not return");
                    app.save();
                    stage = 3;
                }
                case 3 -> {
                    if (!capture(mc, "audio-options")) return;
                    app.settings.visualQuality.select(VisualQuality.Preset.EXTREME);
                    app.settings.hudStyle = Settings.HudStyle.CIRCULAR;
                    app.settings.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    app.settings.lyricsHud = true;
                    app.save();
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    stage = 4;
                }
                case 4 -> {
                    if (!capture(mc, "circular-extreme")) return;
                    mc.gui.setScreen(new LyricsScreen(app, null));
                    stage = 5;
                }
                case 5 -> {
                    if (!capture(mc, "lyrics")) return;
                    app.playback.seek(20);
                    app.settings.audio.normalization = app.settings.audio.analyzeLoudness = true;
                    app.save();
                    stage = 6;
                    at = System.nanoTime();
                }
                case 6 -> {
                    Path cache = app.config.directory().resolve("loudness-cache.json");
                    if (!Files.isRegularFile(cache) && System.nanoTime() - at < TimeUnit.SECONDS.toNanos(40)) return;
                    require(Files.isRegularFile(cache), "Background loudness analysis did not publish a cache");
                    app.settings.audio.neutral = true;
                    app.settings.audio.listening = AudioSettings.Listening.NORMAL;
                    app.settings.visualQuality.select(VisualQuality.Preset.STANDARD);
                    app.settings.lyricsHud = false;
                    app.settings.hudStyle = Settings.HudStyle.CLASSIC;
                    app.save();
                    app.playback.stop();
                    stage = 7;
                }
                case 7 -> {
                    if (captures != 3) return;
                    for (Path file : preparation.join().stream().map(Track::file).toList()) {
                        require(file.startsWith(work), "Cleanup escaped test directory");
                        Files.delete(file);
                    }
                    app.save();
                    org.slf4j.LoggerFactory.getLogger("darkmusic-advanced-smoke").info("DARKMUSIC_SMOKE_PASS:{} real-device ALAC/AAC/Opus/FLAC transitions, sample-rate/channel adaptation, pause/seek, neutral and DSP, live Extreme Circular HUD and lyrics view, background loudness cache; three private fullscreen GUI-scale-2 captures", Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                    stage = 999;
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            stage = 999;
            org.slf4j.LoggerFactory.getLogger("darkmusic-advanced-smoke").error("DARKMUSIC_SMOKE_FAIL", e);
            mc.stop();
        }
    }
}
