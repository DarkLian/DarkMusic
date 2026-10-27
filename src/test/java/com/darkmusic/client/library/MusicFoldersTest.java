package com.darkmusic.client.library;

import com.darkmusic.client.backup.BackupArchive;
import com.darkmusic.client.config.*;
import com.darkmusic.client.gui.FolderTree;
import com.darkmusic.client.playlist.PlaylistStore;
import com.darkmusic.client.testing.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MusicFoldersTest {
    @Test
    void sameNamedFoldersUseTheShortestDistinctPathSuffix() {
        Path a = Path.of("one", "music"), b = Path.of("two", "music");
        assertEquals(a.toString(), MusicFolders.label(a, List.of(a, b)));
        assertEquals(b.toString(), MusicFolders.label(b, List.of(a, b)));
        assertEquals("music", MusicFolders.label(a, List.of(a)));
    }

    @TempDir
    Path directory;

    @Test
    void migrationAndDestinationChangesPreserveRegisteredRootsAndIdentities() throws Exception {
        Path original = directory.resolve("collection"), other = directory.resolve("other"), builtIn = directory.resolve("default");
        var s = new Settings();
        s.musicDirectory = original.toString();
        s.roots.put(original.toString(), "existing");
        var first = MusicFolders.resolve(s, builtIn);
        assertEquals(Map.of(original, "existing"), first);
        s.musicFolders = List.of(original.toString(), other.toString());
        s.musicDirectory = other.toString();
        var second = MusicFolders.resolve(s, builtIn);
        assertEquals(List.of(original, other), new ArrayList<>(second.keySet()));
        assertEquals("existing", second.get(original));
        assertEquals(2, second.size());
        s.musicDirectory = "";
        var third = MusicFolders.resolve(s, builtIn);
        assertEquals(List.of(original, other, builtIn), new ArrayList<>(third.keySet()));
        assertEquals(3, third.size());
        assertEquals("existing", third.get(original));
        assertTrue(MusicFolders.overlaps(third.keySet(), original.resolve("nested")));
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(s), Settings.class);
        assertEquals(third, MusicFolders.resolve(restored, builtIn));
    }

    @Test
    void additionalFoldersAndSaveDestinationChangesKeepRegistrationOrder() {
        var settings = new Settings();
        Path builtIn = directory.resolve("default");
        var expected = new ArrayList<Path>();
        expected.add(builtIn);
        MusicFolders.resolve(settings, builtIn);
        for (int index = 1; index <= 5; index++) {
            Path folder = directory.resolve("folder-" + index);
            expected.add(folder);
            var registered = new ArrayList<>(settings.musicFolders);
            registered.add(folder.toString());
            settings.musicFolders = registered;
            assertEquals(expected, new ArrayList<>(MusicFolders.resolve(settings, builtIn).keySet()));
        }
        var identities = new LinkedHashMap<>(settings.roots);
        for (Path destination : expected.reversed()) {
            settings.musicDirectory = destination.toString();
            assertEquals(expected, new ArrayList<>(MusicFolders.resolve(settings, builtIn).keySet()));
            assertEquals(identities, settings.roots);
        }
        var restored = ConfigStore.JSON.fromJson(ConfigStore.JSON.toJson(settings), Settings.class);
        assertEquals(expected, new ArrayList<>(MusicFolders.resolve(restored, builtIn).keySet()));
    }

    @Test
    void overlappingLegacyFoldersDoNotDisplaceTheSaveDestination() {
        var settings = new Settings();
        Path destination = directory.resolve("nested");
        settings.musicDirectory = destination.toString();
        settings.musicFolders = List.of(directory.toString(), destination.toString());
        assertEquals(List.of(destination), new ArrayList<>(MusicFolders.resolve(settings, directory).keySet()));
    }

    @Test
    void treeAndBackupKeepIdenticalRelativePathsInDifferentRootsSeparate() throws Exception {
        var a = new Track("a", "nested/song.opus", directory.resolve("first/nested/song.opus"), "", "", "", 10, 1, 1, "");
        var b = new Track("b", "nested/song.opus", directory.resolve("second/nested/song.opus"), "", "", "", 10, 1, 1, "");
        var roots = new LinkedHashMap<Path, String>();
        roots.put(directory.resolve("first"), "a");
        roots.put(directory.resolve("second"), "b");
        var tree = new FolderTree(List.of(a, b), List.of("@a/nested", "@b/nested"), roots);
        assertEquals(2, tree.visible(Set.of()).size());
        assertEquals(4, tree.visible(Set.of("@a", "@b")).size());
        assertTrue(FolderTree.includes("@a/nested", a));
        assertFalse(FolderTree.includes("@a/nested", b));
        assertTrue(FolderTree.includes("@b", b));
        assertEquals("first/nested", tree.display("@a/nested"));
        var s = new Settings();
        s.musicFolders = roots.keySet().stream().map(Path::toString).toList();
        roots.forEach((p, id) -> s.roots.put(p.toString(), id));
        var playlist = new PlaylistStore.Playlist("list", "Both", List.of(PlaylistStore.Entry.of(a), PlaylistStore.Entry.of(b)));
        var data = BackupArchive.snapshot(s, "a", List.of(playlist), Map.of());
        assertFalse(data.settings().has("musicFolders"));
        Path archive = directory.resolve("setup.zip");
        BackupArchive.write(archive, data);
        var loaded = BackupArchive.read(archive);
        var restored = BackupArchive.prepare(loaded, new BackupArchive.Selection(true, true, true, true, true, true), s, "b", List.of(a, b), List.of(), Map.of(), Set.of());
        assertEquals(playlist.entries(), restored.playlists().getFirst().entries());
        assertEquals(s.musicFolders, restored.settings().musicFolders);
    }

    @Test
    void oneUnavailableRootDoesNotBlockOtherScansOrQualifyForPlaylistPruning() throws Exception {
        Path available = Files.createDirectories(directory.resolve("available/nested")), offline = directory.resolve("offline");
        try (var library = new MusicLibrary(directory.resolve("config"))) {
            library.setRoots(Map.of(available.getParent(), "online", offline, "offline"), available.getParent());
            Await.until(Duration.ofSeconds(5), () -> !library.snapshot().scanning(), "scan finished");
            var snapshot = library.snapshot();
            assertFalse(snapshot.scanning());
            assertEquals(Set.of("online"), snapshot.completeRoots());
            assertFalse(snapshot.complete());
            assertTrue(snapshot.folders().contains("@online/nested"));
            var entry = new PlaylistStore.Entry("offline", "kept.opus");
            assertTrue(PlaylistStore.missing(offline, "offline", List.of(new PlaylistStore.Playlist("p", "Kept", List.of(entry))), snapshot.tracks()).isEmpty());
        }
    }
}
