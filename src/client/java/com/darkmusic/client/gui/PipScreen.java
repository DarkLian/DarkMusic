package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.pip.PipNativeTools;
import com.darkmusic.client.text.Text;
import net.minecraft.client.gui.screens.Screen;

/**
 * Uses the existing scrollable settings rows at both wide and compact scales.
 */
public final class PipScreen extends AudioPage {
    private boolean shownActive;
    private com.darkmusic.client.pip.PipAudio shownAudio;
    private String shownSetup = "";
    private boolean shownBusy, shownReady, shownEnabled;

    public PipScreen(DarkMusicClient app, Screen parent) {
        super(app, parent, "pip.title");
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.PIP;
    }

    @Override
    protected void populate() {
        var pip = app.pip;
        shownActive = pip.active();
        shownAudio = pip.audio();
        var s = app.settings.pip;
        var setup = pip.tools.runtime;
        shownSetup = setup.status();
        shownBusy = setup.busy();
        shownReady = setup.ready();
        shownEnabled = s.enabled;
        status(() -> Text.get(setup.status()), () -> setup.ready() ? UiStatus.Tone.SUCCESS : setup.status().contains("failed") || setup.status().equals("pip.bundle_damaged") ? UiStatus.Tone.ERROR : UiStatus.Tone.INFO);
        if (setup.busy()) {
            if (setup.status().equals("pip.setup.installing"))
                info(() -> Text.get("pip.setup.progress", setup.downloaded() / 1048576, setup.size() / 1048576));
            action("pip.setup.cancel", setup::cancel).runs();
            return;
        }
        labeledAction(Text.get("pip.setup.check"), Text.get("pip.setup.check.help"), setup::check).runs().active = !pip.active();
        if (s.enabled) labeledAction(Text.get("pip.setup.disable"), Text.get("pip.setup.access.help"), () -> {
            s.revokeAccess();
            pip.stop();
            changed();
        }).runs();
        if (!setup.ready()) {
            labeledAction(Text.get("pip.setup.install"), Text.get("pip.setup.install.help"), () -> confirm("pip.setup.install", "pip.setup.install.confirm", setup::install)).runs().active = setup.compatible();
            return;
        }
        if (!s.enabled) labeledAction(Text.get("pip.setup.enable"), Text.get("pip.setup.access.help"), () -> {
            confirm("pip.setup.enable", "pip.setup.access.confirm", () -> {
                s.enabled = true;
                changed();
            });
        }).runs();
        if (!s.enabled) return;
        status(() -> Text.get(pip.status()), () -> pip.status().equals("pip.live") ? UiStatus.Tone.SUCCESS : pip.status().equals("pip.idle") ? UiStatus.Tone.INFO : UiStatus.Tone.WARNING);
        info(() -> pip.source() == null ? Text.get("pip.no_source") : pip.source().App() + " — " + pip.source().Title());
        if (!PipNativeTools.supported()) {
            status(() -> Text.get("pip.unsupported"), () -> UiStatus.Tone.WARNING);
            return;
        }
        status(() -> Text.get("pip.platform_notice"), () -> UiStatus.Tone.INFO);
        if (com.darkmusic.client.pip.PipController.exclusiveFullscreen())
            status(() -> Text.get("pip.exclusive_warning"), () -> UiStatus.Tone.WARNING);
        labeledAction(Text.get("pip.choose"), Text.get("pip.choose.help"), () -> show(new PipSourcesScreen(app, this)));
        labeledAction(Text.get(pip.active() ? "pip.stop" : "pip.start"), Text.get("pip.start.help"), () -> {
            if (pip.active()) pip.stop();
            else pip.start();
            rebuildWidgets();
        }).runs().active = pip.source() != null;
        toggle("pip.visible", () -> s.visible, v -> s.visible = v);
        toggle("pip.in_menus", () -> s.inMenus, v -> s.inMenus = v);
        var audioToggle = new SettingRow(formX, 0, formWidth, Text.get("pip.audio_spectrum"), () -> s.audioSpectrum && s.audioAccess, () -> {
            if (s.audioSpectrum && s.audioAccess) {
                s.audioSpectrum = false;
                s.audioAccess = false;
                changed();
            } else confirm("pip.audio_spectrum", "pip.setup.audio.confirm", () -> {
                s.audioAccess = true;
                s.audioSpectrum = true;
                changed();
            });
        });
        audioToggle.active = !pip.active() && setup.audioSupported();
        Help.tip(audioToggle, Text.get("pip.audio_spectrum.help"));
        place(audioToggle);
        action("pip.spectrum.title", () -> show(new PipSpectrumScreen(app, this)));
        var capturedAudio = pip.audio();
        if (capturedAudio != null)
            status(() -> Text.get(capturedAudio.status()), () -> capturedAudio.available() ? UiStatus.Tone.SUCCESS : UiStatus.Tone.WARNING);
        action("pip.placement", () -> show(new PipEditorScreen(app, this, false)));
        action("pip.crop", () -> show(new PipEditorScreen(app, this, true)));
        int monitorRate = pip.monitorRefreshRate();
        info(() -> monitorRate > 0 ? Text.get("pip.monitor_rate", monitorRate) : Text.get("pip.monitor_unknown"));
        labeledAction(s.frameRate == 0 ? Text.get("pip.fps.monitor", s.effectiveFrameRate(monitorRate)) : Text.get("pip.fps", s.frameRate), Text.get("pip.fps.help"), () -> {
            int[] caps = com.darkmusic.client.pip.PipSettings.frameRateOptions(pip.monitorRefreshRate());
            int next = 0;
            while (next < caps.length && caps[next] <= s.frameRate) next++;
            s.frameRate = caps[next % caps.length];
            changed();
        }).active = !pip.active();
        var opacity = new ValueSlider(formX, 0, formWidth, (s.opacity - .1) / .9, v -> Text.get("pip.opacity", Math.round((.1 + v * .9) * 100)), v -> s.opacity = .1 + v * .9, app::save, 90);
        opacity.plain(true);
        place(opacity);
        labeledAction(Text.get("pip.focus_key", s.focusKey - 111), Text.get("pip.shortcuts.help"), () -> cycleKey(true)).active = !pip.active();
        labeledAction(Text.get("pip.return_key", s.returnKey - 111), Text.get("pip.shortcuts.help"), () -> cycleKey(false)).active = !pip.active();
    }

    @Override
    public void tick() {
        var setup = app.pip.tools.runtime;
        if (shownActive != app.pip.active() || shownAudio != app.pip.audio() || !shownSetup.equals(setup.status()) || shownBusy != setup.busy() || shownReady != setup.ready() || shownEnabled != app.settings.pip.enabled)
            rebuildWidgets();
    }

    private void confirm(String title, String explanation, Runnable action) {
        show(new ConfirmationScreen(this, Text.component(title), Text.component(explanation), Text.component(title), () -> {
            action.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).nonDestructive());
    }

    private void cycleKey(boolean focus) {
        var s = app.settings.pip;
        int key = focus ? s.focusKey : s.returnKey;
        do {
            key = key >= 135 ? 112 : key + 1;
        } while (!com.darkmusic.client.pip.PipSettings.validKey(key) || key == (focus ? s.returnKey : s.focusKey));
        if (focus) s.focusKey = key;
        else s.returnKey = key;
        changed();
    }
}
