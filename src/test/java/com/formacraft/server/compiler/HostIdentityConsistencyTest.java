package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.style.EffectiveBuildingStyleResolver;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void uniqueCourtyardMatchAcrossBuildingsDoesNotAutoRemap() {
        var houseA = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "house_A", "building_id", "house_A"));
        var lantern = new Component("DECOR_DETAIL", "a", new Vec3i(0, 1, 0),
                new Dimensions(1, 1, 2), List.of(),
                Map.of("component_id", "lantern", "building_id", "house_A", "host_id", "courtyard"));
        var courtB = new Component("COURTYARD", "b", new Vec3i(40, 0, 0),
                new Dimensions(16, 16, 1), List.of(),
                Map.of("component_id", "east_courtyard", "building_id", "house_B"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(houseA, lantern, courtB)).build();
        var repaired = HostIdRemapRepair.apply(plan);
        var fixed = repaired.components().stream()
                .filter(c -> "lantern".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("courtyard", fixed.params().get("host_id"),
                "unique substring match must not cross building_id");
        assertEquals("E_HOST_ID_AMBIGUOUS", AssemblyCompileDiagnostics.get().code());
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

    @Test
    void annexedMassCrownHostsCarryingMassNotUpstreamHost() {
        var main = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 12), List.of(),
                Map.of("component_id", "main"));
        var west = new Component("MASS_SECONDARY", "west", new Vec3i(20, 0, 0),
                new Dimensions(12, 12, 16), List.of(),
                Map.of("component_id", "west_tower", "host_id", "main"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(main, west)).build();
        var crown = ComponentSatelliteInference.makeCrownComponent(plan, west, null, "west");
        assertNotNull(crown);
        assertEquals("west_tower", crown.params().get("host_id"),
                "auto crown must host the carrying tower, not main");
        assertEquals("west_tower#crown", crown.params().get("component_id"));
        assertNull(HostedPlateDesignValidator.checkDuplicateComponentIds(
                List.of(main, west, crown)).orElse(null));
    }

    @Test
    void multipleCrownsOnSameHostGetDistinctIdsAndPositions() {
        var mass = new Component("MASS_MAIN", "m", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 14), List.of(),
                Map.of("component_id", "palace"));
        var crownA = new Component("CROWN", "m", new Vec3i(2, 14, 2),
                new Dimensions(5, 5, 6), List.of(),
                Map.of("host_id", "palace"));
        var crownB = new Component("CROWN", "m", new Vec3i(10, 14, 10),
                new Dimensions(5, 5, 6), List.of(),
                Map.of("host_id", "palace"));
        var components = new java.util.ArrayList<>(List.of(mass, crownA, crownB));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var crowns = components.stream()
                .filter(c -> "CROWN".equals(c.componentType()))
                .toList();
        var ids = crowns.stream()
                .map(c -> String.valueOf(c.params().get("component_id")))
                .toList();
        assertEquals(2, ids.size());
        assertEquals(2, ids.stream().distinct().count(), "two crowns must not share one id: " + ids);
        assertTrue(ids.contains("palace#crown"), ids.toString());
        assertTrue(ids.stream().anyMatch(id -> id.startsWith("palace#crown#")), ids.toString());
        assertEquals(2, crowns.stream().map(Component::relativePosition).distinct().count(),
                "authored multi-crown XY must stay distinct, got " + crowns.stream()
                        .map(Component::relativePosition).toList());
        assertTrue(crowns.stream().anyMatch(c -> c.relativePosition().equals(new Vec3i(2, 14, 2))));
        assertTrue(crowns.stream().anyMatch(c -> c.relativePosition().equals(new Vec3i(10, 14, 10))));
        for (Component c : crowns) {
            assertEquals("palace", c.params().get("host_id"));
        }
        assertTrue(HostedPlateDesignValidator.checkDuplicateComponentIds(components).isEmpty());
    }

    @Test
    void courtyardWithoutHostIsNotBoundToSlotMass() {
        var north = new Component("MASS_MAIN", "n", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "north_wing", "building_id", "north_wing"));
        var court = new Component("COURTYARD_SPACE", "n", new Vec3i(4, 0, 12),
                new Dimensions(10, 10, 1), List.of(),
                Map.of("component_id", "central_tianjing", "building_id", "central_tianjing"));
        var components = new java.util.ArrayList<>(List.of(north, court));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var fixed = components.stream()
                .filter(c -> "central_tianjing".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNull(fixed.params().get("host_id"),
                "independent courtyard must not inherit slot MASS host_id");
        assertTrue(HostedPlateDesignValidator.checkHostBuildingIdentity(components).isEmpty());
    }

    @Test
    void nestedTierWithDistinctBuildingIdsAreNotIdentityConflicts() {
        var tier1 = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "stupa_tier1", "building_id", "stupa_tier1"));
        var tier2 = new Component("MASS_MAIN", "s", new Vec3i(1, 8, 1),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "stupa_tier2", "building_id", "stupa_tier2",
                        "host_id", "stupa_tier1"));
        var tier3 = new Component("MASS_MAIN", "s", new Vec3i(2, 16, 2),
                new Dimensions(8, 8, 8), List.of(),
                Map.of("component_id", "stupa_tier3", "building_id", "stupa_tier3",
                        "host_id", "stupa_tier2"));
        assertTrue(HostedPlateDesignValidator
                .checkHostBuildingIdentity(List.of(tier1, tier2, tier3)).isEmpty());
    }

    @Test
    void exactCrossBuildingHostIsRejectedWithoutTypology() {
        var houseA = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "house_A", "building_id", "A"));
        var houseB = new Component("MASS_MAIN", "b", new Vec3i(40, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "house_B", "building_id", "B"));
        var lantern = new Component("DECOR_DETAIL", "a", new Vec3i(0, 1, 0),
                new Dimensions(1, 1, 2), List.of(),
                Map.of("component_id", "lantern_A", "building_id", "A", "host_id", "house_B"));
        var gap = HostedPlateDesignValidator.checkHostBuildingIdentity(List.of(houseA, houseB, lantern));
        assertTrue(gap.isPresent());
        assertEquals("E_HOST_BUILDING_IDENTITY_CONFLICT", gap.get().code());
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(houseA, houseB, lantern)).build();
        assertEquals("E_HOST_BUILDING_IDENTITY_CONFLICT",
                DesignValidatorSuite.checkAuthored(plan).orElseThrow().code());
    }

    @Test
    void referencedPlateEntersHostIndexForRoofBinding() {
        var deck = new Component("MASS_SECONDARY", "pav", new Vec3i(10, 4, 10),
                new Dimensions(8, 8, 1), List.of(),
                Map.of("component_id", "pavilion_deck", "building_id", "pavilion_B",
                        "extrude_mode", "plate"));
        var roof = new Component("ROOF", "pav", new Vec3i(10, 5, 10),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "pavilion_roof", "host_id", "pavilion_deck"));
        var index = com.formacraft.server.generation.GenerationCompileContext
                .indexComponentsById(List.of(deck, roof));
        assertNotNull(index.getUnique("pavilion_deck"),
                "uniquely referenced plate must resolve as host");
        assertFalse(index.isAmbiguous("pavilion_deck"));
    }

    @Test
    void unreferencedSharedPlateLabelsAreNotDuplicateIds() {
        var a = new Component("MASS_SECONDARY", "a", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 1), List.of(),
                Map.of("component_id", "floor_label", "extrude_mode", "plate"));
        var b = new Component("MASS_SECONDARY", "b", new Vec3i(20, 0, 0),
                new Dimensions(6, 6, 1), List.of(),
                Map.of("component_id", "floor_label", "extrude_mode", "plate"));
        assertTrue(HostedPlateDesignValidator.checkDuplicateComponentIds(List.of(a, b)).isEmpty());
    }

    @Test
    void independentTerraceWithoutHostStaysUnbound() {
        var mill = new Component("MASS_MAIN", "mill", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 10), List.of(),
                Map.of("component_id", "mill_mass", "building_id", "mill_mass"));
        var channelPos = new Vec3i(20, 0, 4);
        var channel = new Component("TERRACE", "mill", channelPos,
                new Dimensions(16, 4, 1), List.of("mill_race"),
                Map.of("component_id", "mill_channel", "building_id", "mill_channel"));
        var components = new java.util.ArrayList<>(List.of(mill, channel));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var fixed = components.stream()
                .filter(c -> "mill_channel".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNull(fixed.params().get("host_id"),
                "independent channel terrace must not inherit mill_mass host");
        assertEquals(channelPos, fixed.relativePosition(),
                "channel geometry must stay authored");
    }

    @Test
    void multipleCrownsWithoutHostIdKeepDistinctPositions() {
        var mass = new Component("MASS_MAIN", "m", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 14), List.of(),
                Map.of("component_id", "palace"));
        var crownA = new Component("CROWN", "m", new Vec3i(2, 14, 2),
                new Dimensions(5, 5, 6), List.of(), Map.of());
        var crownB = new Component("CROWN", "m", new Vec3i(10, 14, 10),
                new Dimensions(5, 5, 6), List.of(), Map.of());
        var components = new java.util.ArrayList<>(List.of(mass, crownA, crownB));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var crowns = components.stream()
                .filter(c -> "CROWN".equals(c.componentType()))
                .toList();
        assertEquals(2, crowns.stream().map(Component::relativePosition).distinct().count(),
                "slot-resolved multi-crown must keep authored XY");
        assertTrue(crowns.stream().anyMatch(c -> c.relativePosition().equals(new Vec3i(2, 14, 2))));
        assertTrue(crowns.stream().anyMatch(c -> c.relativePosition().equals(new Vec3i(10, 14, 10))));
        assertEquals(2, crowns.stream()
                .map(c -> String.valueOf(c.params().get("component_id")))
                .distinct().count());
    }

    @Test
    void hostIdAliasIsCanonicalizedToHost_id() {
        AssemblyCompileDiagnostics.clear();
        var mass = new Component("MASS_MAIN", "e", new Vec3i(40, 0, 0),
                new Dimensions(10, 10, 12), List.of(),
                Map.of("component_id", "east_tower"));
        var roof = new Component("ROOF", "e", new Vec3i(40, 12, 0),
                new Dimensions(10, 10, 3), List.of(),
                Map.of("component_id", "east_roof", "hostId", "east_tower"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(mass, roof)).build();
        var normalized = HostAliasNormalizer.apply(plan);
        var fixed = normalized.components().stream()
                .filter(c -> "east_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("east_tower", fixed.params().get("host_id"));
        assertNull(fixed.params().get("hostId"));
        assertNull(AssemblyCompileDiagnostics.get());
    }

    @Test
    void conflictingHostIdAliasesAreRejected() {
        AssemblyCompileDiagnostics.clear();
        var mass = new Component("MASS_MAIN", "m", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "main"));
        Map<String, Object> roofParams = new java.util.HashMap<>();
        roofParams.put("component_id", "east_roof");
        roofParams.put("host_id", "main");
        roofParams.put("hostId", "east_tower");
        var roof = new Component("ROOF", "m", new Vec3i(0, 8, 0),
                new Dimensions(12, 12, 3), List.of(), roofParams);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(mass, roof)).build();
        HostAliasNormalizer.apply(plan);
        assertEquals("E_HOST_ID_ALIAS_CONFLICT", AssemblyCompileDiagnostics.get().code());
        AssemblyCompileDiagnostics.clear();
    }
}
