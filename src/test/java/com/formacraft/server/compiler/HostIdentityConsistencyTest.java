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
    void floatingMassSnapDoesNotDragDistantSameSlotTerrace() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 10, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "hall", "building_id", "hall"));
        var deckPos = new Vec3i(80, 12, 0);
        var deck = new Component("TERRACE", "site", deckPos,
                new Dimensions(10, 10, 1), List.of(),
                Map.of("component_id", "far_deck", "building_id", "far_deck"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(mass, deck)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        var fixedDeck = repaired.components().stream()
                .filter(c -> "far_deck".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(deckPos, fixedDeck.relativePosition(),
                "distant same-slot terrace must not follow floating mass Y snap");
        var fixedMass = repaired.components().stream()
                .filter(c -> "hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(0, fixedMass.relativePosition().y(), "floating mass should still snap down");
    }

    @Test
    void floatingMassSnapShiftsHostedRoofWithoutComponentId() {
        var hall = new Component("MASS_MAIN", "site", new Vec3i(0, 10, 0),
                new Dimensions(14, 14, 10), List.of(),
                Map.of("component_id", "hall", "building_id", "hall"));
        var roof = new Component("ROOF", "site", new Vec3i(0, 18, 0),
                new Dimensions(14, 14, 3), List.of(),
                Map.of("host_id", "hall"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(hall, roof)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, repaired.components().get(0).relativePosition().y());
        assertEquals(8, repaired.components().get(1).relativePosition().y(),
                "host_id attachment without component_id must still follow mass Y snap");
    }

    @Test
    void floatingMassSnapShiftsNestedHostTreeButNotForeignBuilding() {
        var hall = new Component("MASS_MAIN", "site", new Vec3i(0, 10, 0),
                new Dimensions(14, 14, 10), List.of(),
                Map.of("component_id", "main_hall", "building_id", "main_hall"));
        var annex = new Component("MASS_SECONDARY", "site", new Vec3i(16, 10, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "annex", "building_id", "main_hall", "host_id", "main_hall"));
        var roof = new Component("ROOF", "site", new Vec3i(16, 18, 0),
                new Dimensions(10, 10, 3), List.of(),
                Map.of("component_id", "annex_roof", "host_id", "annex"));
        var chimney = new Component("CHIMNEY", "site", new Vec3i(18, 21, 2),
                new Dimensions(2, 2, 4), List.of(),
                Map.of("component_id", "annex_chimney", "host_id", "annex_roof"));
        var foreignPos = new Vec3i(80, 12, 0);
        var foreign = new Component("TERRACE", "site", foreignPos,
                new Dimensions(8, 8, 1), List.of(),
                Map.of("component_id", "other_deck", "building_id", "other_deck"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(hall, annex, roof, chimney, foreign)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, yOf(repaired, "main_hall"));
        assertEquals(0, yOf(repaired, "annex"));
        assertEquals(8, yOf(repaired, "annex_roof"));
        assertEquals(11, yOf(repaired, "annex_chimney"));
        assertEquals(foreignPos, repaired.components().stream()
                .filter(c -> "other_deck".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow().relativePosition());
    }

    private static int yOf(com.formacraft.common.llm.dto.LlmPlan plan, String id) {
        return plan.components().stream()
                .filter(c -> id.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow()
                .relativePosition().y();
    }

    @Test
    void floatingTowerDoesNotTreatOwnRoofAsSupport() {
        var tower = new Component("TOWER", "keep", new Vec3i(0, 10, 0),
                new Dimensions(6, 6, 12), List.of(),
                Map.of("component_id", "curtain_tower"));
        var roof = new Component("ROOF", "keep", new Vec3i(0, 22, 0),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "tower_roof", "host_id", "curtain_tower"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(tower, roof)).build();
        var repaired = FloatingTowerSnapRepair.apply(plan);
        assertEquals(0, yOf(repaired, "curtain_tower"),
                "roof above the tower must not count as a supporting surface");
        assertEquals(12, yOf(repaired, "tower_roof"),
                "hosted roof must follow the tower snap");
    }

    @Test
    void floatingTowerPreservesExplicitStiltsElevation() {
        var tower = new Component("TOWER", "site", new Vec3i(0, 10, 0),
                new Dimensions(5, 5, 10), List.of("stilts"),
                Map.of("component_id", "raised_tower", "stilts", Boolean.TRUE));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(tower)).build();
        var repaired = FloatingTowerSnapRepair.apply(plan);
        assertEquals(10, yOf(repaired, "raised_tower"));
    }

    @Test
    void twoIndependentFloatingMassesBothSnapDown() {
        var left = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "house_left", "building_id", "house_left"));
        var right = new Component("MASS_MAIN", "site", new Vec3i(20, 8, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "house_right", "building_id", "house_right"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(left, right)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, yOf(repaired, "house_left"));
        assertEquals(0, yOf(repaired, "house_right"));
    }

    @Test
    void sideAdjacentMassIsNotTreatedAsVerticalSupport() {
        // Half-open footprints share the X=10 face only — no bearing area.
        var left = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "house_a", "building_id", "house_a",
                        "anchor_mode", "min_corner"));
        var right = new Component("MASS_MAIN", "site", new Vec3i(10, 9, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "house_b", "building_id", "house_b",
                        "anchor_mode", "min_corner"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(left, right)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, yOf(repaired, "house_a"));
        assertEquals(0, yOf(repaired, "house_b"),
                "face-adjacent neighbor must not become a false landing at y=6");
        assertNotEquals(6, yOf(repaired, "house_b"));
    }

    @Test
    void unhostedTowerFollowsMassSnapWhenMassDropsFirst() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 10, 0),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "keep", "anchor_mode", "min_corner"));
        var tower = new Component("TOWER", "site", new Vec3i(2, 18, 2),
                new Dimensions(6, 6, 10), List.of(),
                Map.of("component_id", "keep_tower", "anchor_mode", "min_corner"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(mass, tower)).build();
        // Production order: mass first, then tower.
        var afterMass = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, yOf(afterMass, "keep"));
        assertEquals(18, yOf(afterMass, "keep_tower"), "unhosted tower stays until tower snap");
        var afterTower = FloatingTowerSnapRepair.apply(afterMass);
        assertEquals(0, yOf(afterTower, "keep"));
        assertEquals(8, yOf(afterTower, "keep_tower"),
                "tower must re-seat on the snapped mass top, not remain at y=18");
    }

    @Test
    void modestPodiumWithoutExternalSupportIsNotSnapped() {
        var podium = new Component("MASS_MAIN", "a", new Vec3i(0, 4, 0),
                new Dimensions(5, 5, 9), List.of(),
                Map.of("component_id", "raised_hall"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(podium)).build();
        assertEquals(4, yOf(FloatingMassSnapRepair.apply(plan), "raised_hall"),
                "small authored podium must not be flattened to terrain");
    }

    @Test
    void massAboveOwnHostedFoundationKeepsElevation() {
        var foundation = new Component("FOUNDATION", "slot_0", new Vec3i(-1, 0, -1),
                new Dimensions(17, 15, 1), List.of(),
                Map.of("component_id", "foundation_house_a", "host_id", "mass_house_a"));
        var mass = new Component("MASS_MAIN", "slot_0", new Vec3i(0, 5, 0),
                new Dimensions(15, 13, 8), List.of(),
                Map.of("component_id", "mass_house_a", "building_id", "mass_house_a"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(foundation, mass)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(5, yOf(repaired, "mass_house_a"),
                "hosted plinth must not pull its carrying mass down");
        assertEquals(0, yOf(repaired, "foundation_house_a"));
    }

    @Test
    void raisedTowerWithHostedPlinthAtY8IsNotFlattened() {
        // oct18_3 pattern: elevated MASS_MAIN + foundation_tower host_id → tower.
        var foundation = new Component("FOUNDATION", "tower", new Vec3i(0, 6, -30),
                new Dimensions(10, 10, 1), List.of(),
                Map.of("component_id", "foundation_tower", "host_id", "tower_main",
                        "building_id", "tower_main"));
        var tower = new Component("MASS_MAIN", "tower", new Vec3i(0, 8, -30),
                new Dimensions(8, 8, 12), List.of("ornament"),
                Map.of("component_id", "tower_main", "building_id", "tower_main"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(foundation, tower)).build();
        assertEquals(8, yOf(FloatingMassSnapRepair.apply(plan), "tower_main"),
                "raised tower with authored plinth must keep stair landing elevation");
    }

    @Test
    void independentFoundationStillCatchesClearlyFloatingMass() {
        var foundation = new Component("FOUNDATION", "site", new Vec3i(0, 0, 0),
                new Dimensions(12, 12, 1), List.of(),
                Map.of("component_id", "pad"));
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 8, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "hall"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(foundation, mass)).build();
        assertEquals(1, yOf(FloatingMassSnapRepair.apply(plan), "hall"),
                "hostless foundation under a mid-air mass remains a landing pad");
    }

    @Test
    void stackedPrimaryMassSittingOnSiblingIsNotFlattened() {
        var base = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 8), List.of(),
                Map.of("component_id", "podium", "building_id", "palace"));
        var upper = new Component("MASS_MAIN", "site", new Vec3i(2, 8, 2),
                new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", "hall", "building_id", "palace", "host_id", "podium"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.of(base, upper)).build();
        var repaired = FloatingMassSnapRepair.apply(plan);
        assertEquals(0, yOf(repaired, "podium"));
        assertEquals(8, yOf(repaired, "hall"),
                "upper mass resting on a lower mass must keep authored elevation");
    }

    @Test
    void ambiguousSameSlotFoundationIsNotAlignedToFirstMass() {
        // Two MASS_MAIN share the slot; hostless foundation must not bind to the first mass.
        var left = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(11, 11, 6), List.of(),
                Map.of("component_id", "house_left", "building_id", "house_left"));
        var right = new Component("MASS_MAIN", "site", new Vec3i(30, 0, 0),
                new Dimensions(11, 11, 6), List.of(),
                Map.of("component_id", "house_right", "building_id", "house_right"));
        var foundationPos = new Vec3i(20, -1, 5);
        var foundation = new Component("FOUNDATION", "site", foundationPos,
                new Dimensions(1, 1, 1), List.of(),
                Map.of("component_id", "mystery_foundation"));
        var components = new java.util.ArrayList<>(List.of(left, right, foundation));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .components(List.copyOf(components)).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var fixed = components.stream()
                .filter(c -> "mystery_foundation".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(foundationPos, fixed.relativePosition(),
                "ambiguous foundation must keep authored placement");
        assertNull(fixed.params().get("host_id"));
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
