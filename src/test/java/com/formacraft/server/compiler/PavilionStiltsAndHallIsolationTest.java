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
    void playerForbidBeatsLlmStiltsParams() {
        Component ting = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of("凉亭", "不要架空"),
                mutable("component_id", "llm_stilts_ting", "building_role", "pavilion",
                        "foundation_type", "stilts", "foundation_mode", "STILTS",
                        "anchor_mode", "min_corner"));
        Component stilts = new Component(
                "FOUNDATION", "main", new Vec3i(0, -12, 0),
                new Dimensions(8, 8, 12), List.of(),
                mutable("component_id", "llm_stilts_pad", "host_id", "llm_stilts_ting",
                        "foundation_type", "stilts", "material", "minecraft:oak_log"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("庭院凉亭", "不要架空"))
                .components(List.of(ting, stilts))
                .build());
        assertFalse(repaired.components().stream().anyMatch(c ->
                "FOUNDATION".equalsIgnoreCase(c.componentType())
                        && c.params() != null
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))),
                "LLM stilts must be stripped when player forbids");
        Component mass = byId(repaired, "llm_stilts_ting");
        assertNotEquals("stilts", String.valueOf(mass.params().get("foundation_type")).toLowerCase());
        assertEquals("GROUND", String.valueOf(mass.params().get("foundation_mode")));
    }

    @Test
    void waterPavilionOnStonePlatformSkipsStiltsWhenPlayerForbids() {
        Component ting = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of("水上亭"),
                mutable("component_id", "stone_deck_ting", "building_role", "pavilion",
                        "anchor_mode", "min_corner"));
        Component plinth = new Component(
                "FOUNDATION", "main", new Vec3i(0, -1, 0),
                new Dimensions(10, 10, 1), List.of("石质平台"),
                mutable("component_id", "stone_deck", "host_id", "stone_deck_ting",
                        "foundation_type", "plinth", "material", "minecraft:stone_bricks"));
        LlmPlan repaired = PavilionOpenFrameRepair.apply(LlmPlanTestFixtures.builder()
                .distinguishingFeatures(List.of("水上亭", "直接落在石质平台上", "不要架空"))
                .components(List.of(ting, plinth))
                .build());
        assertTrue(repaired.components().stream().anyMatch(c ->
                c.params() != null && "stone_deck".equals(String.valueOf(c.params().get("component_id")))));
        assertFalse(repaired.components().stream().anyMatch(c ->
                c.params() != null
                        && "stilts".equalsIgnoreCase(String.valueOf(c.params().get("foundation_type")))),
                "plan forbid must beat 水上 keyword; keep stone platform without piles");
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
    void approachStairsFollowEntranceAfterSatelliteRealign() {
        Component host = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of(),
                mutable("component_id", "haiden_main", "facing", "SOUTH",
                        "anchor_mode", "min_corner", "wall_thickness", 1));
        // LLM door on the wrong (rear) face — aligner rebuilds to front (z=min).
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(5, 1, 11),
                new Dimensions(4, 1, 4), List.of("entrance"),
                mutable("component_id", "haiden_door", "host_id", "haiden_main"));
        Component steps = new Component(
                "STAIR_SYSTEM", "main", new Vec3i(4, -1, 8),
                new Dimensions(3, 4, 3), List.of("stair:straight_single_run"),
                mutable("component_id", "haiden_steps", "host_id", "haiden_main",
                        "exterior", true, "approach_stair", true, "facing", "SOUTH",
                        "from", Map.of("x", 1, "y", 0, "z", 0),
                        "to", Map.of("x", 1, "y", 2, "z", 3)));
        var prepared = ComponentSatelliteInference.prepareComponents(
                LlmPlanTestFixtures.builder().components(List.of(host, door, steps)).build(),
                Map.of(), false);
        Component outDoor = prepared.components().stream()
                .filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        Component outSteps = prepared.components().stream()
                .filter(c -> "haiden_steps".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> to = (Map<String, Object>) outSteps.params().get("to");
        int topX = outSteps.relativePosition().x() + ((Number) to.get("x")).intValue();
        int topY = outSteps.relativePosition().y() + ((Number) to.get("y")).intValue();
        int topZ = outSteps.relativePosition().z() + ((Number) to.get("z")).intValue();
        int doorMidX = outDoor.relativePosition().x() + outDoor.dimensions().width() / 2;
        int doorMidZ = outDoor.relativePosition().z() + outDoor.dimensions().depth() / 2;
        assertEquals(doorMidX, topX);
        assertEquals(outDoor.relativePosition().y(), topY);
        assertEquals(doorMidZ, topZ);
        assertTrue(outDoor.relativePosition().z() <= 1,
                "realigned door should sit on front (z≈0), was " + outDoor.relativePosition());
    }

    @Test
    void stairGuardAloneDoesNotSuppressInteriorStairInference() {
        Component host = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(11, 11, 9), List.of(),
                mutable("component_id", "three_storey_hall", "floor_count", 3,
                        "floor_height", 4, "wall_thickness", 1, "anchor_mode", "min_corner"));
        Component guard = new Component(
                "STRUCTURE", "main", new Vec3i(4, 1, 4),
                new Dimensions(1, 3, 3), List.of(),
                mutable("component_id", "hall_stair_guard_0", "host_id", "three_storey_hall"));
        List<Component> parts = new java.util.ArrayList<>(List.of(host, guard));
        InteriorStairInferenceCompiler.apply(parts);
        assertTrue(parts.stream().anyMatch(c ->
                        "STAIR_SYSTEM".equalsIgnoreCase(c.componentType())
                                && c.params() != null
                                && Boolean.TRUE.equals(c.params().get("compiler_inferred_stair"))),
                "rail/guard furniture must not block missing STAIR_SYSTEM inference");
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
