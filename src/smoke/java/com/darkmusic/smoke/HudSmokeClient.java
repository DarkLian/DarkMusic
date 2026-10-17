package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.config.*;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.hud.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;

import java.nio.file.*;
import java.util.concurrent.*;

/**
 * Runtime checks use the current private library and the same renderer as gameplay.
 */
final class HudSmokeClient {
    private int tick, state, combination, captures;
    private boolean playerSeeked;
    private CompletableFuture<Settings> saved;
    private SettingsScreen screen;

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private Object field(Object value, String name) throws Exception {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            try {
                var f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(value);
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(name);
    }

    private void set(Object value, String name, Object data) throws Exception {
        var f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(value, data);
    }

    private void pan(RowList rows) throws Exception {
        rows.setFocused(true);
        rows.select(0);
        int vertical = (int) field(rows, "scroll");
        require(rows.mouseScrolled(rows.getX() + 30, rows.getY() + 6, -1000, 0), "Horizontal trackpad input was ignored");
        var text = field(rows, "horizontal");
        var lines = (java.util.Map<?, ?>) field(text, "lines");
        var line = lines.get("title0");
        double end = (double) field(line, "offset");
        require(end > 0 && end == (int) field(line, "maximum"), "Long Unicode title did not pan fully right");
        rows.keyPressed(new KeyEvent(263, 0, 0));
        require((double) field(line, "offset") < end, "Left key did not pan the focused row");
        rows.keyPressed(new KeyEvent(262, 0, 0));
        require((double) field(line, "offset") == end, "Right key did not pan the focused row");
        require((int) field(rows, "scroll") == vertical && rows.selected() == 0, "Horizontal panning changed vertical position or selection");
    }

    private MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void capture(Minecraft mc, String name) throws Exception {
        require(mc.getWindow().isFullscreen() && mc.options.guiScale().get() == 3 && mc.getWindow().getGuiScale() == 3, "Capture is not fullscreen at GUI scale 3");
        Path root = Path.of(System.getProperty("darkmusic.privateImages"));
        Files.createDirectories(root.resolve("screenshots"));
        Screenshot.grab(root.toFile(), "combined-hud-3x-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, text -> captures++);
    }

    private void tick(Minecraft mc) {
        if (state == 999) return;
        try {
            if (++tick > 1800) throw new IllegalStateException("HUD smoke timeout");
            if (tick % 6 != 0) return;
            var app = DarkMusicClient.INSTANCE;
            var s = app.settings;
            if (state == 2) {
                screen = new SettingsScreen(app, null);
                mc.gui.setScreen(screen);
            }
            switch (state) {
                case 0 -> {
                    if (Boolean.getBoolean("darkmusic.verifyHudRestart"))
                        require(s.hudEnabled && s.hudArtwork && s.hudProgress && s.hudVisualizer && s.hudX > 0 && s.hudY > 0 && s.hudScale == 1.1 && s.hudBackgroundTransparency == .5 && s.spectrumPosition == Settings.SpectrumPosition.BELOW && s.spectrumDirection == Settings.SpectrumDirection.UPWARD, "HUD configuration did not survive restarting Minecraft");
                    s.hudEnabled = true;
                    mc.options.guiScale().set(3);
                    mc.resizeGui();
                    screen = new SettingsScreen(app, mc.gui.screen());
                    mc.gui.setScreen(screen);
                    double actualScale = s.hudScale;
                    int px = (int) field(screen, "previewX"), py = (int) field(screen, "previewY");
                    screen.mouseScrolled(px + 30, py + 40, 0, 1);
                    require((double) field(screen, "previewScale") == 1.1 && s.hudScale == actualScale, "Preview scaling changed the actual HUD");
                    require(((String) field(screen, "folderPath")).equals(app.library.root().toString()), "Selected folder path is missing");
                    require(screen.children().stream().noneMatch(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals("Music-Player Visualizer")), "Legacy visualizer setting remains");
                    state++;
                }
                case 1 -> {
                    int[] scales = {2, 3, 6};
                    int scale = scales[combination / 32];
                    if (mc.options.guiScale().get() != scale) {
                        mc.options.guiScale().set(scale);
                        mc.resizeGui();
                    }
                    int mask = combination % 8, choice = combination / 8 % 4;
                    s.hudArtwork = (mask & 1) != 0;
                    s.hudProgress = (mask & 2) != 0;
                    s.hudVisualizer = (mask & 4) != 0;
                    s.spectrumPosition = choice / 2 == 0 ? Settings.SpectrumPosition.ABOVE : Settings.SpectrumPosition.BELOW;
                    s.spectrumDirection = choice % 2 == 0 ? Settings.SpectrumDirection.UPWARD : Settings.SpectrumDirection.DOWNWARD;
                    s.hudBackgroundTransparency = (combination % 3) * .5;
                    s.visualizerBars = combination % 2 == 0 ? 8 : 64;
                    s.visualizerSensitivity = 4;
                    s.visualizerSmoothing = .95;
                    app.configureVisualizer();
                    var b = NowPlayingHud.bounds(app, screen.width, screen.height);
                    require(b.x() + b.width() <= screen.width - 8 && b.y() + b.height() <= screen.height - 8, "Combined bounds escaped viewport");
                    for (var child : screen.children())
                        if (child instanceof AbstractWidget widget && widget.visible)
                            require(widget.getY() >= 32 && widget.getBottom() <= screen.height - 12, "A settings control escaped its region");
                    if (++combination == 96) state++;
                }
                case 2 -> {
                    mc.options.guiScale().set(3);
                    mc.resizeGui();
                    s.hudArtwork = s.hudProgress = s.hudVisualizer = true;
                    s.hudEnabled = true;
                    s.hudScale = 1;
                    s.hudBackgroundTransparency = .5;
                    app.save();
                    state++;
                }
                case 3, 5, 7, 9 -> {
                    int choice = (state - 3) / 2;
                    s.spectrumPosition = choice < 2 ? Settings.SpectrumPosition.ABOVE : Settings.SpectrumPosition.BELOW;
                    s.spectrumDirection = choice % 2 == 0 ? Settings.SpectrumDirection.UPWARD : Settings.SpectrumDirection.DOWNWARD;
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    state++;
                }
                case 4, 6, 8, 10 -> {
                    capture(mc, s.spectrumPosition.name().toLowerCase(java.util.Locale.ROOT) + "-" + s.spectrumDirection.name().toLowerCase(java.util.Locale.ROOT));
                    state++;
                }
                case 11 -> {
                    s.hudEnabled = false;
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    state++;
                }
                case 12 -> {
                    capture(mc, "disabled");
                    state++;
                }
                case 13 -> {
                    s.hudEnabled = true;
                    app.playback.stop();
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    state++;
                }
                case 14 -> {
                    capture(mc, "idle-preview");
                    state++;
                }
                case 15 -> {
                    // The shared form is scrollable even at small window sizes; wheel does not scale its preview.
                    double original = s.hudScale;
                    screen.mouseScrolled(20, screen.height - 20, 0, -30);
                    require(s.hudScale == original, "Settings scroll changed HUD scale");
                    var edit = screen.children().stream().filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals("Edit HUD Layout")).map(c -> (AbstractWidget) c).findFirst().orElseThrow();
                    require(edit.visible, "Layout action is unreachable by scrolling");
                    screen.mouseClicked(mouse(edit.getX() + 3, edit.getY() + 3), false);
                    require(mc.gui.screen() instanceof HudEditorScreen, "Layout editor missing");
                    state++;
                }
                case 16 -> {
                    var editor = mc.gui.screen();
                    var b = NowPlayingHud.bounds(app, editor.width, editor.height);
                    double x = b.x() + b.width() / 2.0, y = b.y() + b.height() / 2.0;
                    require(editor.mouseScrolled(x, y, 0, 1), "Combined HUD wheel failed");
                    require(s.hudScale == 1.1, "Combined HUD scale step incorrect");
                    b = NowPlayingHud.bounds(app, editor.width, editor.height);
                    x = b.x() + b.width() / 2.0;
                    y = b.y() + b.height() / 2.0;
                    require(editor.mouseClicked(mouse(x, y), false), "Combined HUD click failed");
                    editor.mouseDragged(mouse(x + 100, y + 70), 100, 70);
                    editor.mouseReleased(mouse(x + 100, y + 70));
                    require(s.hudX > 0 && s.hudY > 0, "Combined HUD did not move");
                    s.spectrumDirection = Settings.SpectrumDirection.UPWARD;
                    app.save();
                    saved = CompletableFuture.supplyAsync(() -> new ConfigStore(app.config.directory(), app.io, msg -> {
                        throw new IllegalStateException(msg);
                    }).load(), app.io);
                    state++;
                }
                case 17 -> {
                    if (!saved.isDone()) return;
                    var loaded = saved.join();
                    require(loaded.hudX.equals(s.hudX) && loaded.hudY.equals(s.hudY) && loaded.hudScale == s.hudScale && loaded.spectrumPosition == s.spectrumPosition && loaded.spectrumDirection == s.spectrumDirection && loaded.hudBackgroundTransparency == s.hudBackgroundTransparency, "Saved HUD settings did not survive reload");
                    capture(mc, "combined-layout");
                    state++;
                }
                case 18 -> {
                    var music = new MusicScreen(app, null);
                    mc.gui.setScreen(music);
                    var track = app.library.snapshot().tracks().getFirst();
                    app.playback.play(app.library.snapshot().tracks(), track);
                    state++;
                }
                case 19 -> {
                    if (!playerSeeked) {
                        if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PLAYING)
                            return;
                        app.playback.seek(30);
                        playerSeeked = true;
                        return;
                    }
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PLAYING || app.playback.state().position() < 30.25)
                        return;
                    capture(mc, "expanded-player");
                    state++;
                }
                case 20 -> {
                    app.volume(.55);
                    app.toggleMute();
                    require(s.volume == 0, "Mute did not apply to actual playback volume");
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.Screen(net.minecraft.network.chat.Component.literal("HUD feedback verification")) {
                        @Override
                        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {
                            g.fill(0, 0, width, height, 0xFF20283A);
                            app.volumeFeedback.render(g);
                        }
                    });
                    state++;
                }
                case 21 -> {
                    capture(mc, "mute-feedback");
                    state++;
                }
                case 22 -> {
                    app.toggleMute();
                    require(s.volume == .55, "Unmute did not restore actual playback volume");
                    state++;
                }
                case 23 -> {
                    capture(mc, "volume-feedback");
                    state++;
                }
                case 24 -> {
                    if (app.volumeFeedback.opacity(System.nanoTime()) > 0) return;
                    state++;
                }
                case 25 -> {
                    capture(mc, "feedback-expired");
                    state++;
                }
                case 26 -> {
                    var music = new MusicScreen(app, null);
                    mc.gui.setScreen(music);
                    music.tick();
                    var track = app.library.snapshot().tracks().getFirst();
                    var cover = app.artwork.get(track);
                    require(cover != null && cover.size() > 256, "High-resolution cover was not loaded");
                    String title = (track.title() + " 音樂 ").repeat(8) + "終點";
                    ((RowList) field(music, "songs")).rows(java.util.List.of(new RowList.Row(title, track.relativePath().repeat(6))));
                    ((RowList) field(music, "navigation")).rows(java.util.List.of(new RowList.Row(title, "")));
                    state++;
                }
                case 27 -> {
                    pan((RowList) field(mc.gui.screen(), "songs"));
                    pan((RowList) field(mc.gui.screen(), "navigation"));
                    state++;
                }
                case 28 -> {
                    capture(mc, "long-player-text");
                    var downloads = new DownloadScreen(app, mc.gui.screen());
                    mc.gui.setScreen(downloads);
                    String link = "https://example.com/collection";
                    ((net.minecraft.client.gui.components.EditBox) field(downloads, "url")).setValue(link);
                    var track = app.library.snapshot().tracks().getFirst();
                    var entry = new com.darkmusic.client.download.DownloadModels.Entry(1, (track.title() + " 日本語 中文 ").repeat(12) + "終點", link, "Generic", "one", true);
                    set(app.downloads, "preview", new com.darkmusic.client.download.DownloadModels.Preview(link, "Collection", "Generic", true, false, 1, 1, false, java.util.List.of(entry)));
                    set(app.downloads, "message", ("Collection details: " + track.relativePath() + " ").repeat(10));
                    set(app.downloads, "published", null);
                    downloads.tick();
                    state++;
                }
                case 29 -> {
                    pan((RowList) field(mc.gui.screen(), "rows"));
                    require(mc.gui.screen().mouseScrolled(20, 158, -1000, 0), "Long download status did not scroll");
                    state++;
                }
                case 30 -> {
                    capture(mc, "long-download-text");
                    mc.gui.screen().onClose();
                    mc.gui.setScreen(new DownloadToolsScreen(app, mc.gui.screen()));
                    var cancel = (AbstractWidget) field(mc.gui.screen(), "cancel");
                    require(!cancel.visible, "Idle tools screen still shows a Cancel button");
                    var check = (net.minecraft.client.gui.components.Button) field(mc.gui.screen(), "check");
                    check.onClick(mouse(check.getX() + 3, check.getY() + 3), false);
                    mc.gui.screen().tick();
                    require(cancel.visible, "Running tool check has no operation-cancel button");
                    var cancelButton = (net.minecraft.client.gui.components.Button) cancel;
                    cancelButton.onClick(mouse(cancelButton.getX() + 3, cancelButton.getY() + 3), false);
                    state++;
                }
                case 31 -> {
                    if (app.downloads.snapshot().analyzing()) return;
                    mc.gui.screen().tick();
                    require(!((AbstractWidget) field(mc.gui.screen(), "cancel")).visible, "Tool cancel button remains after completion");
                    capture(mc, "tools-idle");
                    state++;
                }
                case 32 -> {
                    app.speed(1.5);
                    app.speedFeedback.show(s.speed);
                    app.volumeFeedback.show(s.volume);
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.Screen(net.minecraft.network.chat.Component.literal("Speed feedback verification")) {
                        @Override
                        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {
                            g.fill(0, 0, width, height, 0xFF20283A);
                            app.speedFeedback.render(g);
                            app.volumeFeedback.render(g);
                        }
                    });
                    state++;
                }
                case 33 -> {
                    require(app.speedFeedback.opacity(System.nanoTime()) > 0, "Speed feedback is missing");
                    capture(mc, "speed-feedback");
                    state++;
                }
                case 34 -> {
                    if (app.speedFeedback.opacity(System.nanoTime()) > 0) return;
                    state++;
                }
                case 35 -> {
                    capture(mc, "speed-feedback-expired");
                    state++;
                }
                default -> {
                    require(captures >= 16, "Private captures did not complete");
                    String id = Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip();
                    org.slf4j.LoggerFactory.getLogger("darkmusic-hud-smoke").info("DARKMUSIC_SMOKE_PASS:{} download re-entry/search/range/audition and combined HUD: 96 runtime combinations, GUI scales 2/3/6, live and idle previews, master switch, combined drag/wheel, disk reload", id);
                    state = 999;
                    mc.stop();
                }
            }
        } catch (Throwable failure) {
            org.slf4j.LoggerFactory.getLogger("darkmusic-hud-smoke").error("DARKMUSIC_SMOKE_FAIL", failure);
            state = 999;
            mc.stop();
        }
    }
}
