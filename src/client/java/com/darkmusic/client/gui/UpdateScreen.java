package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

final class UpdateScreen extends AudioPage {
    private String status = "update.checking";
    private boolean started;
    private java.nio.file.Path shownFailure;

    UpdateScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "update.check");
    }

    @Override
    protected void populate() {
        section("update.check");
        shownFailure = app.updates.installFailureLog();
        if (shownFailure != null) {
            status(() -> Text.get("update.install_failed"), () -> UiStatus.Tone.ERROR);
            action("update.open_log", app.updates::openInstallLog).runs();
        }
        status(() -> status.equals("update.found") ? Text.get(status, app.updates.availableVersion()) : Text.get(status), () -> switch (status) {
            case "update.failed", "update.check_failed", "update.download_failed", "update.install_failed" ->
                UiStatus.Tone.ERROR;
            case "update.found", "update.latest", "update.ready" -> UiStatus.Tone.SUCCESS;
            case "update.development", "update.unsupported" -> UiStatus.Tone.WARNING;
            default -> UiStatus.Tone.INFO;
        });
        if (!app.updates.canInstall()) info("update.development");
        if (status.equals("update.found") && app.updates.canInstall()) {
            action("update.download", () -> {
                app.updates.download();
                status = "update.downloading";
                rebuildWidgets();
            }).runs();
            info("update.install_help");
        }
        if (!status.equals("update.checking")) action("update.check", this::check).runs();
    }

    @Override
    public void tick() {
        super.tick();
        if (!java.util.Objects.equals(shownFailure, app.updates.installFailureLog())) rebuildWidgets();
        if (!started) {
            started = true;
            check();
        } else if (status.equals("update.downloading")) {
            String next = app.updates.downloadStatus();
            if (!next.equals(status)) {
                status = next;
                rebuildWidgets();
            }
        }
    }

    private void check() {
        if (!isCurrent()) return;
        status = "update.checking";
        rebuildWidgets();
        app.updates.checkNow(result -> {
            status = result;
            if (isCurrent()) rebuildWidgets();
        });
    }
}
