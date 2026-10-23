package com.darkmusic.smoke;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.audio.AudioBackend;
import com.darkmusic.client.gui.*;
import com.darkmusic.client.hud.NowPlayingHud;
import com.darkmusic.client.library.Track;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.input.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Development-only checks in an isolated title-screen client; no server connections.
 */
public final class SmokeClient implements ClientModInitializer {
    private final DesktopWindowSmoke windowChecks = new DesktopWindowSmoke();
    private final Set<String> playing = new HashSet<>();
    private int ticks, stage = -1, frame;
    private boolean selecting, fullscreenRequested, failed;
    private MusicScreen player;
    private int musicCount;
    private String playlistId;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("darkmusic.cleanupSmoke")) new CleanupSmokeClient().start();
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
            return com.darkmusic.client.text.Text.get("format.darkmusic.view", com.darkmusic.client.text.Text.get("view.darkmusic." + label.substring(6).toLowerCase(Locale.ROOT)));
        try (var input = getClass().getResourceAsStream("/assets/darkmusic/lang/en_us.json")) {
            var catalog = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : catalog.entrySet())
                if (entry.getValue().getAsString().equals(label))
                    return com.darkmusic.client.text.Text.get(entry.getKey());
        } catch (java.io.IOException e) {
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

    private Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private Object field(String name) throws Exception {
        return field(player, name);
    }

    private void checkVolumeBindings(Minecraft mc, DarkMusicClient app) throws Exception {
        require(app.bindings().length == 10, "Expected ten native bindings");
        require(app.open.getDefaultKey().getValue() == 66 && app.mute.getDefaultKey().getValue() == 77 && app.toggle.getDefaultKey().getValue() == 92, "Open/mute/play defaults are wrong");
        require(app.slower.getDefaultKey().getValue() == 263 && app.faster.getDefaultKey().getValue() == 262 && app.normalSpeed.getDefaultKey().getValue() == 320, "Speed defaults are wrong");
        require(app.next.getDefaultKey().getValue() == 93 && app.previous.getDefaultKey().getValue() == 91, "Track bracket defaults are wrong");
        require(app.volumeUp.getDefaultKey().getValue() == 265 && app.volumeDown.getDefaultKey().getValue() == 264, "Volume arrow defaults are wrong");
        var up = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.up");
        var down = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.down");
        var oldMute = app.mute.saveString();
        var oldUp = app.volumeUp.saveString();
        var oldDown = app.volumeDown.saveString();
        var oldToggle = app.toggle.saveString();
        double oldVolume = app.settings.volume;
        var tick = DarkMusicClient.class.getDeclaredMethod("tick", Minecraft.class, boolean.class);
        tick.setAccessible(true);
        try {
            app.volumeUp.setKey(up);
            app.volumeDown.setKey(down);
            app.toggle.setKey(com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
            net.minecraft.client.KeyMapping.resetMapping();
            app.mute.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f8"));
            var muteKey = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f8");
            net.minecraft.client.KeyMapping.resetMapping();
            app.volume(.5);
            net.minecraft.client.KeyMapping.click(muteKey);
            tick.invoke(app, mc, false);
            require(app.settings.volume == .5, "Mute shortcut fired in a menu");
            net.minecraft.client.KeyMapping.click(muteKey);
            tick.invoke(app, mc, true);
            require(app.settings.volume == 0 && app.settings.lastAudibleVolume == .5, "Mute did not remember audible volume");
            require(app.volumeFeedback.opacity(System.nanoTime()) > 0, "Mute feedback was not shown");
            net.minecraft.client.KeyMapping.click(muteKey);
            tick.invoke(app, mc, true);
            require(app.settings.volume == .5, "Unmute did not restore volume");
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, false);
            require(app.settings.volume == .5, "Volume shortcut fired while menus were blocked");
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.settings.volume == .55, "Volume up step failed");
            net.minecraft.client.KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.settings.volume == .5, "Volume down step failed");
            app.volume(1);
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.settings.volume == 1, "Volume exceeded 100%");
            app.volume(0);
            net.minecraft.client.KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.settings.volume == 0, "Volume dropped below zero");
            app.volume(.5);
            app.toggle.setKey(up);
            net.minecraft.client.KeyMapping.resetMapping();
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.settings.volume == .5, "Conflicting volume shortcut fired");
            require(app.warning.contains("Shortcut conflict"), "Conflicting shortcut needs a visible warning");
            app.warning = "";
            app.toggle.setKey(com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
            app.volumeUp.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f10"));
            app.save();
            require(app.volumeUp.saveString().equals("key.keyboard.f10"), "Native volume binding did not change");
        } finally {
            app.mute.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldMute));
            app.volumeUp.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldUp));
            app.volumeDown.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldDown));
            app.toggle.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldToggle));
            net.minecraft.client.KeyMapping.resetMapping();
            app.volume(oldVolume);
        }
    }

    private void checkSpeedBindings(Minecraft mc, DarkMusicClient app) throws Exception {
        String oldUp = app.faster.saveString(), oldDown = app.slower.saveString(), oldNormal = app.normalSpeed.saveString();
        double old = app.settings.speed;
        var up = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f13");
        var down = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f14");
        var normal = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f15");
        var tick = DarkMusicClient.class.getDeclaredMethod("tick", Minecraft.class, boolean.class);
        tick.setAccessible(true);
        try {
            app.faster.setKey(up);
            app.slower.setKey(down);
            app.normalSpeed.setKey(normal);
            net.minecraft.client.KeyMapping.resetMapping();
            app.speed(1);
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, false);
            require(app.settings.speed == 1, "Speed shortcut fired in a menu");
            net.minecraft.client.KeyMapping.click(up);
            tick.invoke(app, mc, true);
            require(app.settings.speed == 1.1 && app.speedFeedback.opacity(System.nanoTime()) > 0, "Speed shortcut feedback missing");
            net.minecraft.client.KeyMapping.click(down);
            tick.invoke(app, mc, true);
            require(app.settings.speed == 1, "Speed down shortcut failed");
            app.speed(1.5);
            net.minecraft.client.KeyMapping.click(normal);
            tick.invoke(app, mc, true);
            require(app.settings.speed == 1 && app.speedFeedback.opacity(System.nanoTime()) > 0, "Reset-speed feedback missing");
        } finally {
            app.faster.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldUp));
            app.slower.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldDown));
            app.normalSpeed.setKey(com.mojang.blaze3d.platform.InputConstants.getKey(oldNormal));
            net.minecraft.client.KeyMapping.resetMapping();
            app.speed(old);
        }
    }

    private void row(Minecraft mc, Object screen, String name, int index, boolean activate) throws Exception {
        var rows = (RowList) field(screen, name);
        if (!rows.visible && name.equals("navigation")) {
            SmokeUi.click(mc, com.darkmusic.client.text.Text.get("ui.darkmusic.collections"));
            rows = (RowList) field(screen, name);
        }
        int height = (int) field(rows, "rowHeight");
        rows.onClick(mouse(rows.getX() + 4, rows.getY() + index * height + 4), activate);
    }

    private void view(Minecraft mc, String name) throws Exception {
        SmokeUi.library(mc);
        for (int i = 0; i < 4 && !field("mode").equals(name); i++) {
            String compact = "View: " + field("mode");
            boolean cycle = mc.gui.screen().children().stream().anyMatch(x -> x instanceof AbstractWidget b && b.getMessage().getString().equals(translated(compact)));
            click(mc, cycle ? compact : name);
        }
        require(field("mode").equals(name), "View did not switch to " + name);
    }

    private void checkPlaylistBrowsing(Minecraft mc, DarkMusicClient app) throws Exception {
        var tracks = app.library.snapshot().tracks();
        var first = tracks.getFirst();
        var second = tracks.get(1);
        require(field("group").equals(playlistId), "First playlist was not selected");
        app.playlists.removeAll(playlistId, tracks.stream().map(Track::id).collect(java.util.stream.Collectors.toSet()));
        app.playlists.addAll(playlistId, List.of(second, first));
        player.tick();
        require(field("visible").equals(List.of(second, first)), "Playlist order was lost");
        String duplicate = app.playlists.create("Smoke test");
        app.playlists.add(duplicate, first);
        player.tick();
        row(mc, player, "navigation", 1, false);
        require(field("group").equals(duplicate) && field("visible").equals(List.of(first)), "Duplicate names confused selection");
        app.playlists.rename(duplicate, "Renamed");
        player.tick();
        require(field("group").equals(duplicate), "Rename lost selection");
        app.playlists.delete(duplicate);
        player.tick();
        require(field("group").equals(playlistId), "Deletion did not select remaining playlist");
        var search = (EditBox) field("search");
        search.setValue(second.relativePath());
        require(field("visible").equals(List.of(second)), "Playlist search failed");
        row(mc, player, "songs", 0, true);
        require(app.playback.state().track().id().equals(second.id()), "Playlist playback failed");
        search.setValue("");
        app.playlists.add(playlistId, new Track(first.rootId(), "missing.mp3", first.file(), "Missing", "", "", 0, 0, 0, ""));
        player.tick();
        require(field("playlistNotice").toString().equals(com.darkmusic.client.text.Text.get("format.darkmusic.missing_entries", 1)), "Missing entry notice absent");
        app.playlists.remove(playlistId, 2);
        String empty = app.playlists.create("Empty");
        player.tick();
        row(mc, player, "navigation", 1, false);
        require(((List<?>) field("visible")).isEmpty(), "Empty playlist exposed all songs");
        app.playlists.delete(empty);
        app.playlists.delete(playlistId);
        player.tick();
        require(((List<?>) field("visible")).isEmpty(), "No-playlists state exposed library");
        playlistId = app.playlists.create("Smoke test");
        app.playlists.addAll(playlistId, tracks);
        player.tick();
    }

    private void checkNavigationAndJump(DarkMusicClient app) throws Exception {
        var search = (EditBox) field("search");
        var current = app.playback.state().track();
        require(current != null, "Jump test needs current playback");
        var visible = (java.util.List<?>) field("visible");
        require(visible.stream().anyMatch(t -> ((Track) t).id().equals(current.id())), "Playing track missing before filtering");
        search.setValue("No matching title for navigation check");
        require(((java.util.List<?>) field("visible")).isEmpty(), "Search did not hide playing track");
        player.jumpToPlaying();
        var selected = (Track) field("selected");
        require(selected.id().equals(current.id()), "Jump did not select the currently playing track");
        require(((EditBox) field("search")).getValue().isEmpty(), "Jump did not clear incompatible search");
        require(app.playback.state().track().id().equals(current.id()), "Jump changed playback");
        var sourceField = app.playback.getClass().getDeclaredField("source");
        sourceField.setAccessible(true);
        var originalSource = app.playback.source();
        for (var origin : List.of(new com.darkmusic.client.audio.PlaybackController.Source("All", "", "", app.library.rootId()),
            new com.darkmusic.client.audio.PlaybackController.Source("All", current.relativePath().contains("/") ? current.relativePath().substring(0, current.relativePath().lastIndexOf('/')) : "", "", app.library.rootId()),
            new com.darkmusic.client.audio.PlaybackController.Source("Playlists", playlistId, "", app.library.rootId()),
            new com.darkmusic.client.audio.PlaybackController.Source("Artists", current.artist(), current.title(), app.library.rootId()),
            new com.darkmusic.client.audio.PlaybackController.Source("Albums", current.album(), "", app.library.rootId()))) {
            sourceField.set(app.playback, origin);
            ((EditBox) field("search")).setValue("No matches before source jump");
            player.jumpToPlaying();
            require(field("mode").equals(origin.mode()) && field("group").equals(origin.group()), "Jump lost playback source: " + origin);
            require(((EditBox) field("search")).getValue().equals(origin.query()), "Jump lost source search");
            require(((Track) field("selected")).id().equals(current.id()), "Source jump selected a different track");
            var groups = (List<?>) field("groups");
            var rows = (List<RowList.Row>) field(field("navigation"), "rows");
            var tracks = app.library.snapshot().tracks();
            long count = origin.mode().equals("Playlists") ? app.playlists.resolve(origin.group(), tracks).size() : tracks.stream().filter(track -> switch (origin.mode()) {
                case "Artists" -> track.artist().equals(origin.group());
                case "Albums" -> track.album().equals(origin.group());
                default -> com.darkmusic.client.gui.FolderTree.includes(origin.group(), track);
            }).count();
            require(rows.get(groups.indexOf(origin.group())).title().endsWith(" (" + count + ")"), "Navigation count differs from total playable tracks: " + origin);
        }
        sourceField.set(app.playback, originalSource);
        player.jumpToPlaying();
        var list = (RowList) field("songs");
        require(com.darkmusic.client.text.Text.get("text.darkmusic.scroll_long_text").equals("text.darkmusic.scroll_long_text"), "Long-text hover hint remains translated");
        var originalRows = (java.util.List<RowList.Row>) field(list, "rows");
        list.rows(java.util.stream.IntStream.range(0, 100).mapToObj(i -> new RowList.Row(current.title(), "")).toList());
        list.reveal(99);
        require(list.selected() == 99 && (int) field(list, "scroll") > 0, "Jump did not scroll the target row into view");
        list.rows(originalRows);
    }

    private void checkColors(Minecraft mc, DarkMusicClient app) throws Exception {
        var settings = new SettingsScreen(app, player);
        mc.gui.setScreen(settings);
        click(mc, "Spectrum Colors");
        require(mc.gui.screen() instanceof ColorsScreen, "Spectrum colors did not open");
        click(mc, "Aurora Borealis");
        require(app.settings.spectrumColors.preset == com.darkmusic.client.config.VisualColors.Preset.AURORA, "Preset did not apply");
        var start = (EditBox) field(mc.gui.screen(), "start");
        var end = (EditBox) field(mc.gui.screen(), "end");
        start.setValue("#55AAFF");
        end.setValue("#FFAACC");
        require(app.settings.spectrumColors.color(0) == 0xFF55AAFF && app.settings.spectrumColors.color(1) == 0xFFFFAACC, "Custom mixed colors failed");
        start.setValue("#xyz");
        require(app.settings.spectrumColors.color(0) == 0xFF55AAFF, "Invalid color changed the last valid color");
        click(mc, "Moonlight");
        escape(mc);
        require(mc.gui.screen() == settings, "Colors Escape did not return to Settings");
        click(mc, "Progress Bar Colors");
        click(mc, "Neon Dream");
        require(app.settings.progressColors.preset == com.darkmusic.client.config.VisualColors.Preset.NEON_DREAM && app.settings.spectrumColors.preset == com.darkmusic.client.config.VisualColors.Preset.MOONLIGHT, "Progress and spectrum colors are not independent");
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
            if (failed || app == null || app.library == null || !app.playlistsReady || !mc.isGameLoadFinished() || mc.gui.overlay() != null)
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
                app.chooseDirectory(Path.of(System.getProperty("darkmusic.music")), () -> {
                });
                return;
            }
            if (app.library.snapshot().scanning() || app.library.snapshot().tracks().size() < 3) return;
            musicCount = app.library.snapshot().tracks().size();
            var audio = app.playback.state();
            if (audio.status() == AudioBackend.Status.ERROR || !app.warning.isBlank())
                throw new IllegalStateException("Audio/library error: " + app.warning + " " + audio.error());
            if (audio.status() == AudioBackend.Status.PLAYING && audio.position() > 0)
                playing.add(audio.track().relativePath());
            if (stage == -1) {
                require(mc.level == null && mc.player == null, "Smoke test stays at title screen");
                if (Boolean.getBoolean("darkmusic.testNativeBindings"))
                    require(app.volumeUp.saveString().equals("key.keyboard.f10"), "Legacy config overrode the native controls binding");
                mc.gui.setScreen(new TitleScreen());
                app.open();
                player = (MusicScreen) mc.gui.screen();
                app.playlists.all().stream().map(p -> p.id()).toList().forEach(app.playlists::delete);
                playlistId = app.playlists.create("Smoke test");
                app.playlists.add(playlistId, app.library.snapshot().tracks().getFirst());
                app.settings.hudArtwork = true;
                app.settings.hudX = app.settings.hudY = 0.0;
                app.settings.hudScale = 1;
                app.settings.hudVisualizer = false;

                app.configureVisualizer();
                app.playback.shuffle(false);
                app.playback.repeat(com.darkmusic.client.audio.PlaybackController.Repeat.OFF);
                app.speed(1);
                app.playback.volume(.05);
                app.playback.play(app.library.snapshot().tracks(), app.library.snapshot().tracks().getFirst());
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
                    var coverTrack = app.library.snapshot().tracks().stream().filter(t -> t.relativePath().contains("Departure")).findFirst();
                    coverTrack.ifPresent(app.artwork::get);
                    var slider = (ValueSlider) field("progress");
                    var backendField = app.playback.getClass().getDeclaredField("audio");
                    backendField.setAccessible(true);
                    var backend = backendField.get(app.playback);
                    var generationField = backend.getClass().getDeclaredField("generation");
                    generationField.setAccessible(true);
                    var generation = (java.util.concurrent.atomic.AtomicLong) generationField.get(backend);
                    long before = generation.get();
                    double y = slider.getY() + 8;
                    mc.gui.screen().mouseClicked(mouse(slider.getX() + 12, y), false);
                    for (int i = 0; i < 5; i++)
                        mc.gui.screen().mouseDragged(mouse(slider.getX() + 15 + i * 2, y), 2, 0);
                    require(generation.get() == before, "Seek drag restarted the decoder before release");
                    mc.gui.screen().mouseReleased(mouse(slider.getX() + 23, y));
                    require(generation.get() == before + 1, "Seek release must issue exactly one command");
                    app.playback.pause();
                    click(mc, "Settings");
                }
                case 1 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED, "Pause failed");
                    app.playback.seek(.5);
                    if (!app.settings.lyricsEnabled) click(mc, "Enable Lyrics");
                    click(mc, "Enable Lyrics");
                    require(!app.settings.lyricsEnabled, "Global lyrics toggle did not disable lyrics");
                    require(mc.gui.screen().children().stream().filter(w -> w instanceof AbstractWidget).map(w -> ((AbstractWidget) w).getMessage().getString()).noneMatch(text -> text.equals("Lyrics") || text.equals("Lyrics in HUD")), "Disabled lyrics controls remained visible");
                    click(mc, "Enable Lyrics");
                    require(app.settings.lyricsEnabled, "Global lyrics toggle did not re-enable lyrics");
                }
                case 2 -> {
                    require(audio.status() == AudioBackend.Status.PAUSED && audio.position() >= .49, "Paused seek failed");
                    app.speed(1.5);
                    require(app.playback.speed() == 1.5, "Speed did not apply");
                    app.playback.toggle();
                    click(mc, "HUD Artwork");
                    require(!app.settings.hudArtwork, "Artwork toggle failed");
                    click(mc, "HUD Artwork");
                    click(mc, "Edit HUD Layout");
                }
                case 3 -> {
                    require(mc.gui.screen() instanceof HudEditorScreen, "HUD editor not opened");
                    var b = NowPlayingHud.bounds(app, mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 5, b.y() + 5), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() + 105, b.y() + 85), 100, 80);
                    mc.gui.screen().mouseReleased(mouse(b.x() + 105, b.y() + 85));
                    require(app.settings.hudX > 0 && app.settings.hudY > 0, "HUD did not drag");
                    var resized = NowPlayingHud.bounds(app, mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseScrolled(resized.x() + 5, resized.y() + 5, 0, 1);
                    require(app.settings.hudScale == 1.1, "Scale did not change by 0.1");
                    require(mc.gui.screen().children().stream().noneMatch(x -> x instanceof ValueSlider), "HUD scale slider was not removed");
                    var spectrum = com.darkmusic.client.hud.HudLayout.bounds(app.settings, mc.gui.screen().width, mc.gui.screen().height);
                    double previousScale = app.settings.hudScale;
                    mc.gui.screen().mouseScrolled(spectrum.x() + spectrum.width() - 3, spectrum.y() + 3, 0, 1);
                    require(app.settings.hudScale == Math.round((previousScale + .1) * 10) / 10.0, "Combined editor did not scale the visualizer");
                    require(app.settings.hudScale == 1.2, "Combined spectrum did not share the HUD scale");
                }
                case 4 -> {
                    escape(mc);
                    require(mc.gui.screen() instanceof SettingsScreen, "Escape did not return from HUD editor");
                }
                case 5 -> {
                    require(Arrays.asList(mc.options.keyMappings).containsAll(Arrays.asList(app.bindings())), "Native controls are missing DarkMusic bindings");
                    checkVolumeBindings(mc, app);
                    checkSpeedBindings(mc, app);
                    app.faster.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.f9"));
                    app.save();
                    require(app.faster.saveString().equals("key.keyboard.f9"), "Native speed binding did not change");
                    app.faster.setKey(com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
                    net.minecraft.client.KeyMapping.resetMapping();
                    app.save();
                    escape(mc);
                    require(mc.gui.screen() == player, "Escape did not return to player");
                    click(mc, "Manage Playlists");
                }
                case 6 -> {
                    require(mc.gui.screen() instanceof PlaylistScreen, "Editor not opened");
                    var coverTrack = app.library.snapshot().tracks().stream().filter(t -> t.relativePath().contains("Departure")).findFirst();
                    if (coverTrack.isPresent())
                        require(app.artwork.get(coverTrack.get()) != null, "Real artwork did not become a Minecraft texture");
                    var rows = (List<RowList.Row>) field(field(mc.gui.screen(), "lists"), "rows");
                    require(rows.getFirst().title().endsWith(" (" + app.playlists.resolve(playlistId, app.library.snapshot().tracks()).size() + ")"), "Manage Playlists count differs from playable tracks");
                    click(mc, "Add Songs");
                }
                case 7 -> {
                    require(mc.gui.screen() instanceof TrackPickerScreen, "Picker not opened");
                    click(mc, "Select All");
                    var selection = (SongSelection) field(mc.gui.screen(), "selection");
                    require(selection.selected().size() == musicCount - 1, "Select All did not exclude the existing song");
                    var search = (EditBox) field(mc.gui.screen(), "search");
                    search.setValue(app.library.snapshot().tracks().get(1).relativePath());
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
                    require(app.playlists.get(playlistId).entries().size() == musicCount, "Bulk add did not deduplicate");
                    row(mc, mc.gui.screen(), "entries", 0, false);
                    mc.gui.screen().tick();
                    click(mc, "Remove Selected");
                    require(app.playlists.get(playlistId).entries().size() == musicCount - 1, "Bulk removal failed");
                    click(mc, "Add Songs");
                    click(mc, "Select All");
                    escape(mc);
                    require(app.playlists.get(playlistId).entries().size() == musicCount - 1, "Cancelled picker changed playlist");
                    app.playlists.addAll(playlistId, app.library.snapshot().tracks());
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
                    click(mc, "1.0x");
                    require(app.settings.speed == 1, "Speed reset failed");
                    var speed = (ValueSlider) field("speed");
                    speed.setFocused(true);
                    speed.keyPressed(new KeyEvent(262, 0, 0));
                    require(app.settings.speed == 1.1, "Speed slider step failed");
                    speed.keyPressed(new KeyEvent(263, 0, 0));
                    require(app.settings.speed == 1, "Speed slider reverse step failed");
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                }
                case 11 -> {
                    click(mc, "Settings");
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        var closer = new javax.swing.Timer(300, event -> {
                            if (!DesktopFolderPicker.isOpen()) ((javax.swing.Timer) event.getSource()).stop();
                            else DesktopFolderPicker.cancel();
                        });
                        closer.start();
                    });
                    click(mc, "Choose Music Folder");
                }
                case 12 -> {
                    require(mc.gui.screen() instanceof SettingsScreen && !DesktopFolderPicker.isOpen(), "Folder dialog did not cancel back to settings");
                    require(mc.getWindow().isFullscreen(), "Fullscreen was not restored after the desktop folder dialog");
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
                    var first = app.library.snapshot().tracks().getFirst();
                    app.playback.play(List.of(first), first);
                    mc.gui.setScreen(new HudEditorScreen(app, player));
                }
                case 14 -> {
                    escape(mc);
                    mc.gui.setScreen(new PlaylistScreen(app, player));
                }
                case 15 -> {
                    var first = app.library.snapshot().tracks().getFirst();
                    app.playlists.add(playlistId, new Track(first.rootId(), "missing.mp3", first.file(), "Missing", "", "", 0, 0, 0, ""));
                    mc.gui.screen().tick();
                    ((SongSelection) field(mc.gui.screen(), "selection")).choose(musicCount, false, false);
                    mc.gui.screen().tick();
                    click(mc, "Remove Selected");
                    require(app.playlists.get(playlistId).entries().size() == musicCount, "Missing entry removal failed");
                }
                case 16 -> {
                    app.warn("Smoke transient warning");
                    var expiry = DarkMusicClient.class.getDeclaredField("warningUntil");
                    expiry.setAccessible(true);
                    expiry.setLong(app, 0);
                }
                case 17 -> {
                    escape(mc);
                    click(mc, "Downloads");
                    require(mc.gui.screen() instanceof DownloadScreen, "Downloader not opened");
                    require(button(mc, "Download One Video").active == false, "Downloads enabled before analysis");
                    ((EditBox) field(mc.gui.screen(), "url")).setValue("file:///invalid");
                    click(mc, "Analyze");
                }
                case 18 -> {
                    require(!app.downloads.snapshot().analyzing(), "Invalid URL analysis remained active");
                    require(app.downloads.snapshot().items().isEmpty(), "Analysis queued a download");
                    require(!app.downloads.snapshot().message().isBlank(), "Invalid URL has no feedback");
                    click(mc, "Tools");
                    require(mc.gui.screen() instanceof DownloadToolsScreen, "Tool settings not opened");
                }
                case 19 -> {
                    escape(mc);
                    require(mc.gui.screen() instanceof DownloadScreen, "Tool Escape navigation failed");
                    require(((EditBox) field(mc.gui.screen(), "url")).getValue().equals("file:///invalid"), "URL lost across navigation");
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
                    app.settings.hudVisualizer = false;
                    require(mc.gui.screen() instanceof SettingsScreen, "Unified settings did not open");
                    click(mc, "Music Spectrum");
                    require(app.settings.hudVisualizer, "HUD visualizer toggle failed");
                    click(mc, "Music Spectrum");
                    require(!app.settings.hudVisualizer, "Shared spectrum toggle did not disable");
                    click(mc, "Music Spectrum");
                }
                case 23 -> click(mc, "Edit HUD Layout");
                case 24 -> {
                    var b = com.darkmusic.client.hud.HudLayout.bounds(app.settings, mc.gui.screen().width, mc.gui.screen().height);
                    mc.gui.screen().mouseClicked(mouse(b.x() + 4, b.y() + 4), false);
                    mc.gui.screen().mouseDragged(mouse(b.x() - 36, b.y() - 16), -40, -20);
                    mc.gui.screen().mouseReleased(mouse(b.x() - 36, b.y() - 16));
                    require(app.settings.hudX < .5 && app.settings.hudY < .5, "Combined HUD did not drag");
                    escape(mc);
                    escape(mc);
                    mc.options.guiScale().set(2);
                    mc.resizeGui();
                }
                case 25 -> {
                    mc.gui.setScreen(new TitleScreen());
                    app.requestScreen(true);
                }
                case 26 -> {
                    require(mc.gui.screen() instanceof HudEditorScreen, "Client layout command request did not open the editor");
                    escape(mc);
                    app.requestScreen(false);
                }
                case 27 -> {
                    require(mc.gui.screen() instanceof MusicScreen, "Client player command request did not open the player");
                    player = (MusicScreen) mc.gui.screen();
                    checkNavigationAndJump(app);
                    checkColors(mc, app);
                }
                default -> {
                    app.playback.stop();
                    org.slf4j.LoggerFactory.getLogger("darkmusic-smoke").info("DARKMUSIC_SMOKE_PASS:" + java.nio.file.Files.readString(Path.of(System.getProperty("darkmusic.smokeRunFile"))).strip() + " command screen requests, jump to playing, removed row tooltips, row reveal, unified settings/player and HUD toggles/combined HUD drag/compact layouts, downloader navigation/invalid URL/queue/tools/layouts, bulk selection/search/cancel, speed/pause/seek, shortcuts, HUD drag/scale, Escape, layouts, actual device playback: {}", playing);
                    mc.stop();
                }
            }
        } catch (Throwable e) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger("darkmusic-smoke").error("DARKMUSIC_SMOKE_FAIL", e);
            mc.stop();
        }
    }
}
