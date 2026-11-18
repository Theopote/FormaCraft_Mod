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
        assertAdjacentStoreysConnected(masses);
    }

    @Test
    void equalEnvelopeTargetLeavesEveryStoreyGeometryUntouched() {
        int[] heights = {8, 8, 7, 6, 5};
        List<Component> tiers = stackedTiers(heights);
        List<int[]> before = captureGeom(tiers);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度34格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));

        List<Component> masses = pagodaTiers(out);
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        assertEquals(5, masses.size());
        for (int i = 0; i < masses.size(); i++) {
            assertEquals(before.get(i)[0], masses.get(i).relativePosition().y(),
                    "Y must stay put when envelope already matches: " + masses.get(i).params().get("component_id"));
            assertEquals(before.get(i)[1], masses.get(i).dimensions().height(),
                    "height must stay put when envelope already matches: "
                            + masses.get(i).params().get("component_id"));
            assertNotEquals(34, masses.get(i).dimensions().height(),
                    "must not stamp whole envelope onto a storey");
        }
        int minY = masses.getFirst().relativePosition().y();
        int maxTop = masses.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElseThrow();
        assertEquals(34, maxTop - minY);
    }

    @Test
    void fairShrinkTwentyFourFourToTwelveSucceeds() {
        // 20+4+4 → 12 with floor_count=1 (min band 4): must become 4+4+4, not reject.
        List<Component> tiers = stackedTiersWithFloors(new int[]{20, 4, 4}, 1);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度12格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        List<Component> masses = pagodaTiers(out);
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        assertEquals(3, masses.size());
        assertEquals(12, envelopeHeight(masses));
        for (Component c : masses) {
            assertEquals(4, c.dimensions().height(),
                    "fair min-band allocation must keep every storey usable");
        }
        assertAdjacentStoreysConnected(masses);
    }

    @Test
    void matchedEnvelopeWithOversizedFloorBandsReportsGap() {
        // Two tiers × 8 = 16 matches target, but floor_count=2 × floor_height=5 needs 10 each.
        List<Component> tiers = stackedTiersWithFloors(new int[]{8, 8}, 2);
        for (Component c : tiers) {
            c.params().put("floor_height", 5);
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度16格的宝塔。");
        assertTrue(out.hasCapabilityGap(), "matched envelope with impossible floor bands must gap");
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", out.capabilityGap().code());
        List<Component> masses = pagodaTiers(out);
        assertEquals(8, masses.get(0).dimensions().height());
        assertEquals(8, masses.get(1).dimensions().height());
    }

    @Test
    void matchedEnvelopeSealsHeightWithoutChangingGeometry() {
        List<Component> tiers = stackedTiers(new int[]{8, 8, 7, 6, 5});
        List<int[]> before = captureGeom(tiers);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度34格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        List<Component> masses = pagodaTiers(out);
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        for (int i = 0; i < masses.size(); i++) {
            assertEquals(before.get(i)[0], masses.get(i).relativePosition().y());
            assertEquals(before.get(i)[1], masses.get(i).dimensions().height());
            assertTrue(com.formacraft.common.typology.UserConstraintContract
                            .isServerVerified(masses.get(i).params(), "height"),
                    "matched envelope must still seal storey height");
        }
    }

    @Test
    void infeasibleShrinkReportsStackedHeightGapWithoutOverlappingTiers() {
        // floor_count=2 needs ≥8 band; five such tiers cannot fit into height 10.
        List<Component> tiers = stackedTiersWithFloors(new int[]{8, 8, 7, 6, 5}, 2);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度10格的宝塔。");
        assertTrue(out.hasCapabilityGap(), "infeasible shrink must gap");
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", out.capabilityGap().code());
        List<Component> masses = pagodaTiers(out);
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        // Original geometry preserved — no overlapping fabricated tiers.
        int y = 0;
        for (int i = 0; i < masses.size(); i++) {
            assertEquals(y, masses.get(i).relativePosition().y());
            assertEquals(new int[]{8, 8, 7, 6, 5}[i], masses.get(i).dimensions().height());
            y += masses.get(i).dimensions().height();
        }
    }

    @Test
    void scaledEnvelopeNotRegrownByFloorLayoutOverflowRepair() {
        // floor_count=2 × floor_height=5 needs 10; scale 50→45 yields ~9/storey — repair would
        // grow without the seal, but must clamp into the sealed envelope instead.
        List<Component> tiers = stackedTiersWithFloors(new int[]{10, 10, 10, 10, 10}, 2);
        for (Component c : tiers) {
            c.params().put("floor_height", 5);
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan injected = RequestIntentInjector.ensure(plan, "建造一座高度45格的宝塔。");
        assertFalse(injected.hasCapabilityGap(), () -> String.valueOf(injected.capabilityGap()));
        List<Component> beforeRepair = pagodaTiers(injected);
        beforeRepair.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        int envelopeBefore = envelopeHeight(beforeRepair);
        assertEquals(45, envelopeBefore);

        LlmPlan repaired = FloorLayoutOverflowRepair.apply(injected);
        List<Component> after = pagodaTiers(repaired);
        after.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        assertEquals(45, envelopeHeight(after),
                "FloorLayoutOverflowRepair must not re-grow sealed stacked envelope");
        for (Component c : after) {
            assertTrue(com.formacraft.common.typology.UserConstraintContract
                            .isServerVerified(c.params(), "height"),
                    "scaled storey height must remain sealed");
        }
    }

    @Test
    void stackedScaleKeepsRoofAndOpeningAttachedToHost() {
        List<Component> comps = new ArrayList<>(stackedTiers(new int[]{8, 8, 7, 6, 5}));
        // Roof on top tier; window mid first tier.
        Map<String, Object> roofParams = new HashMap<>();
        roofParams.put("component_id", "pagoda_roof");
        roofParams.put("host_id", "pagoda_tier_5");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of(), roofParams));
        Map<String, Object> winParams = new HashMap<>();
        winParams.put("component_id", "pagoda_window");
        winParams.put("host_id", "pagoda_tier_1");
        comps.add(new Component("FACADE_WINDOWS", "pagoda", new Vec3i(1, 2, 0),
                new Dimensions(2, 1, 2), List.of(), winParams));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));

        Component top = null;
        Component roof = null;
        Component window = null;
        for (Component c : out.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if ("pagoda_tier_5".equals(id)) top = c;
            if ("pagoda_roof".equals(id)) roof = c;
            if ("pagoda_window".equals(id)) window = c;
        }
        assertNotNull(top);
        assertNotNull(roof);
        assertNotNull(window);
        assertEquals(top.relativePosition().y() + top.dimensions().height(),
                roof.relativePosition().y(),
                "roof must stay on the scaled top plane");
        Component base = pagodaTiers(out).stream()
                .filter(c -> "pagoda_tier_1".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(base.relativePosition().y() + 2, window.relativePosition().y(),
                "window must keep offset from scaled host base");
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
        assertAdjacentStoreysConnected(masses);
    }

    @Test
    void distinctBuildingEntityCountCollapsesStackedStoreys() {
        assertEquals(1, RequestIntentInjector.distinctBuildingEntityCount(
                stackedTiers(new int[]{4, 4, 4, 4, 4})));
    }

    private static void assertAdjacentStoreysConnected(List<Component> masses) {
        masses.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        for (int i = 1; i < masses.size(); i++) {
            int prevTop = masses.get(i - 1).relativePosition().y()
                    + masses.get(i - 1).dimensions().height();
            assertEquals(prevTop, masses.get(i).relativePosition().y(),
                    "storeys must remain stacked without gap/overlap");
        }
    }

    private static int envelopeHeight(List<Component> masses) {
        int minY = masses.stream().mapToInt(c -> c.relativePosition().y()).min().orElse(0);
        int maxTop = masses.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElse(0);
        return maxTop - minY;
    }

    private static List<int[]> captureGeom(List<Component> tiers) {
        List<int[]> out = new ArrayList<>();
        for (Component c : tiers) {
            out.add(new int[]{c.relativePosition().y(), c.dimensions().height()});
        }
        return out;
    }

    private static List<Component> stackedTiers(int[] heights) {
        return stackedTiersWithFloors(heights, 0);
    }

    private static List<Component> stackedTiersWithFloors(int[] heights, int floorCount) {
        List<Component> tiers = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            if (floorCount > 0) {
                params.put("floor_count", floorCount);
            }
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
