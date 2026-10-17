package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.library.Track;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.*;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Opt-in real-collection checks. Only the isolated build/capture-run profile is changed.
 */
final class MusicCaptureClient {
    private final Set<String> played = new HashSet<>(), captured = new HashSet<>();
    private int ticks, frame, stage = -1, audition, auditionStarted;
    private boolean fullscreen, selecting, ready, positioned;
    private List<Track> tracks;
    private Track featured;
    private MusicScreen player;
    private float[] frozenSpectrum;
    private String playlist;

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    private MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    }

    private AbstractWidget button(Minecraft mc, String name) {
        return mc.gui.screen().children().stream().filter(x -> x instanceof AbstractWidget b && b.getMessage().getString().equals(name)).map(x -> (AbstractWidget) x).findFirst().orElseThrow(() -> new IllegalStateException("Missing button: " + name));
    }

    private void click(Minecraft mc, String name) {
        var b = button(mc, name);
        require(b.active, "Inactive: " + name);
        mc.gui.screen().mouseClicked(mouse(b.getX() + 3, b.getY() + 3), false);
    }

    private void escape(Minecraft mc) {
        mc.gui.screen().keyPressed(new KeyEvent(256, 0, 0));
    }

    private void capture(Minecraft mc, String name) throws java.io.IOException {
        require(mc.getWindow().isFullscreen() && GLFW.glfwGetWindowMonitor(mc.getWindow().handle()) != 0, "Capture requires fullscreen");
        require(mc.options.guiScale().get() == 3 && mc.getWindow().getGuiScale() == 3, "Capture requires configured and effective GUI scale 3: configured=" + mc.options.guiScale().get() + ", effective=" + mc.getWindow().getGuiScale());
        var privateImages = Path.of(System.getProperty("darkmusic.privateImages"));
        java.nio.file.Files.createDirectories(privateImages.resolve("screenshots"));
        Screenshot.grab(privateImages.toFile(), "100-test-3x-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, text -> {
            LoggerFactory.getLogger("darkmusic-capture").info("FULLSCREEN GUI_SCALE=3 {}x{} {}: {}", mc.getWindow().getWidth(), mc.getWindow().getHeight(), name, text.getString());
            mc.execute(() -> captured.add(name));
        });
    }

    private void row(Minecraft mc, Object screen, String name, int index) throws Exception {
        var list = (RowList) field(screen, name);
        list.onClick(mouse(list.getX() + 4, list.getY() + index * 32 + 4), false);
    }

    private void checkDragging(Minecraft mc, DarkMusicClient app) throws Exception {
        var screen = mc.gui.screen();
        var list = (RowList) field(screen, "entries");
        var selection = (SongSelection) field(screen, "selection");
        var original = app.playlists.get(playlist).entries();
        row(mc, screen, "entries", 0);
        row(mc, screen, "entries", 1);
        row(mc, screen, "entries", 4);
        var checked = selection.selected();
        long revision = app.playlists.revision();
        double x = list.getX() + 40, y = list.getY() + 40;
        screen.mouseClicked(mouse(x, y), false);
        screen.mouseDragged(mouse(x, list.getY() + 185), 0, 145);
        require(app.playlists.revision() == revision, "Drag preview saved before drop");
        require(field(list, "drag") instanceof PlaylistDrag, "Dragging did not start");
        require((long) field(list, "animation") > 0, "Displaced rows did not start animation");
        screen.mouseReleased(mouse(x, list.getY() + 185));
        var reordered = app.playlists.get(playlist).entries();
        require(reordered.subList(4, 6).equals(original.subList(0, 2)), "Connected block order changed");
        require(reordered.get(2).equals(original.get(4)) && selection.selected().equals(checked), "Separate selection moved with connected block");
        require(app.playlists.revision() == revision + 1, "Drop was not one atomic save");
        long saved = app.playlists.revision();
        screen.mouseClicked(mouse(x, list.getY() + 12), false);
        screen.mouseDragged(mouse(x, list.getY() + 115), 0, 103);
        escape(mc);
        require(mc.gui.screen() == screen && app.playlists.revision() == saved, "Escape did not cancel just the drag");
        screen.mouseClicked(mouse(x, list.getY() + 12), false);
        screen.mouseDragged(mouse(x, list.getY() + 115), 0, 103);
        screen.mouseReleased(mouse(list.getX() - 10, list.getY() + 115));
        require(app.playlists.revision() == saved, "Outside drop changed order");
        app.playlists.moveBlock(playlist, 4, 2, 0);
        screen.tick();
        selection.clear();
    }

    private void tick(Minecraft mc) {
        try {
            if (++ticks > 6000) throw new IllegalStateException("Real collection check timed out");
            var app = DarkMusicClient.INSTANCE;
            if (app == null || app.library == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            require(mc.level == null && mc.player == null, "Capture stays at title screen");
            if (!fullscreen) {
                fullscreen = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                frame = ticks;
                return;
            }
            // Fullscreen resize is asynchronous. Apply the option only after the new
            // framebuffer exists, otherwise Minecraft validates 3 against the small window.
            if (ticks - frame < 20) return;
            if (mc.options.guiScale().get() != 3 || mc.getWindow().getGuiScale() != 3) {
                mc.options.guiScale().set(3);
                mc.resizeGui();
                frame = ticks;
                return;
            }
            if (!selecting) {
                selecting = true;
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                return;
            }
            if (app.library.snapshot().scanning()) return;
            if (!ready) {
                tracks = app.library.snapshot().tracks();
                require(tracks.size() >= 2, "Real collection needs at least two songs");
                ready = true;
                app.playback.volume(.05);
                app.speed(1);
                app.playback.shuffle(false);
                app.playback.repeat(com.darkmusic.client.audio.PlaybackController.Repeat.OFF);
                app.playback.play(List.of(tracks.getFirst()), tracks.getFirst());
                auditionStarted = ticks;
                return;
            }
            var audio = app.playback.state();
            require(audio.status() != AudioBackend.Status.ERROR && app.warning.isBlank(), "Playback/library error: " + audio.error() + " " + app.warning);
            if (audition < tracks.size()) {
                require(ticks - auditionStarted < 400, "Playback timeout: " + tracks.get(audition).relativePath());
                if (audio.status() != AudioBackend.Status.PLAYING || audio.position() < .3) return;
                played.add(audio.track().id());
                LoggerFactory.getLogger("darkmusic-capture").info("REAL_AUDIO_PASS {}/{}: {}", audition + 1, tracks.size(), audio.track().relativePath());
                if (++audition < tracks.size()) {
                    var next = tracks.get(audition);
                    app.playback.play(List.of(next), next);
                    auditionStarted = ticks;
                    return;
                }
            }
            if (stage == -1) {
                // Prefer a real track with readable Latin metadata and embedded cover art.
                featured = tracks.stream().filter(t -> t.relativePath().contains("Departure")).findFirst().orElse(tracks.getFirst());
                app.settings.volume = .05;
                mc.gui.setScreen(new TitleScreen());
                app.open();
                player = (MusicScreen) mc.gui.screen();
                var selected = player.getClass().getDeclaredField("selected");
                selected.setAccessible(true);
                selected.set(player, featured);
                app.playlists.all().stream().map(p -> p.id()).toList().forEach(app.playlists::delete);
                playlist = app.playlists.create("Favorites");
                app.playlists.addAll(playlist, tracks.subList(0, Math.min(8, tracks.size())));
                app.settings.suppressVanillaMusic = false;
                app.settings.hudArtwork = true;
                app.settings.hudProgress = true;
                app.settings.hudX = .12;
                app.settings.hudY = .24;
                app.settings.hudScale = 1;
                app.settings.volume = .05;
                app.playback.play(List.of(featured), featured);
                stage = 0;
                frame = ticks;
                return;
            }
            if (stage == 0 && !positioned) {
                // Wait for the selected decoder to start before testing a seek.
                if (audio.status() != AudioBackend.Status.PLAYING || !audio.track().id().equals(featured.id())) return;
                app.playback.seek(35);
                positioned = true;
                frame = ticks;
                return;
            }
            if (ticks - frame < 30) return;
            frame = ticks;
            if (mc.gui.screen() instanceof LocalScreen screen) for (var child : screen.children())
                if (child instanceof AbstractWidget w)
                    require(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height, "Widget outside capture screen: " + w.getMessage().getString());
            switch (stage++) {
                case 0 -> {
                    require(audio.track().id().equals(featured.id()) && audio.position() >= 35, "Featured song seek failed");
                    require(app.artwork.get(featured) != null, "Featured song's real artwork did not load");
                    capture(mc, "player");
                    app.playback.pause();
                    click(mc, "Settings");
                }
                case 1 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED, "Real song pause failed");
                    app.playback.seek(60);
                    click(mc, "Autoplay on Launch");
                    require(app.settings.autoplay, "Setting row click failed");
                    var toggle = button(mc, "Autoplay on Launch");
                    toggle.setFocused(true);
                    toggle.keyPressed(new KeyEvent(32, 0, 0));
                    require(!app.settings.autoplay, "Setting row keyboard activation failed");
                    capture(mc, "settings");
                    click(mc, "HUD Settings");
                }
                case 2 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED && audio.position() >= 59.9, "Real song paused seek failed");
                    app.speed(3);
                    app.playback.toggle();
                    click(mc, "HUD Artwork");
                    require(!app.settings.hudArtwork, "HUD art toggle failed");
                    click(mc, "HUD Artwork");
                    click(mc, "Edit HUD Layout");
                }
                case 3 -> {
                    require(audio.status() == AudioBackend.Status.PLAYING && audio.position() > 60 && app.playback.speed() == 3, "Real song 3x speed/resume failed");
                    var b = NowPlayingHud.bounds(app, mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 5, b.y() + 5), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() + 45, b.y() + 35), 40, 30);
                    mc.gui.screen().mouseReleased(mouse(b.x() + 45, b.y() + 35));
                    require(app.settings.hudX > .12 && app.settings.hudY > .24, "HUD drag failed");
                    var slider = mc.gui.screen().children().stream().filter(x -> x instanceof ValueSlider).map(x -> (ValueSlider) x).findFirst().orElseThrow();
                    slider.setFocused(true);
                    slider.keyPressed(new KeyEvent(262, 0, 0));
                    require(app.settings.hudScale == 1.1, "HUD scale failed");
                    app.speed(1);
                }
                case 4 -> {
                    capture(mc, "hud-editor");
                    escape(mc);
                    escape(mc);
                    click(mc, "Manage Playlists");
                }
                case 5 -> {
                    checkDragging(mc, app);
                }
                case 6 -> {
                    capture(mc, "playlists");
                    click(mc, "Add Songs");
                    click(mc, "Select All");
                    var selection = (SongSelection) field(mc.gui.screen(), "selection");
                    var eligible = app.playlists.availableToAdd(playlist, tracks);
                    require(selection.selected().size() == eligible.size() && eligible.size() == tracks.size() - 8, "Existing songs were not hidden");
                    var search = (EditBox) field(mc.gui.screen(), "search");
                    search.setValue(eligible.getFirst().relativePath());
                    require(selection.hidden() == eligible.size() - 1, "Hidden selections lost");
                    click(mc, "Deselect All");
                    require(selection.selected().isEmpty(), "Hidden deselect failed");
                    click(mc, "Invert Selection");
                    require(selection.selected().size() == 1, "Filtered invert failed");
                    search.setValue("");
                    click(mc, "Select All");
                    mc.gui.screen().tick();
                }
                case 7 -> {
                    capture(mc, "bulk-picker");
                    click(mc, "Add Selected");
                    require(app.playlists.get(playlist).entries().size() == tracks.size(), "Bulk add dedup failed");
                    row(mc, mc.gui.screen(), "entries", 0);
                    row(mc, mc.gui.screen(), "entries", 1);
                    mc.gui.screen().tick();
                    click(mc, "Remove Selected");
                    require(app.playlists.get(playlist).entries().size() == tracks.size() - 2, "Bulk remove failed");
                    click(mc, "Add Songs");
                    click(mc, "Select All");
                    escape(mc);
                    require(app.playlists.get(playlist).entries().size() == tracks.size() - 2, "Cancel changed playlist");
                    app.playlists.addAll(playlist, tracks.subList(0, 2));
                    mc.gui.screen().tick();
                    click(mc, "Add Songs");
                    require(((List<?>) field(mc.gui.screen(), "tracks")).isEmpty() && !button(mc, "Add Selected").active, "Full playlist still exposes songs");
                    escape(mc);
                    var entries = (RowList) field(mc.gui.screen(), "entries");
                    double x = entries.getX() + 40;
                    mc.gui.screen().mouseClicked(mouse(x, entries.getY() + 12), false);
                    mc.gui.screen().mouseDragged(mouse(x, entries.getBottom() - 2), 0, entries.getHeight() - 14);
                    for (int i = 0; i < 8; i++) entries.tickDrag();
                    require((int) field(entries, "scroll") > 0, "Drag did not autoscroll");
                    escape(mc);
                    escape(mc);
                    click(mc, "Playlists");
                }
                case 8 -> {
                    capture(mc, "playlist-browser");
                    click(mc, "All");
                }
                case 9 -> {
                    capture(mc, "library");
                }
                case 10 -> {
                    click(mc, "Downloads");
                    Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                    app.downloads.configure(new com.darkmusic.client.download.DownloadModels.Tools(tools.resolve("yt-dlp.exe").toString(), tools.resolve("ffmpeg.exe").toString(), tools.resolve("ffprobe.exe").toString(), ""));
                    ((EditBox) field(mc.gui.screen(), "url")).setValue("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
                    click(mc, "Analyze");
                }
                case 11 -> {
                    if (app.downloads.snapshot().analyzing()) {
                        stage--;
                        return;
                    }
                    require(app.downloads.snapshot().preview() != null, "Public video analysis failed: " + app.downloads.snapshot().message());
                }
                case 12 -> {
                    capture(mc, "downloads");
                    require(app.downloads.snapshot().items().isEmpty(), "Preview unexpectedly queued audio");
                    click(mc, "Tools");
                }
                case 13 -> capture(mc, "download-tools");
                case 14 -> {
                    escape(mc);
                    escape(mc);
                    click(mc, "Settings");
                    click(mc, "Music Visualizer");
                    if (!app.settings.hudVisualizer) click(mc, "HUD Visualizer");
                    require(app.settings.playerVisualizer && app.settings.hudVisualizer, "Visualizer settings were not independent");
                }
                case 15 -> {
                    capture(mc, "visualizer-settings");
                    click(mc, "Edit Visualizer HUD Layout");
                }
                case 16 -> {
                    var b = com.darkmusic.client.hud.SpectrumHud.bounds(app.settings, mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 4, b.y() + 4), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() - 76, b.y() - 26), -80, -30);
                    mc.gui.screen().mouseReleased(mouse(b.x() - 76, b.y() - 26));
                    require(app.settings.visualizerX < 1, "Visualizer drag failed");
                    float[] spectrum = new float[64];
                    app.playback.spectrum().copyTo(spectrum);
                    boolean reactive = false;
                    for (float level : spectrum) if (level > .01) reactive = true;
                    require(reactive, "Real music did not produce a spectrum");
                }
                case 17 -> {
                    capture(mc, "visualizer-hud");
                    app.playback.pause();
                }
                case 18 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED, "Visualizer pause failed");
                    frozenSpectrum = new float[64];
                    app.playback.spectrum().copyTo(frozenSpectrum);
                }
                case 19 -> {
                    float[] spectrum = new float[64];
                    app.playback.spectrum().copyTo(spectrum);
                    require(Arrays.equals(frozenSpectrum, spectrum), "Paused spectrum kept animating");
                    app.playback.seek(60);
                }
                case 20 -> {
                    float[] spectrum = new float[64];
                    app.playback.spectrum().copyTo(spectrum);
                    require(Arrays.equals(new float[64], spectrum), "Seek retained old spectrum");
                    app.playback.toggle();
                    app.speed(2);
                }
                case 21 -> {
                    float[] spectrum = new float[64];
                    app.playback.spectrum().copyTo(spectrum);
                    boolean reactive = false;
                    for (float level : spectrum) if (level > .01) reactive = true;
                    require(reactive, "Spectrum failed to resume at 2x speed");
                    escape(mc);
                    escape(mc);
                    escape(mc);
                    app.speed(1);
                }
                case 22 -> {
                    capture(mc, "visualizer-player");
                    app.playback.stop();
                }
                case 23 -> {
                    float[] spectrum = new float[64];
                    app.playback.spectrum().copyTo(spectrum);
                    require(Arrays.equals(new float[64], spectrum), "Stop retained spectrum");
                }
                default -> {
                    if (captured.size() < 12) {
                        stage--;
                        return;
                    }
                    require(played.size() == tracks.size(), "Not all real tracks played");
                    app.playback.stop();
                    LoggerFactory.getLogger("darkmusic-capture").info("DARKMUSIC_SMOKE_PASS: {} real songs played; live PCM visualizer/pause/seek/resume/speed/stop/HUD drag, downloader preview/tools, 3x speed, connected dragging/cancel/autoscroll, filtered bulk editing, artwork, settings keyboard controls, HUD drag/scale; twelve private fullscreen GUI-scale-3 captures", played.size());
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger("darkmusic-capture").error("DARKMUSIC_SMOKE_FAIL", e);
            mc.stop();
        }
    }
}
