package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

final class UpdateScreen extends AudioPage {
    private String status = "update.checking";
    private boolean started;

    UpdateScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "update.check");
    }

    @Override
    protected void populate() {
        section("update.check");
        info(() -> status.equals("update.found") ? Text.get(status, app.updates.availableVersion()) : Text.get(status));
        if (status.equals("update.found")) {
            action("update.download", () -> {
                app.updates.download();
                status = "update.downloading";
                rebuildWidgets();
            });
            info("update.install_help");
        }
        if (!status.equals("update.checking")) action("update.check", this::check);
    }

    @Override
    public void tick() {
        super.tick();
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
