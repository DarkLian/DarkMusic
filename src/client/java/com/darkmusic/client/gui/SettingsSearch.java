package com.darkmusic.client.gui;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search across every section of the Settings form. The query survives rebuilds such as a toggled option; matching rows
 * are listed in form order, each section introduced by its heading.
 */
final class SettingsSearch {
    private String query = "";
    private final List<SettingsLayout.Section> headings = new ArrayList<>();
    private final Map<AbstractWidget, Integer> rows = new HashMap<>();

    String query() {
        return query;
    }

    void query(String query) {
        this.query = query;
    }

    boolean searching() {
        return !query.isBlank();
    }

    /**
     * Every word of the search must appear in the row's label, help text, or section and group names.
     */
    static boolean matches(String query, String label, String details) {
        String haystack = (label + " " + details).toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).strip().split("\\s+"))
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        return true;
    }

    private boolean matches(SettingsLayout.Placed p) {
        return matches(query, p.widget().getMessage().getString(), p.details());
    }

    /**
     * Lays out the matching rows and their section headings from the top of the results. Returns the results' height.
     */
    int arrange(List<SettingsLayout.Placed> controls, List<SettingsLayout.Section> sections) {
        headings.clear();
        rows.clear();
        int y = 0, lastSection = -1;
        for (var p : controls) {
            if (!matches(p)) continue;
            if (p.section() != lastSection) {
                headings.add(new SettingsLayout.Section(sections.get(p.section()).title(), y));
                y += 24;
                lastSection = p.section();
            }
            rows.put(p.widget(), y);
            y += 26;
        }
        return y;
    }

    List<SettingsLayout.Section> headings() {
        return headings;
    }

    /**
     * A matching row's y within the results, or null when the row does not match.
     */
    Integer row(AbstractWidget widget) {
        return rows.get(widget);
    }
}
