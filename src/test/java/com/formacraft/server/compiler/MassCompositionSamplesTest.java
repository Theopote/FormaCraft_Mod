package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.MassSpatialRelation;
import com.formacraft.common.generation.component.util.ResolvedComponentGeometry;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.1 Task E — ten deterministic mass composition samples.
 */
class MassCompositionSamplesTest {

    @BeforeEach
    void clearDiag() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearDiagAfter() {
        AssemblyCompileDiagnostics.clear();
    }

    private static Component mass(String id, int x, int y, int z, int w, int d, int h) {
        return new Component(
                "MASS_MAIN", "main", new Vec3i(x, y, z),
                new Dimensions(w, d, h), List.of(),
                Map.of("component_id", id, "anchor_mode", "min_corner"));
    }

    private static Component massWith(String id, int x, int y, int z, int w, int d, int h,
                                      Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("anchor_mode", "min_corner");
        params.putAll(extra);
        return new Component("MASS_MAIN", "main", new Vec3i(x, y, z),
                new Dimensions(w, d, h), List.of(), params);
    }

    private static LlmPlan plan(Component... components) {
        return LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build();
    }

    private static Vec3i pos(LlmPlan p, String id) {
        return p.components().stream()
                .filter(c -> id.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow()
                .relativePosition();
    }

    /** S1: adjacent courtyard halls share a face — CONTACTING, no nudge. */
    @Test
    void s01_adjacentHallsContactingNoNudge() {
        Component a = mass("hall_a", 0, 0, 0, 8, 8, 6);
        Component b = mass("hall_b", 8, 0, 0, 8, 8, 6);
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(a, b));
        assertEquals(a.relativePosition(), pos(repaired, "hall_a"));
        assertEquals(b.relativePosition(), pos(repaired, "hall_b"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    /** S2: podium + raised tower Y-disjoint — no separation. */
    @Test
    void s02_podiumTowerYDisjointNoNudge() {
        Component podium = mass("podium", 0, 0, 0, 16, 16, 2);
        Component tower = mass("tower_keep", 4, 4, 4, 8, 8, 12);
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(podium, tower));
        assertEquals(tower.relativePosition(), pos(repaired, "tower_keep"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    /** S3: soft annex pavilion overlapping hall — nudged without explicit opt-in. */
    @Test
    void s03_softAnnexPavilionNudged() {
        Component hall = mass("hall_main", 0, 0, 0, 16, 12, 8);
        Component pavilion = mass("pavilion_water", 12, 0, 4, 6, 6, 5);
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(hall, pavilion));
        assertEquals(hall.relativePosition(), pos(repaired, "hall_main"));
        assertNotEquals(pavilion.relativePosition(), pos(repaired, "pavilion_water"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    /** S4: intentional interlock volumes left alone. */
    @Test
    void s04_intentionalInterlockLeftAlone() {
        Component a = massWith("volume_a", 0, 0, 0, 10, 10, 8,
                Map.of("mass_role", "interlock_volume", "intentional_interlock", true));
        Component b = massWith("volume_b", 4, 2, 4, 10, 10, 8,
                Map.of("mass_role", "interlock_volume", "intentional_interlock", true));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(a, b));
        assertEquals(a.relativePosition(), pos(repaired, "volume_a"));
        assertEquals(b.relativePosition(), pos(repaired, "volume_b"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }

    /** S5: two primary halls 3D-collide without opt-in → E_MASS_FOOTPRINT_CONFLICT. */
    @Test
    void s05_primaryPrimaryConflictEmitsGap() {
        Component a = mass("primary_east", 0, 0, 0, 12, 12, 8);
        Component b = mass("primary_west", 6, 0, 6, 12, 12, 8);
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(a, b));
        assertEquals(a.relativePosition(), pos(repaired, "primary_east"));
        assertEquals(b.relativePosition(), pos(repaired, "primary_west"));
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_MASS_FOOTPRINT_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    /** S6: allow_mass_separation opt-in nudges primary pair. */
    @Test
    void s06_allowMassSeparationOptInNudges() {
        Component a = mass("primary_east", 0, 0, 0, 12, 12, 8);
        Component b = massWith("primary_west", 6, 0, 6, 12, 12, 8,
                Map.of("allow_mass_separation", true));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(a, b));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        assertTrue(
                !a.relativePosition().equals(pos(repaired, "primary_east"))
                        || !b.relativePosition().equals(pos(repaired, "primary_west")),
                "opt-in separation must move at least one primary");
    }

    /** S7: authored sub-3 MASS dimensions preserved (no silent min-3). */
    @Test
    void s07_authoredSub3DimensionsPreserved() {
        Component thin = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 2), List.of(),
                Map.of("component_id", "thin_post", "anchor_mode", "min_corner"));
        Component body = ResolvedComponentGeometry.normalizeBody(thin);
        assertEquals(2, body.dimensions().width());
        assertEquals(2, body.dimensions().depth());
        assertEquals(2, body.dimensions().height());
    }

    /** S8: contiguous nested masses keep authored offsets without stack cue. */
    @Test
    void s08_noAutoCenterWithoutStackCue() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tiered");
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("dimensions", Map.of("width", 8, "depth", 8, "height", 4),
                        "offset", Map.of("x", 0, "y", 0, "z", 0)),
                Map.of("dimensions", Map.of("width", 6, "depth", 6, "height", 4),
                        "offset", Map.of("x", 0, "y", 4, "z", 0)),
                Map.of("dimensions", Map.of("width", 4, "depth", 4, "height", 4),
                        "offset", Map.of("x", 0, "y", 8, "z", 0))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 12), List.of(), params);
        var parts = ResolvedMassPart.resolve(c);
        assertTrue(parts.size() >= 3);
        // Without stack cue the first nested part keeps authored offset (0,0,0), not recentered.
        ResolvedMassPart mid = parts.stream()
                .filter(p -> p.partId().contains("mass_2"))
                .findFirst().orElseThrow();
        assertEquals(0, mid.offset().x());
        assertEquals(0, mid.offset().z());
    }

    /** S9b: authored stepped=true is an explicit stack cue (same as stack_mode). */
    @Test
    void s09b_steppedParamRecentersTiers() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tiered");
        params.put("anchor_mode", "min_corner");
        params.put("stepped", true);
        params.put("masses", List.of(
                Map.of("dimensions", Map.of("width", 8, "depth", 8, "height", 4),
                        "offset", Map.of("x", 0, "y", 0, "z", 0)),
                Map.of("dimensions", Map.of("width", 6, "depth", 6, "height", 4),
                        "offset", Map.of("x", 0, "y", 4, "z", 0)),
                Map.of("dimensions", Map.of("width", 4, "depth", 4, "height", 4),
                        "offset", Map.of("x", 0, "y", 8, "z", 0))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 12), List.of(), params);
        var parts = ResolvedMassPart.resolve(c);
        ResolvedMassPart mid = parts.stream()
                .filter(p -> p.partId().contains("mass_2"))
                .findFirst().orElseThrow();
        assertEquals(1, mid.offset().x());
        assertEquals(1, mid.offset().z());
    }

    /** S9: stack_mode=centered recenters contiguous tiers. */
    @Test
    void s09_stackModeCenteredRecentersTiers() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tiered");
        params.put("anchor_mode", "min_corner");
        params.put("stack_mode", "centered");
        params.put("masses", List.of(
                Map.of("dimensions", Map.of("width", 8, "depth", 8, "height", 4),
                        "offset", Map.of("x", 0, "y", 0, "z", 0)),
                Map.of("dimensions", Map.of("width", 6, "depth", 6, "height", 4),
                        "offset", Map.of("x", 0, "y", 4, "z", 0)),
                Map.of("dimensions", Map.of("width", 4, "depth", 4, "height", 4),
                        "offset", Map.of("x", 0, "y", 8, "z", 0))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 12), List.of(), params);
        var parts = ResolvedMassPart.resolve(c);
        ResolvedMassPart mid = parts.stream()
                .filter(p -> p.partId().contains("mass_2"))
                .findFirst().orElseThrow();
        assertEquals(1, mid.offset().x()); // (8-6)/2
        assertEquals(1, mid.offset().z());
    }

    /** S10b: shallow shared-wall overlap collapses to contacting — no gap, no deep courtyard nudge. */
    @Test
    void s10b_connectionSeamCollapsesToContact() {
        Component hall = mass("main_hall_mass", -6, 0, -5, 13, 11, 9);
        Component tower = mass("right_tower_mass", 6, 0, -4, 8, 8, 16);
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(hall, tower));
        assertEquals(hall.relativePosition(), pos(repaired, "main_hall_mass"));
        Vec3i towerPos = pos(repaired, "right_tower_mass");
        assertNotEquals(tower.relativePosition(), towerPos, "seam resolve must move the overlapping peer");
        assertEquals(7, towerPos.x(), "1-cell X overlap collapses to contacting at x=7");
        assertEquals(tower.relativePosition().z(), towerPos.z());
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        var hallB = new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                -6, 0, -5, 7, 9, 6);
        var towerB = new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                towerPos.x(), towerPos.y(), towerPos.z(),
                towerPos.x() + 8, towerPos.y() + 16, towerPos.z() + 8);
        assertFalse(com.formacraft.common.generation.component.util.MassSpatialRelation.aabb3Intersect(hallB, towerB));
    }

    /** S10: authored vertical stack (nested XZ, disjoint Y) never nudged. */
    @Test
    void s10_verticalStackTiersNeverNudged() {
        Component base = mass("res_main", 0, 0, 0, 24, 24, 10);
        Component tier = massWith("tier_l2", 4, 10, 4, 16, 16, 8,
                Map.of("host_id", "res_main", "tier_index", 1));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan(base, tier));
        assertEquals(tier.relativePosition(), pos(repaired, "tier_l2"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        var ab = new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                0, 0, 0, 24, 10, 24);
        var bb = new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                4, 10, 4, 20, 18, 20);
        assertEquals(MassSpatialRelation.VERTICAL_STACK, MassSpatialRelation.classify(ab, bb, false));
    }
}
