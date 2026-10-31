package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.MusicLibrary;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Output switching on a backend that opens nothing. The device ids name no device and fallback is off, so each
 * switch fails while resolving the device, before any line opens; the success path needs a device and stays with
 * OutputRoutingTest and the testing window.
 */
class OutputActionsTest {
    private final ClientState state = new ClientState();
    private final SettingsStore store = new SettingsStore(s -> CompletableFuture.completedFuture(null), () -> true, () -> {
    });
    private final LinkedBlockingQueue<Runnable> client = new LinkedBlockingQueue<>();
    private JavaSoundBackend backend;

    private static JavaSoundBackend silent() {
        return new JavaSoundBackend(Runnable::run, path -> {
            throw new IllegalStateException("nothing plays in this test");
        }, format -> {
            throw new IllegalStateException("nothing plays in this test");
        });
    }

    private OutputActions actions() {
        store.replace(new Settings());
        return new OutputActions(new OutputActions.Inputs() {
            public JavaSoundBackend backend() {
                return backend;
            }

            public PlaybackController playback() {
                return null;
            }

            public MusicLibrary library() {
                return null;
            }

            public void warn(String text) {
            }
        }, state, store, client::add);
    }

    private void runClientTask() throws InterruptedException {
        var task = client.poll(10, TimeUnit.SECONDS);
        assertNotNull(task, "the switch never reported back");
        task.run();
    }

    @Test
    void nothingSwitchesWithoutABackendOrWhileNotAccepting() throws Exception {
        var actions = actions();
        assertFalse(actions.selectOutputDevice("missing").get());
        try (var b = silent()) {
            backend = b;
            for (int blocked = 0; blocked < 3; blocked++) {
                state.mediaBusy = blocked == 0;
                state.outputChanging = blocked == 1;
                state.resetting = blocked == 2;
                assertFalse(actions.selectOutputDevice("missing").get());
            }
            assertTrue(client.isEmpty());
        }
    }

    @Test
    void aFailedSwitchHoldsExclusiveWorkUntilItReportsAndChangesNothing() throws Exception {
        var actions = actions();
        try (var b = silent()) {
            backend = b;
            var result = actions.selectOutputDevice("missing");
            assertTrue(state.outputChanging, "set once the backend took the request");
            assertFalse(state.accepting());
            runClientTask();
            assertFalse(state.outputChanging);
            assertFalse(result.get());
            assertEquals("", store.settings().outputDevice);
        }
    }

    /**
     * Without a device every switch fails, so this cannot show that a success applies only to its own backend; it
     * shows that a backend replaced meanwhile still releases the exclusive hold.
     */
    @Test
    void aBackendReplacedDuringTheSwitchStillReleasesTheHold() throws Exception {
        var actions = actions();
        try (var first = silent(); var second = silent()) {
            backend = first;
            var result = actions.selectOutputDevice("missing");
            backend = second;
            runClientTask();
            assertFalse(result.get());
            assertFalse(state.outputChanging);
        }
    }

    @Test
    void aClosedBackendRefusesAndTheResultIsFalse() throws Exception {
        var actions = actions();
        var closed = silent();
        closed.close();
        backend = closed;
        var result = actions.selectOutputDevice("missing");
        runClientTask();
        assertFalse(result.get());
        assertEquals("", store.settings().outputDevice);
    }

    @Test
    void analysisNeedsABackendAndALibrary() {
        var actions = actions();
        assertEquals("", actions.outputDeviceId());
        actions.analyzeLibraryLoudness();
        assertFalse(actions.loudnessAnalysis().running());
    }
}
