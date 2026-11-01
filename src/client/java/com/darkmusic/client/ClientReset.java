package com.darkmusic.client;

import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.audio.SleepTimer;
import com.darkmusic.client.audio.WaveformCache;
import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.download.AuditionPlayer;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.gui.widget.DesktopFolderPicker;
import com.darkmusic.client.hud.SpeedFeedback;
import com.darkmusic.client.hud.VolumeFeedback;
import com.darkmusic.client.library.ImportHistory;
import com.darkmusic.client.library.MusicLibrary;
import com.darkmusic.client.pip.PipController;
import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reset to Defaults: admission, and the services to stop, clear and rebuild. {@link ResetOperation} owns the phases
 * and what each failure leaves behind.
 */
final class ClientReset {
    interface Inputs {
        Settings settings();

        ConfigStore config();

        ExecutorService io();

        PlaybackController playback();

        MusicLibrary library();

        DownloadService downloads();

        AuditionPlayer takeAudition();

        void closeCleanup();

        void closeSourceIndex();

        void forgetCleanupReviews();

        LibraryReconciliation reconciliation();

        SleepTimer sleep();

        SpeedFeedback speedFeedback();

        VolumeFeedback volumeFeedback();

        AutoCloseable artwork();

        WaveformCache waveforms();

        PipController pip();

        ImportHistory importHistory();

        void replaceSettings(Settings settings);

        void buildReplaceable(Cleanup acquired);

        Warnings warnings();

        void save();

        void warn(String message);
    }

    record Controls(Runnable clearScreen, Runnable invalidateFolders,
                    Supplier<Map<String, String>> defaults,
                    Runnable restoreDefaults, Consumer<Path> activate, Runnable cancelPicker) {
    }

    private final Inputs services;
    private final ClientState state;
    private final Controls controls;
    private final Runnable cancelAutoplay;
    private final Executor clientThread;

    ClientReset(ClientServices services, ScreenRequests screens, FolderActions folders, Shortcuts shortcuts,
                Runnable cancelAutoplay, Executor clientThread) {
        this(services, services.state, new Controls(screens::clear, folders::invalidate, shortcuts::defaults,
            shortcuts::restoreDefaults, folders::activate, DesktopFolderPicker::cancel), cancelAutoplay, clientThread);
    }

    /**
     * For tests: live reset phases over disposable services and explicit desktop controls.
     */
    ClientReset(Inputs services, ClientState state, Controls controls, Runnable cancelAutoplay, Executor clientThread) {
        this.services = services;
        this.state = state;
        this.controls = controls;
        this.cancelAutoplay = cancelAutoplay;
        this.clientThread = clientThread;
    }

    /**
     * Called on the client thread only, after explicit confirmation.
     */
    CompletableFuture<Void> resetDefaults() {
        if (!state.idle())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("reset.darkmusic.busy")));
        return ResetOperation.run(steps(), clientThread, services.io());
    }

    /**
     * For tests: the real phase implementation, driven independently of the executors.
     */
    ResetOperation.Steps steps() {
        return new ResetSteps();
    }

    private final class ResetSteps implements ResetOperation.Steps {
        private Settings prior;
        private String expected;
        private final Cleanup built = new Cleanup();

        @Override
        public Callable<List<String>> begin() {
            state.resetting = true;
            prior = services.settings();
            var defaultBindings = controls.defaults().get();
            var roots = new LinkedHashSet<Path>();
            roots.add(services.library().root());
            for (String root : prior.roots.keySet())
                try {
                    roots.add(Path.of(root));
                } catch (InvalidPathException ignored) {
                }
            var oldPlayback = services.playback();
            var oldLibrary = services.library();
            var oldDownloads = services.downloads();
            var oldAudition = services.takeAudition();
            // Each step runs even if one before it fails: the storage task must still stop the captured services.
            new Cleanup()
                .then("cleanup scanner", services::closeCleanup)
                .then("source index", services::closeSourceIndex)
                .then("pending requests", () -> {
                    services.forgetCleanupReviews();
                    services.reconciliation().reset();
                    controls.clearScreen().run();
                    controls.invalidateFolders().run();
                    // The timer belongs to the player being replaced; null leaves that player's volume alone.
                    services.sleep().start(0, null, 0);
                    cancelAutoplay.run();
                })
                .then("folder picker", controls.cancelPicker())
                .then("playback", oldPlayback::reset)
                .then("feedback", () -> {
                    services.speedFeedback().clear();
                    services.volumeFeedback().clear();
                })
                .close("artwork", services.artwork())
                .then("waveforms", () -> {
                    if (services.waveforms() != null) services.waveforms().close();
                })
                .then("PiP", () -> {
                    if (services.pip() != null) services.pip().stop();
                }).run();
            var stopping = stopping(oldAudition, oldPlayback, oldDownloads, oldLibrary);
            return () -> {
                ResetOperation.stop(stopping, 15);
                var retained = ResetStorage.clear(services.config().directory(), roots);
                services.importHistory().reset();
                services.config().resetShortcuts(defaultBindings);
                return retained;
            };
        }

        @Override
        public void publish(boolean defaults) {
            services.replaceSettings(defaults ? Settings.defaults(prior) : prior);
            services.buildReplaceable(built);
            if (defaults) {
                services.warnings().clear();
                controls.restoreDefaults().run();
            }
            var config = services.config();
            try {
                controls.activate().accept(services.settings().musicDirectory.isBlank() ? config.defaultMusic() : Path.of(services.settings().musicDirectory));
            } catch (Exception e) {
                controls.activate().accept(config.defaultMusic());
            }
        }

        @Override
        public void abandon() {
            built.run();
        }

        @Override
        public void release() {
            state.resetting = false;
            services.save();
            expected = ConfigStore.JSON.toJson(services.settings());
        }

        @Override
        public void verify() throws IOException {
            if (!Files.readString(services.config().directory().resolve("config.json")).equals(expected))
                throw new IOException(Text.get("reset.darkmusic.save_failed"));
        }

        @Override
        public boolean closed() {
            return state.closed;
        }

        @Override
        public void warn(String message) {
            services.warn(message);
        }
    }

    /**
     * For tests: the same captured services are closed and awaited in this order.
     */
    static List<ResetOperation.Stopping> stopping(AuditionPlayer oldAudition, PlaybackController oldPlayback,
                                                  DownloadService oldDownloads, MusicLibrary oldLibrary) {
        var stopping = new ArrayList<ResetOperation.Stopping>();
        if (oldAudition != null)
            stopping.add(new ResetOperation.Stopping("audition", oldAudition, oldAudition::awaitStopped));
        stopping.add(new ResetOperation.Stopping("playback", oldPlayback, oldPlayback::awaitStopped));
        stopping.add(new ResetOperation.Stopping("downloads", oldDownloads, oldDownloads::awaitStopped));
        stopping.add(new ResetOperation.Stopping("library", oldLibrary, oldLibrary::awaitStopped));
        return List.copyOf(stopping);
    }
}
