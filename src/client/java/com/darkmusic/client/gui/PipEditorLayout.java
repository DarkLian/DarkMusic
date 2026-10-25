package com.darkmusic.client.gui;

import com.darkmusic.client.pip.PipSettings.Bounds;

/**
 * Keep editor buttons away from both the captured image and its spectrum.
 */
public final class PipEditorLayout {
    private PipEditorLayout() {
    }

    public static int buttonY(int screenHeight, Bounds image, Bounds spectrum, int x, int width) {
        int bottom = Math.max(4, screenHeight - 24);
        return overlap(new Bounds(x, bottom, width, 20), image, spectrum) < overlap(new Bounds(x, 4, width, 20), image, spectrum) ? bottom : 4;
    }

    public static long overlap(Bounds button, Bounds image, Bounds spectrum) {
        return area(button, image) + (spectrum == null ? 0 : area(button, spectrum));
    }

    private static long area(Bounds a, Bounds b) {
        return (long) Math.max(0, Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x()))
            * Math.max(0, Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y()));
    }
}
