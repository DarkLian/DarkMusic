package com.darkmusic.client;

import com.darkmusic.client.audio.JavaSoundBackend;
import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.OutputDevices;
import com.darkmusic.client.audio.PlaybackController;
import com.darkmusic.client.config.SettingsStore;
import com.darkmusic.client.library.MusicLibrary;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Output device switching and library loudness analysis, on the current playback backend. Client thread.
 */
final class OutputActions {
    private final Inputs services;
    private final ClientState state;
    private final SettingsStore store;
    private final Executor clientThread;

    interface Inputs {
        JavaSoundBackend backend();

        PlaybackController playback();

        MusicLibrary library();

        void warn(String text);
    }

    OutputActions(Inputs services, ClientState state, SettingsStore store, Executor clientThread) {
        this.services = services;
        this.state = state;
        this.store = store;
        this.clientThread = clientThread;
    }

    String outputDeviceId() {
        var backend = services.backend();
        return backend == null ? "" : backend.outputDeviceId();
    }

    LoudnessCache.AnalysisProgress loudnessAnalysis() {
        var backend = services.backend();
        return backend == null ? LoudnessCache.AnalysisProgress.IDLE : backend.analysisProgress();
    }

    void analyzeLibraryLoudness() {
        var backend = services.backend();
        var library = services.library();
        if (backend == null || library == null || !state.alive()) return;
        if (backend.analysisProgress().running()) backend.cancelAnalysis();
        else if (!library.snapshot().scanning())
            backend.analyzeLibrary(library.snapshot().tracks(), store.settings().audio);
    }

    CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (services.backend() == null || !state.accepting()) {
            result.complete(false);
            return result;
        }
        var selected = services.backend();
        CompletableFuture<Boolean> switching;
        try {
            switching = selected.output(new OutputDevices(id, false, services::warn));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger("darkmusic").warn("Could not start switching the output device", failure);
            result.complete(false);
            return result;
        }
        state.outputChanging = true;
        // Applies only to the backend it was asked of: a reset in between replaces the backend and voids the switch.
        switching.whenComplete((ok, failure) -> clientThread.execute(() -> {
            state.outputChanging = false;
            boolean applied = !state.closed && services.backend() == selected && failure == null && ok;
            try {
                if (applied) {
                    store.update(s -> {
                        s.outputDevice = id;
                        services.playback().outputChanged();
                    });
                }
            } finally {
                result.complete(applied);
            }
        }));
        return result;
    }
}
