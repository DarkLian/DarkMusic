package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.Warnings;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Repeat;
import com.darkmusic.client.config.VisualColors;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.gui.MusicFoldersScreen;
import com.darkmusic.client.hud.HudLayout;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.library.Track;
import com.darkmusic.client.text.Text;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.input.*;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Development-only checks in an isolated title-screen client; no server connections.
 */
public final class SmokeClient implements ClientModInitializer {
    private final DesktopWindowSmoke windowChecks = new DesktopWindowSmoke();
    private final Set<String> playing = new HashSet<>();
    private int ticks, stage = -1, frame, seekWaits;
    private boolean selecting, fullscreenRequested, failed;
    private MusicScreen player;
    private int musicCount;
    private String playlistId;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("darkmusic.pipSmoke")) new PipSmoothClient().start();
        else if (Boolean.getBoolean("darkmusic.lyricsSmoke")) new LyricsSmokeClient().start();
        else if (Boolean.getBoolean("darkmusic.cleanupSmoke")) new CleanupSmokeClient().start();
        else if (Boolean.getBoolean("darkmusic.queueSmoke")) new QueueSmokeClient().start();
        else if (Boolean.getBoolean("darkmusic.advanced")) new AdvancedSmokeClient().start();
        else if (Boolean.getBoolean("darkmusic.resetSmoke")) new ResetSmokeClient().start();
        else if (Boolean.getBoolean("darkmusic.previewSmoke")) new DownloadPreviewClient().start();
        else if (Boolean.getBoolean("darkmusic.capture")) new MusicCaptureClient().start();
        else ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private String translated(String label) {
        if (label.startsWith("View: "))
            return Text.get("format.darkmusic.view", Text.get("view.darkmusic." + label.substring(6).toLowerCase(Locale.ROOT)));
        try (var input = getClass().getResourceAsStream("/assets/darkmusic/lang/en_us.json")) {
            var catalog = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : catalog.entrySet())
                if (entry.getValue().getAsString().equals(label))
                    return Text.get(entry.getKey());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return label;
    }

    private AbstractWidget button(Minecraft mc, String label) {
        return mc.gui.screen().children().stream().filter(x -> x instanceof AbstractWidget).map(x -> (AbstractWidget) x).filter(x -> x.getMessage().getString().equals(translated(label))).findFirst().orElseThrow(() -> new IllegalStateException("Missing button: " + label));
    }

    private void click(Minecraft mc, String label) {
        SmokeUi.click(mc, translated(label));
    }

    private MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    }

    private void escape(Minecraft mc) {
        mc.gui.screen().keyPressed(new KeyEvent(256, 0, 0));
    }

    private void checkVolumeBindings(Minecraft mc, DarkMusicClient app) throws Exception {
        // Every KeyMapping field Shortcuts declares must be listed in bindings(), once.
        long declared = Arrays.stream(app.shortcuts().getClass().getDeclaredFields()).filter(f -> f.getType() == KeyMapping.class).count();
        var bindings = app.shortcuts().bindings();
        require(bindings.length == declared && Arrays.stream(bindings).allMatch(Objects::nonNull)
            && Arrays.stream(bindings).map(KeyMapping::getName).distinct().count() == declared, "bindings() does not list every native binding once");
        require(app.shortcuts().open.getDefaultKey().getValue() == 66 && app.shortcuts().mute.getDefaultKey().getValue() == 77 && app.shortcuts().toggle.getDefaultKey().getValue() == 92, "Open/mute/play defaults are wrong");
        require(app.shortcuts().slower.getDefaultKey().getValue() == 45 && app.shortcuts().faster.getDefaultKey().getValue() == 61 && app.shortcuts().normalSpeed.getDefaultKey().getValue() == 320, "Speed defaults are wrong");
        require(app.shortcuts().seekBack.getDefaultKey().getValue() == 263 && app.shortcuts().seekForward.getDefaultKey().getValue() == 262, "Seek arrow defaults are wrong");
        require(app.shortcuts().stop.getDefaultKey().getValue() == 80 && app.shortcuts().jump.getDefaultKey().getValue() == 79, "Stop/locate defaults are wrong");
        require(app.shortcuts().next.getDefaultKey().getValue() == 93 && app.shortcuts().previous.getDefaultKey().getValue() == 91, "Track bracket defaults are wrong");
        require(app.shortcuts().volumeUp.getDefaultKey().getValue() == 265 && app.shortcuts().volumeDown.getDefaultKey().getValue() == 264, "Volume arrow defaults are wrong");
        var up = InputConstants.getKey("key.keyboard.up");
        var down = InputConstants.getKey("key.keyboard.down");
        var oldMute = app.shortcuts().mute.saveString();
        var oldUp = app.shortcuts().volumeUp.saveString();
        var oldDown = app.shortcuts().volumeDown.saveString();
        var oldToggle = app.shortcuts().toggle.saveString();
        double oldVolume = app.services().settings().volume;
        var tick = DarkMusicClient.class.getDeclaredMethod("tick", Minecraft.class, boolean.class);
        tick.setAccessible(true);
        try {
            app.shortcuts().volumeUp.setKey(up);
            app.shortcuts().volumeDown.setKey(down);
            app.shortcuts().toggle.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            app.shortcuts().mute.setKey(InputConstants.getKey("key.keyboard.f8"));
            var muteKey = InputConstants.getKey("key.keyboard.f8");
            KeyMapping.resetMapping();
            app.sources().volume(.5);
            KeyMapping.click(muteKey);
            tick.invoke(app, mc, false);
            require(app.services().settings().volume == .5, "Mute shortcut fired in a menu");
            KeyMapping.click(muteKey);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == 0 && app.services().settings().lastAudibleVolume == .5, "Mute did not remember audible volume");
            require(app.services().volumeFeedback().opacity(System.nanoTime()) > 0, "Mute feedback was not shown");
            KeyMapping.click(muteKey);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == .5, "Unmute did not restore volume");
            KeyMapping.click(up);
            tick.invoke(app, mc, false);
            require(app.services().settings().volume == .5, "Volume shortcut fired while menus were blocked");
            KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == .55, "Volume up step failed");
            KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == .5, "Volume down step failed");
            app.sources().volume(1);
            KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == 1, "Volume exceeded 100%");
            app.sources().volume(0);
            KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == 0, "Volume dropped below zero");
            app.sources().volume(.5);
            app.shortcuts().toggle.setKey(up);
            KeyMapping.resetMapping();
            KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.services().settings().volume == .5, "Conflicting volume shortcut fired");
            require(app.services().warnings().current().contains("Shortcut conflict"), "Conflicting shortcut needs a visible warning");
            app.services().warnings().clear();
            app.shortcuts().toggle.setKey(InputConstants.UNKNOWN);
            app.shortcuts().volumeUp.setKey(InputConstants.getKey("key.keyboard.f10"));
            app.services().save();
            require(app.shortcuts().volumeUp.saveString().equals("key.keyboard.f10"), "Native volume binding did not change");
        } finally {
            app.shortcuts().mute.setKey(InputConstants.getKey(oldMute));
            app.shortcuts().volumeUp.setKey(InputConstants.getKey(oldUp));
            app.shortcuts().volumeDown.setKey(InputConstants.getKey(oldDown));
            app.shortcuts().toggle.setKey(InputConstants.getKey(oldToggle));
            KeyMapping.resetMapping();
            app.sources().volume(oldVolume);
        }
    }

    private void checkSpeedBindings(Minecraft mc, DarkMusicClient app) throws Exception {
        String oldUp = app.shortcuts().faster.saveString(), oldDown = app.shortcuts().slower.saveString(), oldNormal = app.shortcuts().normalSpeed.saveString();
        double old = app.services().settings().speed;
        var up = InputConstants.getKey("key.keyboard.f13");
        var down = InputConstants.getKey("key.keyboard.f14");
        var normal = InputConstants.getKey("key.keyboard.f15");
        var tick = DarkMusicClient.class.getDeclaredMethod("tick", Minecraft.class, boolean.class);
        tick.setAccessible(true);
        try {
            app.shortcuts().faster.setKey(up);
            app.shortcuts().slower.setKey(down);
            app.shortcuts().normalSpeed.setKey(normal);
            KeyMapping.resetMapping();
            app.sources().speed(1);
            KeyMapping.click(up);
            tick.invoke(app, mc, false);
            require(app.services().settings().speed == 1, "Speed shortcut fired in a menu");
            KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.services().settings().speed == 1.1 && app.services().speedFeedback().opacity(System.nanoTime()) > 0, "Speed shortcut feedback missing");
            KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.services().settings().speed == 1, "Speed down shortcut failed");
            app.sources().speed(1.5);
            KeyMapping.click(normal);
            tick.invoke(app, mc, true);
            require(app.services().settings().speed == 1 && app.services().speedFeedback().opacity(System.nanoTime()) > 0, "Reset-speed feedback missing");
        } finally {
            app.shortcuts().faster.setKey(InputConstants.getKey(oldUp));
            app.shortcuts().slower.setKey(InputConstants.getKey(oldDown));
            app.shortcuts().normalSpeed.setKey(InputConstants.getKey(oldNormal));
            KeyMapping.resetMapping();
            app.sources().speed(old);
        }
    }

    /**
     * Clicks a row of the list rows supplies. The collections list may be hidden; it is opened and read again.
     */
    private void row(Minecraft mc, Supplier<RowList> list, boolean collections, int index, boolean activate) throws Exception {
        var rows = list.get();
        if (!rows.visible && collections) {
            SmokeUi.click(mc, Text.get("ui.darkmusic.collections"));
            rows = list.get();
        }
        int height = (int) SmokeUi.field(rows, RowList.class, "rowHeight");
        // The first 24 pixels of a song row are its checkbox: selecting checks it, activating double-clicks past it.
        var at = mouse(rows.getX() + (activate ? 40 : 4), rows.getY() + index * height + 4);
        if (activate) rows.onClick(at, false);
        rows.onClick(at, activate);
    }

    private void view(Minecraft mc, String name) throws Exception {
        SmokeUi.library(mc);
        for (int i = 0; i < 4 && !player.browser().mode().equals(name); i++) {
            String compact = "View: " + player.browser().mode();
            boolean cycle = mc.gui.screen().children().stream().anyMatch(x -> x instanceof AbstractWidget b && b.getMessage().getString().equals(translated(compact)));
            click(mc, cycle ? compact : name);
        }
        require(player.browser().mode().equals(name), "View did not switch to " + name);
    }

    /**
     * Built-in Favorite Music is listed before user playlists, so rows are found by playlist id.
     */
    @SuppressWarnings("unchecked")
    private void selectGroup(Minecraft mc, String id) throws Exception {
        int index = ((List<String>) player.browser().groups()).indexOf(id);
        require(index >= 0, "Playlist is not listed: " + id);
        row(mc, () -> (RowList) SmokeUi.widget(player, RowList.class, Text.get("text.darkmusic.library_navigation")), true, index, false);
    }

    private void checkPlaylistBrowsing(Minecraft mc, DarkMusicClient app) throws Exception {
        var tracks = app.services().library().snapshot().tracks();
        var first = tracks.getFirst();
        var second = tracks.get(1);
        selectGroup(mc, playlistId);
        require(player.browser().group().equals(playlistId), "Smoke playlist was not selected");
        app.services().playlists().removeAll(playlistId, tracks.stream().map(Track::id).collect(Collectors.toSet()));
        app.services().playlists().addAll(playlistId, List.of(second, first));
        player.tick();
        require(player.browser().visible().equals(List.of(second, first)), "Playlist order was lost");
        String duplicate = app.services().playlists().create("Smoke test");
        app.services().playlists().add(duplicate, first);
        player.tick();
        selectGroup(mc, duplicate);
        require(player.browser().group().equals(duplicate) && player.browser().visible().equals(List.of(first)), "Duplicate names confused selection");
        app.services().playlists().rename(duplicate, "Renamed");
        player.tick();
        require(player.browser().group().equals(duplicate), "Rename lost selection");
        app.services().playlists().delete(duplicate);
        player.tick();
        require(player.browser().group().equals(((List<?>) player.browser().groups()).getFirst()), "Deletion did not fall back to the first playlist");
        selectGroup(mc, playlistId);
        var search = (EditBox) SmokeUi.widget(player, EditBox.class, Text.get("text.darkmusic.search_songs"));
        search.setValue(second.relativePath());
        require(player.browser().visible().equals(List.of(second)), "Playlist search failed");
        row(mc, () -> (RowList) SmokeUi.widget(player, RowList.class, Text.get("text.darkmusic.songs_enter_or_double_click_to_play")), false, 0, true);
        // The backend reports the new track from its own thread; the controller records the request at once.
        var requested = app.services().playback().upNextSnapshot().current();
        require(requested != null && requested.id().equals(second.id()), "Playlist playback failed: requested " + (requested == null ? null : requested.title()) + ", expected " + second.title());
        search.setValue("");
        var missing = new Track(first.rootId(), "missing.mp3", first.file(), "Missing", "", "", 0, 0, 0, "");
        app.services().playlists().add(playlistId, missing);
        player.tick();
        require(player.browser().playlistNotice().toString().equals(Text.get("format.darkmusic.missing_entries", 1)), "Missing entry notice absent");
        app.services().playlists().removeAll(playlistId, Set.of(missing.id()));
        String empty = app.services().playlists().create("Empty");
        player.tick();
        selectGroup(mc, empty);
        require(((List<?>) player.browser().visible()).isEmpty(), "Empty playlist exposed all songs");
        // These playlists are deleted on purpose; play from the library so that is not a missing playback source.
        app.services().playback().play(tracks, second);
        player.tick();
        view(mc, "Playlists");
        app.services().playlists().delete(empty);
        app.services().playlists().delete(playlistId);
        player.tick();
        require(((List<?>) player.browser().visible()).isEmpty(), "No-playlists state exposed library");
        playlistId = app.services().playlists().create("Smoke test");
        app.services().playlists().addAll(playlistId, tracks);
        player.tick();
    }

    private void checkNavigationAndJump(DarkMusicClient app) throws Exception {
        var search = (EditBox) SmokeUi.widget(player, EditBox.class, Text.get("text.darkmusic.search_songs"));
        var current = app.services().playback().state().track();
        require(current != null, "Jump test needs current playback");
        var visible = (List<?>) player.browser().visible();
        require(visible.stream().anyMatch(t -> ((Track) t).id().equals(current.id())), "Playing track missing before filtering");
        search.setValue("No matching title for navigation check");
        require(((List<?>) player.browser().visible()).isEmpty(), "Search did not hide playing track");
        player.jumpToPlaying();
        var selected = (Track) player.browser().selected();
        require(selected.id().equals(current.id()), "Jump did not select the currently playing track");
        require(((EditBox) SmokeUi.widget(player, EditBox.class, Text.get("text.darkmusic.search_songs"))).getValue().isEmpty(), "Jump did not clear incompatible search");
        require(app.services().playback().state().track().id().equals(current.id()), "Jump changed playback");
        var originalSource = app.services().playback().source();
        for (var origin : List.of(new PlaybackController.Source("All", "", "", app.services().library().rootId()),
            new PlaybackController.Source("All", current.relativePath().contains("/") ? current.relativePath().substring(0, current.relativePath().lastIndexOf('/')) : "", "", app.services().library().rootId()),
            new PlaybackController.Source("Playlists", playlistId, "", app.services().library().rootId()),
            new PlaybackController.Source("Artists", current.artist(), current.title(), app.services().library().rootId()),
            new PlaybackController.Source("Albums", current.album(), "", app.services().library().rootId()))) {
            SmokeUi.set(app.services().playback(), PlaybackController.class, "source", origin);
            ((EditBox) SmokeUi.widget(player, EditBox.class, Text.get("text.darkmusic.search_songs"))).setValue("No matches before source jump");
            player.jumpToPlaying();
            require(player.browser().mode().equals(origin.mode()) && player.browser().group().equals(origin.group()), "Jump lost playback source: " + origin);
            // Since v2.0.0 a jump clears the search so the playing song is always visible.
            require(((EditBox) SmokeUi.widget(player, EditBox.class, Text.get("text.darkmusic.search_songs"))).getValue().isEmpty(), "Jump kept a search that can hide the playing song");
            require(((Track) player.browser().selected()).id().equals(current.id()), "Source jump selected a different track");
            var groups = (List<?>) player.browser().groups();
            var rows = (List<RowList.Row>) SmokeUi.field(SmokeUi.widget(player, RowList.class, Text.get("text.darkmusic.library_navigation")), RowList.class, "rows");
            var tracks = app.services().library().snapshot().tracks();
            long count = origin.mode().equals("Playlists") ? app.services().playlists().resolve(origin.group(), tracks).size() : tracks.stream().filter(track -> switch (origin.mode()) {
                case "Artists" -> track.artist().equals(origin.group());
                case "Albums" -> track.album().equals(origin.group());
                default -> FolderTree.includes(origin.group(), track);
            }).count();
            require(rows.get(groups.indexOf(origin.group())).title().endsWith(" (" + count + ")"), "Navigation count differs from total playable tracks: " + origin);
        }
        SmokeUi.set(app.services().playback(), PlaybackController.class, "source", originalSource);
        player.jumpToPlaying();
        var list = (RowList) SmokeUi.widget(player, RowList.class, Text.get("text.darkmusic.songs_enter_or_double_click_to_play"));
        require(Text.get("text.darkmusic.scroll_long_text").equals("text.darkmusic.scroll_long_text"), "Long-text hover hint remains translated");
        var originalRows = (List<RowList.Row>) SmokeUi.field(list, RowList.class, "rows");
        list.rows(IntStream.range(0, 100).mapToObj(i -> new RowList.Row(current.title(), "")).toList());
        list.reveal(99);
        require(list.selected() == 99 && (int) SmokeUi.field(list, RowList.class, "scroll") > 0, "Jump did not scroll the target row into view");
        list.rows(originalRows);
    }

    private void checkColors(Minecraft mc, DarkMusicClient app) throws Exception {
        var settings = new SettingsScreen(app.sources(), player);
        mc.gui.setScreen(settings);
        click(mc, "Spectrum Colors");
        require(mc.gui.screen() instanceof ColorsScreen, "Spectrum colors did not open");
        click(mc, "Aurora Borealis");
        require(app.services().settings().spectrumColors.preset == VisualColors.Preset.AURORA, "Preset did not apply");
        var start = (EditBox) SmokeUi.field(mc.gui.screen(), ColorsScreen.class, "start");
        var end = (EditBox) SmokeUi.field(mc.gui.screen(), ColorsScreen.class, "end");
        start.setValue("#55AAFF");
        end.setValue("#FFAACC");
        require(app.services().settings().spectrumColors.color(0) == 0xFF55AAFF && app.services().settings().spectrumColors.color(1) == 0xFFFFAACC, "Custom mixed colors failed");
        start.setValue("#xyz");
        require(app.services().settings().spectrumColors.color(0) == 0xFF55AAFF, "Invalid color changed the last valid color");
        click(mc, "Moonlight");
        escape(mc);
        require(mc.gui.screen() == settings, "Colors Escape did not return to Settings");
        click(mc, "Progress Bar Colors");
        click(mc, "Neon Dream");
        require(app.services().settings().progressColors.preset == VisualColors.Preset.NEON_DREAM && app.services().settings().spectrumColors.preset == VisualColors.Preset.MOONLIGHT, "Progress and spectrum colors are not independent");
        click(mc, "Moonlight");
        escape(mc);
        escape(mc);
        require(mc.gui.screen() == player, "Color navigation did not preserve player");
    }

    private void tick(Minecraft mc) {
        try {
            ticks++;
            if (ticks > 1800) throw new IllegalStateException("Smoke test timed out");
            var app = DarkMusicClient.INSTANCE;
            if (failed || app == null || app.services().library() == null || !app.services().playlistsReady() || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
                return;
            if (!fullscreenRequested) {
                fullscreenRequested = true;
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return;
            }
            if (!windowChecks.tick(mc)) return;
            if (!selecting) {
                selecting = true;
                // The private collection is Opus, which decodes through the managed ffmpeg.
                if (System.getProperty("darkmusic.downloadTools") != null) {
                    Path tools = Path.of(System.getProperty("darkmusic.downloadTools"));
                    String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
                    app.services().settings().ffmpegPath = tools.resolve("ffmpeg" + ext).toString();
                    app.services().settings().ffprobePath = tools.resolve("ffprobe" + ext).toString();
                    app.services().apply();
                }
                app.sources().chooseMusicFolder(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                return;
            }
            if (app.services().library().snapshot().scanning() || app.services().library().snapshot().tracks().size() < 3)
                return;
            musicCount = app.services().library().snapshot().tracks().size();
            var audio = app.services().playback().state();
            if (audio.status() == AudioBackend.Status.ERROR || !app.services().warnings().current().isBlank())
                throw new IllegalStateException("Audio/library error: " + app.services().warnings().current() + " " + audio.error());
            if (audio.status() == AudioBackend.Status.PLAYING && audio.position() > 0)
                playing.add(audio.track().relativePath());
            if (stage == -1) {
                require(mc.level == null && mc.player == null, "Smoke test stays at title screen");
                if (Boolean.getBoolean("darkmusic.testNativeBindings"))
                    require(app.shortcuts().volumeUp.saveString().equals("key.keyboard.f10"), "Legacy config overrode the native controls binding");
                mc.gui.setScreen(new TitleScreen());
                app.open();
                player = (MusicScreen) mc.gui.screen();
                app.services().playlists().all().stream().map(p -> p.id()).toList().forEach(app.services().playlists()::delete);
                playlistId = app.services().playlists().create("Smoke test");
                app.services().playlists().add(playlistId, app.services().library().snapshot().tracks().getFirst());
                app.services().settings().hudArtwork = true;
                app.services().settings().hudX = app.services().settings().hudY = 0.0;
                app.services().settings().hudScale = 1;
                app.services().settings().hudVisualizer = false;
                // Answer the first-use online-lyrics prompt (offline) up front; a run folder without the earlier
                // answer otherwise opens it over the player at an arbitrary later step.
                app.services().settings().lyricsConsentVersion = 1;
                app.services().settings().lyricsOnline = app.services().settings().lyricsLibraryLookup = false;

                app.services().apply();
                app.services().playback().shuffle(false);
                app.services().playback().repeat(Repeat.OFF);
                app.sources().speed(1);
                app.services().playback().volume(.05);
                app.services().playback().play(app.services().library().snapshot().tracks(), app.services().library().snapshot().tracks().getFirst());
                stage = 0;
                frame = ticks;
                return;
            }
            if (ticks - frame < 24) return;
            frame = ticks;
            if (mc.gui.screen() instanceof LocalScreen screen) {
                for (var widget : screen.children())
                    if (widget instanceof AbstractWidget w) {
                        if (w.visible)
                            require(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= screen.width && w.getBottom() <= screen.height, "Widget outside screen: " + w.getMessage().getString());
                        require(!w.getMessage().getString().equals("Done"), "Done button remains");
                    }
            }
            switch (stage++) {
                case 0 -> {
                    var coverTrack = app.services().library().snapshot().tracks().stream().filter(t -> t.relativePath().contains("Departure")).findFirst();
                    coverTrack.ifPresent(app.services().artwork()::get);
                    var slider = (ValueSlider) SmokeUi.seekBar(player);
                    // The player ignores a seek until its own tick sees the track playing with a known duration;
                    // ffmpeg can take longer than one check interval to start the first track.
                    if (!slider.active || app.services().playback().state().status() != AudioBackend.Status.PLAYING) {
                        require(++seekWaits < 25, "Playback never became seekable: " + app.services().playback().state().status());
                        stage = 0;
                        return;
                    }
                    var generation = (AtomicLong) SmokeUi.field(SmokeUi.field(app.services().playback(), PlaybackController.class, "audio"), JavaSoundBackend.class, "generation");
                    long before = generation.get();
                    double y = slider.getY() + 8;
                    mc.gui.screen().mouseClicked(mouse(slider.getX() + 12, y), false);
                    for (int i = 0; i < 5; i++)
                        mc.gui.screen().mouseDragged(mouse(slider.getX() + 15 + i * 2, y), 2, 0);
                    require(generation.get() == before, "Seek drag restarted the decoder before release");
                    mc.gui.screen().mouseReleased(mouse(slider.getX() + 23, y));
                    require(generation.get() == before + 1, "Seek release must issue exactly one command: " + before + " -> " + generation.get() + " " + app.services().playback().state().status());
                    app.services().playback().pause();
                    click(mc, "Settings");
                }
                case 1 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED, "Pause failed");
                    app.services().playback().seek(.5);
                    if (!app.services().settings().lyricsEnabled) click(mc, "Enable Lyrics");
                    click(mc, "Enable Lyrics");
                    require(!app.services().settings().lyricsEnabled, "Global lyrics toggle did not disable lyrics");
                    var lyricsControls = mc.gui.screen().children().stream().filter(w -> w instanceof AbstractWidget a && a.visible).map(w -> ((AbstractWidget) w).getMessage().getString()).filter(text -> Set.of("Advanced Lyrics", "Lyrics in HUD", "Show Next Line").contains(text)).toList();
                    require(lyricsControls.isEmpty(), "Disabled lyrics controls remained visible: " + lyricsControls + " on " + mc.gui.screen().getClass().getSimpleName());
                    click(mc, "Enable Lyrics");
                    require(app.services().settings().lyricsEnabled, "Global lyrics toggle did not re-enable lyrics");
                    require(mc.gui.screen().children().stream().anyMatch(w -> w instanceof AbstractWidget a && a.visible && a.getMessage().getString().equals("Lyrics in HUD")), "Re-enabled lyrics did not restore its controls");
                }
                case 2 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED && audio.position() >= .49, "Paused seek failed");
                    app.sources().speed(1.5);
                    require(app.services().playback().speed() == 1.5, "Speed did not apply");
                    app.services().playback().toggle();
                    click(mc, Text.get("text.darkmusic.hud_artwork"));
                    require(!app.services().settings().hudArtwork, "Artwork toggle failed");
                    click(mc, Text.get("text.darkmusic.hud_artwork"));
                    click(mc, "Edit HUD Layout");
                }
                case 3 -> {
                    require(mc.gui.screen() instanceof HudEditorScreen, "HUD editor not opened");
                    var b = NowPlayingHud.bounds(app.services().hud(), mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 5, b.y() + 5), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() + 105, b.y() + 85), 100, 80);
                    mc.gui.screen().mouseReleased(mouse(b.x() + 105, b.y() + 85));
                    require(app.services().settings().hudX > 0 && app.services().settings().hudY > 0, "HUD did not drag");
                    var resized = NowPlayingHud.bounds(app.services().hud(), mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseScrolled(resized.x() + 5, resized.y() + 5, 0, 1);
                    require(app.services().settings().hudScale == 1.1, "Scale did not change by 0.1");
                    require(mc.gui.screen().children().stream().noneMatch(x -> x instanceof ValueSlider), "HUD scale slider was not removed");
                    var spectrum = HudLayout.bounds(app.services().settings(), mc.gui.screen().width, mc.gui.screen().height);
                    double previousScale = app.services().settings().hudScale;
                    mc.gui.screen().mouseScrolled(spectrum.x() + spectrum.width() - 3, spectrum.y() + 3, 0, 1);
                    require(app.services().settings().hudScale == Math.round((previousScale + .1) * 10) / 10.0, "Combined editor did not scale the visualizer");
                    require(app.services().settings().hudScale == 1.2, "Combined spectrum did not share the HUD scale");
                }
                case 4 -> {
                    escape(mc);
                    require(mc.gui.screen() instanceof SettingsScreen, "Escape did not return from HUD editor");
                }
                case 5 -> {
                    require(Arrays.asList(mc.options.keyMappings).containsAll(Arrays.asList(app.shortcuts().bindings())), "Native controls are missing DarkMusic bindings");
                    checkVolumeBindings(mc, app);
                    checkSpeedBindings(mc, app);
                    app.shortcuts().faster.setKey(InputConstants.getKey("key.keyboard.f9"));
                    app.services().save();
                    require(app.shortcuts().faster.saveString().equals("key.keyboard.f9"), "Native speed binding did not change");
                    app.shortcuts().faster.setKey(InputConstants.UNKNOWN);
                    KeyMapping.resetMapping();
                    app.services().save();
                    escape(mc);
                    require(mc.gui.screen() == player, "Escape did not return to player");
                    click(mc, "Manage Playlists");
                }
                case 6 -> {
                    require(mc.gui.screen() instanceof PlaylistScreen, "Editor not opened");
                    var coverTrack = app.services().library().snapshot().tracks().stream().filter(t -> t.relativePath().contains("Departure")).findFirst();
                    if (coverTrack.isPresent())
                        require(app.services().artwork().get(coverTrack.get()) != null, "Real artwork did not become a Minecraft texture");
                    var rows = (List<RowList.Row>) SmokeUi.field(SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "lists"), RowList.class, "rows");
                    // Favorite Music is built in and listed first; check the playlist this run created.
                    String expected = "Smoke test (" + app.services().playlists().resolve(playlistId, app.services().library().snapshot().tracks()).size() + ")";
                    int smokeRow = rows.stream().map(RowList.Row::title).toList().indexOf(expected);
                    require(smokeRow >= 0, "Manage Playlists count differs from playable tracks: " + rows.stream().map(RowList.Row::title).toList() + " expected " + expected);
                    row(mc, () -> (RowList) SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "lists"), false, smokeRow, false);
                    click(mc, "Add Songs");
                }
                case 7 -> {
                    require(mc.gui.screen() instanceof TrackPickerScreen, "Picker not opened");
                    click(mc, "Select All");
                    var selection = (SongSelection) SmokeUi.field(mc.gui.screen(), TrackPickerScreen.class, "selection");
                    require(selection.selected().size() == musicCount - 1, "Select All did not exclude the existing song: " + selection.selected().size() + " of " + musicCount + ", eligible " + app.services().playlists().availableToAdd(playlistId, app.services().library().snapshot().tracks()).size());
                    // The picker's search box is its only text input.
                    var search = mc.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
                    search.setValue(app.services().library().snapshot().tracks().get(1).relativePath());
                    require(selection.hidden() == musicCount - 2, "Filtered selections were lost");
                    click(mc, "Deselect All");
                    require(selection.selected().isEmpty(), "Deselect did not clear hidden selection");
                    click(mc, "Invert Selection");
                    require(selection.selected().size() == 1, "Invert failed");
                    search.setValue("");
                    click(mc, "Select All");
                    mc.gui.screen().tick();
                }
                case 8 -> {
                    click(mc, "Add Selected");
                    require(app.services().playlists().get(playlistId).entries().size() == musicCount, "Bulk add did not deduplicate");
                    row(mc, () -> (RowList) SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "entries"), false, 0, false);
                    mc.gui.screen().tick();
                    click(mc, "Remove Selected");
                    require(app.services().playlists().get(playlistId).entries().size() == musicCount - 1, "Bulk removal failed");
                    click(mc, "Add Songs");
                    click(mc, "Select All");
                    escape(mc);
                    require(app.services().playlists().get(playlistId).entries().size() == musicCount - 1, "Cancelled picker changed playlist");
                    app.services().playlists().addAll(playlistId, app.services().library().snapshot().tracks());
                    escape(mc);
                    mc.options.guiScale().set(6);
                    mc.resizeGui();
                    view(mc, "Playlists");
                }
                case 9 -> {
                    checkPlaylistBrowsing(mc, app);
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                }
                case 10 -> {
                    view(mc, "Artists");
                    view(mc, "Albums");
                    view(mc, "All");
                    click(mc, Text.get("key.darkmusic.normal_speed"));
                    require(app.services().settings().speed == 1, "Speed reset failed");
                    // The player syncs its sliders from settings on the next screen tick.
                    mc.gui.screen().tick();
                    var speed = (ValueSlider) SmokeUi.widget(player, ValueSlider.class, Text.get("format.darkmusic.speed", "1.0"));
                    speed.setFocused(true);
                    boolean stepped = speed.keyPressed(new KeyEvent(262, 0, 0));
                    require(app.services().settings().speed == 1.1, "Speed slider step failed: handled " + stepped + ", speed " + app.services().settings().speed);
                    speed.keyPressed(new KeyEvent(263, 0, 0));
                    require(app.services().settings().speed == 1, "Speed slider reverse step failed");
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                }
                case 11 -> {
                    click(mc, "Settings");
                    SwingUtilities.invokeLater(() -> {
                        var closer = new Timer(300, event -> {
                            if (!DesktopFolderPicker.isOpen()) ((Timer) event.getSource()).stop();
                            else DesktopFolderPicker.cancel();
                        });
                        closer.start();
                    });
                    // Folders are added from Settings > Music Folders.
                    click(mc, Text.get("folders.title"));
                    click(mc, Text.get("folders.add"));
                }
                case 12 -> {
                    require(mc.gui.screen() instanceof MusicFoldersScreen && !DesktopFolderPicker.isOpen(), "Folder dialog did not cancel back to Music Folders");
                    require(mc.getWindow().isFullscreen(), "Fullscreen was not restored after the desktop folder dialog");
                    escape(mc);
                    require(mc.gui.screen() instanceof SettingsScreen, "Music Folders Escape did not return to settings");
                    escape(mc);
                    require(mc.gui.screen() == player, "Folder Escape navigation failed");
                    var stale = mc.gui.screen();
                    var title = new TitleScreen();
                    mc.gui.setScreen(title);
                    stale.onClose();
                    require(mc.gui.screen() == title, "Stale screen restored itself");
                }
                case 13 -> {
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                    var first = app.services().library().snapshot().tracks().getFirst();
                    app.services().playback().play(List.of(first), first);
                    mc.gui.setScreen(new HudEditorScreen(app.sources(), player));
                }
                case 14 -> {
                    escape(mc);
                    mc.gui.setScreen(new PlaylistScreen(app.sources(), player));
                }
                case 15 -> {
                    var lists = (List<RowList.Row>) SmokeUi.field(SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "lists"), RowList.class, "rows");
                    int smokeRow = lists.stream().map(RowList.Row::title).toList().indexOf(lists.stream().map(RowList.Row::title).filter(t -> t.startsWith("Smoke test (")).findFirst().orElse(""));
                    require(smokeRow >= 0, "Smoke playlist missing from Manage Playlists: " + lists.stream().map(RowList.Row::title).toList());
                    row(mc, () -> (RowList) SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "lists"), false, smokeRow, false);
                    var first = app.services().library().snapshot().tracks().getFirst();
                    app.services().playlists().add(playlistId, new Track(first.rootId(), "missing.mp3", first.file(), "Missing", "", "", 0, 0, 0, ""));
                    mc.gui.screen().tick();
                    ((SongSelection) SmokeUi.field(mc.gui.screen(), PlaylistScreen.class, "selection")).choose(musicCount, false, false);
                    mc.gui.screen().tick();
                    click(mc, "Remove Selected");
                    require(app.services().playlists().get(playlistId).entries().size() == musicCount, "Missing entry removal failed");
                }
                case 16 -> {
                    app.services().warn("Smoke transient warning");
                    SmokeUi.set(app.services().warnings(), Warnings.class, "warningUntil", 0L);
                }
                case 17 -> {
                    escape(mc);
                    click(mc, "Downloads");
                    require(mc.gui.screen() instanceof DownloadScreen, "Downloader not opened");
                    require(button(mc, "Download One Video").active == false, "Downloads enabled before analysis");
                    ((EditBox) SmokeUi.widget(mc.gui.screen(), EditBox.class, Text.get("text.darkmusic.video_or_collection_url"))).setValue("file:///invalid");
                    click(mc, "Analyze");
                }
                case 18 -> {
                    require(!app.services().downloads().snapshot().analyzing(), "Invalid URL analysis remained active");
                    require(app.services().downloads().snapshot().items().isEmpty(), "Analysis queued a download");
                    require(!app.services().downloads().snapshot().message().isBlank(), "Invalid URL has no feedback");
                    click(mc, "Tools");
                    require(mc.gui.screen() instanceof DownloadToolsScreen, "Tool settings not opened");
                }
                case 19 -> {
                    escape(mc);
                    require(mc.gui.screen() instanceof DownloadScreen, "Tool Escape navigation failed");
                    require(((EditBox) SmokeUi.widget(mc.gui.screen(), EditBox.class, Text.get("text.darkmusic.video_or_collection_url"))).getValue().equals("file:///invalid"), "URL lost across navigation");
                    click(mc, "Queue / Entries");
                    require(!button(mc, "Cancel All").active && !button(mc, "Clear Finished").active, "Empty queue actions should be disabled");
                    mc.options.guiScale().set(6);
                    mc.resizeGui();
                }
                case 20 -> {
                    click(mc, "Tools");
                    require(button(mc, "Install / Update Tools").active, "Managed setup is unavailable");
                    click(mc, "Advanced Paths");
                    require(mc.gui.screen() instanceof DownloadPathsScreen, "Advanced overrides did not open");
                    for (var child : mc.gui.screen().children())
                        if (child instanceof AbstractWidget w)
                            if (w.visible)
                                require(w.getX() >= 0 && w.getY() >= 0 && w.getRight() <= mc.gui.screen().width && w.getBottom() <= mc.gui.screen().height, "Advanced field outside compact screen");
                    escape(mc);
                }
                case 21 -> {
                    escape(mc);
                    escape(mc);
                    require(mc.gui.screen() == player, "Downloader Escape did not return to player");
                }
                case 22 -> {
                    click(mc, "Settings");
                    app.services().settings().hudVisualizer = false;
                    require(mc.gui.screen() instanceof SettingsScreen, "Unified settings did not open");
                    click(mc, "Music Spectrum");
                    require(app.services().settings().hudVisualizer, "HUD visualizer toggle failed");
                    click(mc, "Music Spectrum");
                    require(!app.services().settings().hudVisualizer, "Shared spectrum toggle did not disable");
                    click(mc, "Music Spectrum");
                }
                case 23 -> click(mc, "Edit HUD Layout");
                case 24 -> {
                    var b = HudLayout.bounds(app.services().settings(), mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 4, b.y() + 4), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() - 36, b.y() - 16), -40, -20);
                    mc.gui.screen().mouseReleased(mouse(b.x() - 36, b.y() - 16));
                    require(app.services().settings().hudX < .5 && app.services().settings().hudY < .5, "Combined HUD did not drag");
                    escape(mc);
                    escape(mc);
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                }
                case 25 -> {
                    mc.gui.setScreen(new TitleScreen());
                    app.screens().requestScreen(true);
                }
                case 26 -> {
                    require(mc.gui.screen() instanceof HudEditorScreen, "Client layout command request did not open the editor");
                    escape(mc);
                    app.screens().requestScreen(false);
                }
                case 27 -> {
                    require(mc.gui.screen() instanceof MusicScreen, "Client player command request did not open the player");
                    player = (MusicScreen) mc.gui.screen();
                    checkNavigationAndJump(app);
                    checkColors(mc, app);
                }
                default -> {
                    app.services().playback().stop();
                    LoggerFactory.getLogger("darkmusic-smoke").info("DARKMUSIC_SMOKE_PASS:" + Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip() + " command screen requests, jump to playing, removed row tooltips, row reveal, unified settings/player and HUD toggles/combined HUD drag/compact layouts, downloader navigation/invalid URL/queue/tools/layouts, bulk selection/search/cancel, speed/pause/seek, shortcuts, HUD drag/scale, Escape, layouts, actual device playback: {}", playing);
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            failed = true;
            LoggerFactory.getLogger("darkmusic-smoke").error("DARKMUSIC_SMOKE_FAIL", e);
            mc.stop();
        }
    }
}
