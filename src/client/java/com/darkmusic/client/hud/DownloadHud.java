package com.darkmusic.client.hud;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.download.DownloadModels;
import com.darkmusic.client.download.DownloadService;
import com.darkmusic.client.render.HorizontalText;
import com.darkmusic.client.render.UiTheme;
import com.darkmusic.client.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Queue visibility outlives its screen; completion notices expire even while menus are open.
 */
public final class DownloadHud {
    private final Map<Long, DownloadModels.Status> finishedStates = new HashMap<>();
    private DownloadService service;
    private DownloadModels.Snapshot snapshot;
    private DownloadProgress progress = DownloadProgress.of(List.of());
    private String notice = "";
    private long noticed;
    private boolean failure;
    private List<DownloadModels.Item> activeItems = List.of();

    public static void register(DarkMusicClient app) {
        var hud = new DownloadHud();
        ClientTickEvents.END_CLIENT_TICK.register(client -> hud.update(app.downloads, System.nanoTime()));
        HudLayer.add(app, "downloads", g -> hud.render(app, g));
    }

    public void update(DownloadService current, long now) {
        if (service != current) {
            service = current;
            snapshot = null;
            finishedStates.clear();
            notice = "";
            progress = DownloadProgress.of(List.of());
            activeItems = List.of();
        }
        if (current == null) return;
        var next = current.snapshot();
        if (next == snapshot) return;
        snapshot = next;
        progress = DownloadProgress.of(next.items());
        activeItems = next.items().stream().filter(item -> !item.status().finished()).limit(DownloadService.PARALLEL_DOWNLOADS).toList();
        for (var item : next.items())
            if (item.status().finished() && finishedStates.put(item.id(), item.status()) != item.status()) {
                notice = Text.get("state.darkmusic." + item.status().name().toLowerCase(Locale.ROOT)) + ": " + item.title();
                noticed = now;
                failure = item.status() == DownloadModels.Status.FAILED;
            }
        var present = next.items().stream().filter(item -> item.status().finished()).map(DownloadModels.Item::id).collect(Collectors.toSet());
        finishedStates.keySet().retainAll(present);
    }

    private void render(DarkMusicClient app, GuiGraphicsExtractor g) {
        double age = (System.nanoTime() - noticed) / 1_000_000_000.0;
        boolean active = progress.active() > 0;
        double opacity = active ? 1 : notice.isEmpty() ? 0 : Math.clamp(5 - age, 0, 1);
        if (opacity <= .02 || snapshot == null) return;
        var font = Minecraft.getInstance().font;
        int width = Math.min(260, g.guiWidth() - 16), x = g.guiWidth() - width - 8, y = 8;
        var rows = activeItems;
        boolean showNotice = !notice.isEmpty() && age < 5;
        int height = 25 + rows.size() * 12 + (showNotice ? 12 : 0);
        if (app.settings != null && app.settings.hudEnabled && app.playback != null && app.playback.state().track() != null) {
            var b = NowPlayingHud.bounds(app, g.guiWidth(), g.guiHeight());
            if (x < b.x() + b.width() && x + width > b.x() && y < b.y() + b.height() && y + height > b.y())
                y = (int) Math.ceil(b.y() + b.height()) + 4;
        }
        y = Math.max(4, Math.min(y, g.guiHeight() - height - 4));
        int alpha = (int) (opacity * 255), color = HudText.themed(UiTheme.TEXT, alpha);
        g.fill(x, y, x + width, y + height, HudText.themed(UiTheme.BACKGROUND, (int) (opacity * 220)));
        g.text(font, HorizontalText.ellipsize(font, Text.get("hud.darkmusic.downloads", progress.finished(), progress.total(), Math.round(progress.fraction() * 100), progress.failed()), width - 12), x + 6, y + 5, color);
        g.fill(x + 6, y + 17, x + width - 6, y + 20, HudText.themed(UiTheme.BORDER, alpha));
        g.fill(x + 6, y + 17, x + 6 + (int) ((width - 12) * progress.fraction()), y + 20, HudText.themed(UiTheme.ACCENT, alpha));
        int rowY = y + 25;
        for (var item : rows) {
            String percent = item.progress() >= 0 ? " " + Math.round(item.progress() * 100) + "%" : "";
            String label = Text.get("state.darkmusic." + item.status().name().toLowerCase(Locale.ROOT)) + percent + ": " + item.title();
            g.text(font, HorizontalText.ellipsize(font, label, width - 12), x + 6, rowY, color);
            rowY += 12;
        }
        if (showNotice)
            g.text(font, HorizontalText.ellipsize(font, notice, width - 12), x + 6, rowY, HudText.themed(failure ? UiTheme.WARNING : UiTheme.ACCENT, alpha));
    }
}
