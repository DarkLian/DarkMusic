package com.darkmusic.client.gui;

/**
 * Immutable viewport geometry shared by painting, clipping and native hit targets.
 */
public record PlayerLayout(int width, int height, Rect navigation, Rect tracks, Rect inspector, Rect dock) {
    public static final int CONTROL_GAP = 4;

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    public static PlayerLayout of(int width, int height) {
        int margin = width < 480 ? 8 : 12;
        int dockHeight = 64;
        int top = height < 300 ? 88 : 96, dockY = height - dockHeight - 8;
        int available = Math.max(1, dockY - 16 - top);
        int nav = width >= 480 && height >= 300 ? Math.clamp(width / 6, 104, 156) : 0;
        int panel = width >= 640 && height >= 400 ? Math.clamp(width / 4, 140, 220) : 0;
        if (panel > 0) {
            int listX = margin + panel + 8;
            int listWidth = width - 2 * listX;
            if (listWidth >= 240)
                return new PlayerLayout(width, height, new Rect(margin, top, panel, available),
                    new Rect(listX, top, listWidth, available),
                    new Rect(width - margin - panel, top, panel, available),
                    new Rect(margin, dockY, width - margin * 2, dockHeight));
        }
        int listX = margin + (nav > 0 ? nav + 8 : 0);
        int listWidth = width - margin - listX;
        return new PlayerLayout(width, height, new Rect(margin, top, nav, available),
            new Rect(listX, top, listWidth, available),
            new Rect(width - margin, top, 0, available),
            new Rect(margin, dockY, width - margin * 2, dockHeight));
    }

    public boolean singleColumn() {
        return navigation.width() == 0;
    }

    public int artworkSize() {
        return inspector.width() == 0 ? 0 : Math.min(inspector.width() - 16, inspector.height() - spectrumHeight() - 100);
    }

    public int transportY() {
        return dock.y() + 32;
    }

    public int transportButtonWidth() {
        return width < 400 ? 20 : 24;
    }

    private static int fixedControlsWidth(int buttonWidth) {
        // Nine icon buttons, the wider Play button, and ten equal gaps.
        return 9 * buttonWidth + 8 + 10 * CONTROL_GAP;
    }

    public int controlWidth() {
        return Math.clamp((dock.width() - 9 * transportButtonWidth() - 56) / 2, 32, 104);
    }

    public int controlsWidth() {
        return controlWidth() * 2 + fixedControlsWidth(transportButtonWidth());
    }

    public int transportX() {
        return (width - controlsWidth()) / 2;
    }

    public int seekY() {
        return dock.y() + 2;
    }

    public int spectrumHeight() {
        return Math.min(40, Math.max(16, inspector.height() / 7));
    }

    public int spectrumY() {
        return inspector.bottom() - spectrumHeight() - 8;
    }
}
