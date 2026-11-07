package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCirculationOps;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-24 pavilion generation contract: MASS_MAIN-only hex still gets a roof,
 * USER_EXPLICIT post height is not clamped to 5, courtyard pavilions skip stilts,
 * and approach stairs snap to the host entrance while preserving authored stairs blocks.
 */
class PavilionGenerationContractTest {

    @Test
    void massMainOnlyHexPavilionInfersPyramidRoof() {
        Map<String, Object> params = mutable(
                "component_id", "hex_ting",
                "shape", "hexagon",
                "building_role", "pavilion",
                "anchor_mode", "min_corner");
        Component mass = new Component(
                "MASS_MAIN", "p", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 5), List.of("六角亭", "攒尖"),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("六角攒尖亭"))
                .components(List.of(mass))
                .build();

        LlmPlan afterOpen = PavilionOpenFrameRepair.apply(plan);
        Component opened = byId(afterOpen, "hex_ting");
        assertNotEquals("none", String.valueOf(opened.params().get("roof_type")).toLowerCase());
        assertEquals("pyramid", String.valueOf(opened.params().get("preferred_roof_type")).toLowerCase());
        assertFalse(com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(opened));

        var prepared = ComponentSatelliteInference.prepareComponents(afterOpen, Map.of(), false);
        assertTrue(prepared.components().stream()
                        .anyMatch(c -> "ROOF".equalsIgnoreCase(c.componentType())),
                "MASS_MAIN-only pavilion must still get a satellite ROOF");
        Component roof = prepared.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(roof.params().get("roof_type")).toLowerCase().contains("pyramid"),
                "expected pyramid roof, got " + roof.params().get("roof_type"));
    }

    @Test
    void sealedEightBlockHexAndSoftFiveBlockOctagonKeepOwnHeights() {
        Map<String, Object> hexParams = mutable(
                "component_id", "tall_hex",
                "shape", "hexagon",
                "building_role", "pavilion",
                "anchor_mode", "min_corner",
                "height", 8);
        UserConstraintContract.applyAuthoritativeHeightValue(hexParams, 8);
        Component hex = new Component(
                "MASS_MAIN", "east", new Vec3i(20, 0, 0),
                new Dimensions(9, 9, 8), List.of("六角亭", "pavilion"),
                hexParams);
        Component oct = new Component(
                "MASS_MAIN", "west", new Vec3i(-20, 0, 0),
                new Dimensions(9, 9, 7), List.of("八角亭", "pavilion"),
                mutable("component_id", "soft_oct", "shape", "octagon",
                        "plan_type", "octagon", "building_role", "pavilion",
                        "anchor_mode", "min_corner", "height", 7));

        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("六角亭", "八角亭"))
                .components(List.of(hex, oct))
                .build());

        Component hexOut = byId(repaired, "tall_hex");
        Component octOut = byId(repaired, "soft_oct");
        assertEquals(8, hexOut.dimensions().height(), "USER_EXPLICIT 8-block hex must not clamp to 5");
        assertEquals(8, ((Number) hexOut.params().get("height")).intValue());
        assertEquals(5, octOut.dimensions().height(), "soft pavilion still uses 3–5 recommendation");
    }

    @Test
    void courtyardHexPavilionDoesNotInjectTwelveDeepOakStilts() {
        Component ting = new Component(
                "MASS_MAIN", "p", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of("六角亭", "庭院"),
                mutable("component_id", "courtyard_ting", "shape", "hexagon",
                        "building_role", "pavilion", "anchor_mode", "min_corner"));
        Component stonePad = new Component(
                "FOUNDATION", "p", new Vec3i(-1, -1, -1),
                new Dimensions(10, 10, 1), List.of("石质台基"),
                mutable("component_id", "ting_plinth", "host_id", "courtyard_ting",
                        "material", "minecraft:stone_bricks", "foundation_type", "pad"));

        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("庭院六角亭", "石质台基", "不需要架空"))
                .components(List.of(ting, stonePad))
                .build());

        assertEquals(2, repaired.components().size(), "must not inject stilts foundation");
        Component pad = byId(repaired, "ting_plinth");
        assertEquals("FOUNDATION", pad.componentType());
        assertNotEquals("stilts", String.valueOf(pad.params().get("foundation_type")).toLowerCase());
        assertFalse(repaired.components().stream().anyMatch(c ->
                c.params() != null
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))
                        && Integer.valueOf(12).equals(c.dimensions() == null ? null : c.dimensions().height())));
    }

    @Test
    void approachStairsSnapToHostEntranceAndPreserveAuthoredStairsBlock() {
        Component main = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of("typology:japanese_haiden"),
                Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden",
                        "facing", "SOUTH"));
        Component entrance = new Component(
                "ENTRANCE", "main", new Vec3i(6, 1, 11),
                new Dimensions(2, 1, 3), List.of("door"),
                Map.of("component_id", "haiden_door", "host_id", "haiden_main"));
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, 14),
                new Dimensions(4, 3, 3), List.of(),
                mutable("component_id", "haiden_steps", "host_id", "haiden_main",
                        "stairs", "minecraft:andesite_stairs", "facing", "SOUTH"));

        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, entrance, steps))
                .build());
        Component out = byId(repaired, "haiden_steps");
        assertEquals("STAIR_SYSTEM", out.componentType());
        assertEquals("minecraft:andesite_stairs", out.params().get("stairs"),
                "authored stairs block must not be remapped away");
        assertNull(AssemblyCirculationOps.flightError(out.params().get("from"), out.params().get("to")));

        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) out.params().get("to");
        int topX = out.relativePosition().x() + ((Number) to.get("x")).intValue();
        int topY = out.relativePosition().y() + ((Number) to.get("y")).intValue();
        int topZ = out.relativePosition().z() + ((Number) to.get("z")).intValue();
        int expectX = entrance.relativePosition().x() + entrance.dimensions().width() / 2;
        int expectZ = entrance.relativePosition().z() + entrance.dimensions().depth() / 2;
        assertEquals(expectX, topX);
        assertEquals(entrance.relativePosition().y(), topY);
        assertEquals(expectZ, topZ);
    }

    private static Component byId(LlmPlan plan, String id) {
        return plan.components().stream()
                .filter(c -> c.params() != null && id.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + id));
    }

    private static Map<String, Object> mutable(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
