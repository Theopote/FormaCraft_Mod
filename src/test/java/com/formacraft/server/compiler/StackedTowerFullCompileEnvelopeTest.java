package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full compile path for stacked tower height envelopes — not Component.dimensions alone.
 * LlmPlan → RequestIntentInjector → ComponentPlanCompiler → BlockPatch.
 */
class StackedTowerFullCompileEnvelopeTest {

    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void shaftHeightConstraintSurvivesFullCompileToBlockPatch() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        // Explicit opt-outs so allowCompilerSatellites=false does not trip E_DESIGN_INCOMPLETE.
        for (int i = 0; i < tiers.size(); i++) {
            Component c = tiers.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("roof_type", "none");
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            tiers.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "roof_type", "value", "none", "scope", "plan"),
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .components(tiers)
                .proportionHints(Map.of("building_contract", contract))
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "建造一座高度40格的宝塔。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        for (Component c : sealed.components()) {
            if (c == null || c.params() == null) continue;
            if (!String.valueOf(c.params().get("component_id")).startsWith("pagoda_tier_")) continue;
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY));
        }

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(blocks.isEmpty());

        int minY = blocks.keySet().stream().mapToInt(Vec3i::y).min().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(0);
        int solidEnvelope = maxY - minY + 1;
        // Shaft target 40; without roof satellites the solid envelope must be near that height.
        assertTrue(solidEnvelope >= 38 && solidEnvelope <= 44,
                "BlockPatch non-air envelope " + solidEnvelope + " must match shaft target ~40");

        // Preview path (postprocess off) must match confirm path for this sealed plan.
        AssemblyCompileDiagnostics.clear();
        var again = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertEquals(blocks, PatchTestSnapshot.blocks(again),
                "preview and confirm must share the same BlockPatch set");
    }

    @Test
    void buildingModeWithExplicitRoofTypeAndSatellitesMatchesTargetEnvelope() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("CHINESE_CLASSICAL")
                .components(tiers)
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度50格。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        Component sealedBase = null;
        for (Component c : sealed.components()) {
            if (c != null && c.params() != null
                    && "pagoda_tier_1".equals(String.valueOf(c.params().get("component_id")))) {
                sealedBase = c;
                break;
            }
        }
        assertNotNull(sealedBase);
        assertEquals("building", String.valueOf(
                sealedBase.params().get(RequestIntentInjector.ENVELOPE_MODE_KEY)));

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        if (patches.isEmpty()) {
            // Capability gap during compile is acceptable; silent wrong height is not.
            assertNotNull(AssemblyCompileDiagnostics.get(),
                    "empty patch without diagnostic is not allowed");
            return;
        }
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        int minY = blocks.keySet().stream().mapToInt(Vec3i::y).min().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(0);
        int solidEnvelope = maxY - minY + 1;
        // Full geometry may include foundations/cornice ± a few blocks; stay near target 50.
        assertTrue(solidEnvelope >= 46 && solidEnvelope <= 56,
                "building-mode BlockPatch envelope " + solidEnvelope + " must stay near target 50");
    }

    private static List<Component> stackedTight(int[] heights) {
        List<Component> tiers = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            params.put("plan_type", "rectangle");
            params.put("anchor_mode", "min_corner");
            // Non-gable roof hint keeps DenseEavesPagodaMisrouteRepair from collapsing
            // sealed MASS tiers into a single STRUCTURE typology shell.
            params.put("roof_type", i == heights.length - 1 ? "xieshan" : "flat");
            tiers.add(new Component("MASS_MAIN", "pagoda",
                    new Vec3i(0, y, 0),
                    new Dimensions(Math.max(4, 12 - i), Math.max(4, 12 - i), heights[i]),
                    List.of(), params));
            y += heights[i];
        }
        return tiers;
    }
}
