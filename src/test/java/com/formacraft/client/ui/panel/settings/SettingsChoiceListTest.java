package com.formacraft.client.ui.panel.settings;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SettingsChoiceListTest {
    @Test
    void allModelsRemainReachableBeyondTheOldTwelveItemLimit() {
        var list = new SettingsChoiceList();
        var options = IntStream.range(0, 200).mapToObj(i -> new SettingsChoicePanel.Option("vendor/model-" + i, "Model " + i)).toList();
        list.reset(options);
        list.viewport(4);
        assertEquals(options.subList(0, 4), list.visible());
        list.scroll(1000);
        assertEquals(options.subList(196, 200), list.visible());
        list.viewport(10);
        assertEquals(options.subList(190, 200), list.visible());
        list.scroll(-1000);
        assertEquals(options.subList(0, 10), list.visible());
    }

    @Test
    void searchingFromTheBottomResetsTheViewportAndMatchesIdsOrLabels() {
        var list = new SettingsChoiceList();
        list.reset(List.of(new SettingsChoicePanel.Option("vendor/custom", "自定义模型"),
                new SettingsChoicePanel.Option("other", "Other")));
        list.viewport(1);
        list.scroll(10);
        list.search(" VENDOR/ ");
        assertEquals("vendor/custom", list.visible().getFirst().value());
        list.search("自定义");
        assertEquals(1, list.size());
        list.search("missing");
        assertTrue(list.visible().isEmpty());
        list.search("");
        assertEquals("vendor/custom", list.visible().getFirst().value());
    }

    @Test
    void asyncRefreshKeepsTheSearchAndClampsAfterResultsShrink() {
        var list = new SettingsChoiceList();
        list.reset(List.of());
        list.viewport(3);
        list.search("local");
        list.setOptions(List.of(new SettingsChoicePanel.Option("local-model", "Local"),
                new SettingsChoicePanel.Option("remote", "Remote")));
        assertEquals(1, list.size());
        assertEquals("local-model", list.visible().getFirst().value());
        list.setOptions(List.of());
        assertTrue(list.visible().isEmpty());
    }
}
