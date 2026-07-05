package com.formacraft.client.ui.panel.settings;

import java.util.List;

/** Search engine provider presets for the settings panel cycle button. */
public final class SettingsSearchProviders {
    public record Entry(String id, String label) {}

    public static final List<Entry> CYCLE = List.of(
            new Entry("auto", "自动（Wikipedia → API → DuckDuckGo）"),
            new Entry("duckduckgo", "DuckDuckGo（免费）"),
            new Entry("bing", "Bing Search API"),
            new Entry("google_cse", "Google 自定义搜索"),
            new Entry("wikipedia_only", "仅 Wikipedia")
    );

    private SettingsSearchProviders() {}
}
