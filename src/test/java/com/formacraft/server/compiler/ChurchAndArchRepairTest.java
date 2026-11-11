package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolation for church gothic strip + triumphal through-passage + greenhouse octagon. */
class ChurchAndArchRepairTest {

    @Test
    void brutalistChurchStripsGothicCathedralTypology() {
        Component mass = new Component(
                "MASS_MAIN", "church", new Vec3i(0, 0, 0),
                new Dimensions(24, 36, 16), List.of("church", "nave"),
                Map.of(
                        "component_id", "brutalist_church",
                        "typology_id", "gothic_cathedral_hall",
                        "building_role", "hall_main"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("建一座野兽派教堂")
                .distinguishingFeatures(List.of("brutalist", "教堂"))
                .components(List.of(mass))
                .build();

        LlmPlan repaired = ChurchGothicMisrouteRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertNull(out.params().get("typology_id"));
    }

    @Test
    void explicitGothicChurchKeepsTypology() {
        Component mass = new Component(
                "MASS_MAIN", "church", new Vec3i(0, 0, 0),
                new Dimensions(24, 36, 16), List.of("gothic", "church"),
                Map.of(
                        "component_id", "gothic_church",
                        "typology_id", "gothic_cathedral_hall"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("建一座哥特式教堂")
                .distinguishingFeatures(List.of("gothic", "教堂"))
                .components(List.of(mass))
                .build();

        LlmPlan repaired = ChurchGothicMisrouteRepair.apply(plan);
        assertEquals("gothic_cathedral_hall",
                String.valueOf(repaired.components().getFirst().params().get("typology_id")));
    }

    @Test
    void triumphalArchExpandsThinEntranceThroughHostDepth() {
        Component host = new Component(
                "MASS_MAIN", "arch", new Vec3i(0, 0, 0),
                new Dimensions(21, 8, 14), List.of("凯旋门"),
                mutable(
                        "component_id", "arch_body",
                        "facing", "SOUTH",
                        "anchor_mode", "min_corner",
                        "typology_id", "triumphal_arch"));
        Component door = new Component(
                "ENTRANCE", "portal", new Vec3i(7, 0, 7),
                new Dimensions(5, 1, 6), List.of(),
                mutable(
                        "component_id", "central_bay",
                        "host_id", "arch_body",
                        "facing", "SOUTH",
                        "anchor_mode", "min_corner"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("巴洛克凯旋门")
                .distinguishingFeatures(List.of("baroque", "凯旋门"))
                .components(List.of(host, door))
                .build();

        LlmPlan repaired = TriumphalArchPassageRepair.apply(plan);
        Component hostOut = byId(repaired, "arch_body");
        Component doorOut = byId(repaired, "central_bay");

        assertEquals(Boolean.TRUE, hostOut.params().get("through_passage"));
        assertEquals(Boolean.TRUE, hostOut.params().get("hollow"));
        assertNull(hostOut.params().get("typology_id"));
        assertEquals(5, doorOut.dimensions().width());
        assertEquals(8, doorOut.dimensions().depth());
        assertEquals(0, doorOut.relativePosition().z());
    }

    @Test
    void westFacingTriumphalArchKeepsBayWidthNotFullHostWidth() {
        Component plinth = new Component(
                "FOUNDATION", "arch", new Vec3i(-11, 0, -5),
                new Dimensions(23, 11, 2), List.of("plinth"),
                mutable("component_id", "arch_plinth", "anchor_mode", "min_corner", "host_id", "arch_body"));
        Component host = new Component(
                "MASS_MAIN", "arch", new Vec3i(0, 2, 0),
                new Dimensions(21, 9, 12), List.of("凯旋门", "three_bay"),
                mutable(
                        "component_id", "arch_body",
                        "facing", "WEST",
                        "typology_id", "triumphal_arch"));
        Component door = new Component(
                "ENTRANCE", "arch", new Vec3i(-2, 2, -4),
                new Dimensions(5, 1, 8), List.of("central_arched_passage"),
                mutable(
                        "component_id", "entrance_central",
                        "host_id", "arch_body",
                        "facing", "WEST",
                        "clear_width", 5));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("三跨双向对称的巴洛克凯旋门")
                .distinguishingFeatures(List.of("三跨", "凯旋门"))
                .components(List.of(plinth, host, door))
                .build();

        LlmPlan repaired = TriumphalArchPassageRepair.apply(plan);
        Component hostOut = byId(repaired, "arch_body");
        Component doorOut = byId(repaired, "entrance_central");

        assertEquals(-10, hostOut.relativePosition().x());
        assertEquals(-4, hostOut.relativePosition().z());
        assertEquals("min_corner", String.valueOf(hostOut.params().get("anchor_mode")));
        assertEquals(5, doorOut.dimensions().width());
        assertEquals(9, doorOut.dimensions().depth());
        assertTrue(doorOut.dimensions().width() < hostOut.dimensions().width());
    }

    @Test
    void octagonGreenhouseForcesSquareCentreAlignedBody() {
        Component body = new Component(
                "MASS_MAIN", "greenhouse_main", new Vec3i(0, 1, 0),
                new Dimensions(8, 6, 8), List.of("正八角形平面", "玻璃表皮"),
                mutable(
                        "component_id", "gh_body",
                        "anchor_mode", "center",
                        "shape", "octagon",
                        "radius", 7,
                        "wall_block", "minecraft:glass",
                        "glazing_ratio", 0.85));
        Component columns = new Component(
                "DECOR_DETAIL", "greenhouse_main", new Vec3i(-4, 1, -3),
                new Dimensions(8, 6, 8), List.of("角柱"),
                mutable(
                        "component_id", "gh_frame_columns",
                        "host_id", "gh_body",
                        "anchor_mode", "min_corner",
                        "kind", "column_ring"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("边长为 5 的正八角形双层植物温室")
                .distinguishingFeatures(List.of("正八角", "温室"))
                .components(List.of(body, columns))
                .build();

        LlmPlan repaired = OctagonGreenhouseRepair.apply(plan);
        Component bodyOut = byId(repaired, "gh_body");
        Component colOut = byId(repaired, "gh_frame_columns");

        assertEquals(bodyOut.dimensions().width(), bodyOut.dimensions().depth());
        assertTrue(bodyOut.dimensions().width() >= 13);
        assertEquals(0, colOut.relativePosition().x());
        assertEquals(0, colOut.relativePosition().z());
        assertEquals("center", String.valueOf(colOut.params().get("anchor_mode")));
    }

    @Test
    void octagonGreenhouseKeepsGlazedShellAndNativeOctagon() {
        Component greenhouse = new Component(
                "MASS_MAIN", "gh", new Vec3i(0, 0, 0),
                new Dimensions(15, 15, 10), List.of("正八角", "温室", "greenhouse"),
                mutable(
                        "component_id", "octagon_greenhouse",
                        "plan_type", "octagon",
                        "building_role", "greenhouse",
                        "window_ratio", 0.7,
                        "open", true,
                        "wall_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .playerFidelityNoticeZh("正八角形双层植物温室")
                .distinguishingFeatures(List.of("正八角", "温室"))
                .components(List.of(greenhouse))
                .build();

        LlmPlan afterShape = FootprintShapeRemapRepair.apply(plan);
        LlmPlan afterOpen = PavilionOpenFrameRepair.apply(afterShape);
        Component out = afterOpen.components().getFirst();

        assertEquals("octagon", String.valueOf(out.params().get("shape")).toLowerCase());
        assertEquals(8, ((Number) out.params().get("sides")).intValue());
        assertEquals("none", String.valueOf(out.params().get("plan_type")).toLowerCase());
        assertEquals(10, out.dimensions().height());
        assertNotEquals(Boolean.TRUE, out.params().get("open"));
        assertNotEquals(Boolean.TRUE, out.params().get("suppress_windows"));
        assertNull(out.params().get("wall_type"));

        ResolvedFootprint fp = ResolvedFootprint.resolve(out, 15, 15);
        assertEquals(ResolvedFootprint.FootprintShape.OCTAGON, fp.shape());
        assertTrue(fp.contains(7, 7));
        assertFalse(fp.contains(0, 0));
    }

    private static Component byId(LlmPlan plan, String id) {
        return plan.components().stream()
                .filter(c -> id.equals(String.valueOf(c.params().get("component_id"))))
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
