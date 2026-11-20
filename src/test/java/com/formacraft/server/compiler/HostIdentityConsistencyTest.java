package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.style.EffectiveBuildingStyleResolver;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostIdentityConsistencyTest {
    @BeforeEach
    @AfterEach
    void clearGap() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void ambiguousCourtyardSubstringDoesNotAutoRemap() {
        var east = new Component("COURTYARD", "e", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 1), List.of(),
                Map.of("component_id", "east_courtyard"));
        var west = new Component("COURTYARD", "w", new Vec3i(40, 0, 0),
                new Dimensions(16, 16, 1), List.of(),
                Map.of("component_id", "west_courtyard"));
        var decor = new Component("DECOR_DETAIL", "e", new Vec3i(0, 1, 0),
                new Dimensions(1, 1, 2), List.of(),
                Map.of("component_id", "lantern", "host_id", "courtyard"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(east, west, decor)).build();
        var repaired = HostIdRemapRepair.apply(plan);
        var lantern = repaired.components().stream()
                .filter(c -> "lantern".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("courtyard", lantern.params().get("host_id"),
                "must keep original host_id when east/west both match");
        assertEquals("E_HOST_ID_AMBIGUOUS", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void duplicateRoofIdsWithoutTypologyAreRejectedByValidator() {
        var a = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "mansion_A", "building_id", "A"));
        var roofA = new Component("ROOF", "a", new Vec3i(0, 8, 0),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "shared_roof", "host_id", "mansion_A"));
        var b = new Component("MASS_MAIN", "b", new Vec3i(40, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "mansion_B", "building_id", "B"));
        var roofB = new Component("ROOF", "b", new Vec3i(40, 8, 0),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "shared_roof", "host_id", "mansion_B"));
        var gap = HostedPlateDesignValidator.check(
                LlmPlanTestFixtures.builder().components(List.of(a, roofA, b, roofB)).build(),
                List.of(a, roofA, b, roofB));
        assertTrue(gap.isPresent());
        assertEquals("E_DUPLICATE_COMPONENT_ID", gap.get().code());
    }

    @Test
    void styleResolverDoesNotInheritAcrossDuplicateRoofIds() {
        var a = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "mansion_A", "style_profile", "Baroque_Palace"));
        var roofA = new Component("ROOF", "a", new Vec3i(0, 8, 0),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "shared_roof", "host_id", "mansion_A"));
        var b = new Component("MASS_MAIN", "b", new Vec3i(40, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "mansion_B", "style_profile", "Chinese_Vernacular_Huizhou"));
        var roofB = new Component("ROOF", "b", new Vec3i(40, 8, 0),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "shared_roof", "host_id", "mansion_B"));
        var chimney = new Component("CROWN", "b", new Vec3i(40, 11, 0),
                new Dimensions(2, 2, 3), List.of(),
                Map.of("component_id", "chimney", "host_id", "shared_roof"));
        var plan = LlmPlanTestFixtures.builder()
                .styleProfile("Modern_International")
                .components(List.of(a, roofA, b, roofB, chimney)).build();
        // Ambiguous shared_roof must not invent Baroque vs Huizhou from list order.
        assertNull(EffectiveBuildingStyleResolver.resolveLocalOrHost(plan, chimney));
    }
}
