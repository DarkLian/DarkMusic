package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.render.UiIcon;
import com.darkmusic.client.render.UiStatus;
import com.darkmusic.client.text.Text;
import com.darkmusic.client.tools.DownloadDiagnostics;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;

/**
 * A local, bounded report; no network, tool installation or log collection on open.
 */
public final class DownloadDiagnosticScreen extends AudioPage<DarkMusicClient> {
    private final DownloadDiagnostics.Diagnostic diagnostic;
    private final Runnable retry;
    private String copyStatus = "";
    private UiStatus.Tone copyTone = UiStatus.Tone.INFO;

    public DownloadDiagnosticScreen(DarkMusicClient app, Screen parent, DownloadDiagnostics.Diagnostic diagnostic, Runnable retry) {
        super(app, parent, "download.diag.title");
        this.diagnostic = diagnostic;
        this.retry = retry;
    }

    @Override
    protected UiIcon pageIcon() {
        return UiIcon.WARNING;
    }

    @Override
    protected void populate() {
        section("download.diag.what");
        info(() -> Text.get("download.diag.stage." + diagnostic.stage().name().toLowerCase(Locale.ROOT)));
        status(diagnostic::summary, () -> UiStatus.Tone.ERROR);
        info(() -> Text.get("download.diag.source", Text.get("download.diag.source." + diagnostic.source().name().toLowerCase(Locale.ROOT))));
        section("download.diag.meaning");
        status(diagnostic::explanation, () -> UiStatus.Tone.WARNING);
        section("download.diag.try");
        status(diagnostic::action);
        section("download.diag.technical");
        info(() -> Text.get("download.diag.tool", diagnostic.tool().display(), diagnostic.toolVersion()));
        info(() -> Text.get("download.diag.exit", diagnostic.exitCode() == null ? "—" : diagnostic.exitCode()));
        if (!diagnostic.detail().isEmpty()) info(diagnostic::detail);
        info("download.diag.privacy");
        action("download.diag.copy", () -> {
            try {
                String version = FabricLoader.getInstance().getModContainer("darkmusic").orElseThrow().getMetadata().getVersion().getFriendlyString();
                minecraft.keyboardHandler.setClipboard(diagnostic.report(version));
                copyStatus = Text.get("download.diag.copied");
                copyTone = UiStatus.Tone.SUCCESS;
            } catch (Exception failure) {
                copyStatus = Text.get("download.diag.copy_failed");
                copyTone = UiStatus.Tone.ERROR;
            }
            rebuildWidgets();
        }).runs();
        if (!copyStatus.isEmpty()) status(() -> copyStatus, () -> copyTone);
        if (retry != null && diagnostic.canRetry()) {
            int index = rowCount();
            action("download.diag.retry", () -> {
                retry.run();
                onClose();
            }).runs();
            enableSince(index, !app.downloads.snapshot().analyzing());
        }
        if (diagnostic.toolsRelated())
            action("text.darkmusic.download_tools", () -> show(new DownloadToolsScreen(app, this))).navigates();
    }
}
