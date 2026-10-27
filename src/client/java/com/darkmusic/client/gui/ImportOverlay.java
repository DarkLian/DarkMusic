package com.darkmusic.client.gui;

import com.darkmusic.client.DarkMusicClient;
import com.darkmusic.client.text.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Constant-size geometry; reuses the menu blur pass only while an import cue is visible.
 */
final class ImportOverlay {
    private long entered;

    boolean visible(DarkMusicClient app) {
        return app.importing;
    }

    void render(DarkMusicClient app, GuiGraphicsExtractor g, int width, int height) {
        if (!app.importing) {
            entered = 0;
            return;
        }
        if (entered == 0) entered = System.nanoTime();
        g.nextStratum();
        if (Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1) g.blurBeforeThisStratum();
        g.fill(0, 0, width, height, 0xCC0C0E18);
        int w = Math.max(1, Math.min(width - 24, 360)), h = 108;
        int x = (width - w) / 2, y = (height - h) / 2;
        UiTheme.panel(g, x, y, w, h);
        int accent = UiTheme.mix(UiTheme.BORDER, UiTheme.ACCENT, .65f + .25f * (float) Math.sin((System.nanoTime() - entered) / 250_000_000.0));
        g.outline(x, y, w, h, accent);
        UiIcon.DOWNLOAD.draw(g, (width - 24) / 2, y + 12, 24, UiTheme.ACCENT);
        var font = Minecraft.getInstance().font;
        String title = Text.get("import.darkmusic.importing");
        g.text(font, HorizontalText.ellipsize(font, title, w - 20), x + (w - Math.min(font.width(title), w - 20)) / 2, y + 47, UiTheme.TEXT);
        String label = Text.get("import.darkmusic.destination", app.importDestination);
        label = HorizontalText.ellipsize(font, label, w - 24);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 65, UiTheme.SECONDARY);
        String hint = Text.get("import.darkmusic.copying");
        hint = HorizontalText.ellipsize(font, hint, w - 24);
        g.text(font, hint, x + (w - font.width(hint)) / 2, y + 83, UiTheme.MUTED);
    }
}
