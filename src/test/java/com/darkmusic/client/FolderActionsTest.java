package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.MusicLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Folder choices check the folder on the browse pool and change the settings on the client thread. Both queues are
 * driven by hand here, so a reset or restore can land between the check and its result. Every folder is a temporary
 * directory; the built-in music folder is the temporary config's.
 */
class FolderActionsTest {
    @TempDir
    Path temp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Runnable> browsing = new ArrayDeque<>(), client = new ArrayDeque<>();
    private final List<String> warnings = new ArrayList<>();
    private final ClientState state = new ClientState();
    private int saves;
    private final SettingsStore store = new SettingsStore(s -> {
        saves++;
        return CompletableFuture.completedFuture(null);
    }, () -> true, () -> {
    });
    private ConfigStore config;
    private MusicLibrary library;
    private FolderActions folders;

    @BeforeEach
    void open() throws Exception {
        config = new ConfigStore(Files.createDirectories(temp.resolve("config")), io, warnings::add);
        library = new MusicLibrary(temp.resolve("data"));
        store.replace(new Settings());
        folders = new FolderActions(new FolderActions.Inputs() {
            public ConfigStore config() {
                return config;
            }

            public MusicLibrary library() {
                return library;
            }

            public Future<?> browse(Runnable task) {
                browsing.add(task);
                return CompletableFuture.completedFuture(null);
            }

            public void warn(String text) {
                warnings.add(text);
            }
        }, state, store, client::add);
    }

    @AfterEach
    void close() {
        library.close();
        io.shutdownNow();
    }

    private void drain() {
        while (!browsing.isEmpty() || !client.isEmpty()) {
            while (!browsing.isEmpty()) browsing.poll().run();
            while (!client.isEmpty()) client.poll().run();
        }
    }

    @Test
    void choosingOnFreshSettingsActivatesTheFolder() throws Exception {
        assertNull(store.settings().musicFolders, "fresh settings have no folder list");
        Path chosen = Files.createDirectories(temp.resolve("chosen"));
        var done = new boolean[1];
        folders.choose(chosen, () -> done[0] = true);
        drain();
        assertTrue(done[0]);
        String canonical = chosen.toRealPath().toString();
        assertEquals(canonical, store.settings().musicDirectory);
        assertTrue(store.settings().musicFolders.contains(canonical));
        assertTrue(library.roots().containsKey(chosen.toRealPath()));
        assertEquals(1, saves);
    }

    @Test
    void aChoiceThatFinishesAfterAResetOrRestoreIsDropped() throws Exception {
        Path chosen = Files.createDirectories(temp.resolve("chosen"));
        var done = new boolean[1];
        folders.choose(chosen, () -> done[0] = true);
        browsing.poll().run();
        folders.invalidate();
        drain();
        assertFalse(done[0]);
        assertEquals("", store.settings().musicDirectory);
        assertEquals(0, saves);
        folders.add(chosen);
        browsing.poll().run();
        folders.invalidate();
        drain();
        assertNull(store.settings().musicFolders);
        folders.reset();
        browsing.poll().run();
        folders.invalidate();
        drain();
        assertEquals(0, saves);
    }

    @Test
    void aChoiceWaitsForMediaWorkAndAResultIsDroppedWhileBusy() throws Exception {
        Path chosen = Files.createDirectories(temp.resolve("chosen"));
        state.mediaBusy = true;
        folders.choose(chosen, () -> fail("chosen while busy"));
        assertTrue(browsing.isEmpty());
        assertEquals(1, warnings.size());
        state.mediaBusy = false;
        folders.choose(chosen, () -> fail("applied while busy"));
        browsing.poll().run();
        state.mediaBusy = true;
        drain();
        assertEquals("", store.settings().musicDirectory);
        assertFalse(folders.remove(chosen), "removing waits for media work too");
    }

    @Test
    void addAndRemoveKeepTheDestinationAndWorkWithoutAFolderList() throws Exception {
        folders.activate(config.defaultMusic());
        Path extra = Files.createDirectories(temp.resolve("extra"));
        folders.add(extra);
        drain();
        String canonical = extra.toRealPath().toString();
        assertTrue(store.settings().musicFolders.contains(canonical));
        assertTrue(library.roots().containsKey(extra.toRealPath()));
        assertFalse(folders.remove(library.root()), "the destination folder stays");
        assertTrue(folders.remove(extra.toRealPath()));
        assertFalse(store.settings().musicFolders.contains(canonical));
        assertFalse(library.roots().containsKey(extra.toRealPath()));
    }

    @Test
    void anOverlappingFolderIsRefused() throws Exception {
        Path outer = Files.createDirectories(temp.resolve("outer"));
        Path inner = Files.createDirectories(outer.resolve("inner"));
        store.settings().musicDirectory = outer.toRealPath().toString();
        folders.activate(outer.toRealPath());
        folders.add(inner);
        drain();
        assertFalse(store.settings().musicFolders.contains(inner.toRealPath().toString()));
        assertFalse(warnings.isEmpty());
    }

    @Test
    void resetChoosesTheBuiltInFolderInsideTheConfig() throws Exception {
        Path chosen = Files.createDirectories(temp.resolve("chosen"));
        folders.choose(chosen, () -> {
        });
        drain();
        folders.reset();
        drain();
        assertTrue(config.defaultMusic().startsWith(temp));
        assertTrue(Files.isDirectory(config.defaultMusic()));
        assertEquals("", store.settings().musicDirectory);
        assertTrue(library.roots().containsKey(config.defaultMusic().toRealPath()));
    }
}
