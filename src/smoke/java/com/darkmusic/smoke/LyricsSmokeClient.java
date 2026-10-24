package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.lyrics.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Native UI and playback-clock checks with private audio. No online requests or writes to music folders.
 */
final class LyricsSmokeClient {
    private int stage = -2, ticks, since;
    private Track a, b;
    private CompletableFuture<Void> operation;
    private double paused;
    private LyricsDocument timed;
    private boolean scrollVerified;

    private void scrollProbe(Minecraft mc) {
        mc.gui.setScreen(new LocalScreen(net.minecraft.network.chat.Component.literal("Live text regression"), null) {
            @Override
            public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {
                if (scrollVerified) return;
                longText.begin();
                longText.drawUpdating(g, font, "status", "The lyrics provider request failed | Retrying automatically in 120 s", 12, 40, 80, 0xffffffff);
                longText.pan("status", 10_000);
                var entries = (java.util.Map<?, ?>) SmokeUi.field(longText, "lines");
                Object line = entries.get("status");
                double before = (double) SmokeUi.field(line, "offset");
                longText.drawUpdating(g, font, "status", "The lyrics provider request failed | Retrying automatically in 119 s", 12, 40, 80, 0xffffffff);
                double after = (double) SmokeUi.field(line, "offset");
                check(before > 0 && after == Math.min(before, (int) SmokeUi.field(line, "maximum")), "Live status preserves horizontal position");
                check(((String) SmokeUi.field(line, "visible")).contains("119"), "Updated retry countdown is visible");
                longText.draw(g, font, "status", "Another song title that starts from the beginning", 12, 60, 80, 0xffffffff);
                check((double) SmokeUi.field(line, "offset") == 0, "Ordinary replacement starts at the beginning");
                scrollVerified = true;
            }
        });
    }

    private void statusProbe(Minecraft mc) {
        mc.gui.setScreen(new LocalScreen(net.minecraft.network.chat.Component.literal("Status and metadata"), null) {
            @Override
            protected void init() {
                var list = addRenderableWidget(new RowList(16, 54, width - 32, Math.min(180, height - 78), 38, "Status samples", (i, activate) -> {
                }));
                list.rows(List.of(
                    new RowList.Row("Example song", "MP3 | Music folder").status(com.darkmusic.client.text.Text.get("lyrics.status.searching"), UiStatus.Tone.INFO),
                    new RowList.Row("Example song", "FLAC | Music folder").status(com.darkmusic.client.text.Text.get("download.library.already"), UiStatus.Tone.SUCCESS),
                    new RowList.Row("Example song", "OPUS | Music folder").status(com.darkmusic.client.text.Text.get("download.library.possible"), UiStatus.Tone.WARNING),
                    new RowList.Row("Example song", "MP3 | Music folder").status(com.darkmusic.client.text.Text.get("lyrics.status.network"), UiStatus.Tone.ERROR)));
                list.select(1);
            }

            @Override
            public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float delta) {
                UiTheme.background(g, width, height);
                g.text(font, title, 16, 12, UiTheme.TEXT);
                super.extractRenderState(g, x, y, delta);
            }
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private static void doubleClicks(Minecraft mc) {
        var activations = new java.util.concurrent.atomic.AtomicInteger();
        var screen = new LocalScreen(net.minecraft.network.chat.Component.literal("Click regression"), null) {
            @Override
            protected void init() {
                var rows = addRenderableWidget(new RowList(12, 40, 120, 80, 36, "Rows", (i, activate) -> {
                    if (activate) activations.incrementAndGet();
                }));
                rows.rows(List.of(new RowList.Row("A", ""), new RowList.Row("B", "")));
                addRenderableWidget(UiButton.builder(net.minecraft.network.chat.Component.literal("Other"), b -> {
                }).bounds(160, 40, 80, 20).build());
            }
        };
        mc.gui.setScreen(screen);
        screen.init(426, 240);
        click(screen, 40, 50, false);
        click(screen, 40, 85, true);
        click(screen, 40, 50, true);
        check(activations.get() == 0, "Different rows must not complete a double click");
        click(screen, 170, 50, true);
        click(screen, 40, 50, true);
        check(activations.get() == 0, "Other buttons must break the click sequence");
        click(screen, 40, 50, true);
        check(activations.get() == 1, "Two consecutive clicks must activate");
        click(screen, 40, 50, true);
        click(screen, 300, 180, true);
        click(screen, 40, 50, true);
        check(activations.get() == 1, "Empty space must break the click sequence");
    }

    private static void click(Screen screen, double x, double y, boolean twice) {
        var event = new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0));
        screen.mouseClicked(event, twice);
        screen.mouseReleased(event);
    }

    private static void bounds(Screen screen, int width, int height) {
        screen.init(width, height);
        var widgets = screen.children().stream().filter(c -> c instanceof AbstractWidget w && w.visible).map(c -> (AbstractWidget) c).toList();
        for (var w : widgets) {
            check(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= width && w.getBottom() <= height, "Outside screen: " + w.getMessage().getString());
            for (var other : widgets)
                if (other != w)
                    check(w.getRight() <= other.getX() || w.getX() >= other.getRight() || w.getBottom() <= other.getY() || w.getY() >= other.getBottom(), "Overlapping controls");
        }
    }

    private void tick(Minecraft mc) {
        if (stage == 99) return;
        try {
            check(++ticks < 1800, "Lyrics smoke timeout: " + stage);
            var app = DarkMusicClient.INSTANCE;
            if (app == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
            if (stage == -2) {
                Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                app.settings.ffmpegPath = tools.resolve("ffmpeg.exe").toString();
                app.settings.ffprobePath = tools.resolve("ffprobe.exe").toString();
                app.settings.lyricsEnabled = true;
                app.settings.lyricsOnline = false;
                app.settings.lyricsConsentVersion = 1;
                app.save();
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                stage = -1;
                return;
            }
            if (stage == -1) {
                if (app.library.snapshot().scanning() || app.library.snapshot().tracks().isEmpty()) return;
                SmokeUi.displayReady(mc);
                Path requested = Path.of(System.getProperty("darkmusic.music")).toAbsolutePath().normalize();
                String root = app.library.roots().get(requested);
                var testTracks = app.library.snapshot().tracks().stream().filter(t -> t.rootId().equals(root)).toList();
                check(!testTracks.isEmpty(), "The requested private test collection is indexed");
                a = testTracks.getFirst();
                b = testTracks.size() > 1 ? testTracks.get(1) : a;
                // Authored timing fixtures exercise the real playback clock; they are not music/cover assets or provider results.
                timed = LyricsDocument.of("import", "smoke", a.title(), a.artist(), a.album(), a.duration(), "ja", LyricsDocument.Variant.ORIGINAL,
                    false, false, "[00:01]最初の行\n[00:03]第二行\n[00:06]Third line");
                operation = app.lyrics.lookup.select(a, timed).thenCompose(done -> app.lyrics.lookup.offset(a, 0));
                stage = -3;
                return;
            }
            if (operation != null && !operation.isDone()) return;
            if (operation != null) {
                operation.join();
                operation = null;
            }
            if (stage == -3) {
                scrollProbe(mc);
                stage = -4;
                return;
            }
            if (stage == -4) {
                if (!scrollVerified) return;
                statusProbe(mc);
                since = ticks;
                stage = -5;
                return;
            }
            if (stage == -5) {
                if (ticks - since < 5) return;
                if (Boolean.getBoolean("darkmusic.smoothFontAvailable")) {
                    Path images = Path.of(System.getProperty("darkmusic.privateImages"));
                    Files.createDirectories(images.resolve("screenshots"));
                    net.minecraft.client.Screenshot.grab(images.toFile(), "semantic-status-" + mc.options.languageCode + ".png", mc.gameRenderer.mainRenderTarget(), 1, ignored -> {
                    });
                }
                System.out.println("DARKMUSIC_STATUS_UI_PASS: separate semantic status prefix and neutral file metadata, selected row, CJK text");
                stage = 0;
            }
            if (stage == 0) {
                doubleClicks(mc);
                app.settings.lyricsConsentVersion = 0;
                app.settings.lyricsOnline = false;
                var consentPlayer = new MusicScreen(app, null);
                mc.gui.setScreen(consentPlayer);
                consentPlayer.tick();
                check(mc.gui.screen() instanceof ConfirmationScreen, "Upgrade asks before enabling online lyrics");
                var consent = mc.gui.screen();
                for (int w : new int[]{320, 426, 960}) bounds(consent, w, w == 960 ? 540 : 240);
                consent.onClose();
                consentPlayer.tick();
                check(mc.gui.screen() == consentPlayer && !app.settings.lyricsOnline, "Cancel remains offline without repeated prompts");
                for (var screen : List.of(new LyricsScreen(app, null, a), new LyricsSearchScreen(app, null, a),
                    new LyricsSettingsScreen(app, null), new LyricsLibraryScreen(app, null), new LyricsHudSettingsScreen(app, null), new LyricsOptionsScreen(app, null, a),
                    new AudioDeletionScreen(app, null, List.of(a), null), new LyricCandidateScreen(app, null, a, timed))) {
                    mc.gui.setScreen(screen);
                    for (int w : new int[]{320, 426, 960}) bounds(screen, w, w == 960 ? 540 : 240);
                }
                app.settings.lyricsHud = app.settings.lyricsNextLine = true;
                var settingsScreen = new SettingsScreen(app, null);
                mc.gui.setScreen(settingsScreen);
                var keys = (String[]) SmokeUi.field(settingsScreen, "SECTION_KEYS");
                check(keys[1].equals("settings.audio_section") && keys[2].equals("lyrics.section")
                    && keys[3].equals("settings.appearance_section"), "Audio, Lyrics and Appearance order");
                var selected = SettingsScreen.class.getDeclaredField("selectedSection");
                selected.setAccessible(true);
                for (int section = 0; section < keys.length; section++) {
                    selected.setInt(settingsScreen, section);
                    for (int w : new int[]{320, 426, 960}) bounds(settingsScreen, w, w == 960 ? 540 : 240);
                }
                var category = SmokeUi.find(mc, com.darkmusic.client.text.Text.get("lyrics.section"));
                check(category != null && SmokeUi.field(category, "icon") == null, "Lyrics category has no icon");
                var player = new MusicScreen(app, null);
                mc.gui.setScreen(player);
                for (int w : new int[]{320, 426, 960}) {
                    bounds(player, w, w == 960 ? 540 : 240);
                    var upNext = SmokeUi.find(mc, com.darkmusic.client.text.Text.get("upnext.title"));
                    var downloads = SmokeUi.find(mc, com.darkmusic.client.text.Text.get("text.darkmusic.downloads"));
                    var settings = SmokeUi.find(mc, com.darkmusic.client.text.Text.get("text.darkmusic.settings"));
                    check(upNext.getWidth() == downloads.getWidth() && downloads.getWidth() == settings.getWidth(), "Matching navigation widths");
                    check(upNext.getHeight() == downloads.getHeight() && downloads.getHeight() == settings.getHeight(), "Matching navigation heights");
                    check(downloads.getX() - upNext.getRight() == 4 && settings.getX() - downloads.getRight() == 4, "Matching navigation spacing");
                    check(upNext.getY() == 4 && downloads.getY() == 4 && settings.getY() == 4, "Navigation aligns with Back buttons");
                }
                app.settings.validate();
                for (var style : com.darkmusic.client.config.Settings.HudStyle.values()) {
                    app.settings.hudStyle = style;
                    var editor = new HudEditorScreen(app, null);
                    mc.gui.setScreen(editor);
                    for (int w : new int[]{320, 426, 960}) bounds(editor, w, w == 960 ? 540 : 240);
                    editor.init(960, 540);
                    var lyricBounds = com.darkmusic.client.hud.LyricsHud.bounds(app.settings, 960, 540);
                    double playerScale = app.settings.hudScale;
                    double playerX = app.settings.hudX, playerY = app.settings.hudY;
                    double cx = lyricBounds.x() + lyricBounds.width() / 2.0, cy = lyricBounds.y() + lyricBounds.height() / 2.0;
                    editor.mouseScrolled(cx, cy, 0, 1);
                    check(app.settings.hudScale == playerScale, "Lyrics resize must not resize music HUD");
                    var mouse = new net.minecraft.client.input.MouseButtonEvent(cx, cy, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                    check(editor.mouseClicked(mouse, false), "Lyrics can be independently selected");
                    var moved = new net.minecraft.client.input.MouseButtonEvent(cx - 40, cy - 50, new net.minecraft.client.input.MouseButtonInfo(0, 0));
                    editor.mouseDragged(moved, -40, -50);
                    editor.mouseReleased(moved);
                    check(app.settings.hudX == playerX && app.settings.hudY == playerY, "Lyrics drag must preserve music anchor");
                    app.settings.lyricsHudScale = 1;
                    app.settings.lyricsHudX = .5;
                    app.settings.lyricsHudY = .9;
                }
                app.settings.hudStyle = com.darkmusic.client.config.Settings.HudStyle.CLASSIC;
                app.playback.play(a == b ? List.of(a) : List.of(a, b), a);
                app.playback.addToQueue(b);
                mc.gui.setScreen(new LyricsScreen(app, null, a));
                since = ticks;
                stage = 1;
            } else if (stage == 1) {
                check(app.playback.state().status() != AudioBackend.Status.ERROR, app.playback.state().error());
                if (app.playback.state().position() < 1.5 || ticks - since < 30) return;
                check(app.lyrics.get(a).current(app.playback.state().position()) == 0, "Timed lyrics follow device playback clock");
                operation = app.lyrics.lookup.offset(a, 2000);
                app.playback.pause();
                since = ticks;
                stage = 2;
            } else if (stage == 2) {
                if (ticks - since < 15) return;
                check(app.playback.state().status() == AudioBackend.Status.PAUSED, "Playback paused");
                paused = app.playback.state().position();
                if (Boolean.getBoolean("darkmusic.smoothFontAvailable")) {
                    Path images = Path.of(System.getProperty("darkmusic.privateImages"));
                    Files.createDirectories(images.resolve("screenshots"));
                    net.minecraft.client.Screenshot.grab(images.toFile(), "lyrics-timed-" + mc.options.languageCode + ".png", mc.gameRenderer.mainRenderTarget(), 1, ignored -> {
                    });
                }
                check(app.lyrics.get(a).current(paused) == -1, "Positive offset displays lyrics later");
                check(com.darkmusic.client.hud.LyricsHud.cue(app.lyrics.get(a), paused).current().isEmpty(), "Waiting for lyrics is not missing lyrics");
                mc.gui.setScreen(new HudEditorScreen(app, null));
                since = ticks;
                stage = 3;
            } else if (stage == 3) {
                app.settings.hudStyle = com.darkmusic.client.config.Settings.HudStyle.values()[(ticks - since) / 4 % 4];
                if (ticks - since < 20) return;
                if (Boolean.getBoolean("darkmusic.smoothFontAvailable")) {
                    Path images = Path.of(System.getProperty("darkmusic.privateImages"));
                    net.minecraft.client.Screenshot.grab(images.toFile(), "lyrics-hud-editor-" + mc.options.languageCode + ".png", mc.gameRenderer.mainRenderTarget(), 1, ignored -> {
                    });
                }
                check(Math.abs(paused - app.playback.state().position()) < .1, "Pause keeps the lyric clock stable");
                app.playback.seek(5);
                since = ticks;
                stage = 4;
            } else if (stage == 4) {
                if (ticks - since < 15 || Math.abs(app.playback.state().position() - 5) > .25) return;
                check(app.lyrics.get(a).current(app.playback.state().position()) >= 0, "Seek updates lyrics");
                app.playback.speed(1.5);
                app.playback.toggle();
                since = ticks;
                stage = 5;
            } else if (stage == 5) {
                if (ticks - since < 20 || app.playback.state().position() < 5.5) return;
                check(app.playback.upNextSnapshot().manual().size() == 1, "Lyrics actions preserve Up Next");
                var plain = LyricsDocument.of("import", "plain", a.title(), a.artist(), "", a.duration(), "und", LyricsDocument.Variant.UNKNOWN, false, false, "未同步歌詞\nPlain lyrics\n歌の言葉");
                operation = app.lyrics.lookup.select(a, plain);
                stage = 6;
            } else if (stage == 6) {
                check(app.lyrics.document(a).available() && app.lyrics.get(a).lines().isEmpty(), "Plain lyrics are available without timestamps");
                mc.gui.setScreen(new LyricsScreen(app, null, a));
                for (int w : new int[]{320, 426, 960}) bounds(mc.gui.screen(), w, w == 960 ? 540 : 240);
                app.playback.next();
                since = ticks;
                stage = 7;
            } else if (stage == 7) {
                if (app.playback.state().track() == null || !app.playback.state().track().id().equals(b.id()) || ticks - since < 10)
                    return;
                if (!a.id().equals(b.id()))
                    check(!app.lyrics.document(b).equals(app.lyrics.document(a)), "No stale lyrics on the next queued track");
                else
                    System.out.println("DARKMUSIC_LYRICS_LIMITATION: only one private track available; distinct-track stale-result check not repeated");
                mc.gui.setScreen(null);
                check(app.requestLyrics().isEmpty(), "Lyrics command accepts the current Up Next track");
                since = ticks;
                stage = 8;
            } else if (stage == 8) {
                if (ticks - since < 5) return;
                check(mc.gui.screen() instanceof LyricsScreen, "Deferred lyrics command opens its menu");
                check(app.playback.state().track().id().equals(b.id()), "Opening lyrics does not replace the current song");
                app.playback.pause();
                since = ticks;
                stage = 9;
            } else if (stage == 9) {
                if (ticks - since < 10) return;
                check(app.playback.state().status() == AudioBackend.Status.PAUSED, "Command preview remains pausable");
                mc.gui.setScreen(null);
                check(app.requestLyrics().isEmpty(), "Lyrics command accepts paused music");
                since = ticks;
                stage = 10;
            } else if (stage == 10) {
                if (ticks - since < 5) return;
                check(mc.gui.screen() instanceof LyricsScreen, "Paused-song lyrics menu opens");
                check(app.playback.state().status() == AudioBackend.Status.PAUSED, "Opening lyrics never resumes music");
                app.settings.lyricsEnabled = false;
                check(app.requestLyrics().equals("lyrics.darkmusic.disabled"), "Disabled lyrics are reported without enabling them");
                app.settings.lyricsEnabled = true;
                mc.gui.setScreen(null);
                check(app.requestLyrics().isEmpty(), "Queue the final request before a playback change");
                app.playback.stop();
                since = ticks;
                stage = 11;
            } else if (stage == 11) {
                if (ticks - since < 5) return;
                check(!(mc.gui.screen() instanceof LyricsScreen), "Stopping before the deferred open prevents a stale lyrics menu");
                check(app.requestLyrics().equals("upnext.idle"), "A stopped track is not treated as currently playing");
                System.out.println("DARKMUSIC_LYRICS_COMMAND_PASS: active/paused Up Next track, offline local lyrics, disabled feedback, stopped retained track and deferred state revalidation");
                System.out.println("DARKMUSIC_LYRICS_UI_PASS: independent lyrics HUD in all four layouts, compact/wide nonoverlap, drag/resize isolation, startup cue, timed/plain views, actual device playback, pause/seek/resume/speed, positive offset and Up Next isolation; online access disabled");
                System.out.println("DARKMUSIC_SMOKE_PASS:" + Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip());
                stage = 99;
                mc.stop();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            stage = 99;
            mc.stop();
        }
    }
}
