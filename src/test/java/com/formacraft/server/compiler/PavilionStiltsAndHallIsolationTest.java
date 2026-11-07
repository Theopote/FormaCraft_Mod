package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.FacadeDirection;
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

/** Round-25: stilts negation/scope, main_pavilion hall isolation, entrance-face stair snap. */
class PavilionStiltsAndHallIsolationTest {

    @Test
    void noStiltsWhenMassMainOnlySaysNoElevatedWoodenPosts() {
        Component ting = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 5), List.of("六角亭", "木柱", "不需要架空"),
                mutable("component_id", "garden_hex_ting", "shape", "hexagon",
                        "building_role", "pavilion", "anchor_mode", "min_corner"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("普通庭院凉亭", "不需要架空", "使用木柱"))
                .components(List.of(ting))
                .build());
        assertEquals(1, repaired.components().size(), "must not inject stilts foundation");
        assertFalse(repaired.components().stream().anyMatch(c ->
                "FOUNDATION".equalsIgnoreCase(c.componentType())
                        && c.params() != null
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))));
        Component out = repaired.components().getFirst();
        assertEquals(Boolean.TRUE, out.params().get("open"));
    }

    @Test
    void waterPavilionStiltsDoNotBleedToSiblingGroundPavilion() {
        Component water = new Component(
                "MASS_MAIN", "east", new Vec3i(20, 0, 0),
                new Dimensions(8, 8, 5), List.of("水上亭", "pavilion"),
                mutable("component_id", "east_water_pavilion", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
        Component ground = new Component(
                "MASS_MAIN", "west", new Vec3i(-20, 0, 0),
                new Dimensions(9, 9, 5), List.of("六角亭", "石质台基"),
                mutable("component_id", "west_hex_pavilion", "shape", "hexagon",
                        "building_role", "pavilion", "anchor_mode", "min_corner"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("园林", "东侧水上亭", "西侧六角亭"))
                .components(List.of(water, ground))
                .build());
        assertTrue(repaired.components().stream().anyMatch(c ->
                c.params() != null
                        && "east_water_pavilion".equals(String.valueOf(c.params().get("host_id")))
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))),
                "water pavilion should get stilts");
        assertFalse(repaired.components().stream().anyMatch(c ->
                c.params() != null
                        && "west_hex_pavilion".equals(String.valueOf(c.params().get("host_id")))
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))),
                "ground hex pavilion must not inherit sibling stilts");
    }

    @Test
    void mainPavilionSlotMainStillOpensAsHexPavilion() {
        Component ting = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 5), List.of("六角亭"),
                mutable("component_id", "main_pavilion", "building_role", "pavilion",
                        "shape", "hexagon", "anchor_mode", "min_corner"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(ting))
                .build());
        Component out = repaired.components().getFirst();
        assertEquals(Boolean.TRUE, out.params().get("open"), "main_pavilion must not skip open-frame");
        assertEquals("none", String.valueOf(out.params().get("wall_type")));
        assertEquals("hexagon", String.valueOf(out.params().get("shape")));
        assertEquals(6, ((Number) out.params().get("sides")).intValue());
        assertEquals(Boolean.TRUE, out.params().get("hollow"));
    }

    @Test
    void defaultStairSnapMatchesSatelliteEntranceFaceForAllCardinals() {
        for (String facing : List.of("SOUTH", "NORTH", "EAST", "WEST")) {
            Component host = new Component(
                    "STRUCTURE", "main", new Vec3i(0, 0, 0),
                    new Dimensions(14, 12, 8), List.of("typology:japanese_haiden"),
                    Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden",
                            "facing", facing));
            Component steps = new Component(
                    "STRUCTURE", "main", new Vec3i(4, 0, 14),
                    new Dimensions(4, 6, 3), List.of(),
                    mutable("component_id", "haiden_steps", "host_id", "haiden_main",
                            "facing", facing));
            LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                    .components(List.of(host, steps))
                    .build());
            Component out = byId(repaired, "haiden_steps");
            assertEquals("STAIR_SYSTEM", out.componentType());
            assertNull(AssemblyCirculationOps.flightError(out.params().get("from"), out.params().get("to")));

            int[] thr = FacadeDirection.frontEntranceThresholdXZ(0, 0, 14, 12, facing);
            @SuppressWarnings("unchecked")
            Map<String, Object> to = (Map<String, Object>) out.params().get("to");
            int topX = out.relativePosition().x() + ((Number) to.get("x")).intValue();
            int topZ = out.relativePosition().z() + ((Number) to.get("z")).intValue();
            assertEquals(thr[0], topX, "facing=" + facing + " topX");
            assertEquals(thr[1], topZ, "facing=" + facing + " topZ");
        }
    }

    @Test
    void verifiedRoofNoneIsPreservedOnOpenPavilion() {
        Map<String, Object> params = mutable(
                "component_id", "open_colonnade",
                "building_role", "pavilion",
                "shape", "hexagon",
                "roof_type", "none",
                "anchor_mode", "min_corner");
        UserConstraintContract.stampVerified(params, "roof_type");
        Component ting = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of("无屋顶", "柱廊"),
                params);
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(ting))
                .build());
        assertEquals("none", String.valueOf(byId(repaired, "open_colonnade").params().get("roof_type")));
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
