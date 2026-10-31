package com.darkmusic.client;

import com.darkmusic.client.config.ConfigStore;
import com.darkmusic.client.config.ResetStorage;
import com.darkmusic.client.config.Settings;
import com.darkmusic.client.gui.DesktopFolderPicker;
import com.darkmusic.client.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reset to Defaults: admission, and the services to stop, clear and rebuild. {@link ResetOperation} owns the phases
 * and what each failure leaves behind.
 */
final class ClientReset {
    private final ClientServices services;
    private final ScreenRequests screens;
    private final FolderActions folders;
    private final Shortcuts shortcuts;
    private final Runnable cancelAutoplay;
    private final Executor clientThread;

    ClientReset(ClientServices services, ScreenRequests screens, FolderActions folders, Shortcuts shortcuts,
                Runnable cancelAutoplay, Executor clientThread) {
        this.services = services;
        this.screens = screens;
        this.folders = folders;
        this.shortcuts = shortcuts;
        this.cancelAutoplay = cancelAutoplay;
        this.clientThread = clientThread;
    }

    /**
     * Called on the client thread only, after explicit confirmation.
     */
    CompletableFuture<Void> resetDefaults() {
        if (!services.state.idle())
            return CompletableFuture.failedFuture(new IllegalStateException(Text.get("reset.darkmusic.busy")));
        return ResetOperation.run(new ResetSteps(), clientThread, services.io());
    }

    private final class ResetSteps implements ResetOperation.Steps {
        private Settings prior;
        private String expected;
        private final Cleanup built = new Cleanup();

        @Override
        public Callable<List<String>> begin() {
            var state = services.state;
            state.resetting = true;
            prior = services.settings();
            var defaultBindings = shortcuts.defaults();
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
                    screens.clear();
                    folders.invalidate();
                    // The timer belongs to the player being replaced; null leaves that player's volume alone.
                    services.sleep().start(0, null, 0);
                    cancelAutoplay.run();
                })
                .then("folder picker", DesktopFolderPicker::cancel)
                .then("playback", oldPlayback::reset)
                .then("feedback", () -> {
                    services.speedFeedback().clear();
                    services.volumeFeedback().clear();
                })
                .then("artwork", services.artwork()::close)
                .then("waveforms", () -> {
                    if (services.waveforms() != null) services.waveforms().close();
                })
                .then("PiP", () -> {
                    if (services.pip() != null) services.pip().stop();
                }).run();
            var stopping = new ArrayList<ResetOperation.Stopping>();
            if (oldAudition != null)
                stopping.add(new ResetOperation.Stopping("audition", oldAudition, oldAudition::awaitStopped));
            stopping.add(new ResetOperation.Stopping("playback", oldPlayback, oldPlayback::awaitStopped));
            stopping.add(new ResetOperation.Stopping("downloads", oldDownloads, oldDownloads::awaitStopped));
            stopping.add(new ResetOperation.Stopping("library", oldLibrary, oldLibrary::awaitStopped));
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
                shortcuts.restoreDefaults();
            }
            var config = services.config();
            try {
                folders.activate(services.settings().musicDirectory.isBlank() ? config.defaultMusic() : Path.of(services.settings().musicDirectory));
            } catch (Exception e) {
                folders.activate(config.defaultMusic());
            }
        }

        @Override
        public void abandon() {
            built.run();
        }

        @Override
        public void release() {
            services.state.resetting = false;
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
            return services.state.closed;
        }

        @Override
        public void warn(String message) {
            services.warn(message);
        }
    }
}
