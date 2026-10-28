package com.darkmusic.client.gui;

import com.darkmusic.client.audio.OutputDevices;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class OutputDeviceScreen extends AudioPage<AudioSettingsScreen.Sources> {
    private List<OutputDevices.Device> devices = List.of();
    private boolean loading, changing, started;
    private String status = "";
    private UiStatus.Tone statusTone = UiStatus.Tone.INFO;

    public OutputDeviceScreen(AudioSettingsScreen.Sources app, Screen parent) {
        super(app, parent, "device.title");
    }

    @Override
    protected void populate() {
        section("device.title");
        info("device.help");
        if (!status.isEmpty()) status(() -> status, () -> statusTone);
        info(() -> Text.get("device.current", app.outputDeviceId().isEmpty() ? Text.get("device.default") : devices.stream().filter(d -> d.id().equals(app.outputDeviceId())).map(OutputDevices.Device::name).findFirst().orElse(Text.get("device.unavailable"))));
        var defaultRow = new SettingRow(formX, 0, formWidth, Text.get("device.default"), () -> app.outputDeviceId().isEmpty(), () -> select(""));
        place(defaultRow);
        Help.tip(defaultRow, Text.get("device.help"));
        for (var device : devices) {
            var row = new SettingRow(formX, 0, formWidth, device.name(), () -> app.outputDeviceId().equals(device.id()), () -> select(device.id()));
            place(row);
            Help.tip(row, Text.get(device.selectable() ? "device.help" : "device.ambiguous"));
            row.active = device.selectable();
        }
        action("device.refresh", this::refresh).runs();
        if (loading || changing) enableSince(0, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (!started) {
            started = true;
            refresh();
        }
    }

    private void refresh() {
        if (loading || changing) return;
        loading = true;
        status = Text.get("device.loading");
        statusTone = UiStatus.Tone.INFO;
        rebuildWidgets();
        CompletableFuture.supplyAsync(OutputDevices::available, app.io()).whenComplete((list, error) -> minecraft.execute(() -> {
            loading = false;
            if (error == null) {
                devices = list;
                status = "";
            } else {
                status = Text.get("device.failed");
                statusTone = UiStatus.Tone.ERROR;
                LoggerFactory.getLogger("darkmusic").warn("Cannot enumerate audio outputs", error);
            }
            if (isCurrent()) rebuildWidgets();
        }));
    }

    private void select(String id) {
        if (changing || loading) return;
        changing = true;
        status = Text.get("device.changing");
        statusTone = UiStatus.Tone.INFO;
        rebuildWidgets();
        app.selectOutputDevice(id).whenComplete((ok, error) -> minecraft.execute(() -> {
            changing = false;
            status = Text.get(error == null && ok ? "device.changed" : "device.failed");
            statusTone = error == null && ok ? UiStatus.Tone.SUCCESS : UiStatus.Tone.ERROR;
            app.announce(status);
            if (isCurrent()) rebuildWidgets();
        }));
    }
}
