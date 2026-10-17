package com.darkmusic.client.hud;

import com.darkmusic.client.download.DownloadModels;

import java.util.List;

/**
 * Snapshot-only arithmetic shared by HUD rendering and verification.
 */
public record DownloadProgress(int total, int finished, int failed, int active, double fraction) {
    public static DownloadProgress of(List<DownloadModels.Item> items) {
        int done = 0, failed = 0, active = 0;
        double progress = 0;
        for (var item : items) {
            if (item.status().finished()) {
                done++;
                progress++;
                if (item.status() == DownloadModels.Status.FAILED) failed++;
            } else {
                active++;
                if (Double.isFinite(item.progress())) progress += Math.clamp(item.progress(), 0, 1);
            }
        }
        return new DownloadProgress(items.size(), done, failed, active, items.isEmpty() ? 0 : progress / items.size());
    }
}
