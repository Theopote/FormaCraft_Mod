package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Wing named identity must not bind pavilion when both exist in a compound. */
class WingPavilionIdentityBindingTest {

    @Test
    void westWingHeightDoesNotAffectPavilionOrEastWing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        hall("main_hall", 0, 10),
                        wing("west_wing", -14, 8),
                        wing("east_wing", 14, 8),
                        pavilion("garden_pavilion", 0, 6)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房高度12格。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(12, heightOf(out, "west_wing"));
        assertEquals(8, heightOf(out, "east_wing"));
        assertEquals(6, heightOf(out, "garden_pavilion"),
                "pavilion must not receive wing height via roleHint=pavilion");
        assertEquals(10, heightOf(out, "main_hall"));
    }

    @Test
    void westWingRoofOmitDoesNotAffectPavilionOrEastWing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        hall("main_hall", 0, 10),
                        wingWithRoof("west_wing", -14),
                        wingWithRoof("east_wing", 14),
                        pavilionWithRoof("garden_pavilion", 0)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房不要屋顶。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals("none", roofOf(out, "west_wing"));
        assertEquals("gable", roofOf(out, "east_wing"));
        assertEquals("xieshan", roofOf(out, "garden_pavilion"),
                "pavilion roof must stay when only west wing is omitted");
    }

    @Test
    void wingRoleHintIsNotCollapsedToPavilion() {
        var heights = UserConstraintContract.parseExplicitHeightBindings("西厢房高度12格");
        assertEquals(1, heights.size());
        assertEquals("wing", heights.get(0).roleHint());
        assertEquals(List.of("wing"), heights.get(0).namedKeys());

        var omits = UserConstraintContract.parseDesignOmitBindings("西厢房不要屋顶");
        assertEquals(1, omits.size());
        assertEquals("wing", omits.get(0).roleHint());
        assertEquals(List.of("wing"), omits.get(0).namedKeys());
    }

    private static Component hall(String id, int x, int h) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "hall");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 0),
                new Dimensions(15, 11, h), List.of(), params);
    }

    private static Component wing(String id, int x, int h) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "wing");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 0),
                new Dimensions(9, 13, h), List.of(), params);
    }

    private static Component wingWithRoof(String id, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "wing");
        params.put("roof_type", "gable");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 0),
                new Dimensions(9, 13, 6), List.of(), params);
    }

    private static Component pavilion(String id, int x, int h) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "pavilion");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 8),
                new Dimensions(7, 7, h), List.of(), params);
    }

    private static Component pavilionWithRoof(String id, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "pavilion");
        params.put("roof_type", "xieshan");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 8),
                new Dimensions(7, 7, 6), List.of(), params);
    }

    private static int heightOf(LlmPlan plan, String id) {
        for (Component c : plan.components()) {
            if (c != null && c.params() != null
                    && id.equals(String.valueOf(c.params().get("component_id")))) {
                return c.dimensions().height();
            }
        }
        fail("missing " + id);
        return -1;
    }

    private static String roofOf(LlmPlan plan, String id) {
        for (Component c : plan.components()) {
            if (c != null && c.params() != null
                    && id.equals(String.valueOf(c.params().get("component_id")))) {
                Object roof = c.params().get("roof_type");
                if (roof == null) roof = c.params().get("roofType");
                return roof == null ? "" : String.valueOf(roof);
            }
        }
        fail("missing " + id);
        return "";
    }
}
