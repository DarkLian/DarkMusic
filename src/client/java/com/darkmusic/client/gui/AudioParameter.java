package com.darkmusic.client.gui;

import com.darkmusic.client.text.Text;

import java.util.Locale;

/**
 * Value/slider mapping, also shared with exact-value editing.
 */
record AudioParameter(double low, double high, boolean logarithmic, String unit) {
    AudioParameter {
        if (!Double.isFinite(low) || !Double.isFinite(high) || high <= low || logarithmic && low <= 0)
            throw new IllegalArgumentException("parameter range");
    }

    double value(double position) {
        double p = Math.clamp(position, 0, 1);
        return logarithmic ? low * Math.pow(high / low, p) : low + p * (high - low);
    }

    double position(double value) {
        return Math.clamp(logarithmic ? Math.log(value / low) / Math.log(high / low) : (value - low) / (high - low), 0, 1);
    }

    double parse(String input) {
        try {
            double value = Double.parseDouble(input.strip().replace(',', '.'));
            if (!Double.isFinite(value) || value < low || value > high) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Text.get("audio.parameter.invalid", low, high));
        }
    }

    String format(double value) {
        if (unit.equals(" Hz"))
            return value >= 1000 ? String.format(Locale.ROOT, "%.2f kHz", value / 1000) : String.format(Locale.ROOT, "%.0f Hz", value);
        if (unit.equals(" s"))
            return value < 1 ? String.format(Locale.ROOT, "%.0f ms", value * 1000) : String.format(Locale.ROOT, "%.2f s", value);
        if (unit.equals("%")) return String.format(Locale.ROOT, "%.0f%%", value);
        return String.format(Locale.ROOT, unit.contains("dB") || unit.equals(":1") ? "%.1f%s" : "%.2f%s", value, unit);
    }
}
