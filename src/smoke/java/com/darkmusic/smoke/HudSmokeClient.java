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
    private final float[] pausedStyleLevels = new float[64];
    private int tick, state, combination, captures;
    private boolean playerSeeked;
    private int styleCombination;
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
            if (++tick > 3600) throw new IllegalStateException("HUD smoke timeout");
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
                case 36 -> {
                    int choice = styleCombination;
                    int guiScale = new int[]{2, 3, 6}[choice / 60];
                    if (mc.options.guiScale().get() != guiScale) {
                        mc.options.guiScale().set(guiScale);
                        mc.resizeGui();
                    }
                    s.spectrumStyle = Settings.SpectrumStyle.values()[choice % 5];
                    s.hudShape = Settings.HudShape.values()[choice / 5 % 3];
                    s.hudRaised = choice / 15 % 2 != 0;
                    s.spectrumFinish = choice % 2 == 0 ? Settings.SurfaceFinish.BEVELED : Settings.SurfaceFinish.FLAT;
                    s.progressFinish = choice / 2 % 2 == 0 ? Settings.SurfaceFinish.BEVELED : Settings.SurfaceFinish.FLAT;
                    s.spectrumDirection = Settings.SpectrumDirection.values()[choice / 30 % 2];
                    s.hudBackgroundTransparency = choice % 3 * .5;
                    s.hudArtwork = choice % 2 == 0;
                    s.hudProgress = s.hudVisualizer = true;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    if (++styleCombination == 180) {
                        mc.options.guiScale().set(3);
                        mc.resizeGui();
                        state++;
                    }
                }
                case 37 -> {
                    capture(mc, "new-styles-live-preview");
                    s.spectrumStyle = Settings.SpectrumStyle.SMOOTH_WAVE;
                    s.hudShape = Settings.HudShape.ROUNDED;
                    s.hudRaised = true;
                    s.hudBackgroundTransparency = .25;
                    s.hudArtwork = true;
                    app.save();
                    saved = CompletableFuture.supplyAsync(() -> new ConfigStore(app.config.directory(), app.io, msg -> {
                        throw new IllegalStateException(msg);
                    }).load(), app.io);
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 38 -> {
                    if (!saved.isDone()) return;
                    var loaded = saved.join();
                    require(loaded.spectrumStyle == s.spectrumStyle && loaded.hudShape == s.hudShape && loaded.hudRaised, "Visual styles did not persist");
                    capture(mc, "smooth-rounded-raised");
                    app.playback.pause();
                    state++;
                }
                case 39 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PAUSED) return;
                    app.playback.spectrum().copyTo(pausedStyleLevels);
                    state++;
                }
                case 40 -> {
                    float[] after = new float[64];
                    app.playback.spectrum().copyTo(after);
                    require(java.util.Arrays.equals(pausedStyleLevels, after), "Paused spectrum moved with the new styles");
                    capture(mc, "smooth-paused");
                    app.playback.resume();
                    s.spectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    s.hudShape = Settings.HudShape.CHAMFERED;
                    mc.gui.setScreen(new MusicScreen(app, null));
                    state++;
                }
                case 41 -> {
                    capture(mc, "filled-wave-player-no-status-icon");
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 42 -> {
                    capture(mc, "filled-chamfered-raised");
                    s.spectrumStyle = Settings.SpectrumStyle.BARS;
                    s.hudShape = Settings.HudShape.RECTANGLE;
                    s.hudRaised = false;
                    app.save();
                    state++;
                }
                case 43 -> {
                    s.hudStyle = Settings.HudStyle.CLASSIC;
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    var style = screen.children().stream().filter(c -> c instanceof SettingRow w && w.getMessage().getString().startsWith("HUD Style:")).map(c -> (SettingRow) c).findFirst().orElseThrow();
                    style.onClick(mouse(style.getX() + 4, style.getY() + 4), false);
                    require(s.hudStyle == Settings.HudStyle.CIRCULAR, "HUD style control did not switch to Circular");
                    require(screen.children().stream().noneMatch(c -> c instanceof AbstractWidget w && (w.getMessage().getString().equals("HUD Artwork") || w.getMessage().getString().startsWith("HUD Shape:"))), "Circular HUD exposes inactive artwork/card options");
                    for (var expected : new Settings.SpectrumStyle[]{Settings.SpectrumStyle.FILLED_WAVE, Settings.SpectrumStyle.FILLED_SPIKE, Settings.SpectrumStyle.BARS}) {
                        var wave = screen.children().stream().filter(c -> c instanceof SettingRow w && w.getMessage().getString().startsWith("Spectrum Style:")).map(c -> (SettingRow) c).findFirst().orElseThrow();
                        wave.onClick(mouse(wave.getX() + 4, wave.getY() + 4), false);
                        require(s.circularSpectrumStyle == expected, "Circular style control ignored the selected filled shape");
                    }
                    styleCombination = 0;
                    state++;
                }
                case 44 -> {
                    int choice = styleCombination;
                    int guiScale = new int[]{2, 3, 6}[choice / 8];
                    if (mc.options.guiScale().get() != guiScale) {
                        mc.options.guiScale().set(guiScale);
                        mc.resizeGui();
                    }
                    s.hudProgress = (choice & 1) != 0;
                    s.hudVisualizer = (choice & 2) != 0;
                    s.hudArtwork = (choice & 4) != 0;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    var bounds = NowPlayingHud.bounds(app, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    require(bounds.y() + bounds.height() <= mc.getWindow().getGuiScaledHeight() - 8, "Circular HUD escaped viewport");
                    if (++styleCombination == 24) state++;
                }
                case 45 -> {
                    mc.options.guiScale().set(3);
                    mc.resizeGui();
                    s.hudProgress = s.hudVisualizer = true;
                    s.visualizerBars = 36;
                    s.visualizerSensitivity = 1;
                    s.visualizerSmoothing = .6;
                    app.configureVisualizer();
                    app.save();
                    saved = CompletableFuture.supplyAsync(() -> new ConfigStore(app.config.directory(), app.io, msg -> {
                        throw new IllegalStateException(msg);
                    }).load(), app.io);
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    state++;
                }
                case 46 -> {
                    capture(mc, "circular-live-preview");
                    require(screen.mouseScrolled((int) field(screen, "previewX") + 30, (int) field(screen, "previewY") + 40, 0, 1), "Circular preview did not scale");
                    state++;
                }
                case 47 -> {
                    if (!saved.isDone()) return;
                    require(saved.join().hudStyle == Settings.HudStyle.CIRCULAR, "Circular HUD style did not survive disk reload");
                    screen.mouseScrolled(20, screen.height - 20, 0, -30);
                    var edit = screen.children().stream().filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals("Edit HUD Layout")).map(c -> (AbstractWidget) c).findFirst().orElseThrow();
                    require(edit.visible, "Circular HUD layout action is unreachable");
                    screen.mouseClicked(mouse(edit.getX() + 3, edit.getY() + 3), false);
                    require(mc.gui.screen() instanceof HudEditorScreen, "Circular HUD layout editor did not open");
                    state++;
                }
                case 48 -> {
                    var editor = mc.gui.screen();
                    var bounds = NowPlayingHud.bounds(app, editor.width, editor.height);
                    double x = bounds.x() + bounds.width() / 2.0, y = bounds.y() + bounds.height() / 2.0;
                    require(editor.mouseScrolled(x, y, 0, 1), "Circular HUD wheel scaling failed");
                    bounds = NowPlayingHud.bounds(app, editor.width, editor.height);
                    x = bounds.x() + bounds.width() / 2.0;
                    y = bounds.y() + bounds.height() / 2.0;
                    require(editor.mouseClicked(mouse(x, y), false), "Circular combined HUD drag did not start");
                    editor.mouseDragged(mouse(x + 30, y + 20), 30, 20);
                    editor.mouseReleased(mouse(x + 30, y + 20));
                    state++;
                }
                case 49 -> {
                    capture(mc, "circular-combined-layout");
                    app.playback.pause();
                    state++;
                }
                case 50 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PAUSED) return;
                    app.playback.spectrum().copyTo(pausedStyleLevels);
                    state++;
                }
                case 51 -> {
                    float[] after = new float[64];
                    app.playback.spectrum().copyTo(after);
                    require(java.util.Arrays.equals(pausedStyleLevels, after), "Circular spectrum analysis moved while paused");
                    app.playback.seek(35);
                    app.playback.resume();
                    state++;
                }
                case 52 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PLAYING || app.playback.state().position() < 35)
                        return;
                    capture(mc, "circular-after-seek");
                    app.playback.stop();
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 53 -> {
                    capture(mc, "circular-idle-preview");
                    s.hudEnabled = false;
                    state++;
                }
                case 54 -> {
                    capture(mc, "circular-master-disabled");
                    s.hudEnabled = true;
                    s.hudStyle = Settings.HudStyle.CLASSIC;
                    app.save();
                    state++;
                }
                case 55 -> {
                    s.hudStyle = Settings.HudStyle.CIRCULAR;
                    s.circularSpectrumStyle = Settings.SpectrumStyle.BARS;
                    s.circularText = s.circularIconPulse = s.hudVisualizer = true;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    var tracks = app.library.snapshot().tracks();
                    app.playback.play(tracks, tracks.getFirst());
                    playerSeeked = false;
                    state++;
                }
                case 56 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PLAYING) return;
                    if (!playerSeeked) {
                        app.playback.seek(35);
                        playerSeeked = true;
                        return;
                    }
                    if (app.playback.state().position() < 35.3) return;
                    capture(mc, "circular-filled-bars");
                    s.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 57 -> {
                    capture(mc, "circular-filled-waves");
                    s.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_SPIKE;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 58 -> {
                    capture(mc, "circular-filled-spike");
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    int originalHeight = HudLayout.content(s, Integer.MAX_VALUE).height();
                    var text = screen.children().stream().filter(c -> c instanceof SettingRow w && w.getMessage().getString().equals("Song Information")).map(c -> (SettingRow) c).findFirst().orElseThrow();
                    text.onClick(mouse(text.getX() + 4, text.getY() + 4), false);
                    require(!s.circularText && HudLayout.content(s, Integer.MAX_VALUE).height() < originalHeight, "Circular text toggle did not remove reserved text space");
                    state++;
                }
                case 59 -> {
                    capture(mc, "circular-text-hidden");
                    s.hudVisualizer = false;
                    app.configureVisualizer();
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 60 -> {
                    capture(mc, "circular-pulse-without-spectrum");
                    app.playback.spectrum().copyTo(pausedStyleLevels);
                    require(CircularHudLayout.iconZoom(pausedStyleLevels, s.visualizerBars) > 1, "Icon pulse has no audio data when spectrum is hidden");
                    app.playback.pause();
                    state++;
                }
                case 61 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PAUSED) return;
                    app.playback.spectrum().copyTo(pausedStyleLevels);
                    state++;
                }
                case 62 -> {
                    float[] after = new float[64];
                    app.playback.spectrum().copyTo(after);
                    require(CircularHudLayout.iconZoom(pausedStyleLevels, s.visualizerBars) == CircularHudLayout.iconZoom(after, s.visualizerBars), "Icon pulse changed while paused");
                    s.circularIconPulse = false;
                    app.configureVisualizer();
                    state++;
                }
                case 63 -> {
                    capture(mc, "circular-pulse-disabled");
                    s.circularText = s.circularIconPulse = s.hudVisualizer = true;
                    s.circularSpectrumStyle = Settings.SpectrumStyle.BARS;
                    s.hudStyle = Settings.HudStyle.CLASSIC;
                    app.save();
                    state++;
                }
                case 64 -> {
                    s.hudStyle = Settings.HudStyle.CIRCULAR;
                    s.circularSpectrumStyle = Settings.SpectrumStyle.BARS;
                    s.spectrumFinish = s.progressFinish = Settings.SurfaceFinish.FLAT;
                    screen = new SettingsScreen(app, null);
                    mc.gui.setScreen(screen);
                    for (String label : new String[]{"Spectrum Finish:", "Progress Finish:"}) {
                        var finish = screen.children().stream().filter(c -> c instanceof SettingRow w && w.getMessage().getString().startsWith(label)).map(c -> (SettingRow) c).findFirst().orElseThrow();
                        finish.onClick(mouse(finish.getX() + 4, finish.getY() + 4), false);
                    }
                    require(s.spectrumFinish == Settings.SurfaceFinish.BEVELED && s.progressFinish == Settings.SurfaceFinish.BEVELED, "Finish controls did not apply independently");
                    app.playback.resume();
                    state++;
                }
                case 65 -> {
                    if (app.playback.state().status() != com.darkmusic.client.audio.AudioBackend.Status.PLAYING) return;
                    capture(mc, "beveled-circular-bars");
                    s.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_WAVE;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 66 -> {
                    capture(mc, "beveled-circular-wave");
                    s.circularSpectrumStyle = Settings.SpectrumStyle.FILLED_SPIKE;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 67 -> {
                    capture(mc, "beveled-circular-spike");
                    s.hudStyle = Settings.HudStyle.CLASSIC;
                    s.spectrumStyle = Settings.SpectrumStyle.FILLED_SPIKE;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 68 -> {
                    capture(mc, "beveled-classic-spike");
                    s.spectrumStyle = Settings.SpectrumStyle.BARS;
                    mc.gui.setScreen(new SettingsScreen(app, null));
                    state++;
                }
                case 69 -> {
                    capture(mc, "beveled-classic-bars");
                    mc.gui.setScreen(new MusicScreen(app, null));
                    state++;
                }
                case 70 -> {
                    capture(mc, "beveled-player");
                    s.progressFinish = Settings.SurfaceFinish.FLAT;
                    app.save();
                    saved = CompletableFuture.supplyAsync(() -> new ConfigStore(app.config.directory(), app.io, msg -> {
                        throw new IllegalStateException(msg);
                    }).load(), app.io);
                    state++;
                }
                case 71 -> {
                    if (!saved.isDone()) return;
                    require(saved.join().spectrumFinish == Settings.SurfaceFinish.BEVELED && saved.join().progressFinish == Settings.SurfaceFinish.FLAT, "Independent finishes did not survive disk reload");
                    s.spectrumFinish = s.progressFinish = Settings.SurfaceFinish.FLAT;
                    app.save();
                    state++;
                }
                default -> {
                    require(captures >= 16, "Private captures did not complete");
                    String id = Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip();
                    org.slf4j.LoggerFactory.getLogger("darkmusic-hud-smoke").info("DARKMUSIC_SMOKE_PASS:{} download re-entry/search/range/audition and combined HUD: 96 runtime combinations, GUI scales 2/3/6, live and idle previews, master switch, combined drag/wheel, disk reload; 180 visual style/shape/depth/direction/GUI-scale combinations and paused wave synchronization; Circular HUD controls, 24 toggle/GUI-scale combinations, disk reload, wheel/drag, seek/pause/stop and icon preview; three filled circular shapes, hidden text, pulse with spectrum hidden and paused pulse; independent Flat/Beveled finishes, all shapes, main-player progress, bounded lighting and finish reload", id);
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
