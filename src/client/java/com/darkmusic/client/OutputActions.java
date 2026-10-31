package com.darkmusic.client;

import com.darkmusic.client.audio.LoudnessCache;
import com.darkmusic.client.audio.OutputDevices;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Output device switching and library loudness analysis, on the current playback backend. Client thread.
 */
final class OutputActions {
    private final ClientServices services;

    OutputActions(ClientServices services) {
        this.services = services;
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
        if (backend == null || library == null || !services.state.alive()) return;
        if (backend.analysisProgress().running()) backend.cancelAnalysis();
        else if (!library.snapshot().scanning())
            backend.analyzeLibrary(library.snapshot().tracks(), services.settings().audio);
    }

    CompletableFuture<Boolean> selectOutputDevice(String id) {
        var result = new CompletableFuture<Boolean>();
        if (services.backend() == null || !services.state.accepting()) {
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
        services.state.outputChanging = true;
        // Applies only to the backend it was asked of: a reset in between replaces the backend and voids the switch.
        switching.whenComplete((ok, failure) -> Minecraft.getInstance().execute(() -> {
            services.state.outputChanging = false;
            boolean applied = !services.state.closed && services.backend() == selected && failure == null && ok;
            try {
                if (applied) {
                    services.update(s -> {
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
