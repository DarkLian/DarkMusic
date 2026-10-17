package com.darkmusic.client;

import com.darkmusic.client.command.LocalCommands;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.hud.TitleMarquee;
import com.darkmusic.client.library.Track;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NavigationAndCommandsTest {
    private Track track(String path) {
        return new Track("root", path, Path.of(path), "Title", "Artist", "Album", 1, 1, 0, "");
    }

    @Test
    void foldersStartCollapsedExpandByPathAndIncludeEmptyDirectories() {
        var tracks = List.of(track("A/First.mp3"), track("A/Nested/Next.mp3"), track("AB/Other.mp3"), track("Root.mp3"));
        var tree = new FolderTree(tracks, List.of("Empty/Also Empty"));
        assertEquals(List.of("A", "AB", "Empty"), tree.visible(Set.of()).stream().map(FolderTree.Entry::path).toList());
        assertTrue(tree.visible(Set.of()).getFirst().children());
        assertFalse(tree.visible(Set.of()).getFirst().expanded());
        assertEquals(List.of("A", "A/Nested", "AB", "Empty", "Empty/Also Empty"), tree.visible(Set.of("A", "Empty")).stream().map(FolderTree.Entry::path).toList());
        assertTrue(FolderTree.includes("A", tracks.get(1)));
        assertFalse(FolderTree.includes("A", tracks.get(2)));
        assertTrue(FolderTree.includes("", tracks.get(3)));
        assertTrue(tree.contains("Empty/Also Empty"));
        assertFalse(tree.contains("Missing"));
        var distinct = new FolderTree(List.of(track("a/file.mp3"), track("A/file.mp3")));
        assertEquals(2, distinct.visible(Set.of()).size(), "Case-distinct folders must survive on Linux");
    }

    @Test
    void librarySnapshotIncludesEmptyAndNestedFoldersWithoutRenderThreadFileAccess(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path music = java.nio.file.Files.createDirectories(temp.resolve("music"));
        java.nio.file.Files.createDirectories(music.resolve("Empty/Nested"));
        java.nio.file.Files.createDirectories(music.resolve("Music/Nested"));
        java.nio.file.Files.copy(TestMusic.featured(), music.resolve("Music/Nested/Track.mp3"));
        try (var library = new com.darkmusic.client.library.MusicLibrary(temp.resolve("config"))) {
            library.setRoot(music, "navigation");
            DownloadTest.await(() -> !library.snapshot().scanning());
            assertEquals(1, library.snapshot().tracks().size());
            var tree = new FolderTree(library.snapshot().tracks(), library.snapshot().folders());
            assertEquals(List.of("Empty", "Music"), tree.visible(Set.of()).stream().map(FolderTree.Entry::path).toList());
            assertTrue(tree.contains("Empty/Nested"));
            assertTrue(tree.contains("Music/Nested"));
        }
    }

    @Test
    void overflowingTitlesMoveLeftAndLoopWhileShortTitlesRemainStill() {
        assertEquals(0, TitleMarquee.offset(100, 120, 10_000_000_000L));
        assertEquals(0, TitleMarquee.offset(100, 40, 900_000_000L));
        assertEquals(24, TitleMarquee.offset(100, 40, 2_000_000_000L));
        assertEquals(0, TitleMarquee.offset(100, 40, 6_500_000_000L), .00001);
        assertEquals(12, TitleMarquee.offset(100, 40, 7_000_000_000L), .00001);
    }

    @Test
    void aliasesRunOnlyClientActionsAndHelpProducesLocalFeedbackLinks() throws Exception {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        var player = new AtomicInteger();
        var layout = new AtomicInteger();
        var messages = new ArrayList<Component>();
        FabricClientCommandSource source = (FabricClientCommandSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{FabricClientCommandSource.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "attended" -> true;
                case "sendFeedback" -> {
                    messages.add((Component) args[0]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected command interaction: " + method.getName());
            };
        });
        LocalCommands.register(dispatcher, player::incrementAndGet, layout::incrementAndGet);
        for (String alias : List.of("da", "darkmusic")) {
            assertEquals(1, dispatcher.execute(alias, source));
            assertEquals(1, dispatcher.execute(alias + " gui", source));
            assertEquals(1, dispatcher.execute(alias + " help", source));
        }
        assertEquals(2, player.get());
        assertEquals(2, layout.get());
        assertEquals(6, messages.size());
        var links = messages.get(2).getSiblings().stream().filter(c -> c.getStyle().getClickEvent() != null).toList();
        assertEquals(2, links.size());
        assertInstanceOf(net.minecraft.network.chat.ClickEvent.OpenUrl.class, messages.get(2).getStyle().getClickEvent());
        for (var link : links)
            assertInstanceOf(net.minecraft.network.chat.ClickEvent.OpenUrl.class, link.getStyle().getClickEvent());
    }
}
