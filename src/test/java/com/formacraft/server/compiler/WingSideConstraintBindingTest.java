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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wing side cues must bind locally for height and roof omit — never spray across siblings. */
class WingSideConstraintBindingTest {

    @Test
    void westWingHeightDoesNotChangeEastWing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(wing("west_wing", -12, 8), wing("east_wing", 12, 8)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造东西厢房，西厢房高度12格。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(12, heightOf(out, "west_wing"));
        assertEquals(8, heightOf(out, "east_wing"),
                "east wing must keep original height when only west is scoped");
    }

    @Test
    void jointWestAndEastWingOmitRoofBoth() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        wingWithRoof("west_wing", -12),
                        wingWithRoof("east_wing", 12)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房和东厢房都不要屋顶。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals("none", roofOf(out, "west_wing"));
        assertEquals("none", roofOf(out, "east_wing"));
    }

    @Test
    void commaSeparatedWingOmitBindsIndependently() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        wingWithRoof("west_wing", -12),
                        wingWithRoof("east_wing", 12)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房不要屋顶，东厢房不要屋顶。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals("none", roofOf(out, "west_wing"));
        assertEquals("none", roofOf(out, "east_wing"));

        var omits = UserConstraintContract.parseDesignOmitBindings("西厢房不要屋顶，东厢房不要屋顶。");
        assertEquals(2, omits.size());
        assertTrue(omits.get(0).sourceText().contains("西"));
        assertFalse(omits.get(0).sourceText().contains("东"),
                "comma must bound the first omit clause away from 东厢房");
        assertTrue(omits.get(1).sourceText().contains("东"));
        assertFalse(omits.get(1).sourceText().contains("西"));
    }

    @Test
    void wingSidesFromTextReturnsBothSidesForJointSubject() {
        Set<String> sides = RequestIntentInjector.wingSidesFromText("西厢房和东厢房都不要屋顶");
        assertEquals(Set.of("west", "east"), sides);
        assertNull(RequestIntentInjector.wingSideHint("西厢房和东厢房都不要屋顶"),
                "joint subjects must not collapse to the first side only");
    }

    @Test
    void heightBindingKeepsWingClauseSourceText() {
        var bindings = UserConstraintContract.parseExplicitHeightBindings(
                "建造东西厢房，西厢房高度12格，东厢房高度8格。");
        assertEquals(2, bindings.size());
        assertNotNull(bindings.get(0).sourceText());
        assertTrue(bindings.get(0).sourceText().contains("西")
                        || bindings.get(0).namedKeys().contains("wing"),
                "first height clause must keep west/wing identity");
        assertTrue(bindings.stream().anyMatch(b ->
                b.sourceText() != null && b.sourceText().contains("西") && b.blocks() == 12));
        assertTrue(bindings.stream().anyMatch(b ->
                b.sourceText() != null && b.sourceText().contains("东") && b.blocks() == 8));
    }

    private static Component wing(String id, int x, int height) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "wing");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 0),
                new Dimensions(9, 13, height), List.of(), params);
    }

    private static Component wingWithRoof(String id, int x) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", "wing");
        params.put("roof_type", "gable");
        return new Component("MASS_MAIN", id, new Vec3i(x, 0, 0),
                new Dimensions(9, 13, 6), List.of(), params);
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
