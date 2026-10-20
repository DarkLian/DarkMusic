package com.darkmusic.client.config;

/**
 * Serializable independent colors for spectrum bars and track progress.
 */
public final class VisualColors {
    public Preset preset = Preset.OCEAN_DRIVE;
    public int start = 0x578CFF, end = 0x5EFAF0;

    private static int blend(int first, int last, double mix) {
        return (int) Math.round(first + (last - first) * mix);
    }

    public static Integer parse(String input) {
        String value = input.strip();
        if (value.startsWith("#")) value = value.substring(1);
        return value.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(value, 16) : null;
    }

    public void validate() {
        if (preset == null) preset = Preset.OCEAN_DRIVE;
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
        TWILIGHT(0x667EEA, 0xD78BDE),
        PEACH_GLOW(0xFF9A8B, 0xFFD6A5),
        MINT_BREEZE(0x6EE7B7, 0xA5F3FC),
        COSMIC_DUST(0xA78BFA, 0xF0ABFC),
        BLUE_LAGOON(0x06B6D4, 0x93C5FD),
        GOLDEN_HOUR(0xFDBA74, 0xFDE68A),
        ROSE_QUARTZ(0xFDA4AF, 0xDDD6FE),
        EMERALD_SKY(0x34D399, 0x60A5FA),
        LAVENDER_HAZE(0xC4B5FD, 0xBAE6FD),
        CORAL_REEF(0xFB7185, 0xFDBA74),
        POLAR_NIGHT(0x818CF8, 0x67E8F9),
        CHERRY_BLOSSOM(0xF9A8D4, 0xFDE2E4),
        ELECTRIC_VIOLET(0x8B5CF6, 0x38BDF8),
        CITRUS_FIZZ(0xBEF264, 0xFDE68A),
        AMETHYST_FIRE(0xC084FC, 0xFB923C),
        SEA_GLASS(0x5EEAD4, 0xD9F99D),
        VELVET_SUNSET(0xE879F9, 0xFDA4AF),
        RAINY_CITY(0x94A3B8, 0xA5B4FC),
        HONEYDEW(0x86EFAC, 0xFEF3C7),
        STARRY_NIGHT(0x60A5FA, 0xE0E7FF),
        DRAGON_FRUIT(0xF472B6, 0xC4B5FD),
        DESERT_MIRAGE(0xFCD34D, 0xF9A8D4),
        ARCTIC_AURORA(0x2DD4BF, 0xC4B5FD),
        SILVER_LINING(0xCBD5E1, 0xF8FAFC),
        CUSTOM(0xB5B0FF, 0xB5B0FF);

        public final int start, end;

        Preset(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
