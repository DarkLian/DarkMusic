package com.darkmusic.client.pip;

/**
 * HWND alone is insufficient: a closed window handle can be reused by another process.
 */
public record PipSource(long Handle, int Pid, long CreatedTicks, String Title, String App, boolean Minimized) {
    public PipSource {
        Title = clean(Title);
        App = clean(App);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(512, clean.length()));
    }

    public boolean valid() {
        return Handle > 0 && Pid > 0 && CreatedTicks > 0;
    }
}
