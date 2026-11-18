package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Whole-building height must rescale stacked storeys — never copy height onto every tier. */
class StackedTowerHeightEnvelopeTest {

    @Test
    void stackedPagodaWholeHeightRescalesEnvelopeWithoutCopyingPerTier() {
        List<Component> tiers = stackedTiers(new int[]{8, 8, 7, 6, 5});
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));

        List<Component> masses = pagodaTiers(out);
        assertEquals(5, masses.size());
        for (Component c : masses) {
            assertNotEquals(50, c.dimensions().height(),
                    "must not copy whole-building height onto each storey: "
                            + c.params().get("component_id"));
        }
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        int minY = masses.getFirst().relativePosition().y();
        int maxTop = masses.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElseThrow();
        assertEquals(50, maxTop - minY);
        for (int i = 1; i < masses.size(); i++) {
            assertTrue(masses.get(i).relativePosition().y()
                    >= masses.get(i - 1).relativePosition().y());
        }
    }

    @Test
    void unscopedHeightOnStackedTowerIsOneBuildingNotMultiMassGap() {
        List<Component> tiers = stackedTiers(new int[]{6, 6, 6});
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度30格的建筑。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        List<Component> masses = pagodaTiers(out);
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        int minY = masses.getFirst().relativePosition().y();
        int maxTop = masses.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElseThrow();
        assertEquals(30, maxTop - minY);
        for (Component c : masses) {
            assertNotEquals(30, c.dimensions().height(),
                    "unscoped whole height must not stamp each storey to 30");
        }
    }

    @Test
    void distinctBuildingEntityCountCollapsesStackedStoreys() {
        assertEquals(1, RequestIntentInjector.distinctBuildingEntityCount(
                stackedTiers(new int[]{4, 4, 4, 4, 4})));
    }

    private static List<Component> stackedTiers(int[] heights) {
        List<Component> tiers = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            tiers.add(new Component(
                    "MASS_MAIN", "pagoda",
                    new Vec3i(0, y, 0),
                    new Dimensions(Math.max(4, 12 - i), Math.max(4, 12 - i), heights[i]),
                    List.of(),
                    params));
            y += heights[i];
        }
        return tiers;
    }

    private static List<Component> pagodaTiers(LlmPlan plan) {
        List<Component> out = new ArrayList<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (id.startsWith("pagoda_tier_")) out.add(c);
        }
        return out;
    }
}
