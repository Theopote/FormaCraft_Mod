package com.formacraft.client.ui.panel.settings;

import java.util.List;
import java.util.Locale;

/** Search and viewport state, independent of HUD rendering and text editing. */
final class SettingsChoiceList {
    private List<SettingsChoicePanel.Option> options = List.of(), filtered = List.of();
    private String query = "";
    private int offset, rows;

    void reset(List<SettingsChoicePanel.Option> options) {
        query = "";
        offset = 0;
        setOptions(options);
    }

    void setOptions(List<SettingsChoicePanel.Option> options) {
        this.options = List.copyOf(options);
        filter();
    }

    void search(String text) {
        String next = text.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) return;
        query = next;
        offset = 0;
        filter();
    }

    private void filter() {
        filtered = options.stream().filter(o -> o.label().toLowerCase(Locale.ROOT).contains(query)
                || o.value().toLowerCase(Locale.ROOT).contains(query)).toList();
        clamp();
    }

    void viewport(int rows) { this.rows = Math.max(0, rows); clamp(); }
    void scroll(int rows) { offset += rows; clamp(); }
    private void clamp() { offset = Math.max(0, Math.min(offset, Math.max(0, filtered.size() - rows))); }
    int size() { return filtered.size(); }
    List<SettingsChoicePanel.Option> visible() { return filtered.subList(offset, Math.min(filtered.size(), offset + rows)); }
}
