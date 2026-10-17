package com.darkmusic.client.config;

/**
 * Serializable independent colors for spectrum bars and track progress.
 */
public final class VisualColors {
    public Preset preset = Preset.MOONLIGHT;
    public int start = 0xB5B0FF, end = 0xB5B0FF;

    private static int blend(int first, int last, double mix) {
        return (int) Math.round(first + (last - first) * mix);
    }

    public static Integer parse(String input) {
        String value = input.strip();
        if (value.startsWith("#")) value = value.substring(1);
        return value.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(value, 16) : null;
    }

    public void validate() {
        if (preset == null) preset = Preset.MOONLIGHT;
        start &= 0xFFFFFF;
        end &= 0xFFFFFF;
    }

    public int first() {
        return preset == Preset.CUSTOM ? start : preset.start;
    }

    public int last() {
        return preset == Preset.CUSTOM ? end : preset.end;
    }

    public int color(double position) {
        double mix = Double.isFinite(position) ? Math.clamp(position, 0, 1) : 0;
        int first = first(), last = last();
        int red = blend((first >> 16) & 255, (last >> 16) & 255, mix);
        int green = blend((first >> 8) & 255, (last >> 8) & 255, mix);
        int blue = blend(first & 255, last & 255, mix);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public enum Preset {
        MOONLIGHT(0xB5B0FF, 0xB5B0FF),
        AURORA(0x55F2C0, 0xB27CFF),
        NEON_DREAM(0xFF69C8, 0x55E7FF),
        SOLAR_FLARE(0xFF704D, 0xFFE68A),
        OCEAN_DRIVE(0x578CFF, 0x5EFAF0),
        SAKURA(0xFFB3D1, 0xC4A2FF),
        GLACIER(0x67DEFF, 0xE6F9FF),
        RUBY_PULSE(0xFF526E, 0xFFACD4),
        CUSTOM(0xB5B0FF, 0xB5B0FF);

        public final int start, end;

        Preset(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
