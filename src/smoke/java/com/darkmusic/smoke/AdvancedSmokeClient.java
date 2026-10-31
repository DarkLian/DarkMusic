package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.config.*;
import com.darkmusic.client.download.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.gui.EqualizerGraph;
import com.darkmusic.client.library.*;
import com.darkmusic.client.tools.ProcessRunner;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import org.slf4j.LoggerFactory;

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
            if (app == null || app.services().settings() == null || app.services().library() == null) return;
            switch (stage) {
                case -1 -> {
                    Path source = Path.of(System.getProperty("darkmusic.music"));
                    if (!source.equals(app.services().library().root())) {
                        app.sources().chooseMusicFolder(source, () -> {
                        });
                        return;
                    }
                    if (app.services().library().snapshot().scanning() || app.services().library().snapshot().tracks().isEmpty()) return;
                    Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                    app.services().settings().ffmpegPath = tools.resolve("ffmpeg.exe").toString();
                    app.services().settings().ffprobePath = tools.resolve("ffprobe.exe").toString();
                    app.services().apply();
                    app.services().settings().audio = new AudioSettings();
                    app.services().settings().visualQuality = new VisualQuality();
                    app.services().save();
                    if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                    work = Path.of("native-audio").toAbsolutePath();
                    Files.createDirectories(work);
                    Track song = app.services().library().snapshot().tracks().getFirst();
                    preparation = CompletableFuture.supplyAsync(() -> {
                        try {
                            List<Track> tracks = new ArrayList<>();
                            int i = 0;
                            for (String[] codec : List.of(new String[]{"alac", "m4a"}, new String[]{"aac", "m4a"}, new String[]{"libopus", "opus"}, new String[]{"flac", "flac"})) {
                                Path file = work.resolve("excerpt-" + (i++) + "." + codec[1]);
                                var result = new ProcessRunner().run(List.of(app.services().settings().ffmpegPath, "-nostdin", "-v", "error", "-y", "-i", song.file().toString(), "-map", "0:a:0", "-vn", "-t", "4", "-ar", i % 2 == 0 || codec[0].equals("libopus") ? "48000" : "44100", "-ac", i % 2 == 0 ? "1" : "2", "-c:a", codec[0], "-metadata", "title=" + song.title(), file.toString()), work, new ProcessRunner.Token(), Duration.ofSeconds(30), 16000);
                                require(result.exit() == 0, result.output());
                                tracks.add(new MetadataReader().read("runtime", file.getFileName().toString(), file, Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                            }
                            return List.copyOf(tracks);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, app.services().browsing());
                    stage = 0;
                }
                case 0 -> {
                    if (!preparation.isDone()) return;
                    var tracks = preparation.join();
                    app.services().playback().play(tracks, tracks.getFirst());
                    app.services().settings().audio.crossfade = .5;
                    app.services().save();
                    mc.gui.setScreen(new MusicScreen(app.sources(), null));
                    stage = 1;
                    at = System.nanoTime();
                }
                case 1 -> {
                    var audio = app.services().playback().state();
                    require(audio.status() != AudioBackend.Status.ERROR, audio.error());
                    require(!app.services().warnings().current().contains("Cannot play"), app.services().warnings().current());
                    if (audio.status() == AudioBackend.Status.PLAYING && audio.position() > .5) {
                        heard.add(audio.track().relativePath());
                    }
                    if (heard.size() < 4 || audio.position() < 1) return;
                    app.services().playback().pause();
                    stage = 2;
                    at = System.nanoTime();
                }
                case 2 -> {
                    require(app.services().playback().state().status() == AudioBackend.Status.PAUSED, "Pause did not reach audio device");
                    app.services().playback().seek(.5);
                    app.services().playback().resume();
                    app.services().settings().audio.neutral = false;
                    app.services().settings().audio.equalizer = true;
                    app.services().settings().audio.equalizer(AudioSettings.Equalizer.ROCK);
                    app.services().settings().audio.limiter = true;
                    app.services().settings().audio.reverb = AudioSettings.Reverb.SMALL_ROOM;
                    app.services().save();
                    var songs = app.services().library().snapshot().tracks();
                    app.services().playback().play(songs, songs.getFirst());
                    app.services().settings().audio.equalizer = false;
                    mc.gui.setScreen(new AudioSettingsScreen(app.sources(), null));
                    require(mc.gui.screen().children().stream().noneMatch(w -> w instanceof EqualizerGraph), "Inactive EQ bands were not collapsed");
                    app.services().settings().audio.equalizer = true;
                    // Original bypasses the sound; Custom then starts blank, with the controls adjustable again.
                    app.services().settings().audio.selectProfile("DEFAULT");
                    require(app.services().settings().audio.neutral, "Original did not bypass the sound");
                    mc.gui.setScreen(new AudioSettingsScreen(app.sources(), null));
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof AbstractWidget).map(w -> (AbstractWidget) w).filter(w -> w.getMessage().getString().equals("10-Band Equalizer")).noneMatch(w -> w.active), "Original did not disable bypassed EQ controls");
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof EqualizerGraph).map(w -> (EqualizerGraph) w).noneMatch(w -> w.active), "Original did not disable the EQ graph");
                    app.services().settings().audio.selectProfile(AudioSettings.CUSTOM);
                    require(app.services().settings().audio.profile.equals(AudioSettings.CUSTOM) && !app.services().settings().audio.neutral, "Custom did not start blank and adjustable");
                    app.services().settings().audio.equalizer = true;
                    mc.gui.setScreen(new AudioSettingsScreen(app.sources(), null));
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof EqualizerGraph).count() == 1, "Active EQ bands did not return");
                    app.services().save();
                    stage = 3;
                }
                case 3 -> {
                    if (!capture(mc, "audio-options")) return;
                    app.services().settings().visualQuality.select(VisualQuality.Preset.EXTREME);
                    app.services().settings().hudStyle = Settings.HudStyle.CIRCULAR;
                    app.services().settings().circularSpectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    app.services().settings().lyricsHud = true;
                    app.services().save();
                    mc.gui.setScreen(new SettingsScreen(app.sources(), null));
                    stage = 4;
                }
                case 4 -> {
                    if (!capture(mc, "circular-extreme")) return;
                    mc.gui.setScreen(new LyricsScreen(app.sources(), null));
                    stage = 5;
                }
                case 5 -> {
                    if (!capture(mc, "lyrics")) return;
                    app.services().playback().seek(20);
                    app.services().settings().audio.normalization = app.services().settings().audio.analyzeLoudness = true;
                    app.services().save();
                    stage = 6;
                    at = System.nanoTime();
                }
                case 6 -> {
                    Path cache = app.services().config().directory().resolve("loudness-cache.json");
                    if (!Files.isRegularFile(cache) && System.nanoTime() - at < TimeUnit.SECONDS.toNanos(40)) return;
                    require(Files.isRegularFile(cache), "Background loudness analysis did not publish a cache");
                    app.services().settings().audio.neutral = true;
                    app.services().settings().audio.listening = AudioSettings.Listening.NORMAL;
                    app.services().settings().visualQuality.select(VisualQuality.Preset.STANDARD);
                    app.services().settings().lyricsHud = false;
                    app.services().settings().hudStyle = Settings.HudStyle.CLASSIC;
                    app.services().save();
                    app.services().playback().stop();
                    stage = 7;
                }
                case 7 -> {
                    if (captures != 3) return;
                    for (Path file : preparation.join().stream().map(Track::file).toList()) {
                        require(file.startsWith(work), "Cleanup escaped test directory");
                        Files.delete(file);
                    }
                    app.services().save();
                    LoggerFactory.getLogger("darkmusic-advanced-smoke").info("DARKMUSIC_SMOKE_PASS:{} real-device ALAC/AAC/Opus/FLAC transitions, sample-rate/channel adaptation, pause/seek, neutral and DSP, live Extreme Circular HUD and lyrics view, background loudness cache; three private fullscreen GUI-scale-2 captures", Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                    stage = 999;
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger("darkmusic-advanced-smoke").error("DARKMUSIC_SMOKE_FAIL stage=" + stage, e);
            stage = 999;
            mc.stop();
        }
    }
}
